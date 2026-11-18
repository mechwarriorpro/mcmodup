"""Inventory a local modpack and find dependencies and migration work from bytecode."""

from collections import Counter, defaultdict
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import asdict, dataclass, field
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import tempfile
import zipfile
import zlib

from . import MINECRAFT_VERSION, NEOFORGE_VERSION
from .api import ApiContext, ClassLocation
from .api_rules import platform_symbol
from .audit import Audit, VersionEvidence, audit
from .classfiles import CLASS_LIMIT, Symbol, class_entries, read_class
from .inputs import IGNORED_DIRS, MOD_ARCHIVE_LIMIT, InputError, open_input


ARCHIVE_EXTENSIONS = {".jar", ".zip"}
LOADER_IDS = {"minecraft", "mcp", "forge", "fml"}
STANDARD_PREFIXES = ("java/", "javax/", "jdk/", "sun/", "com/sun/", "org/w3c/dom/", "org/xml/sax/")
DECLARED_ID = re.compile(r"[A-Za-z0-9_.-]+(?:\|[A-Za-z0-9_.-]+)*")
PACK_ARCHIVE_LIMIT = 2 * 1024 * 1024 * 1024
LOADER_FOLDERS = ("mods", "coremods")


@dataclass
class PackMod:
    archive: str
    path: Path
    audit: Audit
    classes: dict[str, str]
    references: dict[Symbol, str]
    corrupt_classes: int = 0


def _archives(root: Path) -> list[Path]:
    loader_folders = [root / name for name in LOADER_FOLDERS
                      if (root / name).is_dir() and not (root / name).is_symlink()]
    files = []

    def walk_error(exc: OSError) -> None:
        raise exc

    for directory in loader_folders or [root]:
        for current, dirs, names in os.walk(directory, onerror=walk_error):
            base = Path(current)
            dirs[:] = [name for name in dirs if not name.startswith(".") and name not in IGNORED_DIRS
                       and not (base / name).is_symlink()]
            files.extend(base / name for name in names if Path(name).suffix.lower() in ARCHIVE_EXTENSIONS
                         and not (base / name).is_symlink() and (base / name).is_file())
    return sorted(files)


def _load(label: str, path: Path, profile: str | None) -> PackMod:
    classes = {}
    refs: dict[Symbol, str] = {}
    annotations = []
    invalid = 0
    invalid_examples = []
    with open_input(path) as source:
        for owner, entry in class_entries(source.files).items():
            try:
                info = read_class(source.read(entry, CLASS_LIMIT))
                if info.owner != owner:
                    raise InputError(f"member declares {info.owner!r}, expected {owner!r}")
            except InputError as exc:
                invalid += 1
                if len(invalid_examples) < 3:
                    invalid_examples.append(f"{entry!r}: {exc}")
                continue
            classes[owner] = entry
            if info.legacy_mod:
                annotations.append((entry, info.legacy_mod))
            for symbol in info.references:
                refs.setdefault(symbol, owner)
        result = audit(source, binary_annotations=annotations, filename=PurePosixPath(label).name)
    result.warnings = [note for note in result.warnings if not note.startswith("No readable Java source found.")]
    if invalid:
        result.warnings.append(f"Skipped {invalid} invalid classes: {'; '.join(invalid_examples)}")
    if not classes:
        result.warnings.append("No indexable JVM bytecode; static dependency detection cannot check this archive.")
    if profile and result.source_version is None and not any(e.kind == "declared" for e in result.version_evidence):
        result.source_version = profile
        result.version_evidence.append(VersionEvidence(profile, "--source-version", "pack assumption"))
        result.warnings = [note for note in result.warnings if not note.startswith("No exact Minecraft version declared.")]
        result.warnings.append(f"No exact Minecraft version in this archive; assumed pack profile {profile}.")
    elif profile and result.source_version != profile:
        result.warnings.append(f"Archive version does not establish {profile}; that legacy index/mapping profile was not applied.")
    return PackMod(label, path, result, classes, refs, invalid)


def _declared_requests(info: Audit) -> list[tuple[str, str, bool, str]]:
    result = set()
    for mod in info.mods:
        for declaration in mod.dependencies:
            origin, _, tail = declaration.partition(": ")
            data = tail if origin == "@Mod dependencies" else json.loads(tail)
            values = data if isinstance(data, list) else [data]
            for node in values:
                if isinstance(node, dict):
                    node = next((node[k] for k in ("modId", "modid", "label") if isinstance(node.get(k), str)), "")
                if not isinstance(node, str):
                    continue
                for clause in map(str.strip, node.split(";")):
                    prefix, sep, tail = clause.partition(":")
                    # FML annotations use after:/before:/required-after:/required-before: before a mod ID.
                    mod_and_version = tail if sep else prefix
                    wanted = mod_and_version.partition("@")[0].strip()
                    if not DECLARED_ID.fullmatch(wanted):
                        continue
                    required = origin == "requiredMods" or prefix.startswith("required-")
                    result.add((mod.mod_id, wanted, required, clause))
    return sorted(result)


@dataclass
class ModPack:
    root: Path
    mods: dict[str, PackMod]
    context: ApiContext
    errors: list[dict[str, str]]
    owners: dict[str, list[str]] = field(default_factory=lambda: defaultdict(list))
    ids: dict[str, set[str]] = field(default_factory=lambda: defaultdict(set))

    selected_archive: str | None = None
    embedded_paths: dict[Path, str] = field(default_factory=dict)
    archive_warnings: list[str] = field(default_factory=list)

    def add(self, label: str, path: Path, *, required: bool = False) -> None:
        try:
            mod = _load(label, path, self.context.profile)
        except (InputError, OSError) as exc:
            if required:
                raise InputError(f"Could not index selected mod {label!r}: {exc}") from exc
            self.errors.append({"archive": label, "error": str(exc)})
            return
        self.mods[label] = mod
        for owner in mod.classes:
            self.owners[owner].append(label)
        for info in mod.audit.mods:
            if info.mod_id:
                self.ids[info.mod_id.casefold()].add(label)

    def origin(self, path: Path) -> str:
        return f"{self.root}!/{self.embedded_paths[path]}" if path in self.embedded_paths else str(path)

    def select(self, selection: str) -> PackMod:
        token = selection.replace("\\", "/").removeprefix("./")
        if token in self.mods:
            return self.mods[token]
        matches = [mod for name, mod in self.mods.items() if PurePosixPath(name).name == token]
        if len(matches) == 1:
            return matches[0]
        raise InputError(f"Select an unambiguous relative archive path from scan-pack (got {selection!r}).")

    def _pack_providers(self, owner: str, version: str | None) -> tuple[str, ...]:
        # Coremods sometimes ship patched copies of Minecraft classes. Those do not
        # establish which version/signature the game or Forge actually declares.
        if any(platform_symbol(symbol) for symbol in self.context.spellings(Symbol(owner), version)):
            return ()
        return tuple(self.owners.get(owner, ()))

    def dependency_locations(self, mod: PackMod, owner: str) -> set[ClassLocation]:
        if owner in mod.classes:
            return set()
        return {ClassLocation(self.mods[path].path, self.mods[path].classes[owner], owner)
                for path in self._pack_providers(owner, mod.audit.source_version)} | self.context.legacy_locations(owner, mod.audit.source_version)

    def _report_mod(self, mod: PackMod) -> dict:
        version = mod.audit.source_version
        dependencies: dict[str, set[str]] = defaultdict(set)
        ambiguity = set()
        unknown = set()
        changes = []
        for ref in sorted(mod.references):
            if ref.owner in mod.classes:
                continue
            providers = self._pack_providers(ref.owner, version)
            if len(providers) == 1:
                dependencies[providers[0]].add(ref.owner)
            elif providers:
                ambiguity.add(ref.owner)
            if self.context.is_api(ref, version):
                changes.append(self.context.analyze(ref, version, mod.references[ref]))
            elif not providers and not ref.owner.startswith(STANDARD_PREFIXES):
                unknown.add(ref.owner)

        declared = []
        for requester, dependency, required, declaration in _declared_requests(mod.audit):
            key = dependency.casefold()
            providers = sorted(self.ids.get(key, ()))
            status = "loader" if key in LOADER_IDS else "ambiguous" if len(providers) > 1 else "present" if providers else "not_found"
            declared.append({"mod": requester, "id": dependency, "required": required,
                             "declaration": declaration, "status": status, "archives": providers})
        return {"archive": mod.archive, "source_version": version,
                "version_evidence": [asdict(e) for e in mod.audit.version_evidence],
                "mods": [asdict(metadata) for metadata in mod.audit.mods],
                "classes_indexed": len(mod.classes), "corrupt_classes": mod.corrupt_classes,
                "declared_dependencies": declared,
                "binary_dependencies": [{"archive": name, "classes": sorted(owners)}
                                        for name, owners in sorted(dependencies.items())],
                "ambiguous_dependency_classes": sorted(ambiguity),
                "unknown_external_classes": {"count": len(unknown), "examples": sorted(unknown)[:30]},
                "api_checks": changes, "api_summary": dict(sorted(Counter(c["status"] for c in changes).items())),
                "manifest_findings": [asdict(f) for f in mod.audit.findings if f.area == "coremod"],
                "warnings": mod.audit.warnings}

    def report(self, selection: str | None = None) -> dict:
        focus = selection or self.selected_archive
        selected = [self.select(focus)] if focus else self.mods.values()
        return {"schema_version": 1, "root": str(self.root), "legacy_profile": self.context.profile,
                "target": f"Minecraft {MINECRAFT_VERSION} / NeoForge {NEOFORGE_VERSION}",
                "legacy_api_inputs": self.context.legacy.paths, "target_api_inputs": self.context.target.paths,
                "warnings": [*self.archive_warnings, *self.context.legacy.warnings, *self.context.target.warnings],
                "duplicate_mod_ids": {modid: sorted(paths) for modid, paths in sorted(self.ids.items()) if len(paths) > 1},
                "duplicate_classes": {name: paths for name, paths in sorted(self.owners.items()) if len(paths) > 1},
                "archives_indexed": len(self.mods), "archive_errors": self.errors,
                "archives": [self._report_mod(mod) for mod in selected]}


def _embedded_mods(root: Path, staging: Path) -> tuple[list[tuple[str, Path]], list[str]]:
    """Stage a single layer of mod archives, never using archive names as disk paths."""
    with open_input(root, archive_limit=PACK_ARCHIVE_LIMIT) as source:
        assert source.archive is not None
        scoped, top_level = [], []
        for name in source.files:
            member = PurePosixPath(name)
            if member.suffix.lower() not in ARCHIVE_EXTENSIONS:
                continue
            if any(part.casefold() in LOADER_FOLDERS for part in member.parts[:-1]):
                scoped.append(name)
            elif len(member.parts) == 1:
                top_level.append(name)
        warnings = source.warnings.copy()
        names = scoped or top_level
        if not names:
            if class_entries(source.files):
                # A single binary dependency JAR (or a flat pack JAR) can supply its own classes.
                return [(root.name, root)], warnings
            raise InputError("No embedded mod JARs/ZIPs in this pack archive. If it is a launcher export with a "
                             "download manifest, use the installed instance folder with the actual mod files.")
        total = 0
        paths = []
        for index, name in enumerate(names):
            entry = source.files[name]
            assert isinstance(entry, zipfile.ZipInfo)
            if entry.file_size > MOD_ARCHIVE_LIMIT or total + entry.file_size > PACK_ARCHIVE_LIMIT:
                raise InputError(f"Embedded mod data exceeds the size limit at {name!r}; use the installed instance folder.")
            path = staging / f"{index}{PurePosixPath(name).suffix.lower()}"
            try:
                with source.archive.open(entry) as stream, path.open("xb") as target:
                    count = 0
                    while chunk := stream.read(1024 * 1024):
                        count += len(chunk)
                        if count > MOD_ARCHIVE_LIMIT or total + count > PACK_ARCHIVE_LIMIT:
                            raise InputError(f"Embedded mod data exceeds the size limit at {name!r}.")
                        target.write(chunk)
            except (OSError, EOFError, zipfile.BadZipFile, RuntimeError, zlib.error) as exc:
                raise InputError(f"Cannot read embedded mod archive {name!r}: {exc}") from exc
            total += count
            paths.append((name, path))
        return paths, warnings


def _digest(path: Path) -> bytes:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").digest()


def _index_pack(root: Path, sources: list[tuple[str, Path]], context: ApiContext,
                selected_mod: Path | None) -> ModPack:
    pack = ModPack(root, {}, context, [])
    if selected_mod is not None:
        selected_mod = selected_mod.expanduser().resolve()
        if not selected_mod.is_file() or selected_mod.suffix.lower() not in ARCHIVE_EXTENSIONS:
            raise InputError(f"--mod-jar requires a local .jar or .zip mod archive: {selected_mod}")
        size = selected_mod.stat().st_size
        if size > MOD_ARCHIVE_LIMIT:
            raise InputError(f"Selected mod exceeds the {MOD_ARCHIVE_LIMIT // (1024 * 1024)} MiB limit.")
        digest = None
        matches = []
        for label, path in sources:
            if path == selected_mod:
                matches.append(label)
            elif path.stat().st_size == size:
                digest = _digest(selected_mod) if digest is None else digest
                if _digest(path) == digest:
                    matches.append(label)
        if matches:
            label = matches[0]
            pack.add(label, selected_mod, required=True)
            sources = [(name, path) for name, path in sources if name not in matches]
        else:
            prefix = "selected"
            names = {name for name, _ in sources}
            while f"{prefix}/{selected_mod.name}" in names:
                prefix += "-mod"
            label = f"{prefix}/{selected_mod.name}"
            pack.add(label, selected_mod, required=True)
        pack.selected_archive = label
    for label, path in sources:
        pack.add(label, path)
    if not pack.mods:
        if pack.errors:
            first = pack.errors[0]
            raise InputError(f"No readable mod archives. {first['archive']}: {first['error']}")
        raise InputError(f"No .jar or .zip mod archives found in {root}.")
    return pack


@contextmanager
def open_pack(path: Path, context: ApiContext, selected_mod: Path | None = None) -> Iterator[ModPack]:
    root = path.expanduser().resolve()
    if root.is_dir():
        sources = [(jar.relative_to(root).as_posix(), jar) for jar in _archives(root)]
        yield _index_pack(root, sources, context, selected_mod)
        return
    if not root.is_file() or root.suffix.lower() not in ARCHIVE_EXTENSIONS:
        raise InputError(f"Expected an installed modpack folder or a .jar/.zip pack archive: {root}")
    with tempfile.TemporaryDirectory(prefix="mcmodup-pack-") as scratch:
        sources, warnings = _embedded_mods(root, Path(scratch))
        pack = _index_pack(root, sources, context, selected_mod)
        pack.embedded_paths = {jar: label for label, jar in sources if jar != root}
        pack.archive_warnings = warnings
        yield pack


def render_pack(report: dict) -> str:
    lines = [f"Modpack: {report['root']!r}", f"Target: {report['target']}",
             f"Archives indexed: {report['archives_indexed']} | Unreadable: {len(report['archive_errors'])}",
             f"API inputs: {len(report['legacy_api_inputs'])} legacy, {len(report['target_api_inputs'])} target",
             f"Duplicate mod IDs: {len(report['duplicate_mod_ids'])} | Duplicate classes: {len(report['duplicate_classes'])}"]
    for error in report["archive_errors"]:
        lines.append(f"  Skipped {error['archive']!r}: {error['error']}")
    for mod in report["archives"]:
        ids = ", ".join(entry["mod_id"] for entry in mod["mods"] if entry["mod_id"])
        lines += [f"\n{mod['archive']!r} ({ids!r}, source {mod['source_version'] or 'unknown'})",
                  f"  {mod['classes_indexed']} readable classes, {len(mod['binary_dependencies'])} binary dependency archive(s)"]
        for dep in mod["binary_dependencies"]:
            lines.append(f"  Uses {len(dep['classes'])} class(es) in {dep['archive']!r}")
        for dep in mod["declared_dependencies"]:
            if dep["status"] in {"not_found", "ambiguous"}:
                lines.append(f"  Declared {'required' if dep['required'] else 'optional'} dependency {dep['id']!r}: {dep['status']}")
        if mod["api_summary"]:
            lines.append("  API checks: " + ", ".join(f"{status}: {n}" for status, n in mod["api_summary"].items()))
        actionable = [check for check in mod["api_checks"] if check["guidance"] or check["status"] == "mapped_signature_found"]
        for check in actionable[:5]:
            lines.append(f"    {check['legacy_names'][-1]}: {check['status']} ({check['used_by']})")
        for warning in mod["warnings"]:
            lines.append(f"  Warning: {warning}")
    for warning in report["warnings"]:
        lines.append(f"Warning: {warning}")
    lines.append("\nUse --json for full dependency and API evidence. Static findings are not runtime compatibility guarantees.")
    return "\n".join(lines)
