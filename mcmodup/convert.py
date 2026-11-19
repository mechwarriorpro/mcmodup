"""Compile a new NeoForge JAR from a recognized, explicitly partial legacy profile."""

from collections import Counter
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import tomllib
import zipfile

from . import JAVA_VERSION, MINECRAFT_VERSION, NEOFORGE_VERSION
from .api import ClassLocation
from . import minechem_final_synthesis, minechem_polytool
from .assets import PNG_SIGNATURE
from .audit import ModMetadata
from .catalogs import RELEASE_394_PROFILE, CatalogEntry, Profile, recover_catalog
from .classfiles import CLASS_LIMIT, Symbol, read_class
from .inputs import InputError, open_input
from .machines import FINAL_LEGACY_SHA256, DecomposerMode, machine_classes, machine_project, machine_recipes
from .pack import ModPack, PackMod
from .port import _build_gradle, _metadata, write_workspace


@dataclass(frozen=True)
class Conversion:
    output: Path
    profile: Profile
    entries: int
    recipes: int


def _document(value: dict) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def _location(pack: ModPack, mod: PackMod, owner: str) -> ClassLocation:
    if owner in mod.classes:
        return ClassLocation(mod.path, mod.classes[owner], owner)
    if not any(ref.owner == owner for ref in mod.references):
        raise InputError(f"Selected mod does not contain or reference the expected catalog {owner}.")
    locations = pack.dependency_locations(mod, owner)
    if len(locations) != 1:
        raise InputError(f"The pack must supply one unambiguous provider of {owner}; found {len(locations)}.")
    location = next(iter(locations))
    for label in pack.owners.get(owner, ()):
        if pack.mods[label].path == location.file and pack.mods[label].audit.source_version != mod.audit.source_version:
            raise InputError(f"The pack provider {label!r} for {owner} has a conflicting or unknown Minecraft version.")
    return location


def _textures(entries: list[CatalogEntry], mod: PackMod, locations: list[ClassLocation]) -> dict[str, bytes]:
    remaining = {entry.texture for entry in entries}
    textures = {}
    for path in dict.fromkeys([mod.path, *(location.file for location in locations)]):
        if not remaining:
            break
        with open_input(path) as source:
            for key in sorted(remaining.intersection(source.files)):
                content = source.read(key, 1024 * 1024)
                if not content.startswith(PNG_SIGNATURE) or f"{key}.mcmeta" in source.files:
                    raise InputError(f"Cannot convert catalog icon {key}: expected a standalone PNG without a sidecar.")
                textures[key] = content
                remaining.remove(key)
    if remaining:
        raise InputError(f"The selected legacy JAR and its catalog provider are missing expected textures: {', '.join(sorted(remaining)[:4])}.")
    return textures


def _java(package: str, namespace: str, entries: list[CatalogEntry], mode: DecomposerMode | None, *, reactors: bool = False) -> str:
    definitions = []
    final = mode == DecomposerMode.FINAL
    exact = mode in (DecomposerMode.FINAL, DecomposerMode.RELEASE_394)
    for entry in entries:
        keys = [f"item.{namespace}.{entry.path}.detail_{i}" for i in range(len(entry.details))]
        lore = ", ".join(f'Component.translatable("{key}")' for key in keys)
        method = f'registerItem("{entry.path}", ChemistryEffects.MoleculeItem::new, ' if exact and entry.path.startswith("molecule_") else f'registerSimpleItem("{entry.path}", '
        definitions.append(f'        entries.add(items.{method}new Item.Properties()'
                           f'.component(DataComponents.LORE, new ItemLore(List.of({lore})))));')
    if exact:
        definitions.append("        ChemistryEffects.register(bus, mod);")
        definitions.extend(('        var polytool = items.registerItem("polytool", PolytoolItem::new);',
                            '        PolytoolItem.register(bus, polytool);', '        entries.add(polytool);'))
    if mode is not None:
        if final:
            definitions.append("        SynthesizerFinal.registerConfig(mod);")
        definitions.append("        entries.addAll(ChemistryMachines.register(bus, items));")
        definitions.append("        entries.addAll(ChemistryResearch.register(bus, items));")
    if reactors:
        definitions.append("        entries.addAll(ReactorMachines.register(bus, items));")
    body = "\n".join(definitions)
    config_import = "import net.neoforged.fml.ModContainer;\n" if exact else ""
    config_argument = ", ModContainer mod" if exact else ""
    return f'''package {package};

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.bus.api.IEventBus;
{config_import}import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** An isolated content catalog, not a replacement for the original mod. */
@Mod(CatalogPort.MOD_ID)
public final class CatalogPort {{
    public static final String MOD_ID = "{namespace}";

    public CatalogPort(IEventBus bus{config_argument}) {{
        var items = DeferredRegister.createItems(MOD_ID);
        var tabs = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);
        var entries = new ArrayList<DeferredItem<?>>();
{body}
        tabs.register("catalog", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.{namespace}.catalog"))
            .icon(() -> entries.getFirst().get().getDefaultInstance())
            .displayItems((parameters, output) -> entries.forEach(item -> output.accept(item.get())))
            .build());
        items.register(bus);
        tabs.register(bus);
    }}
}}
'''


def _recipe_files(namespace: str, entries: list[CatalogEntry]) -> tuple[dict[str, str], dict[str, str]]:
    """Move unambiguous small component lists to 1.21.1 crafting recipes, not legacy machines."""
    candidates = {entry.path: tuple(sorted(entry.components)) for entry in entries if entry.components}
    pattern_counts = Counter(candidates.values())
    files = {}
    status = {}
    for path, shape in candidates.items():
        if sum(count for _, count in shape) > 9:
            status[path] = "too_many_crafting_slots"
        elif pattern_counts[shape] > 1:
            status[path] = "ambiguous_ingredients"
        else:
            status[path] = "crafting_shapeless"
            files[f"src/main/resources/data/{namespace}/recipe/combine/{path}.json"] = _document({
                "type": "minecraft:crafting_shapeless",
                "category": "misc",
                "ingredients": [{"item": f"{namespace}:{item}"} for item, count in shape for _ in range(count)],
                "result": {"id": f"{namespace}:{path}", "count": 1},
            })
    return files, status


def _decomposer_mode(profile: Profile, digest: str) -> DecomposerMode | None:
    if profile.source_sha256 is not None and digest != profile.source_sha256:
        raise InputError(f"{profile.name} requires the exact original archive (SHA-256 {profile.source_sha256}).")
    if profile.name == RELEASE_394_PROFILE:
        return DecomposerMode.RELEASE_394
    if profile.name == "minechem-chemistry":
        return DecomposerMode.FINAL if digest == FINAL_LEGACY_SHA256 else DecomposerMode.BATCH
    return None


def _catalog_entries(profile: Profile, locations: list[ClassLocation]) -> list[CatalogEntry]:
    classes = {}
    for location in locations:
        with open_input(location.file) as artifact:
            classes[location.owner] = read_class(artifact.read(location.entry, CLASS_LIMIT), collect_references=False,
                                                capture_initializer=True)
    return recover_catalog(profile, classes)


def _icon_paths(namespace: str, originals) -> dict[str, str]:
    return {name: f"assets/{namespace}/textures/item/catalog/{i}.png" for i, name in enumerate(sorted(originals))}


def _project_files(profile: Profile, entries: list[CatalogEntry], textures: dict[str, bytes],
                   digest: str, dependency_summary: dict, locations: list[ClassLocation], *,
                   linked_decomposer: bool = False, project_version: str | None = None,
                   paired_limitations: tuple[str, ...] = ()) -> tuple[dict[str, str | bytes], int]:
    namespace = profile.namespace
    package = f"mcmodup.generated.{namespace}"
    mode = _decomposer_mode(profile, digest)
    version = project_version or {DecomposerMode.FINAL: "1.3.4-partial", DecomposerMode.BATCH: "1.2.0-partial",
                                  DecomposerMode.RELEASE_394: "1.3.3-partial"}.get(mode, "1.0.0-partial")
    machine_rules = machine_recipes(namespace, entries, mode=mode, linked=linked_decomposer) if mode else []
    if mode:
        recipe_files, extra_locales = machine_project(namespace, package, entries, machine_rules, mode=mode,
                                                      linked_decomposer=linked_decomposer)
        count = len(machine_rules)
        if mode == DecomposerMode.RELEASE_394:
            inputs = {key for recipe in machine_rules if recipe.machine == "decomposer" for key, _ in recipe.inputs}
            outputs = {key for recipe in machine_rules if recipe.machine == "decomposer" for key, _ in recipe.outputs}
            recipe_status = {entry.path: ("powered_decomposition" if f"{namespace}:{entry.path}" in inputs else
                                          "decomposer_output" if f"{namespace}:{entry.path}" in outputs else "catalog_only")
                             for entry in entries}
            decomposition = sum(rule.machine == "decomposer" for rule in machine_rules)
            features = (f"Recovered .394 catalog items, {decomposition} mapped decomposition rules (including weighted and recursive recipes), "
                        "a saved water/lava tank, and "
                        f"{count - decomposition} measured synthesis registrations with virtual templates and full batches paid on extraction. "
                        "Recipe browsing and journals can select either result of an identical pattern. The microscope records supported discoveries.")
        elif mode == DecomposerMode.FINAL:
            synthesis = {rule.id: rule for rule in machine_rules if rule.machine == "synthesizer"}
            recipe_status = {entry.path: ("synthesis_shadowed_canonical" if synthesis[f"{namespace}:{entry.path}"].canonical.startswith("shadow:")
                                          else "powered_synthesis") for entry in entries if f"{namespace}:{entry.path}" in synthesis}
            features = ("Recovered 5.0.5 FINAL catalog items, chance-based material decomposition with the observed output buffer, "
                        "and an ordered synthesis grid with native FE and on-extraction payment, sided storage gates, and microscope and journal discovery.")
        else:
            recipe_status = {entry.path: "powered_synthesis" for entry in entries if entry.components}
            features = ("Recovered items, powered synthesis/decomposition, and microscope and journal discovery for the recovered recipes. "
                        "The powered machines use NeoForge FE, not the original UE grid.")
    else:
        recipe_files, recipe_status = _recipe_files(namespace, entries)
        extra_locales = {}
        count = len(recipe_files)
        features = "Recovered aspect items and crafting-table combinations. This is not the original research system."
    not_ported = profile.not_ported
    if mode in (DecomposerMode.FINAL, DecomposerMode.RELEASE_394):
        features += (" Includes release-local Polytool infusion and selected modules, molecule drinking, coating and spiked food, "
                     "and journal cloning with component preservation.")
        not_ported = ("Unmapped materials, chemical/other third-party fluids, radiation and decay, "
                      "legacy power or add-on integrations, and old saves. Some materials, recipes and effect interactions "
                      "use native substitutions. Other machines and the original lens chain require a paired or expanded build. "
                      + minechem_polytool.LIMIT)
    elif mode == DecomposerMode.BATCH:
        not_ported = "Journal cloning requires the exact FINAL release. The generic profile does not include chemical effects. " + not_ported
    if paired_limitations:
        features += " Includes both original reactor blueprints, a staged projector and release-specific reactor operation."
        not_ported = " ".join((*paired_limitations, minechem_polytool.LIMIT))
    description = f"Partial mod with new IDs. {features} Not ported: {not_ported}"
    code_notice = ("Includes only selected, rewritten legacy method bodies; no original classes or legacy dependencies are bundled."
                   if linked_decomposer else "This JAR contains no legacy JVM classes or dependencies.")
    notice = f"""PARTIAL PORT — NOT A REPLACEMENT FOR THE ORIGINAL MOD.

Legacy mod: {profile.mod_id} (Minecraft {profile.version}, binary SHA-256 {digest})
Implemented: {features}
Old Minecraft worlds and dependent mods are not supported.
Not ported: {not_ported}

Original data and icons: {profile.attribution}
License: {profile.license}.
Review the original license and authorization before distributing. Do not distribute Thaumcraft
adaptations without the owner's permission. {code_notice}
"""
    src = "src/main/resources"
    prefix = f"{src}/assets/{namespace}"
    files: dict[str, str | bytes] = {
        **recipe_files,
        "settings.gradle": f"pluginManagement {{ repositories {{ gradlePluginPortal() }} }}\nrootProject.name = '{namespace}'\n",
        "build.gradle": _build_gradle(namespace, package, version),
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        f"src/main/java/{package.replace('.', '/')}/CatalogPort.java": _java(package, namespace, entries, mode, reactors=bool(paired_limitations)),
        f"{src}/META-INF/neoforge.mods.toml": _metadata(ModMetadata("", description=description), namespace, profile.title, version, profile.license),
        f"{src}/META-INF/MCModUp-NOTICE.txt": notice,
        f"{src}/data/{namespace}/mcmodup/catalog.json": _document({
            "profile": profile.name, "full_port": False, "source_minecraft": profile.version,
            "source_sha256": digest, "target_minecraft": MINECRAFT_VERSION,
            "target_neoforge": NEOFORGE_VERSION, "not_ported": not_ported,
            "catalog_classes": [location.owner for location in locations],
            **({"decomposer_mode": mode.value} if mode else {}),
            "dependencies_observed": dependency_summary,
            "machine_recipes": [recipe.describe() for recipe in machine_rules],
            "entries": [{"new_id": f"{namespace}:{entry.path}", **entry.legacy,
                         "ingredients": [{"id": f"{namespace}:{item}", "count": count} for item, count in entry.components],
                         "recipe_status": recipe_status.get(entry.path, "primitive")}
                        for entry in entries],
        }),
    }
    icon_ids = {}
    for path, new in _icon_paths(namespace, textures).items():
        icon_ids[path] = f"{namespace}:item/catalog/{Path(new).stem}"
        files[f"{src}/{new}"] = textures[path]
    strings = {f"itemGroup.{namespace}.catalog": profile.title, **extra_locales}
    for entry in entries:
        key = f"item.{namespace}.{entry.path}"
        strings[key] = entry.label
        strings.update({f"{key}.detail_{i}": line for i, line in enumerate(entry.details)})
        files[f"{prefix}/models/item/{entry.path}.json"] = _document({
            "parent": "minecraft:item/generated", "textures": {"layer0": icon_ids[entry.texture]},
        })
    files[f"{prefix}/lang/en_us.json"] = _document(strings)
    return files, count


def _gradle(workspace: Path, timeout: int) -> Path:
    flags = ["--no-daemon", "--console=plain", "build"]
    command = (["cmd", "/d", "/c", "gradlew.bat"] if os.name == "nt" else ["./gradlew"]) + flags
    log = workspace / "mcmodup-build.log"
    try:
        with log.open("wb") as stream:
            run = subprocess.run(command, cwd=workspace, stdin=subprocess.DEVNULL,
                                 stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
    except subprocess.TimeoutExpired as exc:
        raise InputError(f"NeoForge Gradle build exceeded {timeout} seconds. No JAR was written.") from exc
    if run.returncode:
        with log.open("rb") as stream:
            stream.seek(max(0, log.stat().st_size - 6000))
            tail = stream.read().decode("utf-8", "replace").strip()
        raise InputError(f"NeoForge build failed (exit {run.returncode}). Check JDK {JAVA_VERSION} and Gradle network access.\n{tail}")
    jars = list((workspace / "build/libs").glob("*.jar"))
    if len(jars) != 1:
        raise InputError("Gradle did not produce exactly one output JAR.")
    return jars[0]


def _check_artifact(jar: Path, profile: Profile, digest: str) -> None:
    namespace = profile.namespace
    mode = _decomposer_mode(profile, digest)
    owner = f"mcmodup/generated/{namespace}/CatalogPort"
    try:
        with zipfile.ZipFile(jar) as archive:
            metadata = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode())
            classes = {name for name in archive.namelist() if name.endswith(".class")}
            catalog = json.loads(archive.read(f"data/{namespace}/mcmodup/catalog.json"))
            info = read_class(archive.read(f"{owner}.class"))
    except (KeyError, ValueError, zipfile.BadZipFile) as exc:
        raise InputError(f"Built JAR lacks valid NeoForge catalog metadata or code: {exc}") from exc
    declared_mods = metadata.get("mods")
    expected = {owner}
    if mode:
        expected.update(f"mcmodup/generated/{namespace}/{name}" for name in machine_classes(mode))
    top_level = {name.removesuffix(".class").split("$", 1)[0] for name in classes}
    if (top_level != expected or not all(f"{name}.class" in classes for name in expected) or info.owner != owner or Symbol("net/neoforged/fml/common/Mod") not in info.references
            or not isinstance(declared_mods, list) or len(declared_mods) != 1 or not isinstance(declared_mods[0], dict)
            or declared_mods[0].get("modId") != namespace or not isinstance(catalog, dict) or catalog.get("full_port") is not False
            or catalog.get("source_sha256") != digest or catalog.get("decomposer_mode") != (mode.value if mode else None)):
        raise InputError("Built JAR failed the generated-class, mod ID, or partial-port verification.")


def convert_catalog(pack: ModPack, mod: PackMod, profile: Profile, output: Path, timeout: int) -> Conversion:
    if not 1 <= timeout <= 1800:
        raise InputError("Use --timeout 1–1800 seconds.")
    output = output.expanduser()
    if output.suffix.lower() != ".jar" or output.exists() or output.is_symlink():
        raise InputError("Choose a new .jar output path. Existing files are never overwritten.")
    output = output.resolve()
    if pack.root.is_dir() and output.is_relative_to(pack.root):
        raise InputError("Write the new JAR outside the legacy modpack.")
    if mod.audit.source_version != profile.version or profile.mod_id not in {info.mod_id.casefold() for info in mod.audit.mods}:
        raise InputError(f"This profile requires the {profile.mod_id!r} mod for Minecraft {profile.version}.")
    with mod.path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    _decomposer_mode(profile, digest)  # Reject an unpinned input before recovering any declarations.
    locations = [_location(pack, mod, owner) for owner in profile.owners]
    entries = _catalog_entries(profile, locations)
    textures = _textures(entries, mod, locations)
    report = pack.report(mod.archive)["archives"][0]
    observed = {
        "declared": [{"id": entry["id"], "required": entry["required"], "status": entry["status"]}
                     for entry in report["declared_dependencies"]],
        "pack_provider_count": len(report["binary_dependencies"]),
        "bundled_legacy_dependencies": False,
    }
    with tempfile.TemporaryDirectory(prefix="mcmodup-convert-") as scratch:
        workspace = Path(scratch) / "project"
        files, recipe_count = _project_files(profile, entries, textures, digest, observed, locations)
        mode = _decomposer_mode(profile, digest)
        if mode in (DecomposerMode.FINAL, DecomposerMode.RELEASE_394):
            mapping = minechem_polytool.asset_paths(profile.namespace)
            if mode == DecomposerMode.FINAL:
                mapping.update(minechem_final_synthesis.asset_paths(profile.namespace))
            with open_input(mod.path) as source:
                reference = minechem_final_synthesis.read_assets(source, mapping)
            files.update({f"src/main/resources/{new}": reference[old] for old, new in mapping.items()})
        write_workspace(workspace, files)
        built = _gradle(workspace, timeout)
        _check_artifact(built, profile, digest)
        output.parent.mkdir(parents=True, exist_ok=True)
        # Don't expose a half-written result, or overwrite an output created during Gradle's run.
        staging_path = None
        try:
            with tempfile.NamedTemporaryFile(dir=output.parent, delete=False) as staging:
                staging_path = Path(staging.name)
                with built.open("rb") as source:
                    shutil.copyfileobj(source, staging)
            os.link(staging_path, output)
        finally:
            if staging_path is not None:
                staging_path.unlink(missing_ok=True)
    return Conversion(output, profile, len(entries), recipe_count)
