import json
import unittest

from mcmodup import minechem_ore
from mcmodup.minechem_materials import PRODUCT, observations


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
                rows = observations("release394-ore-interop.txt")
                ids = {part[1]: f"{ns}:chemical_{part[1]}" for row in rows.values() for part in PRODUCT.finditer(row)}
                files = minechem_ore.project(ns, "test.port", prepaid, ids)
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

    def test_crafting_gate_and_tag_fallback_keep_release_identity(self):
        final = observations("final-ore-interop.txt")
        disabled = observations("final-ore-disabled.txt")
        other = observations("release394-ore-interop.txt")
        self.assertEqual((final["craft_foreign"], final["craft_extra"], disabled["craft_foreign"], other["craft_foreign"]),
                         ("nativex1:0", "-", "-", "-"))
        self.assertEqual((other["native_uranium_after_scan"], other["external_uranium_after_scan"], other["oreNetherUranium"]),
                         ("[e92x48]", "[e92x48]", "[e92x96]"))
        self.assertEqual((other["explicit_iron_before"], other["explicit_iron_after"], other["blockUranium"]),
                         ("[e26x48]", "[e26x48]", "-"))
        ns = "minechem_chemistry"
        final_files = minechem_ore.project(ns, "test.final", False, {})
        recipe = json.loads(final_files[f"src/main/resources/data/{ns}/recipe/uranium_ore_conversion.json"])
        self.assertEqual(recipe["neoforge:conditions"], [{"type": f"{ns}:uranium_ore_craftable"}])
        self.assertEqual(recipe["ingredients"][0]["base"], {"tag": "c:ores/uranium"})
        self.assertEqual(recipe["result"], {"id": f"{ns}:uranium_ore", "count": 1})
        ids = {part[1]: f"minechem_chemistry_394:chemical_{part[1]}" for row in other.values() for part in PRODUCT.finditer(row)}
        newer_files = minechem_ore.project(ns + "_394", "test.newer", True, ids)
        self.assertFalse(any(path.endswith("uranium_ore_conversion.json") for path in newer_files))
        code = newer_files["src/main/java/test/newer/OreDictionary394.java"]
        self.assertEqual(code.count("new Rule("), len(minechem_ore.ORE_FORMS))
        self.assertIn('tag("c:ores_in_ground/netherrack"), List.of(new Part("minechem_chemistry_394:chemical_e92", 96))', code)
        self.assertFalse(set(minechem_ore.verification_tags().values()) & set(newer_files.values()))


if __name__ == "__main__":
    unittest.main()
