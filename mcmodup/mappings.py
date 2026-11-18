"""Load same-version MCP/SRG aliases and explicit, reviewed cross-version references."""

import csv
from dataclasses import dataclass, field
import io
import json
from pathlib import Path

from . import MINECRAFT_VERSION, SOURCE_VERSIONS
from .classfiles import CLASS_NAME, DESCRIPTOR_CLASSES, JVM_MEMBER, Symbol
from .inputs import InputError


def _read(path: Path) -> str:
    limit = 32 * 1024 * 1024
    with path.expanduser().open("rb") as stream:
        content = stream.read(limit + 1)
    if len(content) > limit:
        raise InputError(f"Mapping file is larger than 32 MiB: {path}")
    try:
        return content.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise InputError(f"Mapping file is not UTF-8: {path}") from exc


def _unique(table: dict, key, value) -> None:
    if key in table and table[key] != value:
        raise InputError(f"conflicting mappings for {key!r}")
    table[key] = value


def _split_member(path: str) -> tuple[str, str]:
    owner, sep, name = path.rpartition("/")
    if not sep or not CLASS_NAME.fullmatch(owner) or not JVM_MEMBER.fullmatch(name):
        raise InputError(f"invalid SRG member path {path!r}")
    return owner, name


def _csv_aliases(path: Path | None) -> dict[str, str]:
    if path is None:
        return {}
    table = {}
    try:
        rows = csv.DictReader(io.StringIO(_read(path), newline=""))
        if not rows.fieldnames or not {"searge", "name"}.issubset(rows.fieldnames):
            raise InputError("expected MCP CSV columns 'searge' and 'name'")
        for row in rows:
            old, new = (row.get(key) or "" for key in ("searge", "name"))
            if not JVM_MEMBER.fullmatch(old) or not JVM_MEMBER.fullmatch(new):
                raise InputError(f"invalid MCP name at CSV line {rows.line_num}")
            _unique(table, old, new)
    except (csv.Error, InputError) as exc:
        raise InputError(f"{path}: {exc}") from exc
    return table


@dataclass
class LegacyNames:
    classes: dict[str, str] = field(default_factory=dict)
    fields: dict[tuple[str, str], tuple[str, str]] = field(default_factory=dict)
    methods: dict[Symbol, Symbol] = field(default_factory=dict)
    field_aliases: dict[str, str] = field(default_factory=dict)
    method_aliases: dict[str, str] = field(default_factory=dict)

    @classmethod
    def load(cls, srg: Path | None, methods: Path | None, fields: Path | None) -> "LegacyNames":
        names = cls(field_aliases=_csv_aliases(fields), method_aliases=_csv_aliases(methods))
        if srg is None:
            return names
        for line_number, line in enumerate(_read(srg).splitlines(), 1):
            parts = line.partition("#")[0].split()
            if not parts or parts[0] == "PK:":
                continue
            try:
                if parts[0] == "CL:" and len(parts) == 3 and all(CLASS_NAME.fullmatch(part) for part in parts[1:]):
                    _unique(names.classes, parts[1], parts[2])
                elif parts[0] == "FD:" and len(parts) == 3:
                    _unique(names.fields, _split_member(parts[1]), _split_member(parts[2]))
                elif parts[0] == "MD:" and len(parts) == 5:
                    pairs = []
                    for owner_and_member, descriptor in ((parts[1], parts[2]), (parts[3], parts[4])):
                        owner, member = _split_member(owner_and_member)
                        symbol = Symbol.parse(f"{owner}#{member}{descriptor}")
                        if symbol.kind != "method":
                            raise InputError("expected a method descriptor in MD record")
                        pairs.append(symbol)
                    _unique(names.methods, pairs[0], pairs[1])
                else:
                    raise InputError("expected a joined.srg CL:, FD:, or MD: record")
            except InputError as exc:
                raise InputError(f"{srg}:{line_number}: {exc}") from exc
        return names

    def spellings(self, raw: Symbol) -> tuple[Symbol, ...]:
        """Return raw, SRG, then MCP spellings without treating them as cross-version aliases."""
        owner, name = self.classes.get(raw.owner, raw.owner), raw.name
        descriptor = DESCRIPTOR_CLASSES.sub(lambda m: "L" + self.classes.get(m[1], m[1]), raw.descriptor)
        if raw in self.methods:
            srg = self.methods[raw]
        else:
            if raw.kind == "field":
                owner, name = self.fields.get((raw.owner, raw.name), (owner, name))
            srg = Symbol(owner, name, descriptor)
        aliases = self.method_aliases if srg.kind == "method" else self.field_aliases
        mcp = Symbol(srg.owner, aliases.get(srg.name, srg.name), srg.descriptor)
        return tuple(dict.fromkeys((raw, srg, mcp)))


@dataclass(frozen=True)
class PortReference:
    target: Symbol
    note: str


@dataclass
class PortMap:
    source_version: str
    entries: dict[Symbol, PortReference]

    @classmethod
    def load(cls, path: Path) -> "PortMap":
        try:
            doc = json.loads(_read(path))
        except (ValueError, RecursionError) as exc:
            raise InputError(f"{path}: invalid port-map JSON: {exc}") from exc
        if (not isinstance(doc, dict) or doc.get("from_version") not in SOURCE_VERSIONS
                or doc.get("to_version") != MINECRAFT_VERSION or not isinstance(doc.get("entries"), list)):
            raise InputError(f"{path}: expected from_version, to_version={MINECRAFT_VERSION!r}, and an entries array")
        entries = {}
        for i, entry in enumerate(doc["entries"], 1):
            try:
                if not isinstance(entry, dict) or not all(isinstance(entry.get(key), str) for key in ("from", "to")):
                    raise InputError("expected string 'from' and 'to' symbols")
                before, after = Symbol.parse(entry["from"]), Symbol.parse(entry["to"])
                note = entry.get("note", "")
                if before.kind != after.kind or not isinstance(note, str):
                    raise InputError("symbols must have the same kind; 'note' must be text")
                _unique(entries, before, PortReference(after, note))
            except InputError as exc:
                raise InputError(f"{path}: entries[{i - 1}]: {exc}") from exc
        return cls(doc["from_version"], entries)
