"""Run as python -m mcmodup."""

import argparse
from dataclasses import asdict
import json
from pathlib import Path
import sys

from . import MINECRAFT_VERSION, SOURCE_VERSIONS
from .api import ApiContext, ApiIndex
from .audit import audit, render_audit
from .decompile import DecompileOptions, decompile
from .inputs import InputError, open_input
from .mappings import LegacyNames, PortMap
from .pack import ModPack, render_pack
from .port import PortOptions, generate_port


def _pack_arguments(command: argparse.ArgumentParser) -> None:
    command.add_argument("input", type=Path, help="an installed modpack folder or its mods folder")
    command.add_argument("--source-version", choices=SOURCE_VERSIONS,
                         help="verified Minecraft version of this pack and its legacy API/mappings")
    command.add_argument("--legacy-api", type=Path, action="append", default=[], metavar="JAR_OR_CLASSES",
                         help="local compiled Forge, Minecraft or library API (repeatable; needs --source-version)")
    command.add_argument("--legacy-srg", type=Path, metavar="JOINED.SRG", help="obfuscated-to-SRG mappings for that legacy release")
    command.add_argument("--legacy-methods", type=Path, metavar="METHODS.CSV", help="optional SRG-to-MCP method names")
    command.add_argument("--legacy-fields", type=Path, metavar="FIELDS.CSV", help="optional SRG-to-MCP field names")


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="mcmodup", description=f"Prepare legacy Forge mods for a source port to NeoForge {MINECRAFT_VERSION}.")
    commands = parser.add_subparsers(dest="command", required=True)
    inspect = commands.add_parser("inspect", help="audit a source directory, ZIP, or JAR without modifying it")
    inspect.add_argument("--json", action="store_true", help="emit a machine-readable audit")
    port = commands.add_parser("port", help="generate a new NeoForge workspace from Java source")
    for command in (inspect, port):
        command.add_argument("input", type=Path, help="legacy source directory or ZIP-format archive")
        command.add_argument("--source-version", choices=SOURCE_VERSIONS,
                             help="verified Minecraft version when metadata is missing or conflicting")
    port.add_argument("--output", "-o", type=Path, required=True, help="new directory outside the source tree")
    port.add_argument("--mod-id", help="select or override the legacy mod ID")
    port.add_argument("--name", help="override the display name")
    port.add_argument("--mod-version", help="override the generated mod's version")
    port.add_argument("--license", help="set the verified license for the generated mod")
    port.add_argument("--plain-item", action="append", default=[], metavar="ITEM_ID",
                      help="explicitly add a plain, behavior-free item stub (repeatable)")
    port.add_argument("--github-actions", action="store_true", help="include a JDK 21 build-and-artifact workflow")

    pack = commands.add_parser("scan-pack", help="index a modpack's bytecode, dependencies, and API changes")
    _pack_arguments(pack)
    pack.add_argument("--mod", metavar="ARCHIVE", help="report only this archive while still indexing its peers")
    pack.add_argument("--target-api", type=Path, action="append", default=[], metavar="JAR_OR_CLASSES",
                      help="compiled NeoForge 1.21.1 API or Mojang-named Minecraft classes (repeatable)")
    pack.add_argument("--port-map", type=Path, metavar="JSON", help="reviewed cross-version references to verify in the target API")
    pack.add_argument("--json", action="store_true", help="emit full version, dependency and signature evidence")

    refs = commands.add_parser("decompile-deps", help="locally decompile selected dependency classes for one mod")
    _pack_arguments(refs)
    refs.add_argument("--mod", required=True, metavar="ARCHIVE", help="relative archive path from scan-pack")
    refs.add_argument("--vineflower", required=True, type=Path, metavar="JAR", help="trusted local Vineflower executable JAR")
    refs.add_argument("--java", default="java", help="Java executable for Vineflower (default: java)")
    refs.add_argument("--class", dest="classes", action="append", default=[], metavar="CLASS",
                      help="limit to this referenced class (repeatable, exact slash or dot name)")
    refs.add_argument("--max-classes", type=int, default=250, help="cap staged classes, including nested classes (default: 250)")
    refs.add_argument("--timeout", type=int, default=180, help="limit decompiler runtime in seconds (default: 180)")
    refs.add_argument("--output", "-o", type=Path, required=True, help="new local reference folder outside the modpack")
    return parser


def _pack_context(args: argparse.Namespace) -> ApiContext:
    if (args.legacy_api or args.legacy_srg or args.legacy_methods or args.legacy_fields) and not args.source_version:
        raise InputError("Legacy API or mapping files require --source-version so they aren't applied to the wrong release.")
    names = LegacyNames.load(args.legacy_srg, args.legacy_methods, args.legacy_fields)
    mapping_path = getattr(args, "port_map", None)
    mapping = PortMap.load(mapping_path) if mapping_path else None
    return ApiContext(legacy=ApiIndex.load(args.legacy_api), target=ApiIndex.load(getattr(args, "target_api", [])),
                      names=names, profile=args.source_version, port_map=mapping)


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.command in {"scan-pack", "decompile-deps"}:
            pack = ModPack.load(args.input, _pack_context(args))
            if args.command == "scan-pack":
                report = pack.report(args.mod)
                print(json.dumps(report, indent=2) if args.json else render_pack(report))
            else:
                output = decompile(pack, pack.select(args.mod), DecompileOptions(
                    tool=args.vineflower, output=args.output, java=args.java, selected_classes=tuple(args.classes),
                    max_classes=args.max_classes, timeout=args.timeout))
                print(f"Wrote local dependency references: {output}")
                print("See decompile-report.json and decompiler.log. These files are not included in a build or push.")
        else:
            with open_input(args.input) as source:
                result = audit(source, args.source_version)
                if args.command == "inspect":
                    print(json.dumps(asdict(result), indent=2) if args.json else render_audit(result))
                else:
                    output = generate_port(source, result, PortOptions(
                        output=args.output, mod_id=args.mod_id, name=args.name, mod_version=args.mod_version,
                        license=args.license, plain_items=tuple(args.plain_item), github_actions=args.github_actions))
                    print(f"Created a NeoForge {MINECRAFT_VERSION} porting workspace: {output}")
                    print("Not a finished port. Start with PORTING.md, then build and test with JDK 21.")
    except (InputError, OSError) as exc:
        print(f"mcmodup: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
