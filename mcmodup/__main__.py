"""Run as python -m mcmodup."""

import argparse
from dataclasses import asdict
import json
from pathlib import Path
import sys

from . import MINECRAFT_VERSION, SOURCE_VERSIONS
from .api import ApiContext, ApiIndex
from .audit import audit, render_audit
from .catalogs import PROFILES
from .convert import convert_catalog
from .decompile import DecompileOptions, decompile
from .inputs import InputError, open_input
from .mappings import LegacyNames, PortMap
from .pack import open_pack, render_pack
from .port import PortOptions, generate_port


def _pack_arguments(command: argparse.ArgumentParser, *, positional: bool = True) -> None:
    if positional:
        command.add_argument("input", type=Path, help="an installed modpack folder or a .jar/.zip with embedded mods")
    command.add_argument("--source-version", choices=SOURCE_VERSIONS,
                         help="verified Minecraft version of this pack and its legacy API/mappings")
    command.add_argument("--legacy-api", type=Path, action="append", default=[], metavar="JAR_OR_CLASSES",
                         help="local compiled Forge, Minecraft or library API (repeatable; needs --source-version)")
    command.add_argument("--legacy-srg", type=Path, metavar="JOINED.SRG", help="obfuscated-to-SRG mappings for that legacy release")
    command.add_argument("--legacy-methods", type=Path, metavar="METHODS.CSV", help="optional SRG-to-MCP method names")
    command.add_argument("--legacy-fields", type=Path, metavar="FIELDS.CSV", help="optional SRG-to-MCP field names")


def _mod_selection(command: argparse.ArgumentParser, *, required: bool = False) -> None:
    select = command.add_mutually_exclusive_group(required=required)
    select.add_argument("--mod", metavar="ARCHIVE", help="select an archive already in the pack by its relative path")
    select.add_argument("--mod-jar", type=Path, metavar="JAR", help="select a separate legacy mod JAR to analyze against the pack")


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="mcmodup", description=f"Inspect legacy mods and prepare native modpack updates for Minecraft {MINECRAFT_VERSION} / NeoForge.")
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
    _mod_selection(pack)
    pack.add_argument("--target-api", type=Path, action="append", default=[], metavar="JAR_OR_CLASSES",
                      help="compiled NeoForge 1.21.1 API or Mojang-named Minecraft classes (repeatable)")
    pack.add_argument("--port-map", type=Path, metavar="JSON", help="reviewed cross-version references to verify in the target API")
    pack.add_argument("--json", action="store_true", help="emit full version, dependency and signature evidence")

    refs = commands.add_parser("decompile-deps", help="locally decompile selected dependency classes for one mod")
    _pack_arguments(refs)
    _mod_selection(refs, required=True)
    refs.add_argument("--vineflower", required=True, type=Path, metavar="JAR", help="trusted local Vineflower executable JAR")
    refs.add_argument("--java", default="java", help="Java executable for Vineflower (default: java)")
    refs.add_argument("--class", dest="classes", action="append", default=[], metavar="CLASS",
                      help="limit to this referenced class (repeatable, exact slash or dot name)")
    refs.add_argument("--max-classes", type=int, default=250, help="cap staged classes, including nested classes (default: 250)")
    refs.add_argument("--timeout", type=int, default=180, help="limit decompiler runtime in seconds (default: 180)")
    refs.add_argument("--output", "-o", type=Path, required=True, help="new local reference folder outside the modpack")
    convert = commands.add_parser("convert-jar", help="build a partial NeoForge JAR for a supported legacy profile")
    convert.add_argument("input", type=Path, help="the original legacy mod JAR")
    convert.add_argument("--pack", required=True, type=Path, help="installed modpack folder or a .jar/.zip with embedded mods")
    convert.add_argument("--profile", required=True, choices=tuple(PROFILES), help="which legacy mod adaptation to build")
    convert.add_argument("--allow-partial", action="store_true", help="acknowledge that the result is not the complete original mod")
    convert.add_argument("--output", "-o", type=Path, required=True, help="new .jar path outside the legacy pack")
    convert.add_argument("--timeout", type=int, default=600, help="Gradle build timeout in seconds (default: 600)")
    _pack_arguments(convert, positional=False)

    updater = commands.add_parser("update-pack", help="plan, build or stage standalone 1.21.1 updates")
    ui = commands.add_parser("pack-ui", help="open the native modpack updater with checkboxes")
    for command in (updater, ui):
        command.add_argument("input", type=Path, help="installed pack with the legacy mods under mods/")
        command.add_argument("--target", type=Path, help="installed NeoForge 1.21.1 pack (defaults to input); platform files under bin/")
        command.add_argument("--output", "-o", type=Path, help="new directory outside both packs for the staged mods/ folder")
        command.add_argument("--source-ports", type=Path, metavar="TRUSTED_JSON",
                             help="build selected exact legacy releases and add-ons using this trusted source recipe manifest")
        command.add_argument("--allow-preview-ports", action="store_true",
                             help="allow marked preview recipes with documented parity gaps (still requires GameTests)")
        command.add_argument("--build-timeout", type=int, default=1200, metavar="SECONDS",
                             help="Gradle timeout per source port (default: 1200)")
    updater.add_argument("--mod", action="append", default=[], metavar="ARCHIVE",
                         help="select a legacy archive by its relative path (repeatable); omit to list")
    updater.add_argument("--json", action="store_true", help="show the catalog or plan as JSON")
    ui.add_argument("--host", default="127.0.0.1", help="interface for the local workbench (default: 127.0.0.1)")
    ui.add_argument("--port", type=int, default=8765, help="local HTTP port (default: 8765)")
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
        if args.command in {"update-pack", "pack-ui"}:
            from . import native_pack
            from .source_ports import BuildOptions

            if args.allow_preview_ports and not args.source_ports:
                raise InputError("--allow-preview-ports requires --source-ports.")
            options = BuildOptions(args.source_ports, args.allow_preview_ports, args.build_timeout) if args.source_ports else None
            state = native_pack.inventory(args.input, args.target or args.input, options)
            if args.command == "pack-ui":
                from .pack_ui import serve

                serve(state, args.output, args.host, args.port)
            elif not args.mod:
                if args.output:
                    raise InputError("Select at least one archive with --mod before staging updates.")
                catalog = state.catalog()
                if args.json:
                    print(json.dumps(catalog, indent=2))
                else:
                    print(f"Native updates for Minecraft {MINECRAFT_VERSION} / NeoForge {catalog['neoforge']}")
                    for item in catalog["rows"]:
                        print(f"{item['status']:>10}  {item['id']!r} — {item['reason']}")
                    for issue in catalog["target_issues"]:
                        print(f"Target issue: {issue}")
                    print("Use pack-ui for checkboxes, or --mod ARCHIVE to plan a selection.")
            else:
                update = native_pack.plan(state, args.mod)
                report = update.report
                if report["ready"] and args.output:
                    output = native_pack.stage(update, args.output)
                    report = json.loads((output / native_pack.REPORT_NAME).read_text(encoding="utf-8"))
                    report["output"] = str(output)
                if args.json:
                    print(json.dumps(report, indent=2))
                else:
                    for path, related in report["auto_addons"].items():
                        print(f"Add-on {path!r}: {related['reason']}")
                    if report["build_order"]:
                        print("Build order: " + " → ".join(report["build_order"]))
                    for item in report["files"]:
                        print(f"{item['action']:>10}  {item['file']!r} ← {item['from']!r}")
                    for issue in report["problems"]:
                        print(f"Blocked: {issue}")
                    for name, build in report["native_builds"].items():
                        print(f"Built {name!r}: {build['gametests']['passed']} source-project server GameTests passed; "
                              f"{build['legacy_assets']['unmapped']} legacy asset paths unverified ({build['status']}).")
                    if "output" in report:
                        print(f"Staged standalone mods: {report['output']}. See INSTALL.txt. Neither input pack was modified.")
                    elif report["ready"]:
                        action = "build, test and stage" if update.build_order else "stage"
                        print(f"Plan ready. Give -o NEW_DIR to {action}. Test the staged pack in a fresh world.")
                return int(not report["ready"])
        elif args.command == "convert-jar":
            if not args.allow_partial:
                raise InputError("Full automatic conversion is not supported. These profiles build partial JARs; "
                                 "use --allow-partial only if that is what you want. No JAR was written.")
            profile = PROFILES[args.profile]
            args.source_version = args.source_version or profile.version
            if args.source_version != profile.version:
                raise InputError(f"{profile.name} is only supported for Minecraft {profile.version}.")
            with open_pack(args.pack, _pack_context(args), args.input) as pack:
                assert pack.selected_archive is not None
                result = convert_catalog(pack, pack.select(pack.selected_archive), profile, args.output, args.timeout)
            print(f"Built partial NeoForge {MINECRAFT_VERSION} JAR "
                  f"({result.entries} recovered items, {result.recipes} adapted recipes): {result.output}")
            print(f"Not ported: {result.profile.not_ported}")
        elif args.command in {"scan-pack", "decompile-deps"}:
            with open_pack(args.input, _pack_context(args), args.mod_jar) as pack:
                if args.command == "scan-pack":
                    report = pack.report(args.mod)
                    print(json.dumps(report, indent=2) if args.json else render_pack(report))
                else:
                    selection = args.mod or pack.selected_archive
                    assert selection is not None  # decompile-deps requires either --mod or --mod-jar
                    output = decompile(pack, pack.select(selection), DecompileOptions(
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
