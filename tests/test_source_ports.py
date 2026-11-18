import hashlib
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from mcmodup.inputs import InputError
from mcmodup.native_pack import REPORT_NAME, inventory, plan, stage
from mcmodup.source_ports import BuildOptions, digest, load_ports
from test_cli import run_cli
from test_native_pack import legacy, native, setup_platform
from test_pack import jar


def recipe(original: Path, project_zip: Path, mod_id: str, *, dependencies=(), **overrides) -> dict:
    return {"legacy_sha256": digest(original), "source_zip": project_zip.name, "source_sha256": digest(project_zip),
            "ids": {mod_id: "2.0"}, "neoforge": "[21.1.259,21.2)",
            "dependencies": [{"id": name, "range": spec} for name, spec in dependencies],
            "artifact": f"build/libs/{mod_id}.jar", "tasks": ["build", "runGameTestServer"],
            "game_report": "build/gametest.xml", "features": {"Main behavior": "nativecase"},
            "status": "reviewed", **overrides}


def write_manifest(path: Path, *rows: dict) -> Path:
    path.write_text(json.dumps({"schema_version": 1, "ports": rows}), encoding="utf-8")
    return path


class SourcePortTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.src, self.target, self.output = (self.root / x for x in ("old", "target", "stage"))
        self.manifest = self.root / "reviewed.json"
        setup_platform(self.src, "1.7.10")
        setup_platform(self.target)
        self.old_base = legacy(self.src, "mods/OldBase.jar", "techbase", owner="old/example/TechBase")
        self.asset = "assets/techbase/textures/items/gear.png"
        with zipfile.ZipFile(self.old_base, "a") as archive:
            archive.writestr(self.asset, b"original asset bytes")
        self.base_src = jar(self.root / "Tech.zip", **{"settings.gradle": "rootProject.name='base'"})
        self.base = recipe(self.old_base, self.base_src, "techbase", dependencies=(("energylib", "[1.0]"),),
                           import_assets={self.asset: "src/main/resources/" + self.asset})
        self.library = native(self.target, "lib/Power.jar", "energylib", version="1.0")
        self.older_native = native(self.target, "mods/Base-existing.jar", "techbase", version="1.0")

    def _list(self, *, allow=False):
        return inventory(self.src, self.target, BuildOptions(self.manifest, allow_preview=allow))

    def test_exact_source_and_addon_build_in_dependency_order_from_local_jars(self):
        addon = legacy(self.src, "mods/Tools.jar", "techtools", refs=("old/example/TechBase",))
        addon_source = jar(self.root / "Tools.zip", **{"settings.gradle": "rootProject.name='tools'"})
        tool = recipe(addon, addon_source, "techtools", dependencies=(("techbase", "[2.0]"),))
        write_manifest(self.manifest, self.base, tool)
        before = {p: digest(p) for p in self.root.rglob("*") if p.is_file()}
        state = self._list()
        result = plan(state, ["mods/OldBase.jar"])
        self.assertTrue(result.report["ready"], result.problems)
        self.assertEqual(result.report["build_order"], ["mods/OldBase.jar", "mods/Tools.jar"])
        self.assertEqual(set(result.addons), {"mods/Tools.jar"})
        self.assertEqual(result.removed, {"mods/Base-existing.jar": "build/mods/OldBase.jar"})
        self.assertEqual(plan(state, ["mods/Tools.jar"]).report["build_order"], result.report["build_order"])
        calls = []

        def gradle(port, workspace, java, deps, target, timeout):
            calls.append(next(iter(port.ids)))
            self.assertFalse(self.output.exists())
            self.assertEqual(target, "21.1.267")
            self.assertEqual(digest(deps / "Power.jar"), digest(self.library))
            if "techbase" in port.ids:
                self.assertEqual((workspace / "src/main/resources" / self.asset).read_bytes(), b"original asset bytes")
            else:
                self.assertTrue((deps / "techbase.jar").is_file())
            self._fake_gradle(port, workspace, java, deps, target, timeout)

        with patch("mcmodup.native_pack.require_jdk", return_value="unused-java"), patch("mcmodup.source_ports._gradle", gradle):
            stage(result, self.output)
        self.assertEqual(calls, ["techbase", "techtools"])
        self.assertEqual({f.name for f in (self.output / "mods").iterdir()}, {"techbase.jar", "techtools.jar", "Power.jar"})
        report = json.loads((self.output / REPORT_NAME).read_text())
        self.assertEqual(report["native_builds"]["techbase.jar"]["legacy_assets"]["retained"], 1)
        self.assertEqual(report["native_builds"]["techtools.jar"]["gametests"]["passed"], 1)
        self.assertEqual(report["native_builds"]["techtools.jar"]["dependencies_sha256"]["techbase.jar"],
                         digest(self.output / "mods/techbase.jar"))
        self.assertFalse(report["gameplay_verified"], "Source GameTests don't certify whole-pack or client gameplay")
        self.assertEqual({p: digest(p) for p in before}, before)
        self.assertFalse(result.native_builds, "Planning should not mutate its report after a build")

    def test_unported_addon_blocks_even_if_there_is_a_prebuilt_match(self):
        legacy(self.src, "mods/OldAddon.jar", "addon", requirements=("required-after:techbase",))
        native(self.src, "lib/Addon-filename.jar", "addon")
        write_manifest(self.manifest, self.base)
        result = plan(self._list(), ["mods/OldBase.jar"])
        self.assertIn("mods/OldAddon.jar", result.addons)
        self.assertFalse(result.report["ready"])
        self.assertIn("No source recipe for this exact legacy archive", " ".join(result.problems))
        with self.assertRaises(InputError):
            stage(result, self.output)
        self.assertFalse(self.output.exists())

    def test_no_recipe_fallback_or_preview_without_opt_in(self):
        row = {**self.base, "status": "preview", "limitations": ["Unverified rendering"]}
        write_manifest(self.manifest, row)
        self.assertFalse(plan(self._list(), ["mods/OldBase.jar"]).report["ready"])
        state = self._list(allow=True)
        self.assertEqual(state.catalog()["rows"][0]["status"], "preview")
        self.assertTrue(plan(state, ["mods/OldBase.jar"]).report["ready"])
        with zipfile.ZipFile(self.old_base, "a") as old:
            old.writestr("different-bytes.txt", "same version but not the same release")
        self.assertFalse(plan(self._list(allow=True), ["mods/OldBase.jar"]).report["ready"])

    def test_source_digest_zip_safety_and_no_prebuilt_outputs(self):
        executables = [
            ({**self.base, "source_sha256": "0" * 64}, "Source ZIP SHA-256 differs"),
            (self._archive_with("../bad.txt"), "safe relative path"),
            (self._archive_with("gradle/escape", symlink=True), "special file"),
            (self._archive_with("build/libs/techbase.jar"), "prebuilt output"),
        ]
        for row, reason in executables:
            with self.subTest(reason=reason):
                write_manifest(self.manifest, row)
                with patch("mcmodup.native_pack.require_jdk", return_value="unused"), patch("mcmodup.source_ports._gradle") as run:
                    with self.assertRaisesRegex(InputError, reason):
                        stage(plan(self._list(), ["mods/OldBase.jar"]), self.output)
                    run.assert_not_called()
                self.assertFalse(self.output.exists())

    def test_failed_or_vacuous_tests_wrong_metadata_and_missing_asset_do_not_publish(self):
        def build_result(report, *, meta=None, omit_asset=False):
            def fake(port, workspace, *_args):
                self._fake_gradle(port, workspace, *(_args[:4]), report=report, meta=meta, omit_asset=omit_asset)
            return fake

        for report, extra, error in (
            ('<testsuite tests="0"/>', {}, "named NeoForge GameTests"),
            ('<testsuite><testcase name="nativecase"><skipped/></testcase></testsuite>', {}, "skipped"),
            ('<testsuite><testcase name="unrelated"/></testsuite>', {}, "missing or ambiguous"),
            (None, {"meta": "othermod"}, "did not match"),
            (None, {"omit_asset": True}, "missing or changed without a reviewed mapping"),
        ):
            with self.subTest(error=error):
                write_manifest(self.manifest, self.base)
                with patch("mcmodup.native_pack.require_jdk", return_value="unused"), patch(
                        "mcmodup.source_ports._gradle", build_result(report, **extra)):
                    with self.assertRaisesRegex(InputError, error):
                        stage(plan(self._list(), ["mods/OldBase.jar"]), self.output)
                self.assertFalse(self.output.exists())

    def test_converts_missing_locales_without_overwriting_authored_files(self):
        asset_root = "assets/techbase/lang"
        resources = "src/main/resources/"
        existing = b'{"item.techbase.gear":"Authored gear"}'
        with zipfile.ZipFile(self.old_base, "a") as archive:
            archive.writestr(f"{asset_root}/en_US.lang", "item.techbase.gear=Gear\n")
            archive.writestr(f"{asset_root}/de_DE.lang", "# German\nitem.techbase.gear=Zahnrad\ntooltip.techbase.gear=Größe = klein\n")
        with zipfile.ZipFile(self.base_src, "a") as project:
            project.writestr(f"{resources}{asset_root}/en_us.json", existing)
        row = {**self.base, "legacy_sha256": digest(self.old_base), "source_sha256": digest(self.base_src),
               "locale_imports": {asset_root: resources + asset_root}, "asset_map": {
                   f"{asset_root}/en_US.lang": {"path": f"{asset_root}/en_us.json",
                       "sha256": hashlib.sha256(existing).hexdigest()}}}
        write_manifest(self.manifest, row)
        with patch("mcmodup.native_pack.require_jdk", return_value="unused"), patch(
                "mcmodup.source_ports._gradle", self._fake_gradle):
            stage(plan(self._list(), ["mods/OldBase.jar"]), self.output)
        report = json.loads((self.output / REPORT_NAME).read_text())["native_builds"]["techbase.jar"]
        [locale] = report["converted_locales"]
        self.assertEqual(locale["path"], f"{asset_root}/de_de.json")
        self.assertEqual(locale["unsupported_lines"], 0)
        self.assertEqual(report["legacy_assets"]["adapted"], 2)
        with zipfile.ZipFile(self.output / "mods/techbase.jar") as archive:
            self.assertEqual(archive.read(f"{asset_root}/en_us.json"), existing)
            self.assertEqual(json.loads(archive.read(locale["path"])),
                             {"item.techbase.gear": "Zahnrad", "tooltip.techbase.gear": "Größe = klein"})

    def test_requires_legacy_art_outside_assets_even_for_strict_previews(self):
        logo = "assets/techbase/textures/gui/original_logo.png"
        with zipfile.ZipFile(self.old_base, "a") as archive:
            archive.writestr("LegacyLogo.png", b"original logo bytes")
        row = {**self.base, "legacy_sha256": digest(self.old_base), "status": "preview",
               "limitations": ["Client coverage is incomplete"], "require_asset_coverage": True}
        write_manifest(self.manifest, row)
        with patch("mcmodup.native_pack.require_jdk", return_value="unused"), patch(
                "mcmodup.source_ports._gradle", self._fake_gradle):
            with self.assertRaisesRegex(InputError, "LegacyLogo.png"):
                stage(plan(self._list(allow=True), ["mods/OldBase.jar"]), self.output)
            self.assertFalse(self.output.exists())
            row["asset_map"] = {"LegacyLogo.png": {"path": logo}}
            row["import_assets"] = {**row["import_assets"], "LegacyLogo.png": "src/main/resources/" + logo}
            write_manifest(self.manifest, row)
            stage(plan(self._list(allow=True), ["mods/OldBase.jar"]), self.output)
        report = json.loads((self.output / REPORT_NAME).read_text())["native_builds"]["techbase.jar"]
        self.assertEqual(report["legacy_assets"]["original_count"], 2)
        self.assertEqual(report["legacy_assets"]["unmapped"], 0)
        [logo_row] = [row for row in report["legacy_assets"]["mappings"] if row["original"] == "LegacyLogo.png"]
        self.assertEqual(logo_row["status"], "relocated")
        with zipfile.ZipFile(self.output / "mods/techbase.jar") as archive:
            self.assertEqual(archive.read(logo), b"original logo bytes")

    def test_exact_original_jar_is_used_only_for_a_fresh_named_test_report(self):
        checks = {"jar_name": "Original.jar", "report": "build/oracle.xml", "cases": ["sameOriginalData()"]}
        write_manifest(self.manifest, {**self.base, "original_jar_checks": checks})
        original_hash = digest(self.old_base)

        def result(mode):
            def gradle(port, workspace, *args):
                original = workspace / checks["jar_name"]
                self.assertEqual(digest(original), original_hash)
                self._fake_gradle(port, workspace, *args)
                if mode == "missing":
                    return
                cases = '<testcase name="sameOriginalData()"/>'
                if mode == "unrelated":
                    cases = '<testcase name="differentTest()"/>'
                if mode == "skipped":
                    cases = '<testcase name="sameOriginalData()"><skipped/></testcase>'
                (workspace / checks["report"]).write_text(f'<testsuite tests="1">{cases}</testsuite>', encoding="utf-8")
                if mode == "changed":
                    original.write_bytes(b"different JAR")
                if mode == "embedded":
                    with zipfile.ZipFile(workspace / port.artifact, "a") as archive:
                        archive.write(original, "reference/custom-path.dat")
            return gradle

        with patch("mcmodup.native_pack.require_jdk", return_value="unused"):
            for mode, error in (("missing", "safe, bounded output"), ("unrelated", "missing or ambiguous"),
                                ("skipped", "skipped"), ("changed", "Build changed the exact original"),
                                ("embedded", "embeds the test-only original JAR")):
                with self.subTest(mode=mode), patch("mcmodup.source_ports._gradle", result(mode)):
                    with self.assertRaisesRegex(InputError, error):
                        stage(plan(self._list(), ["mods/OldBase.jar"]), self.output)
                    self.assertFalse(self.output.exists())
            with patch("mcmodup.source_ports._gradle", result("pass")):
                stage(plan(self._list(), ["mods/OldBase.jar"]), self.output)
        self.assertEqual(digest(self.old_base), original_hash)
        report = json.loads((self.output / REPORT_NAME).read_text())["native_builds"]["techbase.jar"]["original_jar_checks"]
        self.assertEqual(report["passed"], 1)
        self.assertEqual(report["cases"], checks["cases"])
        self.assertEqual(report["original_sha256"], original_hash)

    def test_plan_is_read_only_and_rejects_option_like_tasks(self):
        write_manifest(self.manifest, self.base)
        proc = run_cli("update-pack", self.src, "--target", self.target, "--source-ports", self.manifest,
                       "--mod", "mods/OldBase.jar", "--json")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(json.loads(proc.stdout)["build_order"], ["mods/OldBase.jar"])
        write_manifest(self.manifest, {**self.base, "tasks": ["runGameTestServer", "--exclude-task"]})
        with self.assertRaisesRegex(InputError, "task paths only"):
            load_ports(BuildOptions(self.manifest))

    def test_detects_build_dependency_cycle_before_executing_projects(self):
        addon = legacy(self.src, "mods/Tools.jar", "techtools", refs=("old/example/TechBase",))
        tool = recipe(addon, self.base_src, "techtools", dependencies=(("techbase", "[2.0]"),))
        base = {**self.base, "dependencies": [{"id": "techtools", "range": "[2.0]"}]}
        write_manifest(self.manifest, base, tool)
        result = plan(self._list(), ["mods/OldBase.jar"])
        self.assertIn("build-time dependency cycle", " ".join(result.problems))
        self.assertFalse(result.report["ready"])

    def _archive_with(self, entry, *, symlink=False):
        archive = self.root / (entry.rsplit("/", 1)[-1] + ".zip")
        info = zipfile.ZipInfo(entry)
        if symlink:
            info.create_system = 3
            info.external_attr = (stat.S_IFLNK | 0o777) << 16
        with zipfile.ZipFile(archive, "w") as files:
            files.writestr(info, "bytes")
        return {**self.base, "source_zip": archive.name, "source_sha256": digest(archive)}

    def _fake_gradle(self, port, workspace, java, deps, target, timeout, *, report=None, meta=None, omit_asset=False):
        name = next(iter(port.ids))
        resources = workspace / "src/main/resources"
        files = {f.relative_to(resources).as_posix(): f.read_bytes() for f in resources.rglob('*') if f.is_file()}
        native(workspace, port.artifact, meta or name, requirements=tuple((d.mod_id, d.version) for d in port.dependencies),
               more={} if omit_asset else files)
        (workspace / port.game_report).write_text(report if report is not None else
            '<testsuite tests="1"><testcase name="nativecase"/></testsuite>', encoding="utf-8")
