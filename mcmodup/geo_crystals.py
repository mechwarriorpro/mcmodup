"""Native crystal resources with new, vanilla-textured models (no imported crystal artwork)."""

import json
from dataclasses import dataclass


COLORS = tuple("white orange magenta light_blue yellow lime pink gray light_gray cyan purple blue brown green red black".split())


@dataclass(frozen=True)
class Element:
    start: tuple[int, int, int]
    end: tuple[int, int, int]
    texture: str
    angle: float = 0

    def model(self) -> dict:
        element = {"from": self.start, "to": self.end, "faces": {
            direction: {"texture": self.texture} for direction in ("down", "up", "north", "south", "east", "west")}}
        if self.angle:
            element["rotation"] = {"origin": [8, 0, 8], "axis": "y", "angle": self.angle}
        return element


def resources(assets: str, data: str, mod_id: str) -> tuple[dict[str, str], dict[str, str]]:
    files: dict[str, str] = {}
    names: dict[str, str] = {}

    def write(path: str, value: dict) -> None:
        files[path] = json.dumps(value, indent=2) + "\n"

    def recipe(name: str, pattern: tuple[str, ...], key: dict[str, str], output: str) -> None:
        write(f"{data}/recipe/{name}.json", {"type": "minecraft:crafting_shaped", "pattern": pattern,
               "key": {symbol: {"item": item} for symbol, item in key.items()}, "result": {"id": output}})

    for color in COLORS:
        cave, lamp, potion, shard = (f"{kind}_{color}" for kind in ("cave_crystal", "crystal_lamp", "potion_crystal", "crystal_shard"))
        title = color.replace("_", " ").title()
        names[f"item.{mod_id}.{shard}"] = f"{title} Crystal Shard"
        write(f"{assets}/models/item/{shard}.json", {"parent": "minecraft:item/generated",
              "textures": {"layer0": f"minecraft:item/{color}_dye"}})
        for block, label in ((cave, "Cave Crystal"), (lamp, "Crystal Lamp"), (potion, "Potion Crystal")):
            name = f"{mod_id}:{block}"
            model = f"{mod_id}:block/{block}"
            names[f"block.{mod_id}.{block}"] = f"{title} {label}"
            pedestal = block != cave
            elements = [Element((6, 0 if not pedestal else 3, 5), (11, 13, 10), "#crystal", 22.5),
                        Element((2, 0 if not pedestal else 3, 7), (6, 9, 11), "#crystal", -22.5),
                        Element((10, 0 if not pedestal else 3, 8), (14, 10, 12), "#crystal", 45)]
            if pedestal:
                elements.insert(0, Element((0, 0, 0), (16, 3, 16), "#base"))
            write(f"{assets}/models/block/{block}.json", {"parent": "minecraft:block/block", "render_type": "minecraft:translucent",
                  "textures": {"particle": f"minecraft:block/{color}_stained_glass", "crystal": f"minecraft:block/{color}_stained_glass",
                               "base": "minecraft:block/obsidian" if block == potion else "minecraft:block/smooth_stone"},
                  "elements": [element.model() for element in elements]})
            write(f"{assets}/models/item/{block}.json", {"parent": model})
            write(f"{assets}/blockstates/{block}.json", {"variants": {"": {"model": model}}})
            entry = {"type": "minecraft:item", "name": f"{mod_id}:{shard}" if block == cave else name}
            if block == cave:
                entry["functions"] = [
                    {"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 1, "max": 6}},
                    {"function": "minecraft:set_count", "add": True, "count": {"type": "minecraft:uniform", "min": 0, "max": 2}},
                    {"function": "minecraft:explosion_decay"},
                ]
            else:
                entry["conditions"] = [{"condition": "minecraft:survives_explosion"}]
            write(f"{data}/loot_table/blocks/{block}.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [entry]}]})
        recipe(lamp, (" s ", "sss", "SSS"), {"s": f"{mod_id}:{shard}", "S": "minecraft:stone_slab"}, f"{mod_id}:{lamp}")
        inputs = {"R": "minecraft:redstone_block", "G": "minecraft:glowstone", "S": "minecraft:obsidian", "D": "minecraft:gold_block"}
        recipe(potion, ("RsG", "sss", "SDS"), {**inputs, "s": f"{mod_id}:{shard}"}, f"{mod_id}:{potion}")
        recipe(potion + "_from_lamp", ("RlG", "SDS"), {**inputs, "l": f"{mod_id}:{lamp}"}, f"{mod_id}:{potion}")

    feature = f"{mod_id}:cave_crystal"
    write(f"{data}/worldgen/configured_feature/cave_crystal.json", {"type": feature, "config": {}})
    write(f"{data}/worldgen/placed_feature/cave_crystal.json", {"feature": feature, "placement": [
        {"type": "minecraft:rarity_filter", "chance": 3},
        {"type": "minecraft:count", "count": 8},
        {"type": "minecraft:in_square"},
        {"type": "minecraft:height_range", "height": {"type": "minecraft:uniform", "min_inclusive": {"absolute": -48},
                                                    "max_inclusive": {"absolute": 32}}},
        {"type": "minecraft:biome"},
    ]})
    write(f"{data}/neoforge/biome_modifier/cave_crystal.json", {"type": "neoforge:add_features", "biomes": "#minecraft:is_overworld",
          "features": feature, "step": "underground_decoration"})
    return files, names
