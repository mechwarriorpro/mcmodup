"""Exact source identity routing for concurrent releases of the same legacy mod."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

from mcmodup.inputs import InputError
from mcmodup.native_pack import REPORT_NAME, inventory, plan, stage
from mcmodup.source_ports import BuildOptions, digest
from test_native_pack import legacy, native, setup_platform
from test_pack import fixture_class, jar
from test_source_ports import recipe, write_manifest


class SourceIdentityTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.source, self.target, self.output = (self.root / path for path in ("legacy", "target", "pack"))
        setup_platform(self.source, "1.6.4")
        setup_platform(self.target)
        self.labels = [f"mods/{release}/Chemistry.jar" for release in ("1.6.4", "1.7.10")]
        self.ids = ["chemistry_164", "chemistry_1710"]
        self.originals = [legacy(self.source, label, "Chemistry", owner="legacy/chemistry/Base", version=f"{i}.0")
                          for i, label in enumerate(self.labels, 1)]
        self.zip = jar(self.root / "source.zip", **{"settings.gradle": "rootProject.name='example'"})
        self.ports = [recipe(path, self.zip, mod_id, legacy_ids={"chemistry": mod_id})
                      for path, mod_id in zip(self.originals, self.ids)]
        self.manifest = self.root / "recipe.json"
        (self.source / "mcmodup-updates.json").write_text('{"Chemistry": "global_alias"}', encoding="utf-8")

    def get_inventory(self, rows=None):
        write_manifest(self.manifest, *(self.ports if rows is None else rows))
        return inventory(self.source, self.target, BuildOptions(self.manifest))

    def build(self, *, collision=""):
        def fake(port, workspace, *_args):
            mod_id = next(iter(port.ids))
            asset_ns = "shared" if collision == "asset" else mod_id
            files = {f"data/{asset_ns}/recipe/test.json": "{}"}
            if collision == "package":
                files[f"shared/pkg/{mod_id}.class"] = fixture_class(f"shared/pkg/{mod_id}")
            if collision.startswith("tag"):
                bad = mod_id == self.ids[-1]
                files["data/minecraft/tags/block/mineable/pickaxe.json"] = json.dumps({
                    "replace": bad and collision == "tag-replace",
                    "values": ["minecraft:stone" if bad and collision == "tag-foreign" else f"{mod_id}:decomposer"],
                })
                if bad and collision == "tag-assets":
                    files["assets/minecraft/models/block/stone.json"] = "{}"
                if collision.startswith("tag-matter"):
                    files["data/minecraft/tags/block/needs_iron_tool.json"] = json.dumps({
                        "replace": bad and collision == "tag-matter-replace", "values": [f"{mod_id}:uranium_ore"],
                    })
                    for registry in ("block", "item"):
                        files[f"data/c/tags/{registry}/ores/uranium.json"] = json.dumps({
                            "replace": False, "values": ["minechem_other:uranium_ore"
                                if bad and collision == "tag-matter-foreign" else f"{mod_id}:uranium_ore"],
                        })
                    if bad and collision == "tag-matter-resource":
                        files["data/c/recipe/unrelated.json"] = "{}"
            output = native(workspace, port.artifact, mod_id, requirements=tuple((dep.mod_id, dep.version) for dep in port.dependencies),
                            owner=f"modern/{mod_id}/Main", more=files)
            if collision == "nested":
                private = jar(workspace / "nested.jar", **{f"private/{mod_id}/Tools.class": fixture_class(f"private/{mod_id}/Tools")})
                with ZipFile(output, "a") as archive:
                    archive.write(private, "META-INF/jarjar/nested.jar")
            (workspace / port.game_report).write_text('<testsuite><testcase name="nativecase"/></testsuite>', encoding="utf-8")
        return fake

    def test_split_uses_each_originals_sha_instead_of_global_alias(self):
        addon_labels, addons = [], []
        for i, mod_id in enumerate(self.ids, 1):
            old = self.labels[i - 1].rsplit("/", 1)[0] + "/Addon.jar"
            path = legacy(self.source, old, f"chem_addon_{i}", requirements=(f"required-after:Chemistry@[{i}.0]",),
                          refs=("legacy/chemistry/Base",))
            addons.append(recipe(path, self.zip, f"chem_addon_{i}", dependencies=((mod_id, "[2.0]"),)))
            addon_labels.append(old)
        state = self.get_inventory([*self.ports, *addons])
        result = plan(state, self.labels, output_pack=True)
        self.assertTrue(result.report["ready"], result.problems)
        self.assertEqual(set(result.addons), set(addon_labels))
        self.assertEqual(result.report["legacy_selection_conflicts"], [])
        [split] = result.report["legacy_identity_splits"]
        self.assertEqual(split["status"], "separate_source_recipes")
        self.assertEqual([(row["sha256"], row["native_id"]) for row in split["archives"]],
                         [(digest(path), mod_id) for path, mod_id in zip(self.originals, self.ids)])
        deps = {row["consumer"]: row for row in result.report["legacy_requirements"] if row["id"].lower() == "chemistry"}
        for old, new, add in zip(self.originals, self.ids, addon_labels):
            self.assertEqual(deps[add]["provider"]["sha256"], digest(old))
            self.assertEqual(deps[add]["native_binding"]["id"], new)
            self.assertEqual(deps[add]["native_binding"]["status"], "native_metadata")
        self.assertFalse(plan(state, self.labels).report["ready"])
        with patch("mcmodup.native_pack.require_jdk", return_value="unused"), patch("mcmodup.source_ports._gradle", self.build()):
            stage(result, self.output)
        report = json.loads((self.output / REPORT_NAME).read_text())
        self.assertEqual(report["legacy_identity_splits"], [split])
        self.assertEqual(set(report["native_builds"]), {f"{port['artifact'].rsplit('/', 1)[1]}" for port in [*self.ports, *addons]})
        self.assertFalse(report["parity_verified"])

    def test_distinct_exact_maps_and_unchanged_inputs_required(self):
        for rows in (self.ports[:1], [self.ports[0], self.ports[0] | {"legacy_sha256": digest(self.originals[1])}]):
            result = plan(self.get_inventory(rows), self.labels, output_pack=True)
            self.assertFalse(result.report["ready"])
            self.assertEqual(result.report["legacy_identity_splits"], [])
            self.assertEqual(result.report["legacy_selection_conflicts"][0]["id"], "chemistry")
        state = self.get_inventory()
        with ZipFile(self.originals[0], "a") as original:
            original.writestr("changed.txt", b"changed since inventory")
        result = plan(state, self.labels, output_pack=True)
        self.assertIn("original changed after recipe analysis", " ".join(result.problems))
        self.assertFalse(result.report["ready"])

    def test_built_jars_cannot_share_packages_resource_namespaces_or_nested_jars(self):
        result = plan(self.get_inventory(), self.labels, output_pack=True)
        self.assertTrue(result.report["ready"], result.problems)
        for collision, error in (("asset", "shares a native asset/data namespace"), ("package", "shares native Java package"),
                                 ("nested", "cannot have embedded JARs")):
            with self.subTest(collision=collision), patch("mcmodup.native_pack.require_jdk", return_value="unused"), patch(
                    "mcmodup.source_ports._gradle", self.build(collision=collision)):
                with self.assertRaisesRegex(InputError, error):
                    stage(result, self.output)
                self.assertFalse(self.output.exists())

    def test_shared_vanilla_mining_tag_may_only_append_each_jars_own_blocks(self):
        result = plan(self.get_inventory(), self.labels, output_pack=True)
        self.assertTrue(result.report["ready"], result.problems)
        for collision in ("tag-replace", "tag-foreign", "tag-assets", "tag"):
            with self.subTest(collision=collision), patch("mcmodup.native_pack.require_jdk", return_value="unused"), patch(
                    "mcmodup.source_ports._gradle", self.build(collision=collision)):
                if collision == "tag":
                    stage(result, self.output)
                    self.assertTrue((self.output / REPORT_NAME).is_file())
                else:
                    with self.assertRaisesRegex(InputError, "shares a native asset/data namespace 'minecraft'"):
                        stage(result, self.output)
                    self.assertFalse(self.output.exists())

    def test_shared_ore_tags_may_only_append_local_ore_ids(self):
        result = plan(self.get_inventory(), self.labels, output_pack=True)
        self.assertTrue(result.report["ready"], result.problems)
        for collision, rejected in (("tag-matter-foreign", "c"), ("tag-matter-resource", "c"),
                                    ("tag-matter-replace", "minecraft"), ("tag-matter", None)):
            with self.subTest(collision=collision), patch("mcmodup.native_pack.require_jdk", return_value="unused"), patch(
                    "mcmodup.source_ports._gradle", self.build(collision=collision)):
                if rejected:
                    with self.assertRaisesRegex(InputError, f"shares a native asset/data namespace '{rejected}'"):
                        stage(result, self.output)
                    self.assertFalse(self.output.exists())
                else:
                    stage(result, self.output)
                    self.assertTrue((self.output / REPORT_NAME).is_file())

    def test_target_file_does_not_inherit_a_recipe_by_filename(self):
        legacy(self.target, self.labels[0], "Chemistry", version="9.9")
        result = plan(self.get_inventory(), [self.labels[0]])
        self.assertIn("Target contains non-native", " ".join(result.problems))
        self.assertEqual(result.removed, {})

    def test_auto_built_native_dependencies_participate_in_split(self):
        old = legacy(self.source, "mods/Other.jar", "helper")
        other_port = recipe(old, self.zip, "helper", dependencies=tuple((mod_id, "[2]") for mod_id in self.ids))
        state = self.get_inventory([*self.ports, other_port])
        result = plan(state, ["mods/Other.jar"], output_pack=True)
        self.assertTrue(result.report["ready"], result.problems)
        self.assertEqual(result.report["legacy_identity_splits"][0]["id"], "chemistry")
        with patch("mcmodup.native_pack.require_jdk", return_value="unused"), patch(
                "mcmodup.source_ports._gradle", self.build(collision="asset")), self.assertRaisesRegex(InputError, "namespace"):
            stage(result, self.output)
