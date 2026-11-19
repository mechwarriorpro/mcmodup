import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from mcmodup.catalogs import CatalogEntry
from mcmodup.inputs import InputError
from mcmodup import minechem_reactors as reactors


class MinechemReactorProjectTests(unittest.TestCase):
    def test_original_layouts_and_material_counts(self):
        shapes = reactors._blueprint_replacements()
        for name, size, core, materials in (
            ("FISSION", "5,5,5", "2,2,2", (0, 98, 0)),
            ("FUSION", "13,5,13", "6,1,6", (181, 241, 3)),
        ):
            self.assertEqual(shapes[f"{name}_SIZE"], size)
            self.assertEqual(shapes[f"{name}_CORE"], core)
            grid = shapes[f"{name}_GRID"]
            self.assertEqual(tuple(grid.count(cell) for cell in "AC0"), materials)

    def test_reject_invalid_blueprint_instead_of_guessing(self):
        with TemporaryDirectory() as directory:
            trace = Path(directory) / "trace.txt"
            pinned = reactors.BLUEPRINT_TRACE.read_text(encoding="utf-8")
            trace.write_text(pinned.replace("6,1,6", "16,1,6"), encoding="utf-8")
            with patch.object(reactors, "BLUEPRINT_TRACE", trace), self.assertRaises(InputError):
                reactors._blueprint_replacements()

    def test_only_paired_394_recipes_change_legacy_glass_and_paper(self):
        entries = [CatalogEntry(f"element_{number}", f"Element {number}", "unused", (), {"atomic_number": number})
                   for number in range(1, 114)]
        for rf, namespace in ((False, "minechem_chemistry"), (True, "minechem_chemistry_394")):
            with self.subTest(release394=rf):
                resource = reactors.RESOURCE
                asset_root = f"{resource}assets/{namespace}"
                lang = f"{asset_root}/lang/en_us.json"
                tag = resource + "data/minecraft/tags/block/mineable/pickaxe.json"
                files = {lang: '{"existing.key":"Retained"}', tag: '{"replace":false,"values":["minecraft:ice"]}'}
                reactors.add_project(files, namespace, "test.minechem", entries, release_394=rf)
                recipe = lambda name: json.loads(files[f"{resource}data/{namespace}/recipe/{name}.json"])
                base = recipe("fission_blueprint")
                self.assertEqual(base["ingredients"][0]["item"], "minecraft:paper")
                self.assertEqual(recipe("reactor_casing")["result"]["count"], 16)
                self.assertEqual(recipe("microscope")["key"]["L"]["item"], f"{namespace}:microscope_lens")
                if rf:
                    alternate = recipe("fission_blueprint_paired")
                    self.assertEqual(alternate["ingredients"][0]["item"], "minecraft:map")
                    self.assertEqual(base["neoforge:conditions"][0]["value"], alternate["neoforge:conditions"][0])
                    self.assertEqual(recipe("convex_lens_paired")["key"]["G"]["item"], "minecraft:glass_pane")
                else:
                    self.assertNotIn("neoforge:conditions", base)
                    self.assertFalse(any("_paired.json" in name for name in files))
                self.assertFalse(any(name.endswith((".png", ".ogg")) for name in files))
                self.assertTrue(all(f"assets/{namespace}/" in mapped for mapped in reactors.asset_paths(namespace, rf).values()))
                self.assertEqual(json.loads(files[lang])["existing.key"], "Retained")
                self.assertEqual(json.loads(files[tag])["values"][0], "minecraft:ice")
                self.assertTrue(all("@ELEMENTS@" not in code for name, code in files.items() if name.endswith(".java")))
