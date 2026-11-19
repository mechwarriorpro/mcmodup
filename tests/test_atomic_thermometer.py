"""Check source-only preparation and exact Atomic Science method imports."""

from io import BytesIO
import json
import os
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from mcmodup.atomic_thermometer import KERNEL, LEGACY_SHA256, MOD_ID, OWNER, TEXTURES, prepare
from mcmodup.classfiles import read_class
from mcmodup.inputs import InputError
from mcmodup.method_ports import transplant
from mcmodup.source_ports import BuildOptions, load_ports
from mcmodup.runtime import file_digest


class AtomicThermometerTests(unittest.TestCase):
    def test_prepared_source_contains_no_legacy_code_or_textures(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            one, two = prepare(root / "one"), prepare(root / "two")
            self.assertEqual(one.read_bytes(), two.read_bytes())
            with self.assertRaisesRegex(InputError, "Choose a new directory"):
                prepare(one.parent)
            port = load_ports(BuildOptions(one))[LEGACY_SHA256]
            self.assertTrue(port.preview and port.isolated_preview)
            self.assertEqual(port.legacy_ids, {"atomicscience": MOD_ID})
            self.assertEqual(set(port.imports), set(TEXTURES))
            self.assertEqual(file_digest(port.archive), port.source_sha256)
            with ZipFile(port.archive) as source:
                for name in ("Thermometer", "HeatGrid", "Turbine", "TurbineClient"):
                    self.assertIn(f"src/main/java/{KERNEL}/{name}.java", source.namelist())
                steam = json.loads(source.read("src/main/resources/data/c/tags/fluid/steam.json"))
                self.assertEqual(steam, {"replace": False, "values": [f"{MOD_ID}:steam"]})
                recipe = json.loads(source.read(f"src/main/resources/data/{MOD_ID}/recipe/steam_funnel.json"))
                self.assertEqual(recipe["pattern"], [" I ", "I I", "I I"])
                self.assertEqual(recipe["key"], {"I": {"tag": "c:ingots/iron"}})
                self.assertEqual(recipe["result"], {"id": f"{MOD_ID}:steam_funnel", "count": 2})
                self.assertIn("SteamChecks*.class", source.read("build.gradle").decode())
                self.assertIn("TurbineChecks*.class", source.read("build.gradle").decode())
                self.assertFalse(any(path.endswith(".class") or path in port.imports.values() for path in source.namelist()))

    @unittest.skipUnless(os.environ.get("MCMODUP_VOLTZ_ARCHIVE"), "exact Voltz archive not supplied")
    def test_exact_original_links_four_bodies_and_does_not_link_old_types(self):
        with tempfile.TemporaryDirectory() as tmp:
            recipe = prepare(Path(tmp) / "recipe")
            [group] = load_ports(BuildOptions(recipe))[LEGACY_SHA256].method_imports
            with ZipFile(os.environ["MCMODUP_VOLTZ_ARCHIVE"]) as voltz, ZipFile(BytesIO(voltz.read("mods/atomicscience.jar"))) as jar:
                result = transplant(jar.read(OWNER + ".class"), group)
            native = read_class(result.bytecode)
            self.assertEqual({name for name, _ in native.members}, {"configure", "isOverThreshold", "setThreshold", "use"})
            self.assertEqual(result.report()["sha256"], "6b498c018d72cb84c9f1477684e3116ab6f5e1ec71d394c38e7062ab353f1761")
            self.assertTrue(all(ref.owner.startswith((KERNEL + "/", "java/")) for ref in native.references))


if __name__ == "__main__":
    unittest.main()
