import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from mcmodup.geostrata_rocks import LEGACY_SHA256, MOD_ID, RESOURCE, VEINS, prepare, textures
from mcmodup.inputs import InputError
from mcmodup.source_ports import BuildOptions, load_ports


class GeoStrataRockTests(unittest.TestCase):
    def test_recipe_is_reproducible_private_and_hash_bound(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            manifest = prepare(root / "recipe")
            self.assertEqual(manifest.read_bytes(), prepare(root / "again").read_bytes())
            with self.assertRaisesRegex(InputError, "Choose a new directory"):
                prepare(manifest.parent)
            port = load_ports(BuildOptions(manifest))[LEGACY_SHA256]
            self.assertTrue(port.isolated_preview)
            self.assertFalse(port.dependencies or port.method_imports)
            self.assertEqual(port.legacy_ids, {"geostrata": MOD_ID})
            self.assertEqual(port.imports, {old: RESOURCE + new for old, new in textures().items()})
            self.assertEqual(len(port.imports), 146)
            with ZipFile(port.archive) as zipped:
                self.assertFalse(any(name.endswith((".png", ".class")) for name in zipped.namelist()))
                data = RESOURCE + f"data/{MOD_ID}/"
                recipes = [name for name in zipped.namelist() if name.startswith(data + "recipe/")]
                self.assertEqual(len(recipes), 136)
                granite = json.loads(zipped.read(data + "recipe/granite_from_smelting.json"))
                self.assertEqual(granite["ingredient"], {"item": f"{MOD_ID}:granite_cobblestone"})
                self.assertEqual(granite["result"], {"id": f"{MOD_ID}:granite"})
                self.assertEqual(granite["experience"], 0.2)
                tags = RESOURCE + "data/minecraft/tags/block/"
                pickaxe = json.loads(zipped.read(tags + "mineable/pickaxe.json"))
                iron = json.loads(zipped.read(tags + "needs_iron_tool.json"))
                stone = json.loads(zipped.read(tags + "needs_stone_tool.json"))
                self.assertFalse(pickaxe["replace"] or iron["replace"] or stone["replace"])
                self.assertEqual(len(pickaxe["values"]), 153)
                self.assertIn(f"{MOD_ID}:granite", iron["values"])
                self.assertIn(f"{MOD_ID}:marble", stone["values"])
                self.assertNotIn(f"{MOD_ID}:limestone", iron["values"] + stone["values"])

    def test_connected_wall_uses_smooth_texture_and_separate_model_layers(self):
        with tempfile.TemporaryDirectory() as scratch:
            recipe = prepare(Path(scratch) / "recipe")
            port = load_ports(BuildOptions(recipe))[LEGACY_SHA256]
            with ZipFile(port.archive) as zipped:
                assets = RESOURCE + f"assets/{MOD_ID}/models/"
                model = json.loads(zipped.read(assets + "block/granite_connected.json"))
                item = json.loads(zipped.read(assets + "item/granite_connected.json"))
                self.assertEqual(model["textures"]["all"], f"{MOD_ID}:block/granite")
                self.assertEqual(item["textures"]["edge"], f"{MOD_ID}:block/connected/0")
                for index in range(1, 10):
                    face_model = json.loads(zipped.read(assets + f"block/connected/{index}.json"))
                    self.assertEqual(face_model["render_type"], "minecraft:translucent")
                    self.assertEqual(face_model["textures"]["edge"], f"{MOD_ID}:block/connected/{index}")
                    self.assertEqual({face: spec["cullface"] for face, spec in face_model["elements"][0]["faces"].items()},
                                     {face: face for face in ("up", "down", "north", "south", "east", "west")})

    def test_veins_preserve_ore_and_use_observed_bounds(self):
        with tempfile.TemporaryDirectory() as scratch:
            manifest = prepare(Path(scratch) / "recipe")
            port = load_ports(BuildOptions(manifest))[LEGACY_SHA256]
            data = RESOURCE + f"data/{MOD_ID}/"
            with ZipFile(port.archive) as zipped:
                read = lambda path: json.loads(zipped.read(data + path))
                self.assertEqual(sum(vein.attempts for vein in VEINS.values()), 58)
                self.assertEqual(sum("/configured_feature/" in name for name in zipped.namelist()), 17)
                for name, vein in VEINS.items():
                    feature = read(f"worldgen/configured_feature/{name}_vein.json")
                    self.assertEqual(feature["config"]["size"], 32)
                    self.assertEqual(feature["config"]["targets"], [{
                        "target": {"predicate_type": "minecraft:block_match", "block": "minecraft:stone"},
                        "state": {"Name": f"{MOD_ID}:{name}"},
                    }])
                    placement = read(f"worldgen/placed_feature/{name}_vein.json")["placement"]
                    self.assertEqual(placement[0]["count"], vein.attempts)
                    self.assertEqual(placement[2]["height"], {"type": "minecraft:uniform",
                        "min_inclusive": {"absolute": vein.min_y}, "max_inclusive": {"absolute": vein.max_y - 1}})
                    self.assertEqual(placement[-1], {"type": "minecraft:biome"})
                    if name == "onyx":
                        filters = placement[3]["predicate"]["predicates"]
                        self.assertEqual(len(filters), 6)
                        self.assertTrue(all(predicate["fluids"] == "#minecraft:lava" and
                                            sum(abs(delta) for delta in predicate["offset"]) == 1 for predicate in filters))
                for condition in ("any_overworld", "not_desert", "snowy"):
                    modifier = read(f"neoforge/biome_modifier/rocks_{condition}.json")
                    self.assertEqual(modifier["step"], "underground_decoration")
                    self.assertIn("#minecraft:is_overworld", json.dumps(modifier["biomes"]))


if __name__ == "__main__":
    unittest.main()
