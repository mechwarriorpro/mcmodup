"""Select one local, exact-version MCP configuration. Never run or extract MCP scripts."""

from collections import defaultdict, deque
import configparser
import csv
from dataclasses import dataclass
import hashlib
import io
from pathlib import Path, PurePosixPath
import re

from . import SOURCE_VERSIONS
from .classfiles import CLASS_NAME, DESCRIPTOR_CLASSES, METHOD_DESCRIPTOR, ClassFile, Symbol
from .inputs import InputError, open_input
from .mappings import LegacyNames, csv_aliases


MAP_LIMIT = 8 * 1024 * 1024
SIDES = {"client": "0", "server": "1"}
SRG_ID = re.compile(r"(?:func|field)_[0-9]+_[A-Za-z0-9_]+")


def _config_path(path: Path, version: str) -> tuple[Path, str | None]:
    """Find a direct config or one exact-version entry in a local catalog."""
    if not path.is_dir():
        return path, None
    direct = [folder for folder in (path, path / "conf") if (folder / "version.cfg").is_file()]
    if len(direct) > 1:
        raise InputError(f"Multiple MCP configurations under {path}; select the config directory explicitly.")
    if direct:
        return direct[0].resolve(), ""
    entries = [entry for entry in (path / version, path / f"{version}.zip") if entry.exists()]
    if len(entries) != 1:
        raise InputError(f"Expected exactly one local MCP entry {version}/ or {version}.zip under {path}; no other version is used.")
    entry = entries[0].resolve()
    if entry.is_dir():
        candidates = [folder for folder in (entry, entry / "conf") if (folder / "version.cfg").is_file()]
        if len(candidates) != 1:
            raise InputError(f"Expected one MCP version.cfg (in conf/ or at root) in {entry}.")
        return candidates[0].resolve(), ""
    return entry, None


def _packages(text: str, label: str) -> dict[str, str]:
    rows = csv.DictReader(io.StringIO(text, newline=""))
    if not rows.fieldnames or not {"class", "package"}.issubset(rows.fieldnames):
        raise InputError(f"{label}: expected FML packages.csv columns 'class' and 'package'.")
    classes = {}
    for row in rows:
        cls, package = (row.get(key) or "" for key in ("class", "package"))
        if "/" in cls or not CLASS_NAME.fullmatch(cls) or not CLASS_NAME.fullmatch(package):
            raise InputError(f"{label}:{rows.line_num}: invalid FML class or package name.")
        old, new = f"net/minecraft/src/{cls}", f"{package}/{cls}"
        if old in classes and classes[old] != new:
            raise InputError(f"{label}:{rows.line_num}: conflicting FML package for {cls!r}.")
        classes[old] = new
    return classes


@dataclass(frozen=True)
class McpConfig:
    root: Path
    path: Path
    version: str
    release: str
    side: str
    srg_file: str
    digests: dict[str, str]
    raw: LegacyNames
    names: LegacyNames

    @classmethod
    def load(cls, path: Path, version: str, side: str) -> "McpConfig":
        if version not in SOURCE_VERSIONS or side not in SIDES:
            raise InputError("MCP requires a supported exact Minecraft version and client or server side.")
        root = path.expanduser().resolve()
        config, prefix = _config_path(root, version)
        digests = {}
        with open_input(config) as source:
            if prefix is None:
                configs = [name for name in source.files if name == "version.cfg" or name.endswith("/conf/version.cfg")]
                if "conf/version.cfg" in source.files:
                    configs.append("conf/version.cfg")
                if len(configs) != 1:
                    raise InputError(f"{config}: expected one MCP version.cfg at root or in a conf/ directory.")
                prefix = configs[0].removesuffix("version.cfg")
            for name in ("joined.srg", "client.srg", "server.srg", "fields.csv", "methods.csv", "packages.csv"):
                if f"Skipped duplicate archive entry {prefix + name!r}." in source.warnings:
                    raise InputError(f"{config}: ambiguous MCP file {prefix + name!r}.")

            def read(name: str) -> str:
                relative = prefix + name
                if relative not in source.files:
                    raise InputError(f"{config}: missing or ambiguous MCP file {relative!r}.")
                data = source.read(relative, MAP_LIMIT)
                digests[relative] = hashlib.sha256(data).hexdigest()
                try:
                    return data.decode("utf-8-sig")
                except UnicodeDecodeError as exc:
                    raise InputError(f"{config}: MCP file {relative!r} is not UTF-8.") from exc

            version_config = configparser.ConfigParser(interpolation=None)
            try:
                version_config.read_string(read("version.cfg"))
                release = version_config.get("VERSION", "MCPVersion", fallback="").strip()
                client = version_config.get("VERSION", "ClientVersion", fallback="").strip()
                server = version_config.get("VERSION", "ServerVersion", fallback="").strip()
            except configparser.Error as exc:
                raise InputError(f"{config}: invalid MCP version.cfg: {exc}") from exc
            if (client, server) != (version, version) or not release:
                raise InputError(f"{config}: MCP version.cfg declares Minecraft client {client or '?'} / "
                                 f"server {server or '?'}, not exact Minecraft {version} for both (and needs MCPVersion).")
            joined = prefix + "joined.srg" in source.files
            srg_file = "joined.srg" if joined else f"{side}.srg"
            label = f"{config}!/{prefix}"
            raw = LegacyNames(field_aliases=csv_aliases(read("fields.csv"), label + "fields.csv", side=SIDES[side], joined=joined),
                              method_aliases=csv_aliases(read("methods.csv"), label + "methods.csv", side=SIDES[side], joined=joined))
            raw.read_srg(read(srg_file), label + srg_file)
            if not raw.classes or not raw.method_aliases or not raw.field_aliases:
                raise InputError(f"{config}: expected class, method and field naming data in the MCP config.")
            packages_file = prefix + "packages.csv"
            try:
                packages = _packages(read("packages.csv"), label + "packages.csv") if packages_file in source.files else {}
            except csv.Error as exc:
                raise InputError(f"{label}packages.csv: {exc}") from exc

        def moved(owner: str) -> str:
            top, sep, suffix = owner.partition("$")
            return packages.get(owner, packages.get(top, top) + sep + suffix)

        classes = {name: moved(srg) for name, srg in raw.classes.items()}
        for srg in raw.classes.values():
            if moved(srg) != srg:
                classes[srg] = moved(srg)
        names = LegacyNames(classes, {key: (moved(owner), name) for key, (owner, name) in raw.fields.items()},
                            {key: Symbol(moved(srg.owner), srg.name,
                                         DESCRIPTOR_CLASSES.sub(lambda m: "L" + moved(m[1]), srg.descriptor))
                             for key, srg in raw.methods.items()}, raw.field_aliases, raw.method_aliases)
        return cls(root, config, version, release, side, prefix + srg_file, digests, raw, names)

    def evidence(self) -> dict:
        return {"config": str(self.path), "minecraft": self.version, "mcp_version": self.release,
                "side": self.side, "srg": self.srg_file, "sha256": self.digests}

    def class_name(self, owner: str) -> str:
        # Use the remapper's nested-class fallback when no exact class entry exists.
        outer, suffix = owner, ""
        while outer not in self.names.classes and "$" in outer:
            outer, _, inner = outer.rpartition("$")
            suffix = "$" + inner + suffix
        return self.names.classes.get(outer, outer) + suffix

    def remap_file(self, output: Path, classes: dict[str, ClassFile],
                   hierarchy: dict[str, tuple[str, ...]]) -> None:
        """Compose raw -> MCP and owner-scoped SRG -> MCP for a temporary remapper input."""
        methods: dict[Symbol, Symbol] = {}
        fields: dict[tuple[str, str], tuple[str, str]] = {}
        for raw, srg in self.raw.methods.items():
            named = self.names.spellings(raw)[-1]
            methods[raw] = named
            if srg.name != named.name:
                methods.setdefault(srg, named)
                methods.setdefault(Symbol(named.owner, srg.name, named.descriptor), named)
        for raw, srg in self.raw.fields.items():
            owner, name = self.names.fields[raw]
            alias = self.names.field_aliases.get(name, name)
            fields[raw] = (owner, alias)
            if alias != name:
                fields.setdefault(srg, (owner, alias))
                fields.setdefault((owner, name), (owner, alias))

        observed = {symbol for info in classes.values() for symbol in info.references if symbol.name}
        observed.update(Symbol(info.owner, name, desc) for info in classes.values() for name, desc in info.members)
        # Old FML may already have moved the net/minecraft/src classes. SRG IDs
        # still apply to those game types and their known subclasses, not unrelated mods.
        source_types = set(self.names.classes) | set(self.names.classes.values()) | set(self.raw.classes.values())
        flat_types = {name.rsplit("/", 1)[-1] for name in source_types if name.startswith("net/minecraft/src/")}
        candidates = set(hierarchy) | {s.owner for s in observed} | {p for parents in hierarchy.values() for p in parents}
        game = {name for name in candidates if name in source_types or
                (name.startswith("net/minecraft/") and name.rsplit("/", 1)[-1] in flat_types)}
        children = defaultdict(set)
        for child, parents in hierarchy.items():
            for parent in parents:
                children[parent].add(child)
        queue = deque(game)
        while queue:
            for child in children[queue.popleft()] - game:
                game.add(child)
                queue.append(child)
        for symbol in observed:
            if symbol.owner not in game or not SRG_ID.fullmatch(symbol.name):
                continue
            named = self.names.spellings(symbol)[-1]
            if named.name == symbol.name:
                continue
            if symbol.kind == "method" and METHOD_DESCRIPTOR.fullmatch(symbol.descriptor):
                methods.setdefault(symbol, named)
            elif symbol.kind == "field":
                fields.setdefault((symbol.owner, symbol.name), (named.owner, named.name))

        with output.open("w", encoding="utf-8", newline="\n") as stream:
            for raw, named in sorted(self.names.classes.items()):
                if raw != named:
                    stream.write(f"CL: {raw} {named}\n")
            for (owner, name), (named_owner, alias) in sorted(fields.items()):
                if name != alias:
                    stream.write(f"FD: {owner}/{name} {named_owner}/{alias}\n")
            for raw, named in sorted(methods.items()):
                if raw.name != named.name:
                    stream.write(f"MD: {raw.owner}/{raw.name} {raw.descriptor} {named.owner}/{named.name} {named.descriptor}\n")
