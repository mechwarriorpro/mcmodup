"""Prepare the Voltz glass pressure plate and passive platform preview from exact ICBM."""

import json
from pathlib import Path
import shutil
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from . import NEOFORGE_VERSION
from .audit import ModMetadata
from .inputs import InputError
from .port import TEMPLATES, _build_gradle, _metadata, empty_test_structure, write_workspace
from .source_ports import digest


LEGACY_SHA256 = "34a0efec6a1d078d2d0f1b7681c1040d4d68cb096330f4db79bf569a00f75130"
MOD_ID = "icbm_glass_plate"
VERSION = "1.4.1.344-preview.5"
ARCHIVE = f"{MOD_ID}-{VERSION}.jar"
PACKAGE = "mcmodup.icbmplate"
KERNEL = f"mcmodup_kernel/{MOD_ID}"
OWNER = "icbm/contraption/block/BlockGlassPressurePlate"
WORLD = "net/minecraft/world/World"
TEXTURE = "assets/icbm/textures/blocks/glassPressurePlate.png"
PLATFORM = "icbm/sentry/platform/TileTurretPlatform"
RECEIVER = "icbm/sentry/turret/block/TileTurret"
CONSUMER = "icbm/sentry/turret/Turret"
STORAGE = "universalelectricity/api/energy/EnergyStorageHandler"
SIDE = "net/minecraftforge/common/ForgeDirection"
PLATFORM_TEXTURES = {
    f"assets/icbm/textures/blocks/turretPlatform_{side}.png": f"assets/{MOD_ID}/textures/block/sentry_platform_{side}.png"
    for side in ("top", "side", "bottom")
}
BUILDING = (
    ("concrete", "concrete", ("SGS", "GWG", "SGS"), {"S": "minecraft:sand", "G": "minecraft:gravel", "W": "minecraft:water_bucket"}),
    ("compact_concrete", "concreteCompact", ("COC", "OCO", "COC"), {"C": f"{MOD_ID}:concrete", "O": "minecraft:obsidian"}),
    ("reinforced_concrete", "concreteReinforced", ("COC", "OCO", "COC"), {"C": f"{MOD_ID}:compact_concrete", "O": "minecraft:iron_ingot"}),
    ("reinforced_glass", "glassReinforced", ("IGI", "GIG", "IGI"), {"I": "minecraft:iron_ingot", "G": "minecraft:glass"}),
)
TEXTURES = {TEXTURE: f"assets/{MOD_ID}/textures/block/glass_pressure_plate.png", **PLATFORM_TEXTURES,
            **{f"assets/icbm/textures/blocks/{old}.png": f"assets/{MOD_ID}/textures/block/{name}.png" for name, old, _, _ in BUILDING}}
LAUNCH_BLOCKS = {"launcher_base": "iron_block", "launcher_frame": "iron_bars", "launcher_screen": "observer_front", "anti_air_turret": "dispenser_front"}
LAUNCH_ITEMS = {"training_missile": f"{MOD_ID}:item/training_missile", "condensed_missile": "minecraft:item/firework_rocket",
                "target_designator": "minecraft:item/compass"}
TEXTURES["assets/icbm/textures/items/missileModule.png"] = f"assets/{MOD_ID}/textures/item/training_missile.png"

FEATURES = {
    "Glass plate recipe and break behavior": "craftinganddrop",
    "Original pressure-plate redstone updates and release timing": "contactandrelease",
    "Passive platform forwarding, direction order, simulation and no extraction": "passivesentrytransfer",
    "Platform GUI storage, sided extraction and save/load": "storedsentryinventory",
    "Four building blocks with exact recipes, drops and physical properties": "measuredbuildingblocks",
    "Saved tier-one launcher, practice missile flight and active Sentry interception": "savedlauncherandsentry",
}


def method_group() -> dict:
    plate, world = f"{KERNEL}/GlassPlate$Access", f"{KERNEL}/GlassPlate$WorldAccess"
    old_world, new_world = f"L{WORLD};", f"L{world};"
    return {"owner": OWNER, "output": f"{KERNEL}/PlateLogic", "types": {OWNER: plate, WORLD: world},
            "methods": {f"{OWNER}#func_72193_l({old_world}IIII)V": "update", f"{OWNER}#func_71859_p_({old_world})I": "delay"},
            "members": {
                f"{OWNER}#func_94351_d({old_world}III)I": f"{plate}#sense({new_world}III)I",
                f"{OWNER}#func_94355_d(I)I": f"{plate}#toMetadata(I)I",
                f"{OWNER}#func_94354_b_({old_world}III)V": f"{plate}#notifyNeighbors({new_world}III)V",
                f"{OWNER}#func_71859_p_({old_world})I": f"{plate}#checkDelay({new_world})I",
                f"{WORLD}#func_72921_c(IIIII)Z": f"{world}#setMetadata(IIIII)Z",
                f"{WORLD}#func_72909_d(IIIIII)V": f"{world}#markRenderRange(IIIIII)V",
                f"{WORLD}#func_72836_a(IIIII)V": f"{world}#schedule(IIIII)V",
            },
            "redirects": {"getfield": {f"{OWNER}#field_71990_ca:I": f"{plate}#registryId(L{plate};)I"}}}


def platform_methods() -> dict:
    bridge = f"{KERNEL}/SentryBridge"
    access, receiver, consumer, power, side = (f"{bridge}${name}" for name in ("Access", "Receiver", "Consumer", "Power", "Side"))
    transfer = f"(L{SIDE};JZ)J"
    return {"owner": PLATFORM, "output": f"{KERNEL}/SentryPlatformLogic",
            "types": {PLATFORM: access, RECEIVER: receiver, CONSUMER: consumer, STORAGE: power, SIDE: side},
            "methods": {f"{PLATFORM}#onReceiveEnergy{transfer}": "forward", f"{PLATFORM}#onExtractEnergy{transfer}": "extract"},
            "members": {}, "redirects": {
                "getfield": {f"{PLATFORM}#turrets:[L{RECEIVER};": f"{access}#receivers(L{access};)[L{receiver};",
                             f"{CONSUMER}#energy:L{STORAGE};": f"{consumer}#storage(L{consumer};)L{power};"},
                "invokevirtual": {f"{RECEIVER}#getTurret()L{CONSUMER};": f"{receiver}#unwrap(L{receiver};)L{consumer};",
                                  f"{STORAGE}#receiveEnergy(JZ)J": f"{power}#receive(L{power};JZ)J"},
            }}


def _json(data: dict) -> str:
    return json.dumps(data, indent=2) + "\n"


def checks(namespace: str) -> str:
    return (TEMPLATES / "icbm_plate/PlateChecks.java").read_text(encoding="utf-8").replace("@TEST_NAMESPACE@", namespace)


def platform_checks(package: str, namespace: str) -> str:
    return ((TEMPLATES / "icbm_plate/PlatformChecks.java").read_text(encoding="utf-8")
            .replace("@PACKAGE@", package).replace("@TEST_NAMESPACE@", namespace))


def building_checks(package: str, namespace: str) -> str:
    return ((TEMPLATES / "icbm_plate/BuildingChecks.java").read_text(encoding="utf-8")
            .replace("@PACKAGE@", package).replace("@TEST_NAMESPACE@", namespace))


def _building_resources(assets: str, data: str) -> dict[str, str]:
    files = {}
    for name, _, pattern, keys in BUILDING:
        model, item = f"{MOD_ID}:block/{name}", f"{MOD_ID}:{name}"
        files[f"{assets}/blockstates/{name}.json"] = _json({"variants": {"": {"model": model}}})
        cube = {"parent": "minecraft:block/cube_all", "textures": {"all": model}}
        if name == "reinforced_glass":
            cube["render_type"] = "minecraft:translucent"
        files[f"{assets}/models/block/{name}.json"] = _json(cube)
        files[f"{assets}/models/item/{name}.json"] = _json({"parent": model})
        files[f"{data}/recipe/{name}.json"] = _json({"type": "minecraft:crafting_shaped", "category": "building",
            "pattern": pattern, "key": {symbol: {"item": material} for symbol, material in keys.items()}, "result": {"id": item, "count": 8}})
        files[f"{data}/loot_table/blocks/{name}.json"] = _json({"type": "minecraft:block", "pools": [{"rolls": 1,
            "conditions": [{"condition": "minecraft:survives_explosion"}], "entries": [{"type": "minecraft:item", "name": item}]}]})
    return files


def launch_checks(package: str, namespace: str) -> str:
    return ((TEMPLATES / "icbm_plate/LaunchChecks.java").read_text(encoding="utf-8")
            .replace("@PACKAGE@", package).replace("@TEST_NAMESPACE@", namespace))


def _launch_resources(assets: str, data: str) -> dict[str, str]:
    files = {}
    for name, texture in LAUNCH_BLOCKS.items():
        model = f"{MOD_ID}:block/{name}"
        files[f"{assets}/blockstates/{name}.json"] = _json({"variants": {"": {"model": model}}})
        files[f"{assets}/models/block/{name}.json"] = _json({"parent": "minecraft:block/cube_all", "textures": {"all": f"minecraft:block/{texture}"},
            **({"render_type": "minecraft:cutout"} if name == "launcher_frame" else {})})
        files[f"{assets}/models/item/{name}.json"] = _json({"parent": model})
        files[f"{data}/loot_table/blocks/{name}.json"] = _json({"type": "minecraft:block", "pools": [{"rolls": 1,
            "conditions": [{"condition": "minecraft:survives_explosion"}], "entries": [{"type": "minecraft:item", "name": f"{MOD_ID}:{name}"}]}]})
    for name, texture in LAUNCH_ITEMS.items():
        files[f"{assets}/models/item/{name}.json"] = _json({"parent": "minecraft:item/generated", "textures": {"layer0": texture}})
    for name, pattern, materials in (
        ("launcher_base", ["IRI", "CCC", "IRI"], {"I": "minecraft:iron_ingot", "R": "minecraft:redstone", "C": f"{MOD_ID}:reinforced_concrete"}),
        ("launcher_screen", ["IGI", "CRC", "III"], {"I": "minecraft:iron_ingot", "G": "minecraft:glass_pane", "C": "minecraft:copper_ingot", "R": "minecraft:redstone"}),
        ("launcher_frame", ["I I", "IBI", "I I"], {"I": "minecraft:iron_ingot", "B": "minecraft:iron_bars"}),
        ("anti_air_turret", [" I ", "RDR", "CCC"], {"I": "minecraft:iron_ingot", "D": "minecraft:dispenser", "C": "minecraft:copper_ingot", "R": "minecraft:redstone"}),
        ("training_missile", [" P ", " I ", " R "], {"P": "minecraft:paper", "I": "minecraft:iron_ingot", "R": "minecraft:redstone"}),
        ("condensed_missile", [" M ", " T "], {"M": f"{MOD_ID}:training_missile", "T": "minecraft:tnt"}),
        ("target_designator", [" C ", " R "], {"C": "minecraft:compass", "R": "minecraft:redstone"}),
    ):
        files[f"{data}/recipe/{name}.json"] = _json({"type": "minecraft:crafting_shaped", "pattern": pattern,
            "key": {symbol: {"item": item} for symbol, item in materials.items()}, "result": {"id": f"{MOD_ID}:{name}"}})
    return files


def _project(workspace: Path) -> None:
    item, platform = "glass_pressure_plate", "sentry_platform"
    assets, java = f"src/main/resources/assets/{MOD_ID}", "src/main/java"
    prefix = f"{MOD_ID}:block/{item}"
    # Retain the same render geometry across powered states, using the original
    # texture and the native pressure-plate model parents.
    models = {f"{assets}/models/block/{name}.json": _json({"parent": f"minecraft:block/{parent}", "render_type": "minecraft:translucent",
                                                        "textures": {"texture": prefix}})
              for name, parent in ((item, "pressure_plate_up"), (item + "_down", "pressure_plate_down"))}
    title = "ICBM Utilities and Building Blocks (Preview)"
    desc = "Building blocks, glass plate, native first-tier missile launcher and active anti-air Sentry preview. Not a replacement for the exact ICBM add-ons."
    meta = _metadata(ModMetadata("", description=desc), MOD_ID, title, VERSION, "Original ICBM license applies (local conversion)")
    write_workspace(workspace, {
        "settings.gradle": f"rootProject.name = '{MOD_ID}'\n",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "build.gradle": _build_gradle(MOD_ID, PACKAGE, VERSION) + f"""
// The updater writes only the checked method-import class here. No legacy JAR is
// on the compiler or runtime classpath.
tasks.named('compileJava') {{ classpath += files('src/main/resources') }}
tasks.named('jar') {{
    archiveFileName = '{ARCHIVE}'
    exclude 'mcmodup/icbmplate/*Checks*.class', 'data/{MOD_ID}/structure/*.nbt'
}}
neoForge.runs.create('gameTestServer') {{
    type = 'gameTestServer'
    gameDirectory = project.file('run')
    systemProperty 'neoforge.enabledGameTestNamespaces', '{MOD_ID}'
    systemProperty 'mcmodup.gameReport', layout.buildDirectory.file('gametest.xml').get().asFile.absolutePath
}}
""",
        "src/main/resources/META-INF/neoforge.mods.toml": meta.replace(f"[{NEOFORGE_VERSION},21.2)", f"[{NEOFORGE_VERSION}]"),
        **{f"{java}/{KERNEL}/{name}.java": (TEMPLATES / f"icbm_plate/{name}.java").read_bytes()
           for name in ("GlassPlate", "SentryBridge", "SentryMenu", "SentryPlatform", "SentryScreen", "LaunchSystem", "LaunchClient")},
        **{f"{java}/mcmodup/icbmplate/{name}.java": (TEMPLATES / f"icbm_plate/{name}.java").read_bytes()
           for name in ("GlassPlateMod", "BuildingBlocks")},
        f"{java}/mcmodup/icbmplate/LaunchChecks.java": launch_checks(PACKAGE, MOD_ID),
        f"{java}/mcmodup/icbmplate/BuildingChecks.java": building_checks(PACKAGE, MOD_ID),
        f"{java}/mcmodup/icbmplate/PlateChecks.java": checks(MOD_ID),
        f"{java}/mcmodup/icbmplate/PlatformChecks.java": platform_checks(PACKAGE, MOD_ID),
        f"src/main/resources/data/{MOD_ID}/structure/empty.nbt": empty_test_structure(4),
        f"src/main/resources/data/{MOD_ID}/structure/platform_room.nbt": empty_test_structure(5),
        f"src/main/resources/data/{MOD_ID}/structure/launch_room.nbt": empty_test_structure(17),
        f"src/main/resources/data/{MOD_ID}/loot_table/blocks/{platform}.json": _json({"type": "minecraft:block", "pools": [
            {"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD_ID}:{platform}",
                "conditions": [{"condition": "minecraft:survives_explosion"}]}]}],
        }),
        "src/main/resources/data/minecraft/tags/block/mineable/pickaxe.json": _json({"replace": False, "values": [f"{MOD_ID}:{platform}", *(f"{MOD_ID}:{name}" for name, _, _, _ in BUILDING if name != "reinforced_glass")]}),
        f"src/main/resources/data/{MOD_ID}/recipe/{item}.json": _json({"type": "minecraft:crafting_shaped", "category": "redstone",
                "pattern": ["##"], "key": {"#": {"item": "minecraft:glass"}}, "result": {"id": f"{MOD_ID}:{item}", "count": 1}}),
        f"{assets}/lang/en_us.json": _json({f"block.{MOD_ID}.{item}": "Glass Pressure Plate",
                                             f"block.{MOD_ID}.{platform}": "Sentry Platform",
                                             **{f"block.{MOD_ID}.{name}": name.replace("_", " ").title() for name in LAUNCH_BLOCKS},
                                             **{f"item.{MOD_ID}.{name}": name.replace("_", " ").title() for name in LAUNCH_ITEMS},
                                             **{f"block.{MOD_ID}.{name}": name.replace("_", " ").title() for name, _, _, _ in BUILDING}}),
        f"{assets}/blockstates/{item}.json": _json({"variants": {"powered=false": {"model": prefix},
                                                              "powered=true": {"model": prefix + "_down"}}}),
        f"{assets}/models/item/{item}.json": _json({"parent": prefix}),
        f"{assets}/blockstates/{platform}.json": _json({"variants": {"": {"model": f"{MOD_ID}:block/{platform}"}}}),
        f"{assets}/models/block/{platform}.json": _json({"parent": "minecraft:block/cube_bottom_top", "textures": {
            side: f"{MOD_ID}:block/{platform}_{side}" for side in ("top", "side", "bottom")}}),
        f"{assets}/models/item/{platform}.json": _json({"parent": f"{MOD_ID}:block/{platform}"}),
        **models,
        **_building_resources(assets, f"src/main/resources/data/{MOD_ID}"),
        **_launch_resources(assets, f"src/main/resources/data/{MOD_ID}"),
    })


def prepare(output: Path) -> Path:
    """Generate a reviewable, source-only archive and its fingerprinted recipe."""
    output = output.expanduser()
    if output.exists() or output.is_symlink():
        raise InputError("Choose a new directory for the ICBM glass pressure plate recipe")
    output.mkdir(parents=True, exist_ok=False)
    try:
        source = output / "icbm-plate-src.zip"
        with tempfile.TemporaryDirectory(prefix="mcmodup-icbm-plate-") as tmp:
            workspace = Path(tmp) / "project"
            _project(workspace)
            with ZipFile(source, "x") as archive:
                for path in sorted(workspace.rglob("*")):
                    if path.is_file():
                        member = ZipInfo(path.relative_to(workspace).as_posix())
                        member.create_system = 3  # normalized Unix file modes, even on Windows
                        member.external_attr = (0o100755 if member.filename == "gradlew" else 0o100644) << 16
                        archive.writestr(member, path.read_bytes(), compress_type=ZIP_DEFLATED)
        recipe = {"legacy_sha256": LEGACY_SHA256, "source_zip": source.name, "source_sha256": digest(source),
                  "ids": {MOD_ID: VERSION}, "legacy_ids": {"icbm": MOD_ID, "icbm|explosion": None, "icbm|sentry": None},
                  "neoforge": f"[{NEOFORGE_VERSION}]", "artifact": f"build/libs/{ARCHIVE}", "tasks": ["jar", "runGameTestServer"],
                  "game_report": "build/gametest.xml", "minimum_tests": len(FEATURES), "features": FEATURES,
                  "status": "preview", "limitations": [
                      "First-tier launcher and anti-air Sentry are native adaptations; only practice and small condensed missile payloads. No other missile tiers, assemblers, station interfaces, payloads, or other turret types.",
                      "Direct target designator, adjacent frame required, bounded loaded-chunk flight and new recipes/models differ from the original GUI, spread and forced chunks. No original missile physics parity.",
                      "The creative-only platform forwards FE only to native receivers advertising the dedicated Sentry capability. The legacy crafting prerequisites and receivers are not included.",
                      "Separate registry IDs for fresh worlds. Old saves and add-ons expecting ICBM are not supported.",
                      "No graphical client comparison; modern material, collision and redstone APIs are used at the boundary."],
                  "import_assets": {old: f"src/main/resources/{new}" for old, new in TEXTURES.items()},
                  "asset_map": {old: {"path": new} for old, new in TEXTURES.items()},
                  "method_imports": [method_group(), platform_methods()]}
        manifest = output / "icbm-plate.json"
        manifest.write_text(_json({"schema_version": 1, "ports": [recipe]}), encoding="utf-8")
        return manifest.resolve()
    except BaseException:
        shutil.rmtree(output)
        raise
