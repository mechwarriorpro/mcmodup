import re
import unittest
from unittest.mock import patch

from mcmodup.catalogs import CatalogEntry
from mcmodup.inputs import InputError
from mcmodup.minechem_materials import observations
from mcmodup.minechem_polytool import asset_paths, checks, features, infusions, project, trace_name


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
            self.assertEqual(len(files), 4)
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

    def test_unobserved_catalog_or_inconsistent_trace_fails_closed(self):
        entries = catalog(True)
        with self.assertRaisesRegex(InputError, "Polytool element e13"):
            infusions("newer", [row for row in entries if row.path != "element_13"], True)
        source = observations(trace_name(True))
        with patch("mcmodup.minechem_polytool.observations", return_value=source | {"e13": source["e13"].replace(";8,4,8,16,4,", ";8,4,8,32,4,")}):
            with self.assertRaisesRegex(InputError, "Invalid Polytool alloy trace at e13"):
                infusions("newer", entries, True)
