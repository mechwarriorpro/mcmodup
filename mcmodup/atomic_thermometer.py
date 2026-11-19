"""Prepare local thermal machinery previews bound to Voltz Atomic Science 1.2.0."""

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


LEGACY_SHA256 = "80cf5bd9d485377ecde3822154a5db47804fab3e8f78c388cfcf529239655938"
MOD_ID = "atomic_thermometer"
VERSION = "1.2.0-preview.4"
ARCHIVE = f"{MOD_ID}-{VERSION}.jar"
PACKAGE = "mcmodup_kernel.atomic_thermometer"
KERNEL = PACKAGE.replace(".", "/")
OWNER = "atomicscience/process/sensor/TileThermometer"
PLAYER = "net/minecraft/entity/player/EntityPlayer"
VECTOR = "universalelectricity/api/vector/Vector3"
TEXTURES = {
    f"assets/atomicscience/textures/{kind}s/{name}.png": f"assets/{MOD_ID}/textures/{kind}/{name}.png"
    for kind, name in (("block", "thermometer"), ("item", "thermometer"), ("block", "machine"), ("block", "steam"))
}

TEXTURES.update({f"assets/atomicscience/textures/blocks/{old}.png": f"assets/{MOD_ID}/textures/block/{new}.png"
                 for old, new in (("funnel", "steam_funnel"), ("funnel_top", "steam_funnel_top"))})
ACCEL_BLOCKS = {"particle_accelerator": "accelerator", "electromagnet": "electromagnet",
                "electromagnet_glass": "electromagnetGlass", "creative_builder": None}
TEXTURES |= {f"assets/atomicscience/textures/blocks/{old}.png": f"assets/{MOD_ID}/textures/block/{name}.png"
             for name, old in ACCEL_BLOCKS.items() if old}
TEXTURES["assets/atomicscience/textures/items/cellEmpty.png"] = f"assets/{MOD_ID}/textures/item/empty_cell.png"

FEATURES = {
    "Original threshold adjustment and wrapping rules": "thresholdandsettings",
    "Ten-tick thermal samples, tracked coordinates and redstone": "trackedheatandsignal",
    "Measured single-source heat transfer through stone and air": "measuredheatspread",
    "One-cell steam consumption, spin, stored FE and sided transfer": "standalonesteamturbine",
    "Heated water boils and supplies turbine via a steam funnel": "heatedwaterfeedsturbine",
    "Preview funnel recipe, sided gas tank and native persistence": "funnelcraftsidesandsave",
    "Creative ring builder and powered accelerator through saved antimatter output": "builderandaccelerator",
}


def method_group() -> dict:
    access = f"{KERNEL}/Thermometer$Access"
    user, hit = f"{KERNEL}/Thermometer$User", f"{KERNEL}/Thermometer$Hit"
    recv = f"L{access};"
    use = f"(L{PLAYER};IL{VECTOR};)Z"
    return {
        "owner": OWNER, "output": f"{KERNEL}/ThermometerLogic", "types": {OWNER: access, PLAYER: user, VECTOR: hit},
        "methods": {f"{OWNER}#setThreshold(I)V": "setThreshold", f"{OWNER}#isOverThreshold()Z": "isOverThreshold",
                    f"{OWNER}#use{use}": "use", f"{OWNER}#configure{use}": "configure"},
        "members": {},
        "redirects": {
            "getfield": {f"{OWNER}#threshold:I": f"{access}#threshold({recv})I",
                         f"{OWNER}#detectedTemperature:F": f"{access}#sample({recv})F"},
            "putfield": {f"{OWNER}#threshold:I": f"{access}#storeThreshold({recv}I)V"},
            "invokevirtual": {f"{OWNER}#getThershold()I": f"{access}#threshold({recv})I",
                              f"{OWNER}#setThreshold(I)V": f"{access}#setThreshold({recv}I)V",
                              f"{OWNER}#markUpdate()V": f"{access}#refresh({recv})V",
                              f"{PLAYER}#func_70093_af()Z": f"{access}#crouching(L{user};)Z"},
        },
    }


def _json(data: dict) -> str:
    return json.dumps(data, indent=2) + "\n"


def checks(namespace: str) -> str:
    return (TEMPLATES / "atomic_thermometer/ThermometerChecks.java").read_text(encoding="utf-8").replace("@TEST_NAMESPACE@", namespace)


def turbine_checks(package: str, namespace: str) -> str:
    return ((TEMPLATES / "atomic_thermometer/TurbineChecks.java").read_text(encoding="utf-8")
            .replace("@PACKAGE@", package).replace("@NAMESPACE@", namespace))


def steam_checks(package: str, namespace: str) -> str:
    return ((TEMPLATES / "atomic_thermometer/SteamChecks.java").read_text(encoding="utf-8")
            .replace("@PACKAGE@", package).replace("@NAMESPACE@", namespace))


def accelerator_checks(package: str, namespace: str) -> str:
    return ((TEMPLATES / "atomic_thermometer/AcceleratorChecks.java").read_text(encoding="utf-8")
            .replace("@PACKAGE@", package).replace("@NAMESPACE@", namespace))


def _accelerator_resources(assets: str, data: str) -> dict[str, str]:
    files = {}
    for name, image in ACCEL_BLOCKS.items():
        model = f"{MOD_ID}:block/{name}"
        texture = model if image else "minecraft:block/structure_block"
        variants = {"": {"model": model}} if name != "particle_accelerator" else {
            f"facing={side}": {"model": model, "y": rotation} for side, rotation in zip(("north", "east", "south", "west"), (0, 90, 180, 270))}
        files[f"{assets}/blockstates/{name}.json"] = _json({"variants": variants})
        files[f"{assets}/models/block/{name}.json"] = _json({"parent": "minecraft:block/cube_all", "textures": {"all": texture},
            **({"render_type": "minecraft:translucent"} if name == "electromagnet_glass" else {})})
        files[f"{assets}/models/item/{name}.json"] = _json({"parent": model})
        files[f"{data}/loot_table/blocks/{name}.json"] = _json({"type": "minecraft:block", "pools": [{"rolls": 1,
            "conditions": [{"condition": "minecraft:survives_explosion"}], "entries": [{"type": "minecraft:item", "name": f"{MOD_ID}:{name}"}]}]})
    for name, texture in {"empty_cell": f"{MOD_ID}:item/empty_cell", "antimatter_cell": "minecraft:item/dragon_breath"}.items():
        files[f"{assets}/models/item/{name}.json"] = _json({"parent": "minecraft:item/generated", "textures": {"layer0": texture}})
    for name, count, pattern, key in (
        ("particle_accelerator", 1, ["MCM", "IRI", "MCM"], {"C": "minecraft:copper_ingot", "I": "minecraft:iron_ingot", "M": f"{MOD_ID}:electromagnet", "R": "minecraft:respawn_anchor"}),
        ("electromagnet", 4, ["ICI", "CRC", "ICI"], {"I": "minecraft:iron_ingot", "C": "minecraft:copper_ingot", "R": "minecraft:redstone"}),
        ("electromagnet_glass", 4, ["MGM", "GMG", "MGM"], {"G": "minecraft:glass", "M": f"{MOD_ID}:electromagnet"}),
        ("empty_cell", 8, [" G ", "G G", " G "], {"G": "minecraft:glass"}),
    ):
        files[f"{data}/recipe/{name}.json"] = _json({"type": "minecraft:crafting_shaped", "pattern": pattern,
            "key": {letter: {"item": item} for letter, item in key.items()}, "result": {"id": f"{MOD_ID}:{name}", "count": count}})
    return files


def _project(workspace: Path) -> None:
    templates = TEMPLATES / "atomic_thermometer"
    name = "Atomic Science Machines (Preview)"
    description = "Voltz 1.2.0 thermometer, steam funnel and standalone turbine and a limited particle accelerator. Not full Atomic Science."
    prefix, rotor = f"{MOD_ID}:block/thermometer", f"{MOD_ID}:block/steam_turbine"
    funnel = f"{MOD_ID}:block/steam_funnel"
    java, resources = f"src/main/java/{KERNEL}", "src/main/resources"
    assets = f"{resources}/assets/{MOD_ID}"
    data = f"{resources}/data/{MOD_ID}"
    meta = _metadata(ModMetadata("", description=description), MOD_ID, name, VERSION, "Original Atomic Science license applies (local conversion)")
    write_workspace(workspace, {
        "settings.gradle": f"rootProject.name = '{MOD_ID}'\n",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "build.gradle": _build_gradle(MOD_ID, PACKAGE, VERSION) + f"""
tasks.named('compileJava') {{ classpath += files('src/main/resources') }}
tasks.named('jar') {{
    archiveFileName = '{ARCHIVE}'
    exclude '{KERNEL}/ThermometerChecks*.class', '{KERNEL}/TurbineChecks*.class', '{KERNEL}/SteamChecks*.class', '{KERNEL}/AcceleratorChecks*.class', 'data/{MOD_ID}/structure/*.nbt'
}}
neoForge.runs.create('gameTestServer') {{
    type = 'gameTestServer'
    gameDirectory = project.file('run')
    systemProperty 'neoforge.enabledGameTestNamespaces', '{MOD_ID}'
    systemProperty 'mcmodup.gameReport', layout.buildDirectory.file('gametest.xml').get().asFile.absolutePath
}}
""",
        f"{resources}/META-INF/neoforge.mods.toml": meta.replace(f"[{NEOFORGE_VERSION},21.2)", f"[{NEOFORGE_VERSION}]"),
        **{f"{java}/{name}.java": (templates / f"{name}.java").read_bytes()
           for name in ("Thermometer", "ThermometerMod", "HeatGrid", "Turbine", "TurbineClient", "SteamFunnel", "Accelerator")},
        f"{java}/ThermometerChecks.java": checks(MOD_ID),
        f"{java}/TurbineChecks.java": turbine_checks(PACKAGE, MOD_ID),
        f"{java}/SteamChecks.java": steam_checks(PACKAGE, MOD_ID),
        f"{java}/AcceleratorChecks.java": accelerator_checks(PACKAGE, MOD_ID),
        f"{data}/structure/accel_room.nbt": empty_test_structure(15),
        **_accelerator_resources(assets, data),
        f"{resources}/data/{MOD_ID}/structure/empty.nbt": empty_test_structure(5),
        f"{resources}/data/{MOD_ID}/structure/turbine_room.nbt": empty_test_structure(5),
        f"{resources}/data/c/tags/fluid/steam.json": _json({"replace": False, "values": [f"{MOD_ID}:steam"]}),
        f"{resources}/data/minecraft/tags/block/mineable/pickaxe.json": _json({"replace": False, "values": [f"{MOD_ID}:steam_turbine", f"{MOD_ID}:steam_funnel"]}),
        f"{resources}/data/{MOD_ID}/loot_table/blocks/steam_turbine.json": _json({
            "type": "minecraft:block", "pools": [{"rolls": 1, "conditions": [{"condition": "minecraft:survives_explosion"}],
                "entries": [{"type": "minecraft:item", "name": f"{MOD_ID}:steam_turbine"}]}],
        }),
        f"{assets}/lang/en_us.json": _json({f"block.{MOD_ID}.thermometer": "Thermometer", f"itemGroup.{MOD_ID}": name,
            f"info.{MOD_ID}.status": "Threshold: %s K · Reading: %s K", f"info.{MOD_ID}.tracked": "Tracking %s, %s, %s",
            f"info.{MOD_ID}.untracked": "Not tracking a remote position",
            **{f"block.{MOD_ID}.{block}": block.replace("_", " ").title() for block in ACCEL_BLOCKS},
            f"item.{MOD_ID}.empty_cell": "Empty Cell", f"item.{MOD_ID}.antimatter_cell": "Antimatter Cell (125 mg)",
            f"block.{MOD_ID}.steam_turbine": "Steam Turbine", f"fluid.{MOD_ID}.steam": "Steam", f"block.{MOD_ID}.steam_funnel": "Steam Funnel",
            f"info.{MOD_ID}.turbine": "Steam: %s mB · Stored: %s FE · Rotor: %s rad/s"}),
        f"{assets}/blockstates/thermometer.json": _json({"variants": {"": {"model": prefix}}}),
        f"{assets}/models/block/thermometer.json": _json({"parent": "minecraft:block/cube_all", "textures": {"all": prefix}}),
        f"{assets}/models/item/thermometer.json": _json({"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD_ID}:item/thermometer"}}),
        f"{assets}/blockstates/steam_turbine.json": _json({"variants": {"": {"model": rotor}}}),
        f"{assets}/models/block/steam_turbine.json": _json({"parent": "minecraft:block/cube_bottom_top", "textures": {
            "side": f"{MOD_ID}:block/machine", "bottom": "minecraft:block/iron_block", "top": "minecraft:block/cut_copper"}}),
        f"{assets}/models/item/steam_turbine.json": _json({"parent": rotor}),
        f"{assets}/blockstates/steam_funnel.json": _json({"variants": {"": {"model": funnel}}}),
        f"{assets}/models/block/steam_funnel.json": _json({"parent": "minecraft:block/cube_column", "textures": {
            "side": funnel, "end": funnel + "_top"}}),
        f"{assets}/models/item/steam_funnel.json": _json({"parent": funnel}),
        f"{data}/recipe/steam_funnel.json": _json({"type": "minecraft:crafting_shaped", "category": "misc",
            "pattern": [" I ", "I I", "I I"], "key": {"I": {"tag": "c:ingots/iron"}},
            "result": {"id": f"{MOD_ID}:steam_funnel", "count": 2}}),
        f"{data}/loot_table/blocks/steam_funnel.json": _json({"type": "minecraft:block", "pools": [{"rolls": 1,
            "conditions": [{"condition": "minecraft:survives_explosion"}],
            "entries": [{"type": "minecraft:item", "name": f"{MOD_ID}:steam_funnel"}]}]}),
    })


def prepare(output: Path) -> Path:
    output = output.expanduser()
    if output.exists() or output.is_symlink():
        raise InputError("Choose a new directory for the Atomic Science thermometer recipe")
    output.mkdir(parents=True, exist_ok=False)
    try:
        source = output / "atomic-thermometer-src.zip"
        with tempfile.TemporaryDirectory(prefix="mcmodup-atomic-thermometer-") as tmp:
            workspace = Path(tmp) / "project"
            _project(workspace)
            with ZipFile(source, "x") as archive:
                for path in sorted(workspace.rglob("*")):
                    if path.is_file():
                        member = ZipInfo(path.relative_to(workspace).as_posix())
                        member.create_system = 3
                        member.external_attr = (0o100755 if member.filename == "gradlew" else 0o100644) << 16
                        archive.writestr(member, path.read_bytes(), compress_type=ZIP_DEFLATED)
        recipe = {"legacy_sha256": LEGACY_SHA256, "source_zip": source.name, "source_sha256": digest(source),
                  "ids": {MOD_ID: VERSION}, "legacy_ids": {"atomicscience": MOD_ID},
                  "neoforge": f"[{NEOFORGE_VERSION}]", "artifact": f"build/libs/{ARCHIVE}", "tasks": ["jar", "runGameTestServer"],
                  "game_report": "build/gametest.xml", "minimum_tests": len(FEATURES), "features": FEATURES,
                  "status": "preview", "limitations": [
                      "Loaded-chunk server heat grid and water vaporization only. No legacy thermal events, persistent heat, ice or other phase changes.",
                      "Water can produce gas with externally supplied heat. Reactors, multiblock turbine, legacy boil effects and original geometry are absent.",
                      "Adapted iron funnel recipe; original recipe availability is not verified. The native funnel saves its tank, unlike the original reversed persistence callbacks. Thermometer and turbine still lack survival crafting.",
                      "Accelerator uses a bounded planar native beam and adapted recipes. It does not preserve in-flight beams, particle collisions, dark matter or antimatter detonation.",
                      "Creative Builder supports two fixed accelerator ring sizes only; no other Voltz blueprints or legacy UI.",
                      "No ComputerCraft/OpenComputers, Universal Electricity, old saves or legacy add-ons."],
                  "import_assets": {old: f"src/main/resources/{new}" for old, new in TEXTURES.items()},
                  "asset_map": {old: {"path": new} for old, new in TEXTURES.items()}, "method_imports": [method_group()]}
        manifest = output / "atomic-thermometer.json"
        manifest.write_text(_json({"schema_version": 1, "ports": [recipe]}), encoding="utf-8")
        return manifest.resolve()
    except BaseException:
        shutil.rmtree(output)
        raise
