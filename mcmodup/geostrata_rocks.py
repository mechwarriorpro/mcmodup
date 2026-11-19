"""Prepare a local, SHA-bound GeoStrata v23b decorative-rock preview."""

from dataclasses import dataclass
import json
from pathlib import Path
import shutil
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from . import NEOFORGE_VERSION
from .audit import ModMetadata
from .geo_crystals import resources as crystal_resources
from .inputs import InputError
from .port import TEMPLATES, _build_gradle, _metadata, empty_test_structure, write_workspace
from .runtime import file_digest


LEGACY_SHA256 = "afd68aa6ca1222a0f53ee25323c18bd3c2473c346a15345b216c9ef1042a0414"
MOD_ID = "geostrata_rocks"
VERSION = "23b-preview.4"
ARCHIVE = f"{MOD_ID}-{VERSION}.jar"
PACKAGE = "mcmodup.geostratarocks"
RESOURCE = "src/main/resources/"
FEATURES = {
    "All 153 decorative blocks, observed tool tiers and drops": "measuredrockfamilies",
    "Seven measured recipe families, with engraved mirrors": "measuredrockrecipes",
    "Connected wall adjacency masks on all six faces": "measuredconnectedfaces",
    "Registered stone-only veins and biome filters from observed defaults": "orepreservingveins",
    "16 cave crystal colors, drops, lamp recipes and timed auras": "crystalfamiliesandauras",
}


@dataclass(frozen=True)
class Rock:
    name: str
    hardness: float
    resistance: float
    tier: str


# Observed on the original blocks in Forge, not values from a newer source tree.
# See LegacyGeoRockSurvey, run 44453708486 / job 135323545958.
ROCKS = tuple(Rock(*row) for row in (
    ("granite", 10., 18.75, "iron"), ("basalt", 5., 10., "stone"), ("marble", 2.5, 13.75, "stone"),
    ("limestone", 1., 4.5, "wood"), ("shale", 1., 1.5, "wood"), ("sandstone", 2., 3., "wood"),
    ("pumice", 5., 6.25, "wood"), ("slate", 5., 10., "stone"), ("gneiss", 7.5, 10., "iron"),
    ("peridotite", 5., 10., "stone"), ("quartz", 4., 12.5, "stone"), ("granulite", 5., 10., "stone"),
    ("hornfel", 10., 18.75, "iron"), ("migmatite", 5., 10., "stone"), ("schist", 7.5, 10., "stone"),
    ("onyx", 6., 12.5, "iron"), ("opal", 3., 7.5, "stone"),
))
@dataclass(frozen=True)
class Vein:
    min_y: int
    max_y: int  # Original random origin bound is exclusive.
    attempts: int
    condition: str = "any_overworld"


# Exact v23b / matched DragonAPI runtime: 44456516671/135332733142.
# 58 origins at default density, from both observed factors and the direct RNG call count.
VEIN_SIZE = 32
VEINS = {
    "granite": Vein(16, 48, 3), "basalt": Vein(48, 128, 9), "marble": Vein(16, 32, 2),
    "limestone": Vein(48, 128, 9), "shale": Vein(48, 64, 2, "not_desert"), "sandstone": Vein(48, 128, 9),
    "pumice": Vein(0, 16, 1), "slate": Vein(32, 48, 2, "not_desert"), "gneiss": Vein(16, 32, 2),
    "peridotite": Vein(0, 24, 2), "quartz": Vein(0, 64, 3), "granulite": Vein(16, 32, 1),
    "hornfel": Vein(0, 64, 4, "snowy"), "migmatite": Vein(0, 16, 1), "schist": Vein(16, 48, 2),
    "onyx": Vein(0, 24, 5, "adjacent_lava"), "opal": Vein(32, 60, 1),
}


# These shapes are separate modern blocks, not alternate metadata states. A
# connected wall uses its rock's smooth sprite plus the shared overlay sheet.
SHAPES = {"smooth": "", "cobblestone": "_c", "brick": "_b", "round": "_r", "fitted": "_f",
          "tile": "_t", "engraved": "_e", "inscribed": "_i", "connected": ""}
CRAFTING = {
    "brick": (4, ("SS", "SS")),
    "round": (4, ("BB", "BB")),
    "fitted": (2, ("BB",)),
    "tile": (4, (" S ", "S S", " S ")),
    "inscribed": (3, ("B", "S", "B")),
    "connected": (8, ("SSS", "S S", "SSS")),
    "engraved": (2, ("SB", "BS")),  # Minecraft's shaped matcher handles the mirror.
}


def _json(data: dict) -> str:
    return json.dumps(data, indent=2) + "\n"


def _block(rock: Rock, shape: str) -> str:
    return rock.name if shape == "smooth" else f"{rock.name}_{shape}"


def textures() -> dict[str, str]:
    rocks = {f"assets/geostrata/textures/blocks/{rock.name}{suffix}.png": f"assets/{MOD_ID}/textures/block/{_block(rock, shape)}.png"
             for rock in ROCKS for shape, suffix in SHAPES.items() if shape != "connected"}
    return rocks | {f"assets/geostrata/textures/blocks/connected/{i}.png": f"assets/{MOD_ID}/textures/block/connected/{i}.png"
                    for i in range(10)}


def checks(package: str, namespace: str) -> str:
    cases = ",\n".join(f'        new Rock("{rock.name}", {rock.hardness}F, {rock.resistance}F, "{rock.tier}")' for rock in ROCKS)
    return ((TEMPLATES / "geostrata_rocks/GeoRockChecks.java").read_text(encoding="utf-8")
            .replace("@PACKAGE@", package).replace("@NAMESPACE@", namespace).replace("@ROCKS@", cases))


def crystal_checks(package: str, namespace: str) -> str:
    return ((TEMPLATES / "geostrata_rocks/GeoCrystalChecks.java").read_text(encoding="utf-8")
            .replace("@PACKAGE@", package).replace("@NAMESPACE@", namespace))


def _vein_resources(data: str) -> dict[str, str]:
    files: dict[str, str] = {}
    overworld = "#minecraft:is_overworld"
    biome_sets = {
        "any_overworld": overworld,
        "not_desert": {"type": "neoforge:and", "values": [overworld, {"type": "neoforge:not", "value": "#c:is_desert"}]},
        "snowy": {"type": "neoforge:and", "values": [overworld, "#c:is_snowy"]},
    }
    placed_ids: dict[str, list[str]] = {condition: [] for condition in biome_sets}
    for name, vein in VEINS.items():
        feature = f"{MOD_ID}:{name}_vein"
        files[f"{data}/worldgen/configured_feature/{name}_vein.json"] = _json({
            "type": "minecraft:ore", "config": {"size": VEIN_SIZE, "discard_chance_on_air_exposure": 0.0, "targets": [{
                "target": {"predicate_type": "minecraft:block_match", "block": "minecraft:stone"},
                "state": {"Name": f"{MOD_ID}:{name}"},
            }]},
        })
        placement = [
            {"type": "minecraft:count", "count": vein.attempts},
            {"type": "minecraft:in_square"},
            {"type": "minecraft:height_range", "height": {"type": "minecraft:uniform",
                "min_inclusive": {"absolute": vein.min_y}, "max_inclusive": {"absolute": vein.max_y - 1}}},
        ]
        if vein.condition == "adjacent_lava":
            placement.append({"type": "minecraft:block_predicate_filter", "predicate": {
                "type": "minecraft:any_of", "predicates": [
                    {"type": "minecraft:matching_fluids", "fluids": "#minecraft:lava", "offset": [x, y, z]}
                    for x, y, z in ((-1, 0, 0), (1, 0, 0), (0, -1, 0), (0, 1, 0), (0, 0, -1), (0, 0, 1))],
            }})
        placement.append({"type": "minecraft:biome"})
        files[f"{data}/worldgen/placed_feature/{name}_vein.json"] = _json({"feature": feature, "placement": placement})
        placed_ids[vein.condition if vein.condition in biome_sets else "any_overworld"].append(feature)
    for condition, biomes in biome_sets.items():
        # Run after vanilla ore placement; the configured target also excludes ore, deepslate and all other blocks.
        files[f"{data}/neoforge/biome_modifier/rocks_{condition}.json"] = _json({
            "type": "neoforge:add_features", "biomes": biomes, "features": placed_ids[condition], "step": "underground_decoration",
        })
    return files


def _border_cube(offset: float, texture: str, *, border: bool = True) -> dict:
    # In-plane orientations of the legacy border. Top/down use XZ;
    # east/west use YZ; north/south use YX. The base is a regular cube.
    transforms = {"up": ([16, 0, 0, 16], 0), "down": ([16, 16, 0, 0], 0),
                  "east": ([0, 0, 16, 16], 90), "west": ([0, 16, 16, 0], 90),
                  "north": ([0, 0, 16, 16], 90), "south": ([0, 16, 16, 0], 90)}
    faces = {face: {"texture": texture, "cullface": face,
                    **({"uv": uv, "rotation": rotation} if border else {})}
             for face, (uv, rotation) in transforms.items()}
    return {"from": [-offset] * 3, "to": [16 + offset] * 3, "faces": faces}


def _connected_resources(assets: str) -> dict[str, str]:
    return {f"{assets}/models/block/connected/{index}.json": _json({
            "render_type": "minecraft:translucent",
            "textures": {"particle": f"{MOD_ID}:block/connected/{index}", "edge": f"{MOD_ID}:block/connected/{index}"},
            "elements": [_border_cube(0.016, "#edge")],
        }) for index in range(1, 10)}


def _project(workspace: Path) -> None:
    resources = RESOURCE
    assets = f"{resources}assets/{MOD_ID}"
    data = f"{resources}data/{MOD_ID}"
    java_package = PACKAGE.replace(".", "/")
    java = f"src/main/java/{java_package}"
    meta = _metadata(ModMetadata("", authors="Reika Kalseki (original GeoStrata)",
                                 description="17 GeoStrata rock families, cave and potion crystals, colorful lamps and native generation; no DragonAPI implementation."),
                     MOD_ID, "GeoStrata Rocks (Preview)", VERSION, "GeoStrata original assets: private use only without permission")
    files = {
        "settings.gradle": f"rootProject.name = '{MOD_ID}'\n",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "build.gradle": _build_gradle(MOD_ID, PACKAGE, VERSION) + f"""
tasks.named('jar') {{
    archiveFileName = '{ARCHIVE}'
    exclude '{java_package}/*Checks*.class', 'data/{MOD_ID}/structure/*.nbt'
}}
neoForge.runs.create('gameTestServer') {{
    type = 'gameTestServer'
    gameDirectory = project.file('run')
    systemProperty 'neoforge.enabledGameTestNamespaces', '{MOD_ID}'
    systemProperty 'mcmodup.gameReport', layout.buildDirectory.file('gametest.xml').get().asFile.absolutePath
}}
""",
        f"{resources}META-INF/neoforge.mods.toml": meta.replace(f"[{NEOFORGE_VERSION},21.2)", f"[{NEOFORGE_VERSION}]"),
        f"{resources}META-INF/MCModUp-NOTICE.txt": (
            "PRIVATE-USE PREVIEW — not GeoStrata or DragonAPI. Not compatible with old worlds or dependent mods.\n"
            "Original textures by Reika Kalseki, https://github.com/ReikaKalseki/GeoStrata.\n"
            "Do not redistribute GeoStrata assets or derivative builds without the owner's permission.\n"
            "No original Java code is bundled. Tested against the default v23b Forge rock survey.\n"),
        f"{data}/structure/rock_room.nbt": empty_test_structure(3),
        f"{data}/structure/vein_room.nbt": empty_test_structure(19),
        f"{data}/structure/crystal_room.nbt": empty_test_structure(9),
        f"{java}/GeoCrystalChecks.java": crystal_checks(PACKAGE, MOD_ID),
        **_vein_resources(data),
        **_connected_resources(assets),
        f"{java}/GeoRockChecks.java": checks(PACKAGE, MOD_ID),
        **{f"{java}/{name}.java": (TEMPLATES / f"geostrata_rocks/{name}.java").read_text(encoding="utf-8")
           for name in ("ConnectedRock", "GeoConnectedClient", "GeoCrystals", "GeoCrystal")},
    }
    crystals, locales = crystal_resources(assets, data, MOD_ID)
    files.update(crystals)
    locales[f"itemGroup.{MOD_ID}.rocks"] = "GeoStrata Rocks & Crystals (Preview)"
    mining, stone, iron, registrations = [], [], [], []
    for rock in ROCKS:
        raw = f"{MOD_ID}:{rock.name}"
        brick = f"{MOD_ID}:{_block(rock, 'brick')}"
        for shape in SHAPES:
            name = _block(rock, shape)
            model, identifier = f"{MOD_ID}:block/{name}", f"{MOD_ID}:{name}"
            locales[f"block.{MOD_ID}.{name}"] = rock.name.title() + ("" if shape == "smooth" else f" {shape.title()}")
            registrations.append(f'        register("{name}", "{rock.name}", {rock.hardness}F, {rock.resistance}F, {str(shape == "connected").lower()});')
            mining.append(identifier)
            if rock.tier == "stone":
                stone.append(identifier)
            elif rock.tier == "iron":
                iron.append(identifier)
            files[f"{assets}/blockstates/{name}.json"] = _json({"variants": {"": {"model": model}}})
            texture = f"{MOD_ID}:block/{rock.name}" if shape == "connected" else model
            files[f"{assets}/models/block/{name}.json"] = _json({"parent": "minecraft:block/cube_all", "textures": {"all": texture}})
            item_model = {"parent": model}
            if shape == "connected":
                item_model = {"parent": "minecraft:block/block", "render_type": "minecraft:translucent",
                    "textures": {"particle": texture, "base": texture, "edge": f"{MOD_ID}:block/connected/0"},
                    "elements": [_border_cube(0.0, "#base", border=False), _border_cube(0.016, "#edge")]}
            files[f"{assets}/models/item/{name}.json"] = _json(item_model)
            ordinary_drop = f"{MOD_ID}:{_block(rock, 'cobblestone')}" if shape == "smooth" else identifier
            item = {"type": "minecraft:item", "name": ordinary_drop, "conditions": [{"condition": "minecraft:survives_explosion"}]}
            entry = item
            if shape == "smooth":
                silk = {"type": "minecraft:item", "name": identifier, "conditions": [{
                    "condition": "minecraft:match_tool", "predicate": {"predicates": {"minecraft:enchantments": [
                        {"enchantments": "minecraft:silk_touch", "levels": {"min": 1}}]}}}]}
                entry = {"type": "minecraft:alternatives", "children": [silk, item]}
            files[f"{data}/loot_table/blocks/{name}.json"] = _json({"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [entry]}]})
            if shape in CRAFTING:
                count, pattern = CRAFTING[shape]
                keys = {key: {"item": material} for key, material in (("S", raw), ("B", brick)) if any(key in row for row in pattern)}
                files[f"{data}/recipe/{name}.json"] = _json({"type": "minecraft:crafting_shaped", "category": "building",
                                                             "pattern": pattern, "key": keys, "result": {"id": identifier, "count": count}})
        files[f"{data}/recipe/{rock.name}_from_smelting.json"] = _json({
            "type": "minecraft:smelting", "category": "blocks", "ingredient": {"item": f"{MOD_ID}:{_block(rock, 'cobblestone')}"},
            "result": {"id": raw}, "experience": 0.2, "cookingtime": 200,
        })
    for tag, values in (("mineable/pickaxe", mining), ("needs_stone_tool", stone), ("needs_iron_tool", iron)):
        files[f"{resources}data/minecraft/tags/block/{tag}.json"] = _json({"replace": False, "values": values})
    files[f"{assets}/lang/en_us.json"] = _json(locales)
    files[f"{java}/GeoRocks.java"] = ((TEMPLATES / "geostrata_rocks/GeoRocks.java").read_text(encoding="utf-8")
                                     .replace("@REGISTRATIONS@", "\n".join(registrations)))
    write_workspace(workspace, files)


def prepare(output: Path) -> Path:
    output = output.expanduser()
    if output.exists() or output.is_symlink():
        raise InputError("Choose a new directory for the GeoStrata rock recipe")
    output.mkdir(parents=True, exist_ok=False)
    try:
        source = output / "geostrata-rocks-src.zip"
        with tempfile.TemporaryDirectory(prefix="mcmodup-geostrata-rocks-") as scratch:
            workspace = Path(scratch) / "project"
            _project(workspace)
            with ZipFile(source, "x") as archive:
                for path in sorted(workspace.rglob("*")):
                    if path.is_file():
                        name = path.relative_to(workspace).as_posix()
                        member = ZipInfo(name)
                        member.create_system = 3
                        member.external_attr = (0o100755 if name == "gradlew" else 0o100644) << 16
                        archive.writestr(member, path.read_bytes(), compress_type=ZIP_DEFLATED)
        imports = textures()
        recipe = {"legacy_sha256": LEGACY_SHA256, "source_zip": source.name, "source_sha256": file_digest(source),
                  "ids": {MOD_ID: VERSION}, "legacy_ids": {"geostrata": MOD_ID}, "neoforge": f"[{NEOFORGE_VERSION}]",
                  "artifact": f"build/libs/{ARCHIVE}", "tasks": ["jar", "runGameTestServer"], "game_report": "build/gametest.xml",
                  "minimum_tests": len(FEATURES), "features": FEATURES, "status": "preview", "standalone_preview": True,
                  "limitations": ["Native ore placement in Overworld biomes, not the old RNG or exact chunk output. No retro-generation or custom dimension and density settings.",
                                  "Native crystal families use new models, timed effects and adapted cave generation. Nether effects are partial; no crystal brewing, pendants, blooms, clusters or guardian mechanics.",
                                  "Only plain stone is replaced. Original mining timing, opal tint, DragonAPI integrations and old saves are missing. Connected wall masks are measured; lighting and pixel parity are not certified."],
                  "import_assets": {old: RESOURCE + new for old, new in imports.items()},
                  "asset_map": {old: {"path": new} for old, new in imports.items()}}
        manifest = output / "geostrata-rocks.json"
        manifest.write_text(_json({"schema_version": 1, "ports": [recipe]}), encoding="utf-8")
        return manifest.resolve()
    except BaseException:
        shutil.rmtree(output)
        raise
