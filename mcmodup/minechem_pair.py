"""Prepare two release-isolated Minechem recipes with linked output-slot logic."""

import hashlib
import json
from pathlib import Path
import shutil
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from . import NEOFORGE_VERSION
from .api import ClassLocation
from .catalogs import PROFILES, RELEASE_394_PROFILE, RELEASE_394_SHA256, CatalogEntry, Profile
from .classfiles import CLASS_LIMIT
from .convert import _catalog_entries, _icon_paths, _project_files
from .assets import PNG_SIGNATURE
from .inputs import InputError, open_input
from .machines import FINAL_LEGACY_SHA256, DecomposerMode, machine_recipes
from .method_ports import parse_method_ports, transplant
from .port import TEMPLATES, empty_test_structure, write_workspace
from .source_ports import digest


OWNER = "minechem/tileentity/decomposer/DecomposerTileEntity"
STACK = "net/minecraft/item/ItemStack"
RESOURCE = "src/main/resources/"
FINAL = PROFILES["minechem-chemistry"]
RELEASE_394 = PROFILES[RELEASE_394_PROFILE]
VERSIONS = {FINAL.namespace: "1.3.1-partial", RELEASE_394.namespace: "1.3.0-partial"}
FEATURES = {"Buffered iron decomposition and saved remaining output": "savedbufferdrains",
            "Original decomposer output-slot method": "linkedslotrule"}
FEATURES_394 = FEATURES | {"All 198 measured item and molecule decomposition rules": "materialdecompositionmatchestrace",
                           "5,000 mB water tank, shared input priority and buffer save": "watertankroundtripandpriority",
                           "Measured .394 synthesis batches with exact ghost slot counts and extraction cost": "exactsynthesisbatches",
                           "Ghost editing and saved synthesis, no unpaid extraction": "ghosteditingandsavedsynthesis"}


def release394_checks(package: str, test_namespace: str, entries: list[CatalogEntry]) -> str:
    cases = []
    for rule in machine_recipes(RELEASE_394.namespace, entries, mode=DecomposerMode.RELEASE_394):
        if rule.machine == "decomposer" and not rule.fluid_input:
            products = ",".join(f"{key}@{count}" for key, count in rule.outputs)
            cases.append(f"        {json.dumps(f'{rule.id}={products}')}")
    return ((TEMPLATES / "minechem/Release394Checks.java").read_text(encoding="utf-8")
            .replace("@PACKAGE@", package).replace("@TEST_NAMESPACE@", test_namespace)
            .replace("@TRACE@", ",\n".join(cases)))


def synthesis394_checks(package: str, test_namespace: str, entries: list[CatalogEntry]) -> str:
    cases = []
    for rule in machine_recipes(RELEASE_394.namespace, entries, mode=DecomposerMode.RELEASE_394):
        if rule.machine == "synthesizer":
            pattern = ",".join(f"{part[0]}@{part[1]}" if part else "_" for part in rule.ghost_pattern)
            cases.append(f"        {json.dumps(f'{rule.id};{rule.outputs[0][1]};{rule.energy};{pattern}')}")
    return ((TEMPLATES / "minechem/Synthesis394Checks.java").read_text(encoding="utf-8")
            .replace("@PACKAGE@", package).replace("@TEST_NAMESPACE@", test_namespace)
            .replace("@TRACE@", ",\n".join(cases)))


def method_group(profile: Profile) -> dict:
    namespace = profile.namespace
    root = f"mcmodup_kernel/{namespace}"
    access, cell = f"{root}/DecomposerAccess", f"{root}/DecomposerAccess$Cell"
    group = {"owner": OWNER, "output": f"{root}/DecomposerLogic", "types": {OWNER: access, STACK: cell}}
    if profile == FINAL:
        return group | {"methods": {f"{OWNER}#canUnjam()Z": "canUnjam"},
                        "members": {f"{OWNER}#func_70301_a(I)L{STACK};": f"{access}#getSlot(I)L{cell};"},
                        "redirects": {"getstatic": {f"{OWNER}#outputSlots:[I": f"{access}#outputSlots()[I"}}}
    return group | {"methods": {f"{OWNER}#pushQueue()V": "compact"},
                    "members": {f"{OWNER}#func_70299_a(IL{STACK};)V": f"{access}#setSlot(IL{cell};)V",
                                f"{STACK}#func_77976_d()I": f"{cell}#limit()I"},
                    "redirects": {"getfield": {f"{OWNER}#inventory:[L{STACK};": f"{access}#inventory(L{access};)[L{cell};",
                                                f"{STACK}#field_77994_a:I": f"{cell}#count(L{cell};)I"},
                                  "putfield": {f"{STACK}#field_77994_a:I": f"{cell}#setCount(L{cell};I)V"},
                                  "invokestatic": {f"minechem/utils/Compare#stacksAreSameKind(L{STACK};L{STACK};)Z":
                                                   f"{access}#sameKind(L{cell};L{cell};)Z"}}}


def _adapter(namespace: str, prepaid: bool) -> str:
    entry = """public static boolean canUnjam(IItemHandler outputs) {
        return DecomposerLogic.canUnjam(new DecomposerAccess(outputs));
    }""" if not prepaid else """public static void compact(IItemHandlerModifiable outputs) {
        var view = new DecomposerAccess(outputs);
        DecomposerLogic.compact(view);
        for (int i = 1; i <= OUTPUTS; i++) {
            var cell = view.slots[i];
            var stack = cell == null || cell.stack.isEmpty() ? ItemStack.EMPTY : cell.stack;
            if (!ItemStack.matches(stack, outputs.getStackInSlot(i - 1))) outputs.setStackInSlot(i - 1, stack);
        }
    }"""
    return ((TEMPLATES / "minechem/DecomposerAccess.java").read_text(encoding="utf-8")
            .replace("@NAMESPACE@", namespace).replace("@OUTPUTS@", "18" if prepaid else "9").replace("@ENTRY@", entry))


def _checked(original: Path, profile: Profile, expected: str) -> Path:
    original = original.expanduser().resolve()
    if original.suffix.lower() != ".jar" or not original.is_file() or digest(original) != expected:
        raise InputError(f"Supply the exact original Minechem {profile.version} JAR (SHA-256 {expected}).")
    return original


def _recipe(original: Path, profile: Profile, checksum: str, output: Path) -> dict:
    namespace, prepaid = profile.namespace, profile == RELEASE_394
    version = VERSIONS[namespace]
    artifact = f"{namespace}-{version}.jar"
    methods = method_group(profile)
    aliases = {namespace: version}
    (plan,) = parse_method_ports([methods], aliases)
    locations = [ClassLocation(original, owner + ".class", owner) for owner in profile.owners]
    entries = _catalog_entries(profile, locations)
    with open_input(original) as source:
        if source.warnings:
            raise InputError("Cannot verify a Minechem original with duplicate or unsafe members")
        transplant(source.read(OWNER + ".class", CLASS_LIMIT), plan)
        names = {entry.texture for entry in entries}
        textures = {name: source.read(name, 1024 * 1024) for name in sorted(names)}
        for name, data in textures.items():
            if not data.startswith(PNG_SIGNATURE) or f"{name}.mcmeta" in source.files:
                raise InputError(f"Expected a standalone catalog PNG: {name!r}")
    deps = {"bundled_legacy_dependencies": False, "adapter": "native FE and isolated item slots"}
    files, _ = _project_files(profile, entries, textures, checksum, deps, locations,
                              linked_decomposer=True, project_version=version)
    icons = _icon_paths(namespace, textures)
    # The template ZIP carries recovered declarations only. The build reads
    # image bytes and method bodies from the same fingerprinted original.
    for target in icons.values():
        files.pop(RESOURCE + target)
    package = f"mcmodup.generated.{namespace}"
    java_root = package.replace(".", "/")
    tests = f"{java_root}/DecomposerChecks"
    files[f"src/main/java/{tests}.java"] = ((TEMPLATES / "minechem/DecomposerChecks.java").read_text(encoding="utf-8")
        .replace("@NAMESPACE@", namespace).replace("@PREPAID@", str(prepaid).lower()))
    if prepaid:
        files[f"src/main/java/{java_root}/Release394Checks.java"] = release394_checks(package, namespace, entries)
        files[f"src/main/java/{java_root}/Synthesis394Checks.java"] = synthesis394_checks(package, namespace, entries)
    files[f"src/main/java/mcmodup_kernel/{namespace}/DecomposerAccess.java"] = _adapter(namespace, prepaid)
    structure = f"data/{namespace}/structure/test_room.nbt"
    files[RESOURCE + structure] = empty_test_structure()
    metadata = RESOURCE + "META-INF/neoforge.mods.toml"
    files[metadata] = files[metadata].replace(f"[{NEOFORGE_VERSION},21.2)", f"[{NEOFORGE_VERSION}]")
    files["build.gradle"] += f"""
// Includes the relocated method subset, not the original archive.
tasks.named('compileJava') {{ classpath += files('src/main/resources') }}
tasks.named('jar') {{
    archiveFileName = '{artifact}'
    exclude '{tests}.class', '{java_root}/Release394Checks*.class', '{java_root}/Synthesis394Checks*.class', '{structure}'
}}
neoForge.runs.create('gameTestServer') {{
    type = 'gameTestServer'
    gameDirectory = project.file('run')
    systemProperty 'neoforge.enabledGameTestNamespaces', '{namespace}'
    systemProperty 'mcmodup.gameReport', layout.buildDirectory.file('gametest.xml').get().asFile.absolutePath
}}
"""
    archive = output / f"{namespace}-src.zip"
    with tempfile.TemporaryDirectory(prefix="mcmodup-minechem-recipe-") as scratch:
        workspace = Path(scratch) / "src"
        write_workspace(workspace, files)
        with ZipFile(archive, "x") as zipped:
            for path in sorted(workspace.rglob("*")):
                if path.is_file():
                    member = ZipInfo(path.relative_to(workspace).as_posix())
                    member.create_system = 3
                    member.external_attr = (0o100755 if member.filename == "gradlew" else 0o100644) << 16
                    zipped.writestr(member, path.read_bytes(), compress_type=ZIP_DEFLATED)
    if digest(original) != checksum:
        raise InputError("Minechem original changed during recipe preparation")
    return {"legacy_sha256": checksum, "source_zip": archive.name, "source_sha256": digest(archive),
            "ids": aliases, "legacy_ids": {"minechem": namespace}, "neoforge": f"[{NEOFORGE_VERSION}]",
            "artifact": f"build/libs/{artifact}", "tasks": ["jar", "runGameTestServer"], "game_report": "build/gametest.xml",
            "minimum_tests": len(FEATURES_394 if prepaid else FEATURES), "features": FEATURES_394 if prepaid else FEATURES,
            "status": "preview", "limitations": [profile.not_ported, "Fresh worlds only; no legacy ID/save migration or add-on API.",
                "Native FE, menus and item components replace legacy power, GUI and stack APIs; no original client comparison."],
            "method_imports": [methods],
            "import_assets": {old: RESOURCE + new for old, new in icons.items()},
            "asset_map": {old: {"path": new, "sha256": hashlib.sha256(textures[old]).hexdigest()} for old, new in icons.items()}}


def prepare(final: Path, release394: Path, output: Path) -> Path:
    final = _checked(final, FINAL, FINAL_LEGACY_SHA256)
    release394 = _checked(release394, RELEASE_394, RELEASE_394_SHA256)
    output = output.expanduser()
    if output.exists() or output.is_symlink():
        raise InputError("Choose a new directory for the paired Minechem recipes")
    output.mkdir(parents=True, exist_ok=False)
    try:
        rows = [_recipe(final, FINAL, FINAL_LEGACY_SHA256, output),
                _recipe(release394, RELEASE_394, RELEASE_394_SHA256, output)]
        manifest = output / "minechem-pair.json"
        manifest.write_text(json.dumps({"schema_version": 1, "ports": rows}, indent=2) + "\n", encoding="utf-8")
        return manifest.resolve()
    except BaseException:
        shutil.rmtree(output)
        raise
