"""Inventory a local modpack and find dependencies and migration work from bytecode."""

from collections import Counter, defaultdict
from dataclasses import asdict, dataclass, field
import json
import os
from pathlib import Path
import re

from . import MINECRAFT_VERSION, NEOFORGE_VERSION
from .api import ApiContext, ClassLocation
from .audit import Audit, VersionEvidence, audit
from .classfiles import CLASS_LIMIT, Symbol, class_entries, read_class
from .inputs import IGNORED_DIRS, InputError, open_input


ARCHIVE_EXTENSIONS = {".jar", ".zip"}
LOADER_IDS = {"minecraft", "mcp", "forge", "fml"}
STANDARD_PREFIXES = ("java/", "javax/", "jdk/", "sun/", "com/sun/", "org/w3c/dom/", "org/xml/sax/")
DECLARED_ID = re.compile(r"[A-Za-z0-9_.-]+")


@dataclass
class PackMod:
    archive: str
    path: Path
    audit: Audit
    classes: dict[str, str]
    references: dict[Symbol, str]
    corrupt_classes: int = 0


def _archives(root: Path) -> list[Path]:
    loader_folders = [root / name for name in ("mods", "coremods")
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


def _load(root: Path, path: Path, profile: str | None) -> PackMod:
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
        result = audit(source, binary_annotations=annotations)
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
    return PackMod(path.relative_to(root).as_posix(), path, result, classes, refs, invalid)


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

    @classmethod
    def load(cls, root: Path, context: ApiContext) -> "ModPack":
        root = root.expanduser().resolve()
        if not root.is_dir():
            raise InputError(f"Expected a local modpack or mods folder: {root}")
        paths = _archives(root)
        if not paths:
            raise InputError(f"No .jar or .zip mod archives found in {root}.")
        pack = cls(root, {}, context, [])
        for path in paths:
            try:
                mod = _load(root, path, context.profile)
            except (InputError, OSError) as exc:
                pack.errors.append({"archive": path.relative_to(root).as_posix(), "error": str(exc)})
                continue
            pack.mods[mod.archive] = mod
            for owner in mod.classes:
                pack.owners[owner].append(mod.archive)
            for info in mod.audit.mods:
                if info.mod_id:
                    pack.ids[info.mod_id.casefold()].add(mod.archive)
        if not pack.mods:
            first = pack.errors[0]
            raise InputError(f"No readable mod archives. {first['archive']}: {first['error']}")
        return pack

    def select(self, selection: str) -> PackMod:
        token = selection.replace("\\", "/").removeprefix("./")
        if token in self.mods:
            return self.mods[token]
        matches = [mod for name, mod in self.mods.items() if mod.path.name == token]
        if len(matches) == 1:
            return matches[0]
        raise InputError(f"Select an unambiguous relative archive path from scan-pack (got {selection!r}).")

    def dependency_locations(self, mod: PackMod, owner: str) -> set[ClassLocation]:
        if owner in mod.classes:
            return set()
        return {ClassLocation(self.mods[path].path, self.mods[path].classes[owner], owner)
                for path in self.owners.get(owner, ())} | self.context.legacy_locations(owner, mod.audit.source_version)

    def _report_mod(self, mod: PackMod) -> dict:
        version = mod.audit.source_version
        dependencies: dict[str, set[str]] = defaultdict(set)
        ambiguity = set()
        unknown = set()
        changes = []
        for ref in sorted(mod.references):
            if ref.owner in mod.classes:
                continue
            providers = self.owners.get(ref.owner, ())
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
        selected = [self.select(selection)] if selection else self.mods.values()
        return {"schema_version": 1, "root": str(self.root), "legacy_profile": self.context.profile,
                "target": f"Minecraft {MINECRAFT_VERSION} / NeoForge {NEOFORGE_VERSION}",
                "legacy_api_inputs": self.context.legacy.paths, "target_api_inputs": self.context.target.paths,
                "warnings": [*self.context.legacy.warnings, *self.context.target.warnings],
                "duplicate_mod_ids": {modid: sorted(paths) for modid, paths in sorted(self.ids.items()) if len(paths) > 1},
                "duplicate_classes": {name: paths for name, paths in sorted(self.owners.items()) if len(paths) > 1},
                "archives_indexed": len(self.mods), "archive_errors": self.errors,
                "archives": [self._report_mod(mod) for mod in selected]}


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
