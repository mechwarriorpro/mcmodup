"""Plan, build, and stage standalone NeoForge updates from installed modpacks."""

from collections import defaultdict, deque
from dataclasses import dataclass, field, replace
from io import BytesIO
import json
from pathlib import Path
import re
import shutil
import tempfile
import tomllib
from zipfile import BadZipFile, ZipFile
import zlib

from . import MINECRAFT_VERSION, NEOFORGE_VERSION, SOURCE_VERSIONS
from .audit import ModMetadata
from .catalogs import PROFILES
from .classfiles import CLASS_LIMIT, class_entries, read_class
from .inputs import InputError, TEXT_LIMIT, open_input
from .legacy_dependencies import LegacyDependencies, legacy_conflicts
from .method_ports import PROVENANCE as METHOD_PROVENANCE
from .native_resolution import Resolution, dependency_report, resolve
from .pack import PackMod, _load
from .private_libraries import PRIVATE_ROOT, PrivateLibrary, inspect as inspect_private_libraries
from .recipe_conversion import PROVENANCE as RECIPE_PROVENANCE, RecipeConversion, analyze as analyze_recipes
from .runtime import ARCHIVE_EXTENSIONS, InstanceRuntime, Platform, discover, instance_runtime, local_files
from .source_ports import BuildOptions, Dependency, MOD_ID, SHA256, SourcePort, build, load_ports, require_jdk
from .source_ports import digest as _digest, safe_component as _safe_filename
from .versions import matches, supported_range


NATIVE_META = "META-INF/neoforge.mods.toml"
CONNECTOR_PREFIXES = (b"legacyconnector/", b"org/kylepitcock/legacyconnector/", b"org.kylepitcock.legacyconnector.")
CONNECTOR_ENTRIES = {"META-INF/legacyconnector/provenance.json", "META-INF/legacyconnector/legacyconnector-runtime.jar",
                     "org/kylepitcock/legacyconnector/loader/LegacyModLocator.class"}
MAX_ARCHIVES = 4096
REPORT_NAME = "update-report.json"


def _archive(path: Path) -> bool:
    return path.suffix.lower() in ARCHIVE_EXTENSIONS


@dataclass(eq=False)
class NativeMod:
    path: Path
    label: str
    ids: dict[str, str]
    dependencies: tuple[Dependency, ...]
    installed: bool
    target_evidence: bool
    sha256: str
    partial: bool = False
    recipe: SourcePort | None = None
    original: Path | None = None
    classes: frozenset[str] = frozenset()
    private_libraries: tuple[PrivateLibrary, ...] = ()
    private_references: frozenset[str] = frozenset()
    embedded: tuple[str, ...] = ()
    conversion: RecipeConversion | None = None
    resource_namespaces: frozenset[str] = frozenset()


def _main_manifest(text: str) -> dict[str, str]:
    return {key.casefold(): value for line in text.replace("\r\n", "\n").replace("\n ", "").split("\n\n", 1)[0].splitlines()
            for key, sep, value in [line.partition(": ")] if sep}


def _native(path: Path, label: str, neoforge: str, installed: bool = False) -> NativeMod | None:
    with open_input(path) as jar:
        entries = jar.files
        if CONNECTOR_ENTRIES.intersection(entries) or any(name.startswith(("legacyconnector/", "org/kylepitcock/legacyconnector/"))
                                                         for name in entries):
            raise InputError("Legacy Connector and its cached derivatives are not standalone updates")
        if NATIVE_META not in entries:
            return None
        if path.suffix.lower() != ".jar" or not _safe_filename(path.name) or jar.warnings:
            raise InputError("A native update must be a readable .jar without duplicate or unsafe entries")
        manifest = (_main_manifest(jar.text("META-INF/MANIFEST.MF")) if "META-INF/MANIFEST.MF" in entries else {})
        if {"fmlcoreplugin", "fmlat", "tweakclass"} & manifest.keys():
            raise InputError("Legacy launcher hooks are not native NeoForge code")
        try:
            data = tomllib.loads(jar.text(NATIVE_META))
        except (ValueError, RecursionError) as exc:
            raise InputError(f"Invalid NeoForge metadata: {exc}") from exc
        ids = {}
        mods = data.get("mods")
        if not isinstance(mods, list) or not mods or not isinstance(data.get("modLoader"), str):
            raise InputError("Missing NeoForge mod declarations")
        if not any(name.endswith(".class") for name in entries):
            # NeoForge's built-in low-code loader legitimately loads data-only mods.
            if (data["modLoader"] != "lowcodefml" or "pack.mcmeta" not in entries or
                    not any(name.startswith(("data/", "assets/")) for name in entries)):
                raise InputError("No compiled mod code or low-code resources in this native JAR")
        for row in mods:
            mod_id = row.get("modId") if isinstance(row, dict) else None
            version = row.get("version", "") if isinstance(row, dict) else ""
            if (not isinstance(mod_id, str) or not MOD_ID.fullmatch(mod_id) or mod_id in ids or
                    mod_id in {"minecraft", "neoforge", "forge", "legacyconnector"} or not isinstance(version, str)):
                raise InputError("Invalid, duplicate or reserved NeoForge mod ID/version")
            ids[mod_id] = manifest.get("implementation-version", "") if version == "${file.jarVersion}" else version
        checksum = _digest(path)
        requirements = set()
        platform = {"minecraft": MINECRAFT_VERSION, "neoforge": neoforge}
        evidence = False
        partial = bool({"META-INF/MCModUp-NOTICE.txt", RECIPE_PROVENANCE, METHOD_PROVENANCE}.intersection(entries))
        deps = data.get("dependencies", {})
        if not isinstance(deps, dict):
            raise InputError("Invalid NeoForge dependencies table")
        for mod_id in ids:
            rows = deps.get(mod_id, [])
            if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
                raise InputError(f"Invalid dependency rows for {mod_id}")
            for row in rows:
                name, spec = row.get("modId"), row.get("versionRange", "")
                if not isinstance(name, str) or not MOD_ID.fullmatch(name) or not isinstance(spec, str):
                    raise InputError(f"Invalid dependency in {mod_id}")
                if name in {"legacyconnector", "forge", "fml"}:
                    runtime = "Legacy Connector" if name == "legacyconnector" else "legacy/non-NeoForge loaders"
                    raise InputError(f"{mod_id} declares a dependency on {runtime}")
                kind = row.get("type", "required" if row.get("mandatory", True) else "optional")
                if isinstance(kind, str):
                    kind = kind.casefold()
                spec = spec.replace("${file.jarVersion}", manifest.get("implementation-version", "${file.jarVersion}"))
                if kind not in ("incompatible", "required", "optional", "discouraged"):
                    raise InputError(f"Unknown NeoForge dependency type in {mod_id}")
                if name in platform or name in ids:
                    actual = platform[name] if name in platform else ids[name]
                    fits = matches(actual, spec)
                    if kind != "discouraged" and not supported_range(spec):
                        raise InputError(f"{mod_id}: unsupported {name} version range {spec!r}")
                    if (kind in {"required", "optional"} and not fits) or (kind == "incompatible" and fits):
                        raise InputError(f"{mod_id}: incompatible {name} {actual} (declared {kind} {spec!r})")
                    if name in platform and kind in {"required", "optional"}:
                        evidence |= spec.strip() not in {"", "*"}
                elif kind != "discouraged":
                    requirements.add(Dependency(name, spec, kind))
        # Check actual class constants too, not only dependency declarations. Never load these classes.
        refs: set[str] = set()
        embedded = _check_code(entries, jar.read, private_refs=refs)
        private = inspect_private_libraries(jar, ids, {dep.mod_id for dep in requirements})
        namespaces = frozenset(parts[1] for name in entries if name.startswith(("assets/", "data/"))
                               and len(parts := name.split("/", 2)) == 3)
        return NativeMod(path, label, ids, tuple(sorted(requirements, key=lambda d: (d.mod_id, d.version, d.kind))),
                         installed, evidence, checksum, partial, classes=frozenset(class_entries(entries)),
                         private_libraries=private, private_references=frozenset(refs), embedded=embedded,
                         resource_namespaces=namespaces)


def _check_code(entries, read, *, nested: bool = False, private_refs: set[str] | None = None) -> tuple[str, ...]:
    if CONNECTOR_ENTRIES.intersection(entries) or any(name.startswith(("legacyconnector/", "org/kylepitcock/legacyconnector/"))
                                                     for name in entries):
        raise InputError("Legacy Connector and its cached derivatives are not standalone updates")
    for name in entries:
        if name.endswith(".class"):
            data = read(name, CLASS_LIMIT)
            if not data.startswith(b"\xca\xfe\xba\xbe"):
                raise InputError(f"Invalid class in native JAR: {name}")
            if any(prefix in data for prefix in CONNECTOR_PREFIXES):
                raise InputError(f"{name} refers to Legacy Connector")
            if b"cpw/mods/fml/" in data:
                raise InputError(f"{name} still uses the legacy FML API")
            if b"mcmodup_private/" in data and private_refs is not None:
                private_refs.update(ref.owner for ref in read_class(data).references if ref.owner.startswith(PRIVATE_ROOT))
    # Inspect NeoForge Jar-in-Jar payloads as well. They aren't taken as replacements for
    # standalone mod IDs: version negotiation happens in the target loader, not in this planner.
    embedded = sorted(name for name in entries if name.startswith("META-INF/jarjar/") and name.endswith(".jar"))
    if embedded and (nested or len(embedded) > 30):
        raise InputError("Could not verify a deeply nested or oversized Jar-in-Jar set")
    for name in embedded:
        try:
            with ZipFile(BytesIO(read(name, 32 * 1024 * 1024))) as archive:
                info = archive.infolist()
                names = {entry.filename for entry in info}
                if len(names) != len(info):
                    raise InputError(f"Duplicate entries in embedded {name}")

                def inner_read(member: str, maximum: int) -> bytes:
                    if archive.getinfo(member).file_size > maximum:
                        raise InputError(f"Embedded {member!r} exceeds the inspection limit")
                    return archive.read(member)

                if NATIVE_META in names:
                    metadata = tomllib.loads(inner_read(NATIVE_META, TEXT_LIMIT).decode("utf-8-sig"))
                    includes = metadata.get("dependencies", {})
                    rows = metadata.get("mods", [])
                    if not isinstance(includes, dict) or not isinstance(rows, list):
                        raise InputError(f"Invalid embedded mod metadata in {name}")
                    if any(isinstance(row, dict) and row.get("modId") == "legacyconnector"
                           for row in [*rows, *(d for deps in includes.values() if isinstance(deps, list) for d in deps)]):
                        raise InputError(f"Embedded {name} contains or depends on Legacy Connector")
                _check_code(names, inner_read, nested=True, private_refs=private_refs)
        except (BadZipFile, ValueError, RuntimeError, OSError, zlib.error) as exc:
            raise InputError(f"Could not verify embedded {name}: {exc}") from exc
    return tuple(embedded)


@dataclass
class LegacyEntry:
    path: Path
    label: str
    audit: PackMod | None = None
    problem: str = ""

    @property
    def ids(self) -> tuple[str, ...]:
        return tuple(sorted({m.mod_id.casefold() for m in self.audit.audit.mods if m.mod_id})) if self.audit else ()

    @property
    def version(self) -> str | None:
        return self.audit.audit.source_version if self.audit else None

    @property
    def name(self) -> str:
        if self.audit:
            return next((m.name for m in self.audit.audit.mods if m.name), self.path.stem)
        return self.path.stem


@dataclass
class UpdateInventory:
    source: Path
    target: Path
    source_platform: Platform
    target_platform: Platform
    entries: dict[str, LegacyEntry]
    addons: dict[str, dict[str, str]]
    providers: dict[str, list[NativeMod]]
    installed: list[NativeMod]
    target_legacy: dict[str, LegacyEntry]
    passthrough: list[Path]
    aliases: dict[str, str]
    warnings: list[str] = field(default_factory=list)
    target_issues: list[str] = field(default_factory=list)
    build_options: BuildOptions | None = None
    recipes: dict[str, NativeMod] = field(default_factory=dict)
    recipe_issues: dict[str, str] = field(default_factory=dict)
    approved_sources: dict[str, SourcePort] = field(default_factory=dict)
    legacy_declarations: dict[str, list[dict]] = field(default_factory=dict)
    legacy_binary: dict[str, dict] = field(default_factory=dict)
    id_map: Path | None = None
    conversions: dict[str, NativeMod] = field(default_factory=dict)
    conversion_issues: dict[str, str] = field(default_factory=dict)
    allow_recipe_preview: bool = False

    def native_ids(self, item: LegacyEntry) -> set[str]:
        candidate = self.recipes.get(item.label)
        if candidate and candidate.original == item.path and candidate.recipe.legacy_ids is not None:
            return set(candidate.ids)
        return {self.aliases.get(mod_id, mod_id) for mod_id in item.ids}

    def native_binding_id(self, label: str, legacy_id: str) -> str | None:
        candidate = self.recipes.get(label)
        if candidate and candidate.recipe.legacy_ids is not None and legacy_id in candidate.recipe.legacy_ids:
            return candidate.recipe.legacy_ids[legacy_id]
        return self.aliases.get(legacy_id, legacy_id)

    def catalog(self) -> dict:
        rows = []
        present = {mod_id for artifact in self.installed if not artifact.partial for mod_id in artifact.ids}
        for label, item in self.entries.items():
            ids = self.native_ids(item)
            recipe = self.recipes.get(label)
            conversion = self.conversions.get(label)
            if self.build_options is not None:
                if recipe is None:
                    status, reason = "blocked", self.recipe_issues[label]
                elif recipe.recipe.preview:
                    status, reason = "preview", "Builds from reviewed source, with documented gaps."
                else:
                    status, reason = "buildable", "Exact source recipe found. Builds and runs NeoForge GameTests."
            elif not item.problem and ids and ids <= present:
                status, reason = "installed", "Native builds are already in the target mods folder."
            elif not item.problem and ids and all(self.providers.get(key) for key in ids):
                status, reason = "available", "Matching local NeoForge builds found. Select to check dependencies and add-ons."
            elif conversion:
                count = len(conversion.conversion.recipes)
                status = "recipe-preview"
                reason = f"Translate {count} literal recipe{'s' if count != 1 else ''} from the original. Not a full-parity update."
                if not self.allow_recipe_preview:
                    reason += " Requires --allow-recipe-preview with --output-pack."
            elif not item.problem and _has_partial_profile(item):
                status, reason = "partial", "Only a partial conversion profile exists here. It is not a replacement or add-on compatible."
            else:
                missing = sorted(key for key in ids if not self.providers.get(key))
                status, reason = "blocked", item.problem or ("No matching local NeoForge build for " + ", ".join(missing) + "." if missing else
                                                            "No recognizable legacy mod IDs; automatic matching is unavailable.")
            if status == "blocked" and label in self.conversion_issues and self.build_options is None:
                reason += " Automatic recipe conversion: " + self.conversion_issues[label]
            rows.append({"id": label, "name": item.name, "minecraft": item.version, "mods": list(item.ids),
                         "releases": {m.mod_id: m.version for m in item.audit.audit.mods if m.mod_id} if item.audit else {},
                         "native_ids": sorted(ids), "status": status, "reason": reason, "addons": len(self.addons.get(label, {})),
                         **({"source_build": recipe.recipe.describe()} if recipe else {}),
                         **({"conversion": conversion.conversion.report()} if status == "recipe-preview" else {})})
        return {"source": self.source.name, "target": self.target.name, "minecraft": MINECRAFT_VERSION,
                "neoforge": self.target_platform.neoforge, "source_build_mode": self.build_options is not None,
                "allow_recipe_preview": self.allow_recipe_preview,
                "source_bin": self.source_platform.report(),
                "target_bin": self.target_platform.report(), "rows": rows, "warnings": self.warnings, "target_issues": self.target_issues}


def _has_partial_profile(item: LegacyEntry) -> bool:
    for profile in PROFILES.values():
        if profile.mod_id not in item.ids or profile.version != item.version:
            continue
        if profile.source_sha256 is None:
            return True
        try:
            if _digest(item.path) == profile.source_sha256:
                return True
        except OSError:
            return False
    return False


def _legacy(path: Path, label: str, runtime: Platform | None) -> LegacyEntry:
    try:
        audit = _load(label, path, None, runtime)
        known = {e.version for e in audit.audit.version_evidence if e.kind in {"declared", "versioned folder"}}
        problem = ("Conflicting source Minecraft versions; review the input first." if len(known) > 1 else
                   "Unreadable legacy bytecode" if audit.corrupt_classes and not audit.classes else "")
        return LegacyEntry(path, label, audit, problem)
    except (InputError, OSError) as exc:
        return LegacyEntry(path, label, problem=str(exc))


def _legacy_graph(entries: dict[str, LegacyEntry], libraries: dict[str, PackMod], runtime_owners: set[str]) -> tuple[dict, dict, dict]:
    graph = LegacyDependencies({**libraries, **{label: item.audit for label, item in entries.items() if item.audit and not item.problem}})
    edges, declarations, binary = defaultdict(dict), {}, {}
    for label, item in entries.items():
        if label not in graph.mods:
            continue
        declared = graph.declarations(item.audit)
        declarations[label] = declared
        by_path, ambiguous = graph.usage(item.audit, declared, platform_owners=runtime_owners)
        binary[label] = {"providers": [{"archive": path, "classes": sorted(owners)} for path, owners in sorted(by_path.items())],
                         "ambiguous_classes": sorted(ambiguous)}
        declared_paths = {candidate["archive"] for row in declared for candidate in row["candidates"]}
        for row in declared:
            if row["required"] and row["provider"] and (provider := row["provider"]["archive"]) in entries and provider != label:
                reason = f"declares required dependency {row['id']}" + (f" {row['range']}" if row["range"] else "")
                edges[provider][label] = reason
        for path, owners in sorted(by_path.items()):
            if path in entries and path not in declared_paths:
                edges[path][label] = f"references {min(owners).replace('/', '.')}"
    return dict(edges), declarations, binary


def _aliases(path: Path | None) -> dict[str, str]:
    if path is None or not path.exists():
        return {}
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 128 * 1024:
        raise InputError("mcmodup-updates.json must be a small local JSON object of old IDs to native IDs")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, RecursionError) as exc:
        raise InputError(f"Invalid update ID map: {exc}") from exc
    if (not isinstance(data, dict) or any(not isinstance(new, str) or not MOD_ID.fullmatch(new) or
            new in {"minecraft", "neoforge", "forge", "legacyconnector"} for new in data.values()) or
            len(data) != len({key.casefold() for key in data})):
        raise InputError("mcmodup-updates.json must map distinct legacy mod IDs to native mod IDs")
    return {key.casefold(): value for key, value in data.items()}


def inventory(source: Path, target: Path, build_options: BuildOptions | None = None, *,
              approved_sources: dict[str, SourcePort] | None = None, id_map: Path | None = None,
              allow_recipe_preview: bool = False) -> UpdateInventory:
    if build_options and allow_recipe_preview:
        raise InputError("Use recipe preview separately from reviewed source ports.")
    source, target = (path.expanduser().resolve() for path in (source, target))
    if not source.is_dir() or not any((source / name).is_dir() for name in ("mods", "coremods")):
        raise InputError(f"Expected an installed source instance with mods/ or coremods/: {source}")
    if not target.is_dir():
        raise InputError(f"Expected an installed NeoForge target instance: {target}")
    bins = {root: local_files(root, "bin") for root in dict.fromkeys((source, target))}
    src_bin = discover(source, bins=bins[source])
    dst_bin = discover(target, include_server=True, bins=bins[target])
    if not dst_bin.neoforge or {version for version, _ in dst_bin.minecraft} != {MINECRAFT_VERSION}:
        raise InputError(f"Target bin/ or standard server libraries/ must identify Minecraft {MINECRAFT_VERSION} and one installed NeoForge 21.1.x release. "
                         f"An installer alone or mixed runtime versions are not an installation: {target}")
    entries, target_legacy, native, installed, passthrough = {}, {}, [], [], []
    warnings, issues = [*src_bin.warnings, *dst_bin.warnings], []
    libraries = {}
    runtime_owners = set()
    if src_bin.legacy:
        for path in src_bin.legacy_apis(source, src_bin.legacy):
            try:
                with open_input(path) as jar:
                    runtime_owners.update(class_entries(jar.files))
            except InputError as exc:
                warnings.append(f"Could not read local platform classes from {path.name}: {exc}")
    scanned = 0
    for root in bins:
        is_source, is_target = root == source, root == target
        runtime = dst_bin if is_target else src_bin
        platform_labels = {f.path for f in runtime.files} | ({f.path for f in src_bin.files} if is_source else set())
        local = sorted({*bins[root], *local_files(root, "mods"), *(local_files(root, "coremods") if is_source else ()),
                        *local_files(root, "lib"), *local_files(root, "libraries")})
        for path in local:
            label = path.relative_to(root).as_posix()
            top = label.split("/", 1)[0]
            if label in platform_labels:
                continue
            active = is_target and top == "mods"
            old = is_source and top in {"mods", "coremods"}
            if not _archive(path):
                if active:
                    passthrough.append(path)
                if old and path.suffix.lower() == ".litemod":
                    entries[label] = LegacyEntry(path, label, problem="LiteLoader add-on; no supported native replacement lookup.")
                continue
            scanned += 1
            if scanned > MAX_ARCHIVES:
                raise InputError(f"More than {MAX_ARCHIVES} local archives; use a smaller installed instance.")
            try:
                mod = _native(path, ("target/" if is_target else "source/") + label, dst_bin.neoforge, active)
            except (InputError, OSError) as exc:
                if active:
                    issues.append(f"Target {label!r}: {exc}")
                if old:
                    entries[label] = LegacyEntry(path, label, problem=str(exc))
                elif not active and path.suffix.lower() == ".jar" and len(warnings) < 30:
                    warnings.append(f"Skipped local candidate {label!r}: {exc}")
                continue
            if mod is not None:
                if active:
                    installed.append(mod)
                    if not mod.target_evidence:
                        warnings.append(f"Kept installed {label!r}; its metadata does not pin the target platform.")
                if (mod.target_evidence or active) and not mod.partial:
                    native.append(mod)
                elif mod.partial and not active:
                    warnings.append(f"Skipped {label!r}: an mcmodup partial profile is not a drop-in replacement.")
                elif len(warnings) < 30:
                    warnings.append(f"Skipped local candidate {label!r}: no Minecraft/NeoForge target range in metadata.")
            elif old or active:
                record = _legacy(path, label, src_bin if old else None)
                if active:
                    target_legacy[label] = record
                if old:
                    entries[label] = record
            elif is_source and top in {"lib", "libraries", "bin"}:
                record = _legacy(path, label, src_bin)
                if record.audit and not record.problem and (record.audit.classes or record.ids):
                    libraries[label] = record.audit
    providers = defaultdict(list)
    for mod in native:
        for mod_id in mod.ids:
            providers[mod_id].append(mod)
    map_path = id_map.expanduser().resolve(strict=True) if id_map is not None else source / "mcmodup-updates.json"
    state = UpdateInventory(source, target, src_bin, dst_bin, entries, {}, providers, installed,
                            target_legacy, passthrough, _aliases(map_path), warnings, issues, id_map=id_map,
                            allow_recipe_preview=allow_recipe_preview)
    if build_options is None:
        _with_recipe_conversions(state)
    state.addons, state.legacy_declarations, state.legacy_binary = _legacy_graph(entries, libraries, runtime_owners)
    return with_source_ports(state, build_options, approved_sources=approved_sources) if build_options else state


def _with_recipe_conversions(state: UpdateInventory) -> None:
    for label, item in state.entries.items():
        ids = state.native_ids(item)
        if item.problem or not item.audit or not ids or all(state.providers.get(key) for key in ids):
            continue
        count = len(item.audit.classes)
        if not label.startswith("mods/"):
            state.conversion_issues[label] = "The literal recipe converter does not handle coremods."
            continue
        if item.audit.corrupt_classes or count != 1:
            state.conversion_issues[label] = (f"Indexed {count} classes ({item.audit.corrupt_classes} unreadable). "
                                              "Automatic translation supports only a single-class literal recipe archive, "
                                              "not general legacy bytecode.")
            continue
        runtime_version = state.source_platform.legacy
        evidence = {e.kind for e in item.audit.audit.version_evidence if e.version == item.version}
        # A matching runtime is sufficient, but a mixed workspace can have a
        # different loader in bin/. Require both a declaration and an explicitly
        # versioned path before using a different release's symbols in that case.
        if item.version not in SOURCE_VERSIONS or (runtime_version != item.version and (
                "declared" not in evidence or (runtime_version and "versioned folder" not in evidence))):
            state.conversion_issues[label] = ("The exact original Minecraft version needs a matching local runtime or a mod declaration. "
                                              "When bin/ has another release, also use a matching versioned mods/ folder.")
            continue
        try:
            conversion = analyze_recipes(item.path, item.version)
            if state.aliases.get(conversion.mod_id, conversion.mod_id) != conversion.mod_id:
                raise InputError("a replacement ID alias cannot rename an automatic conversion")
            if conversion.unbound_metadata_ids and runtime_version != item.version and not any(
                    e.kind == "declared" and e.origin.endswith(" (@Mod)") for e in item.audit.audit.version_evidence):
                raise InputError("unbound mcmod.info rows cannot establish the original release; supply the installed runtime")
            # This archive's entire class/resource set was established above. Use
            # the actual FML binding, not orphan metadata IDs or inactive edges.
            item.audit.audit.mods = [ModMetadata(item.audit.classes[next(iter(item.audit.classes))], conversion.legacy_id,
                                                conversion.name, conversion.version, item.version, conversion.description,
                                                conversion.authors, conversion.license)]
            if state.providers.get(conversion.mod_id):
                continue
            if state.target_platform.neoforge != NEOFORGE_VERSION:
                raise InputError(f"Recipe previews require installed NeoForge {NEOFORGE_VERSION}.")
            state.conversions[label] = NativeMod(Path(conversion.filename), f"convert/{label}",
                                                {conversion.mod_id: conversion.version}, (), False, True,
                                                conversion.sha256, conversion=conversion)
        except (InputError, OSError) as exc:
            state.conversion_issues[label] = str(exc)


def with_source_ports(state: UpdateInventory, options: BuildOptions, *,
                      approved_sources: dict[str, SourcePort] | None = None, id_map: Path | None = None) -> UpdateInventory:
    """Resolve trusted recipes against an existing inventory without rescanning its bytecode."""
    source_ports = load_ports(options) if approved_sources is None else approved_sources
    providers = defaultdict(list, {key: [mod for mod in mods if not mod.recipe] for key, mods in state.providers.items()})
    state = replace(state, providers=providers, build_options=options, approved_sources=source_ports, recipes={}, recipe_issues={})
    for label, item in state.entries.items():
        ids = state.native_ids(item)
        if item.problem or not ids:
            state.recipe_issues[label] = item.problem or "No recognizable legacy mod IDs."
            continue
        try:
            recipe = source_ports.get(_digest(item.path)) if source_ports else None
        except OSError as exc:
            state.recipe_issues[label] = f"Cannot fingerprint the original archive: {exc}"
            continue
        if recipe is None:
            issue = "No source recipe for this exact legacy archive (SHA-256)."
        elif recipe.legacy_ids is not None and recipe.legacy_ids.keys() != set(item.ids):
            issue = "The SHA-bound legacy_ids map must account for every original mod ID."
        elif recipe.legacy_ids is None and recipe.ids.keys() != ids:
            issue = "The source recipe's native IDs do not match. Review mcmodup-updates.json for renamed IDs."
        elif recipe.preview and not options.allow_preview:
            issue = "Only a preview source recipe exists. It does not meet full parity; requires --allow-preview-ports."
        elif not matches(state.target_platform.neoforge, recipe.neoforge):
            issue = f"The source recipe needs NeoForge {recipe.neoforge}; target has {state.target_platform.neoforge}."
        elif isinstance(recipe.archive, Path) and not recipe.archive.is_file():
            issue = f"Local source ZIP not found: {recipe.archive.name!r}."
        else:
            predicted = NativeMod(Path(recipe.artifact.rsplit("/", 1)[-1]), f"build/{label}", recipe.ids, recipe.dependencies,
                                  False, True, f"source:{recipe.legacy_sha256}", partial=recipe.isolated_preview,
                                  recipe=recipe, original=item.path)
            state.recipes[label] = predicted
            if not recipe.isolated_preview:
                for mod_id in predicted.ids:
                    providers[mod_id].append(predicted)
            continue
        state.recipe_issues[label] = issue
    return state


@dataclass
class UpdatePlan:
    inventory: UpdateInventory
    selected: list[str]
    addons: dict[str, dict[str, str]]
    mods: list[NativeMod]
    roots: set[str]
    removed: dict[str, str]
    problems: list[str]
    build_order: list[NativeMod] = field(default_factory=list)
    native_builds: dict[str, dict] = field(default_factory=dict)
    resolution: Resolution | None = None
    runtime: InstanceRuntime | None = None
    output_pack: bool = False
    provider_pins: dict[str, str] = field(default_factory=dict)
    legacy_hashes: dict[str, str] = field(default_factory=dict)
    legacy_selection_conflicts: list[dict] = field(default_factory=list)
    legacy_identity_splits: list[dict] = field(default_factory=list)
    include_native: tuple[str, ...] = ()

    def _legacy_evidence(self) -> tuple[list[dict], dict[str, dict]]:
        state = self.inventory
        active = {key: mod for mod in self.mods for key in mod.ids}
        requirements, binary = [], {}
        for label in sorted(set(self.selected) | self.addons.keys()):
            ids = state.native_ids(state.entries[label])
            consumers = {active[key] for key in ids if key in active}
            for row in state.legacy_declarations.get(label, ()):
                key = row["id"].casefold()
                provider = row["provider"]
                # Bind a peer's renamed ID through its actual legacy provider,
                # not another release with the same ID. Intra-archive modules
                # are already unambiguous even when a module was left out.
                identity = provider["archive"] if provider else label if key in state.entries[label].ids else ""
                native_id = state.native_binding_id(identity, key)
                binding = {"id": native_id, "status": "integration_not_established"}
                if row["status"] == "loader":
                    binding["status"] = "platform_rewrite"
                elif native_id is None:
                    binding["status"] = "omitted_preview_module"
                elif private := next((lib for mod in consumers for lib in mod.private_libraries
                                      if lib.id == native_id and lib.consumer in ids), None):
                    binding |= {"status": "private_native_library", "namespace": private.namespace, "version": private.version}
                elif (candidate := active.get(native_id)) and not candidate.partial:
                    if candidate in consumers or any(dep.mod_id == native_id and dep.kind in {"required", "optional"} and
                                                     matches(candidate.ids[native_id], dep.version)
                                                     for mod in consumers for dep in mod.dependencies):
                        binding |= {"status": "native_metadata", "archive": candidate.label, "version": candidate.ids[native_id]}
                requirements.append({**row, "consumer": label, "provider": self._legacy_hash(provider) if provider else None,
                                     "candidates": [self._legacy_hash(candidate) for candidate in row["candidates"]],
                                     "native_binding": binding})
            if label in state.legacy_binary:
                record = state.legacy_binary[label]
                binary[label] = {**record, "providers": [self._legacy_hash(p) for p in record["providers"]]}
        return requirements, binary

    def _legacy_hash(self, provider: dict) -> dict:
        return provider | {"sha256": self.legacy_hashes.get(provider["archive"])}

    @property
    def report(self) -> dict:
        requirements, binary = self._legacy_evidence()
        resolution = replace(self.resolution, mods=tuple(self.mods)) if self.resolution else Resolution(tuple(self.mods))
        mode = ("native-source-build" if self.inventory.build_options else
                "automatic-recipe-preview" if any(mod.conversion for mod in self.mods) else "native-neoforge")
        return {"schema_version": 1, "mode": mode,
                "output_mode": "pack" if self.output_pack else "mods",
                "instance_runtime": self.runtime.report() if self.runtime else None,
                "connector_required": False, "native_builds": self.native_builds,
                "build_order": [mod.label.removeprefix("build/") for mod in self.build_order],
                "minecraft": MINECRAFT_VERSION, "neoforge": self.inventory.target_platform.neoforge,
                "source_bin": self.inventory.source_platform.report(), "target_bin": self.inventory.target_platform.report(),
                "selected": self.selected, "include_native": list(self.include_native), "auto_addons": self.addons,
                "ready": bool(self.selected or self.include_native) and not self.problems,
                "problems": self.problems, "warnings": self.inventory.warnings, "replaced_target_files": self.removed,
                "legacy_requirements": requirements, "legacy_class_providers": binary,
                "legacy_version_conflicts": legacy_conflicts(requirements), "legacy_archive_sha256": self.legacy_hashes,
                "legacy_selection_conflicts": self.legacy_selection_conflicts, "legacy_identity_splits": self.legacy_identity_splits,
                "native_dependencies": dependency_report(resolution, self.inventory.providers), "provider_pins": self.provider_pins,
                "files": [{"file": f"mods/{mod.path.name}", "from": mod.label, "ids": mod.ids,
                           "sha256": None if mod.recipe and not self.native_builds else mod.sha256,
                           "action": ("build" if mod.recipe else "convert" if mod.conversion else
                                      "include-native" if mod.installed and not mod.ids.keys().isdisjoint(self.include_native) else
                                      "keep" if mod.installed and not self.output_pack else
                                      "update" if self.roots.intersection(mod.ids) else "dependency"),
                           **({"source_build": mod.recipe.describe()} if mod.recipe else {}),
                           **({"conversion": mod.conversion.report()} if mod.conversion else {}),
                           "private_libraries": [lib.report() for lib in mod.private_libraries],
                           "jar_in_jar": list(mod.embedded),
                           "requires": [{"id": dep.mod_id, "range": dep.version} for dep in mod.dependencies if dep.kind == "required"]}
                          for mod in self.mods], "gameplay_verified": False, "parity_verified": False}


def selected_addons(inventory: UpdateInventory, selected: list[str]) -> dict[str, dict[str, str]]:
    if any(label not in inventory.entries for label in selected):
        raise InputError("Select exact archive paths from the mod list, for example mods/Example.jar.")
    closure = set(selected)
    added = {}
    pending = deque(selected)
    while pending:
        label = pending.popleft()
        for addon, why in sorted(inventory.addons.get(label, {}).items()):
            if addon not in closure:
                closure.add(addon)
                pending.append(addon)
                added[addon] = {"required_by": label, "reason": why}
    return added


def _artifact_conflicts(mods: list[NativeMod] | tuple[NativeMod, ...]) -> list[str]:
    owners = defaultdict(list)
    errors = []
    for mod in mods:
        for owner in mod.classes:
            owners[owner].append(mod.label)
        for ref in sorted(mod.private_references - mod.classes):
            errors.append(f"{mod.label!r} uses private class {ref!r} outside its own JAR.")
    for owner, archives in sorted(owners.items()):
        if len(archives) > 1:
            errors.append(f"Duplicate native class {owner!r}: " + ", ".join(sorted(archives)) + ". Use one shared provider or relocate the port's private copy.")
            if len(errors) >= 30:
                break
    return errors


def parse_pins(rows: list[str]) -> dict[str, str]:
    pins = {}
    for row in rows:
        key, _, sha = row.partition("=")
        if not MOD_ID.fullmatch(key) or not SHA256.fullmatch(sha) or key in pins:
            raise InputError("Use --provider ID=SHA256 once per native mod ID with its lowercase 64-digit JAR fingerprint.")
        pins[key] = sha
    return pins


def _selected_id_conflicts(inventory: UpdateInventory, closure: set[str], hashes: dict[str, str],
                           output_pack: bool) -> tuple[list[dict], list[dict]]:
    # Filenames and a global ID alias cannot distinguish two originals. Only a
    # SHA-bound recipe for *each* input, with disjoint outputs, can opt into a split.
    by_id = defaultdict(list)
    for label in sorted(closure & hashes.keys()):
        item = inventory.entries[label]
        for key in item.ids:
            versions = sorted({m.version for m in item.audit.audit.mods if m.mod_id.casefold() == key and m.version})
            by_id[key].append({"archive": label, "minecraft": item.version, "releases": versions, "sha256": hashes[label]})
    conflicts, splits = [], []
    for key, archives in sorted(by_id.items()):
        digests = {row["sha256"] for row in archives}
        if len(digests) < 2:
            continue
        candidates = [inventory.recipes.get(row["archive"]) for row in archives]
        isolated = (output_pack and len(digests) == len(archives) and
                    all(m and m.recipe.legacy_sha256 == row["sha256"] and (m.recipe.legacy_ids or {}).get(key)
                        for row, m in zip(archives, candidates)))
        if isolated and len({target for m in candidates for target in m.ids}) == sum(len(m.ids) for m in candidates):
            splits.append({"id": key, "status": "separate_source_recipes",
                           "archives": [row | {"native_id": m.recipe.legacy_ids[key]} for row, m in zip(archives, candidates)]})
        else:
            conflicts.append({"id": key, "status": "distinct_selected_originals", "archives": archives})
    return conflicts, splits


def plan(inventory: UpdateInventory, selections: list[str], *, output_pack: bool = False,
         pins: dict[str, str] | None = None, include_native: tuple[str, ...] | list[str] = ()) -> UpdatePlan:
    included = tuple(sorted(set(include_native)))
    if included and not output_pack:
        raise InputError("--include-native requires --output-pack; a mods-folder swap already retains installed candidates.")
    if any(not MOD_ID.fullmatch(mod_id) for mod_id in included):
        raise InputError("Use --include-native with a lowercase NeoForge mod ID from the target's mods/ folder.")
    selected = sorted(set(selections))
    added = selected_addons(inventory, selected)
    closure = set(selected) | added.keys()
    problems = [] if output_pack else list(inventory.target_issues)
    pins = pins or {}
    roots = set()
    forced = {}
    for mod_id in included:
        installed = [mod for mod in inventory.installed if mod_id in mod.ids]
        if len(installed) != 1 or installed[0].partial or not installed[0].target_evidence:
            problems.append(f"Cannot include {mod_id!r}: install exactly one compatible native provider in target mods/. "
                            "Partial conversions and candidates outside mods/ cannot be explicitly included.")
            continue
        candidate, = installed
        roots.update(candidate.ids)
        for key in candidate.ids:
            if key in forced and forced[key] is not candidate:
                problems.append(f"Explicit native inclusions conflict on mod ID {key!r}.")
            forced[key] = candidate
    for label in sorted(closure):
        item = inventory.entries[label]
        ids = inventory.native_ids(item)
        if item.problem or not ids:
            problems.append(f"{label!r}: {item.problem or 'no recognizable legacy mod ID to match'}")
        invalid_ids = {key for key in ids if not MOD_ID.fullmatch(key)}
        if invalid_ids:
            problems.append(f"{label!r}: illegal NeoForge mod ID(s) {', '.join(map(repr, sorted(invalid_ids)))}. "
                            "Use a reviewed ID map for a port that implements those modules.")
        roots.update(ids - invalid_ids)
        if inventory.build_options is not None:
            artifact = inventory.recipes.get(label)
            if artifact is None:
                problems.append(f"{label!r}: {inventory.recipe_issues[label]}")
                continue
            if artifact.recipe.isolated_preview:
                if not output_pack:
                    problems.append(f"{label!r}: this isolated preview must be built into a fresh --output-pack.")
                for addon in sorted(inventory.addons.get(label, {}).keys() & closure):
                    problems.append(f"{addon!r} expects {label!r}; an isolated preview cannot replace its legacy provider.")
        else:
            artifact = inventory.conversions.get(label)
            if artifact is not None:
                if not inventory.allow_recipe_preview or not output_pack or len(closure) != 1 or included:
                    problems.append(f"{label!r}: this is a recipe preview, not a full-parity update. "
                                    "Select this archive alone with --allow-recipe-preview and --output-pack.")
                    artifact = None
            elif label in inventory.conversion_issues and ids and not all(inventory.providers.get(key) for key in ids):
                problems.append(f"{label!r}: recipe preview blocked: {inventory.conversion_issues[label]}")
        if artifact is not None:
            for key in ids:
                if key in forced and forced[key] is not artifact:
                    problems.append(f"Multiple selections would supply native mod ID {key!r}.")
                forced[key] = artifact
    resolution = resolve(roots, inventory.installed, inventory.providers, forced, pins, keep_target=not output_pack,
                         artifact_errors=_artifact_conflicts)
    if resolution.problem:
        problems.append(resolution.problem)
    mods = list(resolution.mods)
    problems.extend(_artifact_conflicts(mods))
    active = defaultdict(list)
    for mod in mods:
        for key in mod.ids:
            active[key].append(mod)
    removed = {}
    if not output_pack and resolution.status == "ready":
        for old in inventory.installed:
            new = {mod for key in old.ids for mod in active[key]}
            if old not in new:
                removed[old.label.removeprefix("target/")] = ", ".join(sorted(mod.label for mod in new))
    # Include native required dependencies of selected mods when replacing a mixed
    # target's old JARs. Unrelated old entries are never removed implicitly.
    replaced_ids = set(roots)
    queue = deque(sorted(roots))
    while queue:
        key = queue.popleft()
        if len(active[key]) != 1:
            continue
        candidate = active[key][0]
        required = {dep.mod_id for dep in candidate.dependencies if dep.kind == "required"}
        queue.extend(sorted(required - replaced_ids))
        replaced_ids.update(required)
    for label, item in (inventory.target_legacy.items() if not output_pack else ()):
        ids = inventory.native_ids(item)
        if (item.problem or not ids or not ids <= replaced_ids or
                not all(len(active[key]) == 1 and not active[key][0].partial for key in ids)):
            problems.append(f"Target contains non-native {label!r}. Update all its mod IDs or choose a clean 1.21.1 target.")
        else:
            removed[label] = ", ".join(sorted({active[key][0].label for key in ids}))
    destinations = set()
    passthrough = set() if output_pack else set(inventory.passthrough)
    for path in [*(mod.path for mod in mods), *sorted(passthrough)]:
        relative = path.relative_to(inventory.target / "mods").as_posix() if path in passthrough else path.name
        if not all(_safe_filename(part) for part in relative.split("/")):
            problems.append(f"Unsafe output path: {relative!r}.")
        if relative.casefold() in destinations:
            problems.append(f"Case-insensitive filename collision in target mods/: {relative!r}.")
        destinations.add(relative.casefold())
    if any(prefix.casefold() in destinations for path in destinations for prefix in _parents(path)):
        problems.append("A target file conflicts with a directory name in mods/.")
    mods.sort(key=lambda a: a.path.name.casefold())
    order = []
    if not problems and inventory.build_options is not None:
        try:
            order = _build_order(mods)
        except InputError as exc:
            problems.append(str(exc))
    runtime = None
    if output_pack and (selected or included):
        try:
            runtime = instance_runtime(inventory.target, inventory.target_platform)
        except (InputError, OSError) as exc:
            problems.append(f"Target instance runtime: {exc}")
    # Include originals built automatically to satisfy native dependencies as
    # well as explicit selections; their packages must coexist too.
    built_originals = {mod.label.removeprefix("build/") for mod in mods if mod.recipe}
    scoped_originals = closure | built_originals
    originals = set(scoped_originals)
    for label in scoped_originals:
        originals.update(provider["archive"] for row in inventory.legacy_declarations.get(label, ()) for provider in row["candidates"])
        originals.update(p["archive"] for p in inventory.legacy_binary.get(label, {}).get("providers", ()))
    legacy_hashes = {}
    for label in sorted(originals):
        path = inventory.source / label
        try:
            if path.resolve() != path:
                raise InputError("source archives must not be symbolic links")
            legacy_hashes[label] = _digest(path)
            conversion, recipe = inventory.conversions.get(label), inventory.recipes.get(label)
            if ((conversion and conversion.conversion.source_sha256 != legacy_hashes[label]) or
                    (recipe and recipe.recipe.legacy_sha256 != legacy_hashes[label])):
                raise InputError("original changed after recipe analysis; scan again")
        except (InputError, OSError) as exc:
            problems.append(f"Could not fingerprint legacy input {label!r}: {exc}")
    conflicts, splits = _selected_id_conflicts(inventory, scoped_originals, legacy_hashes, output_pack)
    for conflict in conflicts:
        archives = ", ".join(f"{row['archive']!r} (Minecraft {row['minecraft'] or 'unknown'})" for row in conflict["archives"])
        problems.append(f"Distinct selected originals share mod ID {conflict['id']!r}: {archives}. "
                        "A single native provider does not preserve both releases. Coexistence needs separate native IDs, "
                        "classes and registry namespaces, or a verified combined port.")
    return UpdatePlan(inventory, selected, added, mods, roots, removed, list(dict.fromkeys(problems)), order,
                      resolution=resolution, runtime=runtime, output_pack=output_pack, provider_pins=pins, legacy_hashes=legacy_hashes,
                      legacy_selection_conflicts=conflicts, legacy_identity_splits=splits, include_native=included)


def _dependencies(mod: NativeMod, providers: dict[str, NativeMod]) -> set[NativeMod]:
    """The local compile/test input for a source project (required, and present optional, transitively)."""
    seen = {mod}
    pending = [mod]
    while pending:
        for dep in pending.pop().dependencies:
            other = providers.get(dep.mod_id)
            if dep.kind in {"required", "optional"} and other is not None and other not in seen:
                seen.add(other)
                pending.append(other)
    return seen - {mod}


def _build_order(mods: list[NativeMod]) -> list[NativeMod]:
    providers = {mod_id: mod for mod in mods for mod_id in mod.ids}
    needs = {mod: {other for other in _dependencies(mod, providers) if other.recipe} for mod in mods if mod.recipe}
    order = []
    while needs:
        ready = sorted((mod for mod, deps in needs.items() if not deps), key=lambda mod: mod.label)
        if not ready:
            cycle = ", ".join(mod.label for mod in needs)
            raise InputError(f"Source ports form a build-time dependency cycle: {cycle}. Split their shared API into a native library.")
        order.extend(ready)
        for mod in ready:
            del needs[mod]
        for deps in needs.values():
            deps.difference_update(ready)
    return order


def _materialize(update: UpdatePlan, scratch: Path) -> UpdatePlan:
    providers = {mod_id: mod for mod in update.mods for mod_id in mod.ids}
    actual, evidence = {}, {}
    java = require_jdk()
    timeout = update.inventory.build_options.timeout
    neoforge = update.inventory.target_platform.neoforge
    for index, expected in enumerate(update.build_order):
        try:
            deps = [actual.get(mod, mod) for mod in sorted(_dependencies(expected, providers), key=lambda mod: mod.label)]
            used = [(mod.path, mod.sha256) for mod in deps]
            jar, checked = build(expected.recipe, expected.original, scratch / f"port-{index + 1}", used, neoforge, java, timeout)
            verified = _native(jar, expected.label, neoforge)
            if (verified is None or not verified.target_evidence or verified.ids != expected.ids or
                    set(verified.dependencies) != set(expected.dependencies) or
                    (verified.partial and not expected.recipe.preview) or (expected.partial and not verified.partial)):
                raise InputError("Compiled JAR did not match the recipe's native IDs, versions, dependencies, or preview status")
            actual[expected] = replace(verified, recipe=expected.recipe)
            evidence[verified.path.name] = {**checked, "jar_sha256": verified.sha256}
        except (InputError, OSError) as exc:
            raise InputError(f"{expected.label.removeprefix('build/')!r}: {exc}") from exc
    mods = [actual.get(mod, mod) for mod in update.mods]
    if conflicts := _artifact_conflicts(mods):
        raise InputError("Built artifacts cannot share the pack: " + " ".join(conflicts))
    _check_split_namespaces(update.legacy_identity_splits, {mod.label: mod for mod in mods})
    return replace(update, mods=mods, native_builds=evidence)


_SHARED_SPLIT_TAGS = {
    "minecraft": frozenset({"data/minecraft/tags/block/mineable/pickaxe.json", "data/minecraft/tags/block/needs_iron_tool.json"}),
    "c": frozenset({"data/c/tags/block/ores/uranium.json", "data/c/tags/item/ores/uranium.json"}),
}


def _isolated_shared_tags(mod: NativeMod, namespace: str) -> bool:
    # The exact additive tag paths above are shared conventions, not mod identities.
    # Never allow other resources, replacement tags, or contributions from another mod's namespace.
    if namespace not in _SHARED_SPLIT_TAGS:
        return False
    with open_input(mod.path) as archive:
        resources = {name for name in archive.files if name.startswith((f"data/{namespace}/", f"assets/{namespace}/"))}
        if not resources or not resources <= _SHARED_SPLIT_TAGS[namespace]:
            return False
        for name in sorted(resources):
            try:
                pairs = json.loads(archive.read(name, 16 * 1024), object_pairs_hook=tuple)
            except (ValueError, InputError, RecursionError):
                return False
            if not isinstance(pairs, tuple) or len(pairs) != 2:
                return False
            data = dict(pairs)
            values = data.get("values")
            if data.get("replace") is not False or not isinstance(values, list) or not values:
                return False
            if not all(isinstance(value, str) and re.fullmatch(r"[a-z][a-z0-9_]*:[a-z0-9_/.-]+", value) and
                       value.split(":", 1)[0] in mod.ids for value in values) or len(set(values)) != len(values):
                return False
    return True


def _check_split_namespaces(splits: list[dict], artifacts: dict[str, NativeMod]) -> None:
    for split in splits:
        packages = set()
        resources: dict[str, list[NativeMod]] = defaultdict(list)
        for row in split["archives"]:
            mod = artifacts[f"build/{row['archive']}"]
            if mod.embedded:
                raise InputError(f"Split legacy ID {split['id']!r} cannot have embedded JARs; use separately declared native libraries.")
            occupied = {name.rpartition('/')[0] for name in mod.classes}
            if shared := sorted(occupied & packages):
                raise InputError(f"Split legacy ID {split['id']!r} shares native Java package {shared[0]!r}.")
            packages.update(occupied)
            for namespace in mod.resource_namespaces:
                resources[namespace].append(mod)
        for namespace, owners in sorted(resources.items()):
            if len(owners) > 1 and not all(_isolated_shared_tags(mod, namespace) for mod in owners):
                raise InputError(f"Split legacy ID {split['id']!r} shares a native asset/data namespace {namespace!r}.")


def _parents(path: str) -> list[str]:
    parts = path.split("/")
    return ["/".join(parts[:i]) for i in range(1, len(parts))]


def stage_destination(state: UpdateInventory, output: Path) -> Path:
    output = output.expanduser()
    if output.exists() or output.is_symlink():
        raise InputError("Choose a new output directory; existing files are never replaced.")
    output = output.resolve()
    if any(output.is_relative_to(root) for root in (state.source, state.target)):
        raise InputError("Write the update outside the source and target instances.")
    return output


def stage(update: UpdatePlan, output: Path) -> Path:
    if not (update.selected or update.include_native) or update.problems:
        raise InputError("Choose at least one mod and resolve all update blockers first.")
    output = stage_destination(update.inventory, output)
    for label, checksum in update.legacy_hashes.items():
        path = update.inventory.source / label
        if path.resolve() != path or _digest(path) != checksum:
            raise InputError(f"Legacy input {label!r} changed during staging. Scan the pack and retry.")
    if update.build_order:
        # Keep dependency copies and all intermediate JARs outside the packs and the final output.
        with tempfile.TemporaryDirectory(prefix="mcmodup-source-build-") as directory:
            _write_stage(_materialize(update, Path(directory).resolve()), output)
    else:
        _write_stage(update, output)
    return output


def _write_stage(update: UpdatePlan, output: Path) -> None:
    output.mkdir(parents=True, exist_ok=False)
    try:
        mods = output / "mods"
        mods.mkdir()
        for mod in update.mods:
            dest = mods / mod.path.name
            if mod.conversion:
                dest.write_bytes(mod.conversion.jar)
                checked = _native(dest, mod.label, update.inventory.target_platform.neoforge)
                if checked is None or checked.ids != mod.ids or not checked.target_evidence or not checked.partial:
                    raise InputError(f"Generated recipe JAR for {mod.label!r} did not match its NeoForge preview metadata.")
            else:
                if mod.path.resolve() != mod.path:
                    raise InputError(f"{mod.label!r} became a symbolic link during staging. Scan and retry.")
                shutil.copyfile(mod.path, dest)
            if _digest(dest) != mod.sha256:
                raise InputError(f"{mod.label!r} changed during staging. Scan and retry.")
        for path in (() if update.output_pack else update.inventory.passthrough):
            dest = mods / path.relative_to(update.inventory.target / "mods")
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, dest)
        if update.runtime:
            for relative, checksum in update.runtime.files.items():
                source = update.inventory.target / relative
                if source.resolve() != source:
                    raise InputError(f"Target runtime file {relative!r} became a symbolic link. Scan and retry.")
                dest = output / relative
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, dest)
                if _digest(dest) != checksum:
                    raise InputError(f"Target runtime file {relative!r} changed during staging. Scan and retry.")
        report = update.report | {"staged": True}
        (output / REPORT_NAME).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        preview_note = ("This output contains preview builds with known parity gaps. See update-report.json.\n"
                        if any(mod.conversion for mod in update.mods) or
                        any(row["status"] == "preview" for row in update.native_builds.values()) else "")
        if update.runtime and update.runtime.launch:
            how = ("From this new directory, use Java 21 to start the copied NeoForge server runtime.\n"
                   "Review Minecraft's EULA yourself; eula.txt and server settings are not copied.\n" +
                   "\n".join(f"{os_name}: {command}" for os_name, command in update.runtime.launch.items()) + "\n")
        elif update.output_pack:
            how = ("In a launcher with the stated Minecraft / NeoForge version installed, create a new profile pointing its game directory here.\n"
                   "This is a game directory, not a launcher install or a self-contained server. The launcher supplies its own classpath and assets.\n")
        else:
            how = "Close the target game, back up its mods/ directory, and replace that directory with this mods/ directory (do not merge).\n"
        (output / "INSTALL.txt").write_text(
            f"Standalone mods for Minecraft {MINECRAFT_VERSION} / NeoForge {update.inventory.target_platform.neoforge}.\n"
            f"{how}Neither input was changed. No saves, configs, credentials, or EULA acceptance were copied. Use a new world first.\n"
            "See update-report.json for dependency evidence and any source build tests.\n"
            "Staging does not prove boot, full-pack gameplay, client rendering, or old save compatibility.\n"
            f"{preview_note}Keep this local unless you have redistribution rights for every file.\n", encoding="utf-8")
    except BaseException:
        shutil.rmtree(output, ignore_errors=True)
        raise
