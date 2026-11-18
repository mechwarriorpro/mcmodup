"""Resolve bytecode API signatures against caller-supplied, versioned class indexes."""

from collections import defaultdict, deque
from dataclasses import asdict, dataclass, field
from pathlib import Path

from .api_rules import platform_symbol, rules_for
from .classfiles import CLASS_LIMIT, ClassFile, Symbol, class_entries, read_class
from .inputs import InputError, open_input
from .mappings import LegacyNames, PortMap


@dataclass(frozen=True)
class ClassLocation:
    file: Path
    entry: str
    owner: str


@dataclass(frozen=True)
class Match:
    status: str
    declared_by: str | None = None
    artifact: str | None = None
    access: str | None = None
    static: bool | None = None
    owner_kind: str | None = None


@dataclass
class ApiIndex:
    paths: list[str] = field(default_factory=list)
    classes: dict[str, list[tuple[ClassLocation, ClassFile]]] = field(default_factory=lambda: defaultdict(list))
    warnings: list[str] = field(default_factory=list)

    @classmethod
    def load(cls, inputs: list[Path]) -> "ApiIndex":
        index = cls()
        for path in dict.fromkeys(path.expanduser().resolve() for path in inputs):
            count = 0
            errors = []
            with open_input(path) as source:
                for owner, entry in class_entries(source.files).items():
                    try:
                        info = read_class(source.read(entry, CLASS_LIMIT), collect_references=False)
                        if info.owner != owner:
                            raise InputError(f"class declares {info.owner!r}, not {owner!r}")
                    except InputError as exc:
                        errors.append(f"{entry!r}: {exc}")
                        continue
                    index.classes[owner].append((ClassLocation(source.path, entry, owner), info))
                    count += 1
                index.warnings.extend(f"{source.path}: {warning}" for warning in source.warnings)
            if not count:
                detail = f" First problem: {errors[0]}" if errors else ""
                raise InputError(f"No readable JVM signatures in API input {path}. Supply compiled API JARs or class directories.{detail}")
            index.paths.append(str(path))
            if errors:
                index.warnings.append(f"{path}: skipped {len(errors)} invalid classes. Examples: {'; '.join(errors[:3])}")
        return index

    def named(self, names: LegacyNames) -> "ApiIndex":
        """Normalize declarations as well as uses (e.g. a raw Forge universal JAR and an SRG mod)."""
        index = ApiIndex(paths=self.paths)
        for entries in self.classes.values():
            for location, info in entries:
                owner = names.spellings(Symbol(info.owner))[-1].owner
                parents = tuple(names.spellings(Symbol(parent))[-1].owner for parent in info.parents)
                members = {}
                for (name, descriptor), flags in info.members.items():
                    mapped = names.spellings(Symbol(info.owner, name, descriptor))[-1]
                    key = (mapped.name, mapped.descriptor)
                    # A non-injective alias table cannot establish one accessible declaration.
                    members[key] = 0 if key in members else flags
                index.classes[owner].append((location, ClassFile(owner, info.access, parents, members)))
        return index

    def locate(self, owner: str) -> ClassLocation | None:
        providers = self.classes.get(owner, ())
        return providers[0][0] if len(providers) == 1 else None

    def lookup(self, symbol: Symbol) -> Match:
        if not self.paths:
            return Match("not_indexed")
        candidates = self.classes.get(symbol.owner, ())
        if not candidates:
            return Match("owner_not_in_index")
        if len(candidates) != 1:
            return Match("ambiguous_owner")
        location, info = candidates[0]
        owner_kind = "interface" if info.access & 0x0200 else "class"
        if not symbol.name:
            return _match(info.access, location, owner_kind)

        queue = deque([symbol.owner])
        visited = set()
        incomplete = False
        while queue:
            owner = queue.popleft()
            if owner in visited:
                continue
            visited.add(owner)
            definitions = self.classes.get(owner, ())
            if len(definitions) != 1:
                if owner != "java/lang/Object":
                    incomplete = True
                continue
            location, info = definitions[0]
            flags = info.members.get((symbol.name, symbol.descriptor))
            if flags is not None and (owner == symbol.owner or flags & (0x0001 | 0x0004)):
                return _match(flags, location, owner_kind, member=True, public_owner=bool(candidates[0][1].access & 0x0001))
            if symbol.name not in {"<init>", "<clinit>"}:
                queue.extend(info.parents)
        return Match("incomplete_hierarchy" if incomplete else "signature_not_in_index")


def _match(flags: int, location: ClassLocation, owner_kind: str, *,
           member: bool = False, public_owner: bool = True) -> Match:
    access = "public" if flags & 0x0001 else "protected" if flags & 0x0004 else "private" if flags & 0x0002 else "package"
    # Protected and package-scoped signatures need caller/inheritance context before assuming linkage.
    visibility = "found" if access == "public" and public_owner else "access_review"
    return Match(visibility, location.owner, str(location.file), access, bool(flags & 0x0008) if member else None, owner_kind)


@dataclass
class ApiContext:
    legacy: ApiIndex = field(default_factory=ApiIndex)
    target: ApiIndex = field(default_factory=ApiIndex)
    names: LegacyNames = field(default_factory=LegacyNames)
    profile: str | None = None
    port_map: PortMap | None = None
    _named_legacy: ApiIndex | None = field(init=False, default=None)

    def __post_init__(self) -> None:
        if self.legacy.paths and (self.names.classes or self.names.fields or self.names.methods
                                  or self.names.field_aliases or self.names.method_aliases):
            self._named_legacy = self.legacy.named(self.names)

    def spellings(self, symbol: Symbol, version: str | None) -> tuple[Symbol, ...]:
        # Do not reinterpret bytes from a different Minecraft version through these aliases.
        if version is None or version != self.profile:
            return (symbol,)
        return self.names.spellings(symbol)

    def applicable_legacy(self, version: str | None) -> ApiIndex | None:
        return self.legacy if version is not None and version == self.profile else None

    def legacy_locations(self, owner: str, version: str | None) -> set[ClassLocation]:
        legacy = self.applicable_legacy(version)
        if legacy is None:
            return set()
        return {location for symbol in self.spellings(Symbol(owner), version)
                for index in (legacy, self._named_legacy) if index is not None
                for location, _ in index.classes.get(symbol.owner, ())}

    def is_api(self, symbol: Symbol, version: str | None) -> bool:
        return bool(self.legacy_locations(symbol.owner, version)) or any(
            platform_symbol(ref) or bool(rules_for(ref, version)) or (self.port_map is not None and version == self.port_map.source_version
                                     and ref in self.port_map.entries)
            for ref in self.spellings(symbol, version))

    def analyze(self, raw: Symbol, version: str | None, used_by: str) -> dict:
        spellings = self.spellings(raw, version)
        readable = spellings[-1]
        legacy = self.applicable_legacy(version)
        legacy_match = Match("profile_not_applicable" if self.legacy.paths and not legacy else "not_indexed")
        legacy_as = readable
        normalized = False
        if legacy and legacy.paths:
            for symbol in spellings:
                legacy_as, legacy_match = symbol, legacy.lookup(symbol)
                if legacy_match.status in {"found", "access_review", "ambiguous_owner"}:
                    break
            else:
                if self._named_legacy is not None:
                    legacy_as, legacy_match = readable, self._named_legacy.lookup(readable)
                    normalized = True

        candidate_map = self.port_map
        mapping = next((candidate_map.entries[symbol] for symbol in reversed(spellings)
                        if symbol in candidate_map.entries), None) if candidate_map and version == candidate_map.source_version else None
        # Rules use deobfuscated old names. No rule is itself a verified cross-version mapping.
        recipes = []
        for rule in dict.fromkeys(rule for symbol in reversed(spellings) for rule in rules_for(symbol, version)):
            recipes.append({"area": rule.area, "versions": rule.versions, "advice": rule.advice,
                            "alternatives": [{"symbol": owner, "target": asdict(self.target.lookup(Symbol(owner)))}
                                             for owner in rule.alternatives]})
        target_symbol = mapping.target if mapping else readable
        if not mapping and "/" not in readable.owner:
            # Two releases may reuse the same single-letter obfuscated name for unrelated classes.
            target_match = Match("named_symbol_required")
        else:
            target_match = self.target.lookup(target_symbol)
        abi_changes = []
        if legacy_match.status in {"found", "access_review"} and target_match.status in {"found", "access_review"}:
            if legacy_match.static != target_match.static:
                abi_changes.append("static/instance invocation changed")
            if legacy_match.owner_kind != target_match.owner_kind:
                abi_changes.append("class/interface owner changed")
            if legacy_match.access != target_match.access:
                abi_changes.append("declaration access changed")
        if mapping:
            status = "mapped_signature_found" if target_match.status == "found" else "mapping_unverified"
        elif target_match.status == "found":
            status = "signature_found_abi_review" if abi_changes else "same_signature_found"
        elif recipes:
            status = "rewrite"
        elif target_match.status == "not_indexed":
            status = "target_not_indexed"
        else:
            status = "review_signature"
        return {"symbol": raw.key, "used_by": used_by, "legacy_names": [symbol.key for symbol in spellings],
                "legacy_lookup": {"symbol": legacy_as.key, "normalized_index": normalized, **asdict(legacy_match)}, "status": status,
                "target_lookup": {"symbol": target_symbol.key, **asdict(target_match)},
                "mapping_note": mapping.note if mapping else None, "abi_changes": abi_changes, "guidance": recipes}
