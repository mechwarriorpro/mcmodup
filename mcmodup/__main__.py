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
from .decompile import DecompileOptions, decompile, decompile_mod
from .inputs import InputError, open_input
from .mappings import LegacyNames, PortMap
from .mcp import McpConfig
from .pack import open_pack, render_pack
from .port_plan import exact_minecraft, plan_port, render_plan
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


def _target_arguments(command: argparse.ArgumentParser) -> None:
    command.add_argument("--target-api", type=Path, action="append", default=[], metavar="JAR_OR_CLASSES",
                         help="compiled NeoForge 1.21.1 API or Mojang-named Minecraft classes (repeatable)")
    command.add_argument("--port-map", type=Path, metavar="JSON", help="reviewed cross-version references to verify in the target API")


def _decompiler_arguments(command: argparse.ArgumentParser, purpose: str, *, max_classes: int) -> None:
    command.add_argument("--vineflower", required=True, type=Path, metavar="JAR", help="trusted local Vineflower executable JAR")
    command.add_argument("--java", default="java", help="Java executable for the tools (default: java)")
    command.add_argument("--class", dest="classes", action="append", default=[], metavar="CLASS",
                         help=f"limit to this {purpose} class (repeatable, exact slash or dot name)")
    command.add_argument("--max-classes", type=int, default=max_classes,
                         help=f"cap staged classes, including nested classes (default: {max_classes})")
    command.add_argument("--timeout", type=int, default=180, help="per-tool runtime limit in seconds (default: 180)")
    command.add_argument("--output", "-o", type=Path, required=True, help="new local reference folder outside the modpack")


def _decompile_options(args: argparse.Namespace) -> DecompileOptions:
    return DecompileOptions(tool=args.vineflower, output=args.output, java=args.java, selected_classes=tuple(args.classes),
                            max_classes=args.max_classes, timeout=args.timeout)


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
    _target_arguments(pack)
    pack.add_argument("--json", action="store_true", help="emit full version, dependency and signature evidence")

    refs = commands.add_parser("decompile-deps", help="locally decompile selected dependency classes for one mod")
    _pack_arguments(refs)
    _mod_selection(refs, required=True)
    _decompiler_arguments(refs, "referenced", max_classes=250)

    mapped = commands.add_parser("decompile-mod", help="decompile a selected mod with exact-version MCP names and a native port checklist")
    plan = commands.add_parser("plan-port", help="prioritize native port work using static and optional Connector evidence")
    for command in (mapped, plan):
        command.add_argument("input", type=Path, help="an installed modpack folder, pack archive, or single legacy mod JAR")
        _mod_selection(command)
        command.add_argument("--source-version", choices=SOURCE_VERSIONS,
                             help="verified Minecraft version when the archive does not declare one")
        command.add_argument("--mcp", type=Path, required=command is mapped, metavar="PATH",
                             help="local MCP ZIP, config folder, or catalog with exact Minecraft version entries")
        command.add_argument("--mcp-side", choices=("client", "server"),
                             help="split SRG side and CSV preference (default: client)")
        command.add_argument("--legacy-api", type=Path, action="append", default=[], metavar="JAR_OR_CLASSES",
                             help="compiled legacy signatures from the resolved Minecraft release (repeatable)")
        _target_arguments(command)
        command.add_argument("--connector-report", type=Path, metavar="JSON", help="local raw Legacy Connector CheckJar JSON")
        command.add_argument("--connector-gameplay", type=Path, metavar="MODS_JSON",
                             help="local Connector docs/data/mods.json; import only same-release gameplay observations")
    _decompiler_arguments(mapped, "selected mod", max_classes=2000)
    mapped.add_argument("--specialsource", required=True, type=Path, metavar="JAR", help="trusted local SpecialSource shaded JAR")
    plan.add_argument("--json", action="store_true", help="emit the full evidence and checklist as JSON instead of Markdown")

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
        command.add_argument("input", type=Path, help="installed pack with the legacy archives under mods/ or coremods/")
        command.add_argument("--target", type=Path, help="installed NeoForge 1.21.1 instance that supplies the runtime and local updates (defaults to input)")
        output = command.add_mutually_exclusive_group()
        output.add_argument("--output", "-o", type=Path, help="new directory for the target's complete replacement mods/ folder")
        output.add_argument("--output-pack", type=Path, metavar="NEW_DIR",
                            help="new instance with only the selected updates, required dependencies, and target runtime files")
        command.add_argument("--id-map", type=Path, metavar="JSON", help="reviewed legacy-to-native mod ID map, instead of the input's mcmodup-updates.json")
        command.add_argument("--provider", action="append", default=[], metavar="ID=SHA256",
                             help="pin a reviewed local native provider by its JAR fingerprint (repeatable)")
        command.add_argument("--source-ports", type=Path, action="append", metavar="TRUSTED_JSON",
                             help="build selected exact legacy releases and add-ons from a trusted recipe manifest (repeatable)")
        command.add_argument("--allow-recipe-preview", action="store_true",
                             help="try automatic recipe translation for one original in a fresh --output-pack; not full parity")
        command.add_argument("--allow-preview-ports", action="store_true",
                             help="allow marked preview recipes with documented parity gaps (still requires GameTests)")
        command.add_argument("--build-timeout", type=int, default=1200, metavar="SECONDS",
                             help="Gradle timeout per source port (default: 1200)")
    updater.add_argument("--mod", action="append", default=[], metavar="ARCHIVE",
                         help="select a legacy archive by its relative path (repeatable); omit to list")
    updater.add_argument("--include-native", action="append", default=[], metavar="MOD_ID",
                         help="copy an already installed target mod and its required dependencies into --output-pack (repeatable)")
    updater.add_argument("--json", action="store_true", help="show the catalog or plan as JSON")
    ui.add_argument("--host", default="127.0.0.1", help="interface for the local workbench (default: 127.0.0.1)")
    ui.add_argument("--port", type=int, default=8765, help="local HTTP port (default: 8765)")

    build = commands.add_parser("build-jars", help="compile the built-in native source ports and stage tested JARs")
    build.add_argument("input", type=Path, help="installed pack with the exact legacy JARs under mods/")
    build.add_argument("--target", type=Path, required=True, help="installed NeoForge 1.21.1 pack with the native dependencies")
    build.add_argument("--mod", action="append", required=True, metavar="ARCHIVE", help="select an original archive by relative path (repeatable)")
    build.add_argument("--output", "-o", type=Path, required=True, help="new directory outside both packs for the staged mods/ folder")
    build.add_argument("--allow-preview-ports", action="store_true", help="allow a native build with known parity gaps")
    build.add_argument("--build-timeout", type=int, default=1200, metavar="SECONDS", help="Gradle timeout per project (default: 1200)")
    build.add_argument("--source-zips", type=Path, metavar="DIR", help="read the pinned source ZIPs from this directory instead of fetching them")
    build.add_argument("--keep-recipe", type=Path, metavar="NEW_DIR", help="preserve the generated recipe and its local sources for review")
    build.add_argument("--json", action="store_true", help="emit the staged build report as JSON")

    plate = commands.add_parser("prepare-icbm-plate", help="prepare the local source recipe for the exact Voltz ICBM glass plate preview")
    plate.add_argument("--output", "-o", required=True, type=Path, help="new directory for a reviewable source ZIP and recipe")
    thermometer = commands.add_parser("prepare-atomic-thermometer", help="prepare a local source recipe for the Voltz Atomic Science thermometer and standalone turbine")
    thermometer.add_argument("--output", "-o", required=True, type=Path, help="new directory for a reviewable source ZIP and recipe")
    catalyst = commands.add_parser("prepare-mimicry-catalyst", help="prepare a local source recipe for five Mimicry 1.3.9 crafts")
    catalyst.add_argument("--output", "-o", required=True, type=Path, help="new directory for a reviewable source ZIP and recipe")
    rocks = commands.add_parser("prepare-geostrata-rocks", help="prepare the local GeoStrata v23b decorative rock preview")
    rocks.add_argument("--output", "-o", required=True, type=Path, help="new directory for a reviewable source ZIP and recipe")
    pair = commands.add_parser("prepare-minechem-pair", help="prepare local preview recipes for both exact Minechem releases")
    pair.add_argument("final", type=Path, help="original Minechem 1.6.4 5.0.5 FINAL JAR")
    pair.add_argument("release394", type=Path, help="original Minechem 1.7.10 5.0.5.394 JAR")
    pair.add_argument("--output", "-o", required=True, type=Path, help="new directory for the local source ZIPs and recipe")
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
        if args.command == "prepare-icbm-plate":
            from .icbm_plate import prepare

            manifest = prepare(args.output)
            print(f"Prepared {manifest}. Review the recipe; use update-pack --source-ports with --allow-preview-ports and --output-pack.")
        elif args.command == "prepare-atomic-thermometer":
            from .atomic_thermometer import prepare

            manifest = prepare(args.output)
            print(f"Prepared {manifest}. Local preview only; use update-pack --source-ports with --allow-preview-ports and --output-pack.")
        elif args.command == "prepare-mimicry-catalyst":
            from .mimicry_catalyst import prepare

            manifest = prepare(args.output)
            print(f"Prepared {manifest}. Local preview only; use update-pack --source-ports with --allow-preview-ports and --output-pack.")
        elif args.command == "prepare-geostrata-rocks":
            from .geostrata_rocks import prepare

            manifest = prepare(args.output)
            print(f"Prepared {manifest}. Private-use preview only; use update-pack --source-ports with --allow-preview-ports and --output-pack.")
        elif args.command == "prepare-minechem-pair":
            from .minechem_pair import prepare

            manifest = prepare(args.final, args.release394, args.output)
            print(f"Prepared {manifest}. Local previews only; use update-pack --source-ports with --allow-preview-ports and --output-pack.")
        elif args.command == "build-jars":
            from .builtin_ports import build_jars
            from .native_pack import REPORT_NAME

            output = build_jars(args.input, args.target, args.mod, args.output, allow_preview=args.allow_preview_ports,
                                timeout=args.build_timeout, source_zips=args.source_zips, keep_recipe=args.keep_recipe)
            report = json.loads((output / REPORT_NAME).read_text(encoding="utf-8"))
            if args.json:
                print(json.dumps(report | {"output": str(output)}, indent=2))
            else:
                print(f"Built and staged native preview JARs under {output / 'mods'}:")
                for name, built in report["native_builds"].items():
                    print(f"  {name}: {built['gametests']['passed']} source GameTests passed")
                print(f"Not full parity. See {REPORT_NAME} for coverage and limitations, and INSTALL.txt for installation.")
        elif args.command in {"update-pack", "pack-ui"}:
            from . import native_pack
            from .source_ports import BuildOptions

            if args.allow_preview_ports and not args.source_ports:
                raise InputError("--allow-preview-ports requires --source-ports.")
            options = (BuildOptions(args.source_ports[0], args.allow_preview_ports, args.build_timeout, tuple(args.source_ports[1:]))
                       if args.source_ports else None)
            state = native_pack.inventory(args.input, args.target or args.input, options, id_map=args.id_map,
                                          allow_recipe_preview=args.allow_recipe_preview)
            pins = native_pack.parse_pins(args.provider)
            new_pack = args.output_pack is not None
            destination = args.output_pack or args.output
            if args.command == "pack-ui":
                from .pack_ui import serve

                serve(state, destination, args.host, args.port, output_pack=new_pack, pins=pins)
            elif not (args.mod or args.include_native):
                if destination:
                    raise InputError("Select a legacy archive with --mod or an installed target mod with --include-native.")
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
                update = native_pack.plan(state, args.mod, output_pack=new_pack, pins=pins, include_native=args.include_native)
                report = update.report
                if report["ready"] and destination:
                    output = native_pack.stage(update, destination)
                    report = json.loads((output / native_pack.REPORT_NAME).read_text(encoding="utf-8"))
                    report["output"] = str(output)
                if args.json:
                    print(json.dumps(report, indent=2))
                else:
                    if report["mode"] == "automatic-recipe-preview":
                        print("Recipe preview only: generated from the original archive, without full parity.")
                    for path, related in report["auto_addons"].items():
                        print(f"Add-on {path!r}: {related['reason']}")
                    for conflict in report["legacy_version_conflicts"]:
                        print(f"Legacy version split for {conflict['id']!r}: " + "; ".join(
                            f"{row['consumer']} {row['range']}" for row in conflict["requirements"]) + ". Modern requirements are checked separately.")
                    for dep in report["native_dependencies"]:
                        if dep["status"] == "shared_native_provider":
                            print(f"Shared dependency {dep['id']!r}: {dep['provider']['version']} from {dep['provider']['archive']!r}")
                    if report["build_order"]:
                        print("Build order: " + " → ".join(report["build_order"]))
                    for item in report["files"]:
                        print(f"{item['action']:>10}  {item['file']!r} ← {item['from']!r}")
                        for lib in item["private_libraries"]:
                            print(f"            private {lib['id']} {lib['version']} → {lib['namespace']}")
                    for issue in report["problems"]:
                        print(f"Blocked: {issue}")
                    for name, build in report["native_builds"].items():
                        print(f"Built {name!r}: {build['gametests']['passed']} source-project server GameTests passed; "
                              f"{build['legacy_assets']['unmapped']} legacy asset paths unverified ({build['status']}).")
                    if "output" in report:
                        label = "Created new pack folder" if new_pack else "Staged standalone mods"
                        print(f"{label}: {report['output']}. See INSTALL.txt. Neither input pack was modified.")
                    elif report["ready"]:
                        action = "build, test and stage" if update.build_order else "stage"
                        print(f"Plan ready. Give -o NEW_DIR to {action}, or --output-pack NEW_DIR for a fresh instance. Test in a fresh world.")
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
        elif args.command in {"plan-port", "decompile-mod"}:
            if args.mcp_side and not args.mcp:
                raise InputError("--mcp-side requires --mcp.")
            with open_pack(args.input, ApiContext(profile=args.source_version), args.mod_jar) as pack:
                selection = args.mod or pack.selected_archive
                if not selection and pack.root.is_file() and pack.root.name in pack.mods:
                    selection = pack.root.name
                if not selection:
                    raise InputError("Select an original archive with --mod or --mod-jar. See scan-pack --json.")
                mod = pack.select(selection)
                version = exact_minecraft(pack, mod)
                mcp = McpConfig.load(args.mcp, version, args.mcp_side or "client") if args.mcp else None
                port_map = PortMap.load(args.port_map) if args.port_map else None
                if port_map and port_map.source_version != version:
                    raise InputError(f"--port-map describes Minecraft {port_map.source_version}, not selected Minecraft {version}.")
                legacy = (ApiIndex.load(args.legacy_api) if args.legacy_api else
                          pack.context.legacy if pack.context.profile == version else ApiIndex())
                pack.context = ApiContext(legacy=legacy, target=ApiIndex.load(args.target_api),
                                          names=mcp.names if mcp else LegacyNames(), profile=version, port_map=port_map)
                report = plan_port(pack, mod, version, mcp, args.connector_report, args.connector_gameplay)
                if args.command == "decompile-mod":
                    assert mcp is not None
                    output = decompile_mod(pack, mod, mcp, args.specialsource, _decompile_options(args), report, render_plan(report))
                    print(f"Wrote local MCP {mcp.release} references for Minecraft {version}: {output}")
                    print("Start with PORTING.md and decompile-report.json. No native JAR was created or verified.")
                else:
                    print(json.dumps(report, indent=2) if args.json else render_plan(report), end="\n" if args.json else "")
        elif args.command in {"scan-pack", "decompile-deps"}:
            with open_pack(args.input, _pack_context(args), args.mod_jar) as pack:
                if args.command == "scan-pack":
                    report = pack.report(args.mod)
                    print(json.dumps(report, indent=2) if args.json else render_pack(report))
                else:
                    selection = args.mod or pack.selected_archive
                    assert selection is not None  # decompile-deps requires either --mod or --mod-jar
                    output = decompile(pack, pack.select(selection), _decompile_options(args))
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
