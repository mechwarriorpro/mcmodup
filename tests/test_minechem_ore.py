import json
import unittest

from mcmodup import minechem_ore
from mcmodup.minechem_materials import observations


class UraniumOreTests(unittest.TestCase):
    def test_pinned_original_placement_and_attempt_stream(self):
        final, newer = [observations(minechem_ore.trace_name(prepaid)) for prepaid in (False, True)]
        self.assertEqual(final, newer)  # The two exact binaries agreed on these controlled samples.
        self.assertEqual(final["forge_registry"], "true")
        self.assertEqual(final["surface"], "0:true,-1:false,1:false")
        self.assertEqual(final["defaults"], "true/5/3")
        _, _, attempts, tail, placed = final["default"].split("|")
        self.assertEqual([int(roll.split(",")[3]) for roll in attempts.split(";")], [0, 6, 9, 8, 3, 12])
        self.assertEqual(len(placed.split(";")), 29)
        self.assertTrue(any(int(x) >= 16 or int(z) >= 16 for x, _, z in (pos.split(",") for pos in placed.split(";"))))
        _, _, masked_attempts, masked_tail, masked_placed = final["masked"].split("|")
        self.assertEqual((masked_attempts, masked_tail), (attempts, tail))
        self.assertTrue(set(masked_placed.split(";")) < set(placed.split(";")))
        self.assertEqual(final["empty_roll"].split("|")[2], "10,39,14,0,1,0,1")
        self.assertEqual(final["empty_roll"].split("|")[-1], "")
        self.assertEqual(final["floor"].split("|")[-1], "7,0,19")
        self.assertEqual(final["ceiling"].split("|")[2].split(",")[1], "49")

    def test_each_release_owns_one_chunk_feature_and_config(self):
        for prepaid in (False, True):
            with self.subTest(prepaid=prepaid):
                ns = "minechem_chemistry_394" if prepaid else "minechem_chemistry"
                files = minechem_ore.project(ns, "test.port", prepaid)
                data = f"src/main/resources/data/{ns}"
                self.assertEqual(json.loads(files[f"{data}/worldgen/configured_feature/uranium_ore.json"]),
                                 {"type": f"{ns}:uranium_veins", "config": {}})
                self.assertEqual(json.loads(files[f"{data}/worldgen/placed_feature/uranium_ore.json"]),
                                 {"feature": f"{ns}:uranium_ore", "placement": []})
                hooks = json.loads(files[f"{data}/neoforge/biome_modifier/uranium_ore.json"])
                self.assertEqual((hooks["biomes"], hooks["features"], hooks["step"]),
                                 ("#minecraft:is_overworld", f"{ns}:uranium_ore", "underground_ores"))
                code = files["src/main/java/test/port/UraniumOreFeature.java"]
                self.assertIn(f"boolean RELEASE_394 = {str(prepaid).lower()};", code)
                self.assertIn('define("generateOre", true)', code)
                self.assertIn('defineInRange("uraniumOreDensity", 5,', code)
                self.assertIn('defineInRange("uraniumOreClusterSize", 3,', code)
                self.assertNotRegex(code, r"@[A-Z_]+@")
                checks = minechem_ore.checks("test.checks", "check", ns, prepaid)
                self.assertNotRegex(checks, r"@[A-Z_]+@")
                self.assertEqual(checks.count("new Sample("), len(minechem_ore.CASES))


if __name__ == "__main__":
    unittest.main()
