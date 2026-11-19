import json
import re
import struct
import unittest

from mcmodup.assets import PNG_SIGNATURE
from mcmodup.inputs import InputError
from mcmodup.minechem_final_synthesis import read_assets
from mcmodup.minechem_materials import observations, release
from mcmodup.minechem_matter import ANIMATED_PNGS, AUTOMATION_TRACE, asset_paths, checks, fluid_rows, project, radiation_rows, trace_name


class MatterTests(unittest.TestCase):
    def ids(self, prepaid):
        namespace = "minechem_chemistry_394" if prepaid else "minechem_chemistry"
        name = trace_name(prepaid, "fluids")
        paths = {"e26" if prepaid else "e25": "element_fe", "m1": "molecule_water",
                 "e82" if prepaid else "e81": "element_pb", "e92" if prepaid else "e91": "element_u"}
        ids = {key: f"{namespace}:" + paths.get(key, ("element_" if key[0] == "e" else "molecule_") + key[1:])
               for key in observations(name) if re.fullmatch(r"[em]\d+", key)}
        if prepaid:
            ids["m1"] = f"{namespace}:molecule_water"
        return namespace, ids

    def test_registry_counts_capabilities_and_colliding_legacy_identifiers(self):
        ns, ids = self.ids(True)
        files, _ = project(ns, "mcmodup.example", ids, {}, True)
        data = f"src/main/resources/data/{ns}"
        fluids = json.loads(files[f"{data}/mcmodup/chemical_fluids.json"])
        self.assertEqual(len(fluids), 285)
        self.assertTrue(all(row['placeable'] and row['tube_volume'] == 125 for row in fluids.values()))
        self.assertNotIn(ns + ":fluid_molecule_water", fluids)
        self.assertEqual(sum(row["legacy_name"] == "mt" for row in fluids.values()), 2)
        mapped, _ = release(ns, ids, prepaid=True)
        fluids_map = {row.id: row for row in mapped if row.volume}
        self.assertEqual(len(fluids_map), 287)
        self.assertEqual(fluids_map[ns + ":fluid_element_fe"].representative, ((ids["e26"], 1),))
        self.assertEqual(fluids_map[ns + ":fluid_molecule_88"].representative, ((ids["e109"], 1),))
        buckets = {row.id: row for row in mapped if row.id.startswith(ns + ":bucket_")}
        self.assertEqual(len(buckets), 284)
        self.assertNotIn(ns + ":bucket_molecule_88", buckets)
        self.assertEqual(buckets[ns + ":bucket_element_fe"].representative, ((ids["e26"], 56),))
        for state in ("still", "flow"):
            png = f"assets/{ns}/textures/block/fluid_{state}.png"
            self.assertIn(png, asset_paths(ns, True).values())
            self.assertIn("animation", json.loads(files[f"src/main/resources/{png}.mcmeta"]))
        self.assertIn('return false ? Items.WATER_BUCKET', files['src/main/java/mcmodup/example/ChemistryFluids.java'])
        with self.assertRaisesRegex(InputError, 'does not match'):
            fluid_rows(True, {key: item for key, item in ids.items() if key != 'e26'})

    def test_observed_automation_and_fuels_only_render_for_release_394(self):
        ns, ids = self.ids(True)
        paths, _ = project(ns, "test.port", ids, {}, True)
        trace = observations(AUTOMATION_TRACE)
        data = json.loads(paths[f"src/main/resources/data/{ns}/mcmodup/fluid_automation.json"])
        self.assertEqual(len(data["reactions"]), 13)
        self.assertEqual(len(data["furnace_fuels"]), 14)
        self.assertEqual(data["ignition"], {ids["m6"]: 2.0})
        self.assertIn({"inputs": [ids["e3"], ids["m1"]], "power": 0.1, "products": [ids["e1"], ids["m148"]]}, data["reactions"])
        self.assertEqual(data["furnace_fuels"][ids["e6"]], 200)
        self.assertFalse(any(":bucket_" in item for item in data["furnace_fuels"]))
        self.assertEqual(trace["dispense_molecule_short"], "ok/air/{m0=4}/{}")
        self.assertEqual(trace["propagate_gas"], "air/e1:0/e1:1")
        self.assertEqual(trace["propagate_liquid"], "m62:1/m62:0/air")
        self.assertEqual(trace["propagate_solid"], "air/e26:0/air")
        self.assertIn("new ChemicalFlow394", paths["src/main/java/test/port/ChemistryFluids.java"])
        self.assertTrue(data["reactionFluidMeetFluid"] and data["reactionItemMeetFluid"])
        for code in ("FluidReactions", "ChemicalFuels", "ChemistryFluids"):
            self.assertNotRegex(paths[f"src/main/java/test/port/{code}.java"], r"@[A-Z_]+@")
        final_ns, final_ids = self.ids(False)
        final, _ = project(final_ns, "test.port", final_ids, {}, False)
        self.assertFalse(any(path.endswith(("FluidReactions.java", "ChemicalFuels.java", "FluidDispensing.java", "ChemicalFlow394.java", "fluid_automation.json"))
                             for path in final))
        self.assertNotIn("FluidReactions", final["src/main/java/test/port/ChemistryFluids.java"])

    def test_only_pinned_fluid_animation_layouts_are_accepted(self):
        name = "assets/minechem/textures/blocks/fluid_still.png"

        class Archive:
            files = {name, f"{name}.mcmeta"}
            dimensions = (16, 128)

            def read(self, path, limit):
                return PNG_SIGNATURE + b"\x00\x00\x00\x0dIHDR" + struct.pack(">II", *self.dimensions)

        source = Archive()
        with self.assertRaisesRegex(InputError, "standalone"):
            read_assets(source, {name: "target"})
        self.assertIn(name, read_assets(source, {name: "target"}, animation_sizes=ANIMATED_PNGS))
        source.dimensions = (16, 256)
        with self.assertRaisesRegex(InputError, "animated Minechem PNG layout"):
            read_assets(source, {name: "target"}, animation_sizes=ANIMATED_PNGS)

    def test_final_fluid_collection_stays_non_placeable(self):
        ns, ids = self.ids(False)
        rows = fluid_rows(False, ids)
        self.assertEqual(len(rows), 260)
        self.assertTrue(all(not row.placeable for row in rows))
        mapped, _ = release(ns, ids, prepaid=False)
        self.assertEqual([(row.id, row.volume) for row in mapped if row.volume], [("minecraft:water", 144)])
        files, _ = project(ns, "test.port", ids, {}, False)
        self.assertFalse(any('/models/item/bucket_' in path for path in files))
        self.assertTrue(any('/recipe/convert_chemical_storage.json' in path for path in files))
        self.assertNotIn('assets/minechem/textures/items/TestTube.png', asset_paths(ns, False))

    def test_radiation_keys_and_defaults_are_release_isolated(self):
        for prepaid in (False, True):
            ns, ids = self.ids(prepaid)
            rows = dict((item, (tier, parts)) for item, tier, parts in radiation_rows(prepaid, ids, fluid_rows(prepaid, ids)))
            prev = ids['e91' if prepaid else 'e90']
            self.assertEqual(rows[ns + ':element_u'], (1, ((prev, 1),)))
            self.assertEqual(ns + ':bucket_element_u' in rows, prepaid)
            self.assertEqual(ids['m109'] in rows, prepaid)
            files, _ = project(ns, "test.port", ids, {}, prepaid)
            data = f"src/main/resources/data/{ns}"
            rad = json.loads(files[f"{data}/mcmodup/radiation.json"])
            self.assertEqual(rad['default_multiplier'], 20 if prepaid else 100)
            self.assertEqual(rad['decay_safe_machines'], prepaid)
            placement = json.loads(files[f"{data}/worldgen/placed_feature/uranium_ore.json"])
            self.assertEqual(placement['placement'][0]['count'], 6)
            self.assertIn('test.port', checks('test.port', 'checker', ns, ids, prepaid))

    def test_decay_probe_rows_and_warning_locales_keep_releases_distinct(self):
        for prepaid in (False, True):
            ns, ids = self.ids(prepaid)
            rows = observations(trace_name(prepaid, "player-decay"))
            self.assertEqual(rows["registered_item_shields"], "0")
            self.assertEqual(rows["cursor"], "e92x3|0.0|[]" if prepaid else "e91x3|0.0|[]")
            self.assertEqual(rows["hotbar"].split("|")[1], "3.0" if prepaid else "1.0")
            self.assertEqual(rows["fusion_storage"].split("|")[1], "0.0")
            self.assertNotEqual(rows["fusion_storage"].split("|")[2], "[]")
            _, locales = project(ns, "example.matter", ids, {}, prepaid)
            warning = locales[f"message.{ns}.radiation_decay"]
            self.assertEqual(warning.count("%s"), 3 if prepaid else 2)
            code = checks("test.port", "checker", ns, ids, prepaid)
            self.assertNotRegex(code, r"@[A-Z_]+@")
            self.assertIn('"fusion_core"', code)
            standalone = checks("test.port", "checker", ns, ids, prepaid, reactors=False)
            self.assertNotIn('"fusion_core"', standalone)
            self.assertNotIn('"fusion_storage"', standalone)
            self.assertEqual("release394DecayBuckets" in code, prepaid)
            if prepaid:
                self.assertEqual(rows["m109_bucket_alternate_byproducts"], "[e17x16]")
                self.assertIn('Map.of("element_87", 8, "bucket_element_17", 1), true', code)
            else:
                self.assertIn('"decomposer_player_25", new Exposure("element_u", 3, 0.0F, null)', code)
                self.assertEqual(rows["scheduler_spacing"], "20")


if __name__ == '__main__':
    unittest.main()
