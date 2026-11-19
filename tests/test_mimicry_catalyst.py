"""Check the exact-source recipe, normalized asset import, and bounded inputs."""

import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from mcmodup.inputs import InputError
from mcmodup.mimicry_catalyst import LEGACY_SHA256, LEGACY_TEXTURE, MOD_ID, TEXTURE, prepare
from mcmodup.runtime import file_digest
from mcmodup.source_ports import BuildOptions, load_ports


class MimicryCatalystTests(unittest.TestCase):
    def test_prepares_no_original_binary_or_asset_and_does_not_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = prepare(root / "recipe")
            other = prepare(root / "other")
            self.assertEqual(manifest.read_bytes(), other.read_bytes())
            with self.assertRaisesRegex(InputError, "Choose a new directory"):
                prepare(manifest.parent)
            port = load_ports(BuildOptions(manifest))[LEGACY_SHA256]
            self.assertTrue(port.preview and port.isolated_preview)
            self.assertEqual(port.legacy_ids, {"mimicry": MOD_ID})
            self.assertEqual(port.imports, {LEGACY_TEXTURE: f"src/main/resources/{TEXTURE}"})
            self.assertEqual(file_digest(port.archive), port.source_sha256)
            self.assertFalse(port.method_imports)
            with ZipFile(port.archive) as source:
                self.assertFalse(any(path.endswith((".class", ".png")) for path in source.namelist()))
                self.assertIn("gradle/wrapper/gradle-wrapper.jar", source.namelist())
                recipe_path = f"src/main/resources/data/{MOD_ID}/recipe/"
                files = {name.removeprefix(recipe_path): json.loads(source.read(name))
                         for name in source.namelist() if name.startswith(recipe_path)}
                self.assertEqual(set(files), {"iron_ore.json", "gold_ore.json", "sand.json", "iron_ingot.json", "lapis_lazuli.json"})
                for name, row in files.items():
                    self.assertEqual(row["type"], "minecraft:crafting_shapeless")
                    self.assertEqual(row["ingredients"], [{"item": f"{MOD_ID}:mimichite"}, {"item": "minecraft:" + name.removesuffix(".json")}])
                self.assertEqual(files["lapis_lazuli.json"]["result"], {"id": "minecraft:lapis_lazuli", "count": 3})


if __name__ == "__main__":
    unittest.main()
