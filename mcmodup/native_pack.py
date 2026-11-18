"""Plan and stage standalone NeoForge updates from two installed modpacks."""

from collections import defaultdict, deque
from dataclasses import dataclass, field, replace
from io import BytesIO
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import tomllib
from zipfile import BadZipFile, ZipFile
import zlib

from . import MINECRAFT_VERSION
from .api_rules import platform_symbol
from .audit import FILENAME_VERSION
from .catalogs import PROFILES
from .classfiles import CLASS_LIMIT
from .inputs import InputError, TEXT_LIMIT, open_input
from .pack import ARCHIVE_EXTENSIONS, LOADER_IDS, STANDARD_PREFIXES, PackMod, _declared_requests, _load
from .source_ports import BuildOptions, Dependency, MOD_ID, SourcePort, build, load_ports, require_jdk
from .source_ports import digest as _digest, safe_component as _safe_filename


NATIVE_META = "META-INF/neoforge.mods.toml"
CONNECTOR_PREFIXES = (b"legacyconnector/", b"org/kylepitcock/legacyconnector/", b"org.kylepitcock.legacyconnector.")
CONNECTOR_ENTRIES = {"META-INF/legacyconnector/provenance.json", "META-INF/legacyconnector/legacyconnector-runtime.jar",
                     "org/kylepitcock/legacyconnector/loader/LegacyModLocator.class"}
ORDERED_VERSION = re.compile(r"([0-9]+(?:\.[0-9]+)*)(?:-(alpha|beta|rc)(?:[.-]?([0-9]+))?)?", re.I)
QUALIFIER = {"alpha": -3, "beta": -2, "rc": -1, "": 0}
NEOFORGE_JAR = re.compile(r"neoforge-(21\.1\.\d+)(?:-universal)?\.jar", re.I)
LEGACY_CLIENTS = {  # Official vanilla client fingerprints, also used by upstream's version detection tooling.
    "465378c9dc2f779ae1d6e8046ebc46fb53a57968": "1.5.2",
    "1703704407101cf72bd88e68579e3696ce733ecd": "1.6.4",
    "e80d9b3bf5085002218d4be59e668bac718abbc6": "1.7.10",
}
MAX_ARCHIVES = 4096
REPORT_NAME = "update-report.json"


def _ordered(value: str) -> tuple[tuple[int, ...], int, int] | None:
    base = value.partition("+")[0]
    if len(base) > 64 or (match := ORDERED_VERSION.fullmatch(base)) is None:
        return None
    parts = [int(part) for part in match[1].split(".")]
    while parts and parts[-1] == 0:
        parts.pop()
    return tuple(parts), QUALIFIER[(match[2] or "").lower()], int(match[3] or "0")


def matches(version: str, spec: str) -> bool:
    """Check a literal or a single numeric/alpha/beta/rc Maven interval; don't guess other ranges."""
    spec = spec.strip()
    if spec in {"", "*"}:
        return True
    if not version or any(char in version for char in "${}@"):
        return False
    if spec.startswith("[") and spec.endswith("]") and "," not in spec:
        spec = spec[1:-1].strip()
        if not spec or any(c in spec for c in "[]()*"):
            return False
    if not spec.startswith(("[", "(")):
        return version.casefold() == spec.casefold()
    if not spec.endswith(("]", ")")):
        return False
    bounds = [value.strip() for value in spec[1:-1].split(",")]
    actual = _ordered(version)
    if len(bounds) != 2 or not any(bounds) or actual is None:
        return False
    lo, hi = (_ordered(value) if value else None for value in bounds)
    if (bounds[0] and lo is None) or (bounds[1] and hi is None):
        return False
    return ((lo is None or actual > lo or (actual == lo and spec[0] == "[")) and
            (hi is None or actual < hi or (actual == hi and spec[-1] == "]")))


def _files(root: Path, directory: str) -> list[Path]:
    base = root / directory
    if base.is_symlink():
        raise InputError(f"Use a real {directory}/ folder, not a symbolic link: {root}")
    if not base.is_dir():
        return []
    result = []

    def onerror(exc: OSError) -> None:
        raise exc

    for current, dirs, files in os.walk(base, onerror=onerror):
        parent = Path(current)
        dirs[:] = sorted(name for name in dirs if not name.startswith("."))
        for name in (*dirs, *files):
            if (parent / name).is_symlink():
                raise InputError(f"Symbolic links are not scanned: {parent / name}")
        result.extend(parent / name for name in sorted(files) if (parent / name).is_file())
    return sorted(result)


def _archive(path: Path) -> bool:
    return path.suffix.lower() in ARCHIVE_EXTENSIONS


@dataclass(frozen=True)
class Platform:
    minecraft: list[tuple[str, str]]
    loaders: list[tuple[str, str]]
    forge: list[str]

    @property
    def neoforge(self) -> str | None:
        versions = {version for version, _ in self.loaders}
        return next(iter(versions)) if len(versions) == 1 else None

    @property
    def legacy(self) -> str | None:
        versions = {version for version, _ in self.minecraft if version in LEGACY_CLIENTS.values()}
        return next(iter(versions)) if len(versions) == 1 else None

    def report(self) -> dict:
        return {"minecraft": [{"version": v, "file": f} for v, f in self.minecraft],
                "neoforge": self.neoforge, "neoforge_files": [f for _, f in self.loaders], "forge_files": self.forge}


def _platform(root: Path, bins: list[Path]) -> Platform:
    game, neo, forge = set(), set(), set()
    for path in filter(_archive, bins):
        filename = path.name.lower()
        loader_match = NEOFORGE_JAR.fullmatch(filename)
        mc_name = filename.startswith(("minecraft", "client-", "server-")) or re.fullmatch(r"1\.\d+(?:\.\d+)?\.jar", filename)
        forge_name = filename == "modpack.jar" or re.match(r"(?:minecraftforge|forge|fml)(?:[-_.]|$)", filename)
        if not (loader_match or mc_name or forge_name):
            continue
        label = path.relative_to(root).as_posix()
        try:
            with open_input(path) as jar:
                if loader_match and "net/neoforged/neoforge/common/NeoForge.class" in jar.files:
                    neo.add((loader_match[1], label))
                if forge_name and ({"net/minecraftforge/common/MinecraftForge.class", "cpw/mods/fml/common/Loader.class"} & jar.files.keys()):
                    forge.add(label)
                    game.update((v, label) for v in FILENAME_VERSION.findall(filename))
                if not mc_name:
                    continue
                if "version.json" in jar.files:
                    v = json.loads(jar.text("version.json"))
                    if isinstance(v, dict):
                        version = v.get("id") or v.get("name")
                        if isinstance(version, str) and re.fullmatch(r"1\.\d+(?:\.\d+)?", version):
                            game.add((version, label))
                elif "META-INF/versions.list" in jar.files:
                    for line in jar.text("META-INF/versions.list").splitlines():
                        fields = line.split("\t")
                        if len(fields) == 3 and fields[1] == MINECRAFT_VERSION and f"META-INF/versions/{fields[2]}" in jar.files:
                            game.add((MINECRAFT_VERSION, label))
                if version := LEGACY_CLIENTS.get(_digest(path, "sha1")):
                    game.add((version, label))
        except (InputError, OSError, ValueError, RecursionError):
            # Unreadable platform candidates do not constitute evidence of an installed runtime.
            continue
    return Platform(sorted(game), sorted(neo), sorted(forge))


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
        if not any(name.endswith(".class") for name in entries):
            raise InputError("No compiled mod code in this native JAR")
        manifest = (_main_manifest(jar.text("META-INF/MANIFEST.MF")) if "META-INF/MANIFEST.MF" in entries else {})
        try:
            data = tomllib.loads(jar.text(NATIVE_META))
        except (ValueError, RecursionError) as exc:
            raise InputError(f"Invalid NeoForge metadata: {exc}") from exc
        ids = {}
        mods = data.get("mods")
        if not isinstance(mods, list) or not mods or not isinstance(data.get("modLoader"), str):
            raise InputError("Missing NeoForge mod declarations")
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
        partial = "META-INF/MCModUp-NOTICE.txt" in jar.files
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
                if name == "legacyconnector":
                    raise InputError(f"{mod_id} declares a dependency on Legacy Connector")
                kind = row.get("type", "required" if row.get("mandatory", True) else "optional")
                if kind not in {"incompatible", "required", "optional", "discouraged"}:
                    raise InputError(f"Unknown NeoForge dependency type in {mod_id}")
                if name in platform or name in ids:
                    actual = platform[name] if name in platform else ids[name]
                    fits = matches(actual, spec)
                    if (kind in {"required", "optional"} and not fits) or (kind == "incompatible" and fits):
                        raise InputError(f"{mod_id}: incompatible {name} {actual} (declared {kind} {spec!r})")
                    if name in platform and kind in {"required", "optional"}:
                        evidence |= spec.strip() not in {"", "*"}
                elif kind != "discouraged":
                    requirements.add(Dependency(name, spec, kind))
        # Check actual class constants too, not only dependency declarations. Never load these classes.
        _check_code(entries, jar.read)
        return NativeMod(path, label, ids, tuple(sorted(requirements, key=lambda d: (d.mod_id, d.version, d.kind))),
                         installed, evidence, checksum, partial)


def _check_code(entries, read, *, nested: bool = False) -> None:
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
                _check_code(names, inner_read, nested=True)
        except (BadZipFile, ValueError, RuntimeError, OSError, zlib.error) as exc:
            raise InputError(f"Could not verify embedded {name}: {exc}") from exc


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

    def native_ids(self, item: LegacyEntry) -> set[str]:
        return {self.aliases.get(mod_id, mod_id) for mod_id in item.ids}

    def catalog(self) -> dict:
        rows = []
        present = {mod_id for artifact in self.installed if not artifact.partial for mod_id in artifact.ids}
        for label, item in self.entries.items():
            ids = self.native_ids(item)
            supported = [p.name for p in PROFILES.values() if p.mod_id in item.ids and p.version == item.version]
            recipe = self.recipes.get(label)
            if self.build_options is not None:
                status, reason = (("preview", "Builds from reviewed source, with documented gaps.") if recipe.recipe.preview else
                                  ("buildable", "Exact source recipe found. Builds and runs NeoForge GameTests.")) if recipe else (
                                  "blocked", self.recipe_issues[label])
            elif not item.problem and ids and ids <= present:
                status, reason = "installed", "Native builds are already in the target mods folder."
            elif not item.problem and ids and all(self.providers.get(key) for key in ids):
                status, reason = "available", "Matching local NeoForge builds found. Select to check dependencies and add-ons."
            elif not item.problem and supported:
                status, reason = "partial", "Only a partial conversion profile exists here. It is not a replacement or add-on compatible."
            else:
                missing = sorted(key for key in ids if not self.providers.get(key))
                status, reason = "blocked", item.problem or ("No matching local NeoForge build for " + ", ".join(missing) + "." if missing else
                                                            "No recognizable legacy mod IDs; automatic matching is unavailable.")
            rows.append({"id": label, "name": item.name, "minecraft": item.version, "mods": list(item.ids),
                         "native_ids": sorted(ids), "status": status, "reason": reason, "addons": len(self.addons.get(label, {})),
                         **({"source_build": recipe.recipe.describe()} if recipe else {})})
        return {"source": self.source.name, "target": self.target.name, "minecraft": MINECRAFT_VERSION,
                "neoforge": self.target_platform.neoforge, "source_build_mode": self.build_options is not None,
                "source_bin": self.source_platform.report(),
                "target_bin": self.target_platform.report(), "rows": rows, "warnings": self.warnings, "target_issues": self.target_issues}


def _legacy(path: Path, label: str, legacy_version: str | None) -> LegacyEntry:
    try:
        version_folder = next((value for value in Path(label).parts[1:-1] if re.fullmatch(r"1\.\d+(?:\.\d+)?", value)), None)
        # Exact declarations win over a bin-derived fallback. Never use a modern daughter JAR as a legacy API.
        audit = _load(label, path, None)
        declared = {e.version for e in audit.audit.version_evidence if e.kind == "declared"}
        if len(declared) > 1 or (version_folder and declared and version_folder not in declared):
            return LegacyEntry(path, label, audit, "Conflicting source Minecraft versions; review the input first.")
        hints = set(FILENAME_VERSION.findall(path.stem))
        audit.audit.source_version = audit.audit.source_version or version_folder or (next(iter(hints)) if len(hints) == 1 else legacy_version)
        return LegacyEntry(path, label, audit, "Unreadable legacy bytecode" if audit.corrupt_classes and not audit.classes else "")
    except (InputError, OSError) as exc:
        return LegacyEntry(path, label, problem=str(exc))


def _addon_edges(entries: dict[str, LegacyEntry]) -> dict[str, dict[str, str]]:
    by_id, owners = defaultdict(list), defaultdict(list)
    for item in entries.values():
        for mod_id in item.ids:
            by_id[mod_id].append(item)
        if item.audit:
            for owner in item.audit.classes:
                owners[owner].append(item)
    result = defaultdict(dict)

    def connect(parents, child: LegacyEntry, why: str) -> None:
        for parent in parents:
            if parent.label != child.label and not (parent.version and child.version and parent.version != child.version):
                result[parent.label].setdefault(child.label, why)

    for child in entries.values():
        if not child.audit:
            continue
        optional = set()
        for _, dependency, required, _ in _declared_requests(child.audit.audit):
            dependency = dependency.casefold()
            if required and dependency not in LOADER_IDS:
                connect(by_id[dependency], child, f"declares required dependency {dependency}")
            if not required:
                optional.add(dependency)
        refs = {ref.owner for ref in child.audit.references if ref.owner not in child.audit.classes and
                not platform_symbol(ref) and not ref.owner.startswith(STANDARD_PREFIXES)}
        for owner in sorted(refs):
            provider = [p for p in owners[owner] if not optional.intersection(p.ids)]
            if len(provider) == 1:
                connect(provider, child, f"references {owner.replace('/', '.')}")
    return dict(result)


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


def inventory(source: Path, target: Path, build_options: BuildOptions | None = None) -> UpdateInventory:
    source, target = (path.expanduser().resolve() for path in (source, target))
    source_ports = load_ports(build_options) if build_options is not None else {}
    for root in (source, target):
        if not root.is_dir() or not (root / "mods").is_dir():
            raise InputError(f"Expected an installed instance with a mods/ folder: {root}")
    bins = {root: _files(root, "bin") for root in dict.fromkeys((source, target))}
    src_bin, dst_bin = (_platform(root, bins[root]) for root in (source, target))
    if not dst_bin.neoforge or MINECRAFT_VERSION not in {version for version, _ in dst_bin.minecraft}:
        raise InputError(f"Target bin/ must contain an identifiable Minecraft {MINECRAFT_VERSION} JAR and one installed NeoForge 21.1.x universal JAR. An installer alone is not an installation: {target}")
    entries, target_legacy, native, installed, passthrough = {}, {}, [], [], []
    warnings, issues = [], []
    scanned = 0
    for root in bins:
        is_source, is_target = root == source, root == target
        runtime = dst_bin if is_target else src_bin
        platform_labels = {label for _, label in (*runtime.minecraft, *runtime.loaders)} | set(runtime.forge)
        local = sorted({*bins[root], *(_files(root, "mods")), *(_files(root, "coremods") if is_source else ()),
                        *_files(root, "lib"), *_files(root, "libraries")})
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
                record = _legacy(path, label, src_bin.legacy if old else None)
                if active:
                    target_legacy[label] = record
                if old:
                    entries[label] = record
    providers = defaultdict(list)
    for mod in native:
        for mod_id in mod.ids:
            providers[mod_id].append(mod)
    state = UpdateInventory(source, target, src_bin, dst_bin, entries, _addon_edges(entries), providers, installed,
                            target_legacy, passthrough, _aliases(source / "mcmodup-updates.json"), warnings, issues,
                            build_options)
    if build_options is not None:
        for label, item in entries.items():
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
            elif recipe.ids.keys() != ids:
                issue = "The source recipe's native IDs do not match. Review mcmodup-updates.json for renamed IDs."
            elif recipe.preview and not build_options.allow_preview:
                issue = "Only a preview source recipe exists. It does not meet full parity; requires --allow-preview-ports."
            elif not matches(dst_bin.neoforge, recipe.neoforge):
                issue = f"The source recipe needs NeoForge {recipe.neoforge}; target has {dst_bin.neoforge}."
            elif isinstance(recipe.archive, Path) and not recipe.archive.is_file():
                issue = f"Local source ZIP not found: {recipe.archive.name!r}."
            else:
                predicted = NativeMod(Path(Path(recipe.artifact).name), f"build/{label}", recipe.ids, recipe.dependencies,
                                      False, True, f"source:{recipe.legacy_sha256}", recipe=recipe, original=item.path)
                state.recipes[label] = predicted
                for mod_id in ids:
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

    @property
    def report(self) -> dict:
        return {"schema_version": 1, "mode": "native-source-build" if self.inventory.build_options else "native-neoforge",
                "connector_required": False, "native_builds": self.native_builds,
                "build_order": [mod.label.removeprefix("build/") for mod in self.build_order],
                "minecraft": MINECRAFT_VERSION, "neoforge": self.inventory.target_platform.neoforge,
                "source_bin": self.inventory.source_platform.report(), "target_bin": self.inventory.target_platform.report(),
                "selected": self.selected, "auto_addons": self.addons, "ready": bool(self.selected) and not self.problems,
                "problems": self.problems, "warnings": self.inventory.warnings, "replaced_target_files": self.removed,
                "files": [{"file": f"mods/{mod.path.name}", "from": mod.label, "ids": mod.ids,
                           "action": "build" if mod.recipe else "keep" if mod.installed else
                                     "update" if self.roots.intersection(mod.ids) else "dependency",
                           **({"source_build": mod.recipe.describe()} if mod.recipe else {}),
                           "requires": [{"id": dep.mod_id, "range": dep.version} for dep in mod.dependencies if dep.kind == "required"]}
                          for mod in self.mods], "gameplay_verified": False}


def plan(inventory: UpdateInventory, selections: list[str]) -> UpdatePlan:
    selected = sorted(set(selections))
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
    problems = list(inventory.target_issues)
    roots = set()
    forced = {}
    for label in sorted(closure):
        item = inventory.entries[label]
        ids = inventory.native_ids(item)
        if item.problem or not ids:
            problems.append(f"{label!r}: {item.problem or 'no recognizable legacy mod ID to match'}")
        roots.update(ids)
        if inventory.build_options is not None:
            recipe = inventory.recipes.get(label)
            if recipe is None:
                problems.append(f"{label!r}: {inventory.recipe_issues[label]}")
            for key in ids:
                if key in forced and forced[key] is not recipe:
                    problems.append(f"Multiple selected legacy archives would build native mod ID {key!r}.")
                forced[key] = recipe
    mods = list(inventory.installed)
    removed = {}
    changed = set()
    while True:
        active = defaultdict(list)
        wants = defaultdict(set, {key: {""} for key in roots})
        for mod in mods:
            for key in mod.ids:
                active[key].append(mod)
            for dep in mod.dependencies:
                if dep.kind == "required":
                    wants[dep.mod_id].add(dep.version)
        for mod in mods:
            for dep in mod.dependencies:
                if dep.kind == "optional" and (dep.mod_id in active or dep.mod_id in wants):
                    wants[dep.mod_id].add(dep.version)
        unresolved = []
        for key, specs in sorted(wants.items()):
            current = active[key]
            if forced.get(key, False) is None:
                continue  # Detailed exact-recipe blocker already recorded for this legacy archive.
            if (len(current) == 1 and not (key in roots and current[0].partial) and
                    (key not in forced or current[0] is forced[key]) and
                    all(matches(current[0].ids[key], spec) for spec in specs)):
                continue
            versions = ", ".join(repr(spec) for spec in sorted(specs) if spec)
            wanted = f"{key!r}" + (f" (requires {versions})" if versions else "")
            if len(current) > 1:
                unresolved.append(f"Target has multiple installed providers for {wanted}.")
                continue
            previous = current[0] if current else None
            accepted = {}
            for choice in inventory.providers.get(key, []):
                if (choice.installed or choice in changed or not choice.target_evidence or
                        any(mod_id in forced and forced[mod_id] is not choice for mod_id in choice.ids) or
                        not all(matches(choice.ids[key], spec) for spec in specs)):
                    continue
                if previous and not previous.ids.keys() <= choice.ids.keys():
                    continue
                if any(other is not previous for mod_id in choice.ids for other in active[mod_id]):
                    continue
                if any(not all(matches(version, spec) for spec in wants.get(mod_id, ())) for mod_id, version in choice.ids.items()):
                    continue
                accepted.setdefault(choice.sha256, choice)
            if key not in forced and (local := {sha: mod for sha, mod in accepted.items() if mod.recipe is None}):
                accepted = local  # A dependency need not be rebuilt if an unambiguous native JAR is in the packs.
            if len(accepted) != 1:
                if accepted:
                    unresolved.append(f"Multiple compatible local JARs for {wanted}: " + ", ".join(x.label for x in accepted.values()) + ".")
                else:
                    files = [x.label for x in inventory.providers.get(key, [])]
                    unresolved.append(f"No compatible standalone NeoForge JAR for {wanted}." + (" Found: " + ", ".join(files) + "." if files else ""))
                continue
            candidate = next(iter(accepted.values()))
            changed.add(candidate)
            if previous is not None:
                mods.remove(previous)
                if previous.installed:
                    removed[previous.label.removeprefix("target/")] = candidate.label
            mods.append(candidate)
            break  # Recompute all constraints, including transitive dependencies.
        else:
            problems.extend(unresolved)
            break
    for mod in mods:
        for dep in mod.dependencies:
            if dep.kind == "incompatible" and any(matches(other.ids[dep.mod_id], dep.version) for other in active.get(dep.mod_id, ())):
                problems.append(f"{mod.label!r} declares an incompatibility with {dep.mod_id} {dep.version!r}.")
    for key, providers in sorted(active.items()):
        if len(providers) > 1:
            problems.append(f"Duplicate target mod ID {key!r}: " + ", ".join(x.label for x in providers))
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
    for label, item in inventory.target_legacy.items():
        ids = inventory.native_ids(item)
        if (item.problem or not ids or not ids <= replaced_ids or
                not all(len(active[key]) == 1 and not active[key][0].partial for key in ids)):
            problems.append(f"Target contains non-native {label!r}. Update all its mod IDs or choose a clean 1.21.1 target.")
        else:
            removed[label] = ", ".join(sorted({active[key][0].label for key in ids}))
    destinations = set()
    for path in [*(mod.path for mod in mods), *inventory.passthrough]:
        relative = path.relative_to(inventory.target / "mods").as_posix() if path in inventory.passthrough else path.name
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
    return UpdatePlan(inventory, selected, added, mods, roots, removed, list(dict.fromkeys(problems)), order)


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
            if (verified is None or verified.partial or not verified.target_evidence or verified.ids != expected.ids or
                    set(verified.dependencies) != set(expected.dependencies)):
                raise InputError("Compiled JAR did not match the recipe's exact native IDs, versions, and dependencies")
            actual[expected] = replace(verified, recipe=expected.recipe)
            evidence[verified.path.name] = {**checked, "jar_sha256": verified.sha256}
        except (InputError, OSError) as exc:
            raise InputError(f"{expected.label.removeprefix('build/')!r}: {exc}") from exc
    return replace(update, mods=[actual.get(mod, mod) for mod in update.mods], native_builds=evidence)


def _parents(path: str) -> list[str]:
    parts = path.split("/")
    return ["/".join(parts[:i]) for i in range(1, len(parts))]


def stage(update: UpdatePlan, output: Path) -> Path:
    if not update.selected or update.problems:
        raise InputError("Choose at least one mod and resolve all update blockers first.")
    output = output.expanduser()
    if output.exists() or output.is_symlink():
        raise InputError("Choose a new output directory; existing files are never replaced.")
    output = output.resolve()
    if any(output.is_relative_to(root) for root in (update.inventory.source, update.inventory.target)):
        raise InputError("Write the update outside the source and target instances.")
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
            shutil.copyfile(mod.path, dest)
            if _digest(dest) != mod.sha256:
                raise InputError(f"{mod.label!r} changed during staging. Scan the pack and retry.")
        for path in update.inventory.passthrough:
            dest = mods / path.relative_to(update.inventory.target / "mods")
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, dest)
        report = update.report
        sha256 = {mod.path.name: mod.sha256 for mod in update.mods}
        for file in report["files"]:
            file["sha256"] = sha256[Path(file["file"]).name]
        (output / REPORT_NAME).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        (output / "INSTALL.txt").write_text(
            f"Standalone mods for Minecraft {MINECRAFT_VERSION} / NeoForge {update.inventory.target_platform.neoforge}.\n"
            "Close the target game, back up its mods/ directory, and replace that directory with this mods/ directory (do not merge).\n"
            "The target bin/, launcher, configs and saves were not changed. Use a new world to test first.\n"
            "See update-report.json for asset checks and server GameTests for any mods built from source.\n"
            "These checks do not certify full-pack gameplay, client rendering, or old save compatibility.\n"
            "Keep this local unless you have redistribution rights for every file.\n", encoding="utf-8")
    except BaseException:
        shutil.rmtree(output, ignore_errors=True)
        raise
