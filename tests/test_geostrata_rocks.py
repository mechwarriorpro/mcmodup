import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from mcmodup.geostrata_rocks import LEGACY_SHA256, MOD_ID, RESOURCE, prepare, textures
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
            self.assertEqual(len(port.imports), 136)
            with ZipFile(port.archive) as zipped:
                self.assertFalse(any(name.endswith((".png", ".class")) for name in zipped.namelist()))
                self.assertFalse(any("worldgen" in name or "connected" in name for name in zipped.namelist()))
                data = RESOURCE + f"data/{MOD_ID}/"
                recipes = [name for name in zipped.namelist() if name.startswith(data + "recipe/")]
                self.assertEqual(len(recipes), 119)
                granite = json.loads(zipped.read(data + "recipe/granite_from_smelting.json"))
                self.assertEqual(granite["ingredient"], {"item": f"{MOD_ID}:granite_cobblestone"})
                self.assertEqual(granite["result"], {"id": f"{MOD_ID}:granite"})
                self.assertEqual(granite["experience"], 0.2)
                tags = RESOURCE + "data/minecraft/tags/block/"
                pickaxe = json.loads(zipped.read(tags + "mineable/pickaxe.json"))
                iron = json.loads(zipped.read(tags + "needs_iron_tool.json"))
                stone = json.loads(zipped.read(tags + "needs_stone_tool.json"))
                self.assertFalse(pickaxe["replace"] or iron["replace"] or stone["replace"])
                self.assertEqual(len(pickaxe["values"]), 136)
                self.assertIn(f"{MOD_ID}:granite", iron["values"])
                self.assertIn(f"{MOD_ID}:marble", stone["values"])
                self.assertNotIn(f"{MOD_ID}:limestone", iron["values"] + stone["values"])


if __name__ == "__main__":
    unittest.main()
