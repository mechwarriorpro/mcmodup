"""Resolve source-pack dependency evidence without interpreting legacy ranges as native ranges."""

from collections import defaultdict
from dataclasses import dataclass
from functools import cached_property
import json
import re
from typing import TYPE_CHECKING

from .api_rules import platform_symbol
from .audit import Audit
from .versions import declared_version, matches, ranges_disjoint, supported_range

if TYPE_CHECKING:
    from .pack import PackMod

LOADER_IDS = {"minecraft", "mcp", "forge", "fml"}
STANDARD_PREFIXES = ("java/", "javax/", "jdk/", "sun/", "com/sun/", "org/w3c/dom/", "org/xml/sax/")
_DECLARED_ID = re.compile(r"[A-Za-z0-9_.-]+(?:\|[A-Za-z0-9_.-]+)*")


@dataclass(frozen=True, order=True)
class Declaration:
    consumer_id: str
    provider_id: str
    required: bool
    text: str
    version_range: str


def _declared_requests(audit: Audit) -> list[Declaration]:
    result = set()
    for mod in audit.mods:
        for declaration in mod.dependencies:
            origin, _, tail = declaration.partition(": ")
            data = tail if origin == "@Mod dependencies" else json.loads(tail)
            values = data if isinstance(data, list) else [data]
            for node in values:
                if isinstance(node, dict):
                    # FML's JSON format normally stores ArtifactVersions as strings, but some
                    # packs ship expanded metadata. Keep its range when one is present.
                    label = next((node[k] for k in ("modId", "modid", "label") if isinstance(node.get(k), str)), "")
                    spec = next((node[k] for k in ("versionRange", "range", "version") if isinstance(node.get(k), str)), "")
                    node = label + ("@" + spec if spec and "@" not in label else "")
                if not isinstance(node, str):
                    continue
                for clause in map(str.strip, node.split(";")):
                    prefix, sep, tail = clause.partition(":")
                    name, _, spec = (tail if sep else prefix).partition("@")
                    if _DECLARED_ID.fullmatch(name.strip()):
                        result.add(Declaration(mod.mod_id, name.strip(), origin == "requiredMods" or prefix.startswith("required-"),
                                               clause, spec.strip()))
    return sorted(result)


@dataclass(frozen=True)
class Provider:
    archive: str
    version: str | None
    minecraft: str | None

    def report(self) -> dict:
        return {"archive": self.archive, "version": self.version, "minecraft": self.minecraft}


class LegacyDependencies:
    def __init__(self, mods: dict[str, "PackMod"]):
        self.mods = mods

    @cached_property
    def by_id(self) -> dict[str, list[Provider]]:
        by_id = defaultdict(list)
        for label, mod in self.mods.items():
            versions = defaultdict(set)
            for meta in mod.audit.mods:
                if meta.mod_id:
                    releases = versions[meta.mod_id.casefold()]
                    if declared_version(meta.version):
                        releases.add(meta.version)
            for key, releases in versions.items():
                by_id[key].append(Provider(label, next(iter(releases)) if len(releases) == 1 else None,
                                           mod.audit.source_version))
        return by_id

    @cached_property
    def owners(self) -> dict[str, list[str]]:
        owners = defaultdict(list)
        for label, mod in self.mods.items():
            for owner in mod.classes:
                owners[owner].append(label)
        return owners

    def _same_release(self, mod: "PackMod", archive: str) -> bool:
        other = self.mods[archive].audit.source_version
        return not (mod.audit.source_version and other and mod.audit.source_version != other)

    def declarations(self, mod: "PackMod") -> list[dict]:
        requests = _declared_requests(mod.audit)
        # Required clauses for the same legacy ID must be satisfied by the same archive.
        required = defaultdict(set)
        for request in requests:
            if request.required:
                required[request.provider_id.casefold()].add(request.version_range)
        rows = []
        for request in requests:
            key = request.provider_id.casefold()
            all_providers = self.by_id.get(key, [])
            scoped = [provider for provider in all_providers if self._same_release(mod, provider.archive)]
            ranges = required[key] if request.required else {request.version_range}
            constrained = ranges - {"", "*"}
            compatible = [p for p in scoped if all(matches(p.version or "", spec) for spec in ranges)]
            unknown = bool(constrained and any(p.version is None for p in scoped))
            if key in LOADER_IDS:
                status = "loader"
            elif not all_providers:
                status = "not_found"
            elif not scoped:
                status = "minecraft_mismatch"
            elif any(not supported_range(spec) for spec in ranges):
                status = "unsupported_range"
            elif len(compatible) > 1:
                status = "ambiguous"
            elif unknown:
                status = "unknown_version"
            elif not compatible:
                status = "version_mismatch"
            else:
                status = "present"
            rows.append({"mod": request.consumer_id, "id": request.provider_id, "range": request.version_range,
                         "required": request.required, "declaration": request.text, "status": status,
                         "archives": sorted(p.archive for p in (compatible if status == "present" else all_providers)),
                         "provider": compatible[0].report() if status == "present" else None,
                         "candidates": [p.report() for p in all_providers]})
        return rows

    def class_providers(self, mod: "PackMod", owner: str, declarations: list[dict]) -> tuple[str, ...]:
        scoped = set(path for path in self.owners.get(owner, ()) if path != mod.archive and self._same_release(mod, path))
        if len(scoped) > 1:
            # Only use a range to disambiguate duplicate owners of the *same* declared library.
            matched = {row["provider"]["archive"] for row in declarations
                       if row["required"] and row["provider"] and row["provider"]["archive"] in scoped and
                       scoped <= {candidate["archive"] for candidate in row["candidates"]}}
            if len(matched) == 1:
                return tuple(matched)
        return tuple(sorted(scoped))

    def usage(self, mod: "PackMod", declared: list[dict], *, platform_owners: set[str] | None = None) -> tuple[dict[str, set[str]], set[str]]:
        dependencies, ambiguous = defaultdict(set), set()
        for ref in mod.references:
            owner = ref.owner
            if (owner in mod.classes or platform_symbol(ref) or owner.startswith(STANDARD_PREFIXES) or
                    (platform_owners is not None and owner in platform_owners)):
                continue
            paths = self.class_providers(mod, owner, declared)
            if len(paths) == 1:
                dependencies[paths[0]].add(owner)
            elif paths:
                ambiguous.add(owner)
        return dependencies, ambiguous


def legacy_conflicts(rows: list[dict]) -> list[dict]:
    by_id = defaultdict(list)
    for row in rows:
        if row["required"] and row["status"] != "loader":
            by_id[row["id"].casefold()].append(row)
    conflicts = []
    for key, group in sorted(by_id.items()):
        if len(group) > 1 and ranges_disjoint(row["range"] for row in group):
            conflicts.append({"id": key, "status": "disjoint_legacy_ranges", "requirements": group})
    return conflicts
