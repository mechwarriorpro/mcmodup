import re
import unittest
from unittest.mock import patch

from mcmodup.catalogs import CatalogEntry
from mcmodup.inputs import InputError
from mcmodup.minechem_materials import observations
from mcmodup.minechem_polytool import asset_paths, checks, combat_trace_name, features, infusions, mining_trace_name, project, trace_name


def catalog(release_394):
    left, right = (1, 114) if release_394 else (0, 113)
    return [CatalogEntry(f"element_{metadata}", "", "", (), {"legacy_metadata": metadata}) for metadata in range(left, right)]


class MinechemPolytoolTests(unittest.TestCase):
    def test_frozen_registrations_and_release_metadata(self):
        final = {row.atomic: row for row in infusions("final", catalog(False), False)}
        newer = {row.atomic: row for row in infusions("newer", catalog(True), True)}
        self.assertEqual(set(final) - set(newer), {3})
        self.assertEqual(set(newer) - set(final), set())
        self.assertEqual((len(final), len(newer)), (49, 48))
        self.assertEqual(final[3].id, "final:element_2")
        self.assertEqual(final[13].id, "final:element_12")
        self.assertEqual(newer[13].id, "newer:element_13")
        self.assertEqual(final[13].alloy, (0, -4, 0, 8, -4))
        self.assertEqual(final[13].mining[:5], (8, 4, 8, 16, 4))
        self.assertEqual(sum(bool(row.alloy) for row in final.values()), 15)
        self.assertTrue(all(final[atomic].mining == newer[atomic].mining for atomic in newer))
        self.assertIn("read=long_as_int;next=long_as_int", observations(trace_name(False))["lithium"])
        self.assertEqual(observations(trace_name(True))["lithium"], "64/0;energy=false")

    def test_ports_render_independent_sources_screens_and_mod_tests(self):
        for prepaid in (False, True):
            ns = "minechem_chemistry_394" if prepaid else "minechem_chemistry"
            files, locales = project(ns, "test.polytool", catalog(prepaid), prepaid)
            self.assertEqual(len(files), 7)
            self.assertTrue(all(re.search(r"@[A-Z_]+@", java) is None for java in files.values()))
            source = files["src/main/java/test/polytool/PolytoolRules.java"]
            self.assertEqual(source.count("new Infusion("), 48 if prepaid else 49)
            self.assertEqual("new Infusion(3," in source, not prepaid)
            self.assertIn(f"{ns}:element_{13 if prepaid else 12}", source)
            self.assertEqual(locales[f"gui.{ns}.polytool.title"], "Polytool · " + (".394" if prepaid else "FINAL"))
            self.assertEqual(set(asset_paths(ns).values()), {f"assets/{ns}/textures/{kind}/polytool.png" for kind in ("item", "gui")})
            code = checks("test.probe", "test", ns, catalog(prepaid), prepaid)
            registered = {name.lower() for name in re.findall(r"public static void (\w+)\(GameTestHelper test\)", code)}
            self.assertEqual(registered, set(features(prepaid).values()))

    def test_mining_evidence_distinguishes_primary_and_extra_ore(self):
        final, newer = (observations(mining_trace_name(flag)) for flag in (False, True))
        self.assertEqual(final.keys(), newer.keys())
        self.assertEqual(len(final), 36)
        self.assertEqual(final["iron_only"], "[{263:0=1}]/[{263:0=1}]")
        self.assertEqual(newer["iron_only"], "[{263:0=1}]/[{}]")
        self.assertEqual(newer["iron_carbon1"], final["iron_only"])
        self.assertTrue(newer["silk_iron_carbon64"].endswith("[{16:0=1}, {16:0=2}, {16:0=3}, {16:0=4}, {16:0=5}]"))
        self.assertEqual(final["sodium_glowstone_3"], "12/50")
        self.assertEqual(newer["sodium_glowstone_3"], "12/0")
        for release_394 in (False, True):
            code = checks("test.probe", "test", "minechem_chemistry", catalog(release_394), release_394)
            source = observations(mining_trace_name(release_394))
            self.assertIn(f'Map.entry("iron_only", "{source["iron_only"]}")', code)
            self.assertIsNone(re.search(r"@[A-Z_]+@", code))

    def test_combat_observations_keep_different_release_paths(self):
        final, newer = (observations(combat_trace_name(value)) for value in (False, True))
        self.assertEqual((len(final), len(newer)), (63, 64))
        self.assertEqual(newer.keys() - final.keys(), {"gold_cofh_api", "gold_cofh_disabled"})
        self.assertEqual(final.keys() - newer.keys(), {"gold_ue_lithium_after"})
        self.assertEqual(final["gold_transfer_roll0"], "1/UNKNOWN/5000000/false/137")
        self.assertEqual(final["gold_ue_lithium_after"], "0")
        self.assertEqual(newer["gold_transfer_roll0"], "1/true/5000000/false/137")
        self.assertEqual(newer["gold_cofh_disabled"], "1/1/true/5000000/false/137")
        self.assertEqual((final["boron16_spider"], newer["boron16_spider"]), ("33.60", "12.80"))
        self.assertEqual((final["titanium_first_hit_kill"], newer["titanium_first_hit_kill"]), ("true/true", "false/false"))
        self.assertEqual((final["silicon_flesh_miss"], newer["silicon_flesh_miss"]), ("{m85=1}", "{}"))
        self.assertEqual((final["francium_mining"], newer["francium_mining"]), ("0/3", "3/0"))
        self.assertIn('Map.entry("helium_unselected", "0.30")', checks("test.probe", "test", "final", catalog(False), False))

    def test_infuser_uses_both_original_radii_and_release_local_help(self):
        for newer in (False, True):
            ns = "minechem_chemistry_394" if newer else "minechem_chemistry"
            files, lang = project(ns, "test.port", catalog(newer), newer)
            screen = files["src/main/java/test/port/PolytoolScreen.java"]
            self.assertIn("CENTER_X = 80, CENTER_Y = 42", screen)
            self.assertIn("INNER = 20, OUTER = 40", screen)
            self.assertIn("RADIANS_PER_FRAME = 0.01", screen)
            self.assertNotRegex(screen, r"@[A-Z_]+@")
            self.assertEqual(f"gui.{ns}.polytool.help_page" in lang, newer)

    def test_unobserved_catalog_or_inconsistent_trace_fails_closed(self):
        entries = catalog(True)
        with self.assertRaisesRegex(InputError, "Polytool element e13"):
            infusions("newer", [row for row in entries if row.path != "element_13"], True)
        source = observations(trace_name(True))
        with patch("mcmodup.minechem_polytool.observations", return_value=source | {"e13": source["e13"].replace(";8,4,8,16,4,", ";8,4,8,32,4,")}):
            with self.assertRaisesRegex(InputError, "Invalid Polytool alloy trace at e13"):
                infusions("newer", entries, True)
