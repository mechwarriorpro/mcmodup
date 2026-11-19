"""A test-only dimension that lets the staged JAR decorate real stone chunks."""

import json
from pathlib import Path


FIXTURES = Path(__file__).with_name("fixtures")


def fixtures(package: str, namespace: str) -> dict[str, str]:
    # NeoForge 1.21.1 GameTestServer uses the flat world preset. Leave its test rooms in
    # the normal flat Overworld and add a distinct, fully decorated stone dimension.
    preset = json.loads((FIXTURES / "ThaumcraftFlatPreset.json").read_text(encoding="utf-8"))
    biome = f"{namespace}:uranium_stone"
    preset["dimensions"][biome] = {"type": "minecraft:overworld", "generator": {
        "type": "minecraft:flat", "settings": {"biome": biome, "features": True, "lakes": False, "structure_overrides": [],
                                                "layers": [{"block": "minecraft:stone", "height": 121}]}}}
    stone_biome = {"has_precipitation": False, "temperature": .8, "downfall": 0.,
                   "effects": {"fog_color": 12638463, "water_color": 4159204, "water_fog_color": 329011, "sky_color": 7907327},
                   "spawners": {}, "spawn_costs": {}, "carvers": {}, "features": []}
    source = (FIXTURES / "MinechemTerrainSmoke.java").read_text(encoding="utf-8")
    data = "src/main/resources/data"
    return {
        f"src/main/java/{package.replace('.', '/')}/MinechemTerrainSmoke.java": source.replace("@PACKAGE@", package).replace("@TEST_NAMESPACE@", namespace),
        f"{data}/minecraft/worldgen/world_preset/flat.json": json.dumps(preset),
        f"{data}/{namespace}/worldgen/biome/uranium_stone.json": json.dumps(stone_biome),
        f"{data}/minecraft/tags/worldgen/biome/is_overworld.json": json.dumps({"replace": False, "values": [biome]}),
    }
