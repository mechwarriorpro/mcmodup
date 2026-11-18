import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

from mcmodup import builtin_ports
from mcmodup.inputs import InputError
from mcmodup.native_pack import REPORT_NAME
from mcmodup.source_ports import digest
from test_cli import run_cli
from test_native_pack import legacy, native, setup_platform
from test_pack import jar
from test_source_ports import recipe, write_manifest


class BuiltinPortTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.src, self.target, self.out = (self.root / part for part in ("old", "target", "output"))
        setup_platform(self.src, "1.7.10")
        setup_platform(self.target)
        self.base_label = "mods/Renamed original.jar"
        self.base_jar = legacy(self.src, self.base_label, "Thaumcraft")
        self.zip = jar(self.root / "PinnedReload.zip", **{"PinnedReload/settings.gradle": "rootProject.name='base'"})
        self.base = recipe(self.base_jar, self.zip, "thaumcraft", source_root="PinnedReload", status="preview",
                           limitations=["Preview fixture"], dependencies=(("architectury", "[13.0.11]"),))
        self.manifest = write_manifest(self.root / "stock.json", self.base)
        native(self.target, "lib/Architectury.jar", "architectury", version="13.0.11")
        keep = patch.object(builtin_ports, "TC_RECIPE", self.manifest)
        keep.start()
        self.addCleanup(keep.stop)

    def build(self, *, selections=None, **kwargs):
        return builtin_ports.build_jars(self.src, self.target, selections or [self.base_label], self.out,
                                       allow_preview=True, **kwargs)

    def test_command_requires_opt_in_even_with_a_native_candidate(self):
        native(self.target, "lib/Prebuilt.jar", "thaumcraft")
        result = run_cli("build-jars", self.src, "--target", self.target, "--mod", self.base_label, "-o", self.out)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("No built-in port has full parity", result.stderr)
        self.assertFalse(self.out.exists())

    def test_preflight_rejects_unported_addons_without_preparing_sources(self):
        addon = "mods/Unknown addon.jar"
        legacy(self.src, addon, "thaumictinkerer", requirements=("required-after:Thaumcraft",))
        native(self.target, "lib/Similar addon.jar", "thaumictinkerer")
        with patch.object(builtin_ports, "require_jdk") as java, patch("mcmodup.tinkerer_computercraft.prepare") as prepare:
            with self.assertRaisesRegex(InputError, "Unknown addon.jar.*no built-in native source recipe"):
                self.build()
            java.assert_not_called()
            prepare.assert_not_called()
        self.assertFalse(self.out.exists())

    def test_preflight_rejects_paired_output_inside_either_pack_or_nested(self):
        for path, error in ((self.target / "recipe", "outside the source and target"),
                            (self.out / "recipe", "separate new directories")):
            with self.subTest(path=path), self.assertRaisesRegex(InputError, error):
                self.build(keep_recipe=path)
            self.assertFalse(path.exists())
            self.assertFalse(self.out.exists())

    def test_stock_cache_is_verified_before_it_is_retained(self):
        remote = {**self.base, "source_url": "https://codeload.github.com/test/pinned/zip/" + "1" * 40}
        del remote["source_zip"]
        write_manifest(self.manifest, remote)
        cache = self.root / "cache"
        cache.mkdir()
        payload = cache / self.zip.name
        payload.write_bytes(b"not the pinned source archive")
        kept = self.root / "recipe"
        with patch.object(builtin_ports, "require_jdk", return_value="unused"), patch("mcmodup.source_ports._gradle") as gradle:
            with self.assertRaisesRegex(InputError, "Source ZIP SHA-256 differs"):
                self.build(source_zips=cache, keep_recipe=kept)
            gradle.assert_not_called()
        self.assertFalse(kept.exists())
        self.assertFalse(self.out.exists())

    def test_exact_addon_is_built_after_exact_base_and_receives_built_jar(self):
        addon = legacy(self.src, "mods/Addon.jar", "ThaumicTinkerer", requirements=("required-after:Thaumcraft",))
        native(self.target, "mods/Prebuilt.jar", "thaumcraft")
        addon_zip = jar(self.root / "AddonSource.zip", **{"settings.gradle": "rootProject.name='addon'"})
        plan_input = recipe(addon, addon_zip, "thaumictinkerer", status="preview", limitations=["Preview fixture"],
                            dependencies=(("thaumcraft", "[2.0]"),))
        kept = self.root / "recipe"
        calls = []

        def prepare(directory, inputs):
            self.assertEqual(directory, kept)
            self.assertIsNone(inputs)
            directory.mkdir()
            shutil.copyfile(self.zip, directory / self.zip.name)
            shutil.copyfile(addon_zip, directory / addon_zip.name)
            return write_manifest(directory / "pair.json", self.base, plan_input)

        def compile_and_test(port, workspace, java, deps, version, timeout):
            [name] = port.ids
            calls.append(name)
            self.assertTrue((deps / "Architectury.jar").is_file())
            if name == "thaumictinkerer":
                self.assertTrue((deps / "thaumcraft.jar").is_file())
                self.assertFalse((deps / "Prebuilt.jar").exists())
            requirements = tuple((dep.mod_id, dep.version) for dep in port.dependencies)
            native(workspace, port.artifact, name, requirements=requirements)
            (workspace / port.game_report).write_text('<testsuite><testcase name="nativecase"/></testsuite>', encoding="utf-8")

        with patch.object(builtin_ports, "TT_LEGACY_HASH", digest(addon)), patch.object(
                builtin_ports, "require_jdk", return_value="unused"), patch("mcmodup.native_pack.require_jdk", return_value="unused"), patch(
                "mcmodup.tinkerer_computercraft.prepare", side_effect=prepare) as pair, patch(
                "mcmodup.source_ports._gradle", side_effect=compile_and_test):
            with self.assertRaisesRegex(InputError, "Select the original Thaumcraft 4.2.3.5 JAR"):
                self.build(selections=["mods/Addon.jar"], keep_recipe=kept)
            pair.assert_not_called()
            self.assertEqual(self.build(keep_recipe=kept), self.out)
        self.assertEqual(calls, ["thaumcraft", "thaumictinkerer"])
        report = json.loads((self.out / REPORT_NAME).read_text())
        self.assertEqual(set(report["native_builds"]), {"thaumcraft.jar", "thaumictinkerer.jar"})
        self.assertEqual(report["build_order"], [self.base_label, "mods/Addon.jar"])
        self.assertIn("mods/Addon.jar", report["auto_addons"])
        self.assertFalse(report["gameplay_verified"])
        self.assertTrue((self.target / "mods/Prebuilt.jar").exists())
        self.assertTrue((kept / "pair.json").is_file())
