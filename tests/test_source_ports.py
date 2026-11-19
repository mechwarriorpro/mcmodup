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
from mcmodup.source_ports import BuildOptions, _gradle, digest, load_ports
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

    def test_standalone_preview_is_explicit_selection_only_without_method_imports(self):
        self.older_native.unlink()
        row = {**self.base, "status": "preview", "standalone_preview": True, "limitations": ["Only one item"]}
        write_manifest(self.manifest, row)
        label = "mods/OldBase.jar"
        self.assertFalse(plan(self._list(), [label], output_pack=True).report["ready"])
        allowed = self._list(allow=True)
        self.assertTrue(allowed.recipes[label].partial)
        self.assertFalse(allowed.providers.get("techbase"))
        self.assertIn("fresh --output-pack", " ".join(plan(allowed, [label]).problems))
        self.assertTrue(plan(allowed, [label], output_pack=True).report["ready"])
        other = legacy(self.src, "mods/Tools.jar", "addon", requirements=("required-after:techbase",))
        addon = recipe(other, self.base_src, "addon", dependencies=(("techbase", "[2.0]"),))
        write_manifest(self.manifest, row, addon)
        blocked = plan(self._list(allow=True), [label], output_pack=True)
        self.assertFalse(blocked.report["ready"])
        self.assertIn("preview cannot replace its legacy provider", " ".join(blocked.problems))
        with self.assertRaises(InputError):
            stage(blocked, self.output)
        self.assertFalse(self.output.exists())
        for invalid in (self.base | {"standalone_preview": True}, row | {"standalone_preview": "true"}):
            write_manifest(self.manifest, invalid)
            with self.assertRaisesRegex(InputError, "standalone_preview must be a boolean"):
                load_ports(BuildOptions(self.manifest, allow_preview=True))

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

    def test_gradle_failure_preserves_gametest_assertion_beyond_log_tail(self):
        write_manifest(self.manifest, self.base)
        port = load_ports(BuildOptions(self.manifest))[digest(self.old_base)]
        workspace = self.root / "gradle-project"
        wrapper = workspace / "gradle/wrapper/gradle-wrapper.jar"
        wrapper.parent.mkdir(parents=True)
        wrapper.touch()
        wrapper.with_suffix(".properties").write_text("distributionSha256Sum=" + "a" * 64 + "\n", encoding="utf-8")
        report = workspace / port.game_report
        report.parent.mkdir()

        def run_stub(*args, **kwargs):
            report.write_text('<testsuite><testcase name="nativecase"><failure message="The turtle did not clear gas"/>'
                              '</testcase></testsuite>', encoding="utf-8")
            kwargs["stdout"].write(b"first failure was in the GameTest report\n" + b"shutdown\n" * 2000 + b"BUILD FAILED\n")
            process = runner.return_value.__enter__.return_value
            process.wait.return_value = 1
            return runner.return_value

        with patch("mcmodup.source_ports.subprocess.Popen") as runner:
            runner.side_effect = run_stub
            with self.assertRaisesRegex(InputError, "nativecase: The turtle did not clear gas") as error:
                _gradle(port, workspace, "java", self.root, "21.1.267", 60)
        self.assertIn("BUILD FAILED", str(error.exception))
        self.assertNotIn("first failure was", str(error.exception))

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

    def test_multiple_manifests_resolve_sources_locally_and_reject_duplicate_sha(self):
        addon = legacy(self.src, "mods/Tools.jar", "techtools", refs=("old/example/TechBase",))
        other = self.root / "other"
        other.mkdir()
        addon_zip = jar(other / self.base_src.name, **{"settings.gradle": "rootProject.name='tools'"})
        tool = recipe(addon, addon_zip, "techtools", dependencies=(("techbase", "[2.0]"),))
        write_manifest(self.manifest, self.base)
        second = write_manifest(other / "tool.json", tool)
        args = ("update-pack", self.src, "--target", self.target, "--source-ports", self.manifest,
                "--source-ports", second, "--mod", "mods/OldBase.jar", "--mod", "mods/Tools.jar", "--json")
        proc = run_cli(*args)
        self.assertEqual(proc.returncode, 0, proc.stderr or proc.stdout)
        self.assertEqual(json.loads(proc.stdout)["build_order"], ["mods/OldBase.jar", "mods/Tools.jar"])
        ports = load_ports(BuildOptions(self.manifest, additional_manifests=(second,)))
        self.assertEqual(ports[digest(self.old_base)].archive, self.base_src)
        self.assertEqual(ports[digest(addon)].archive, addon_zip)
        write_manifest(second, {**tool, "legacy_sha256": self.base["legacy_sha256"]})
        proc = run_cli(*args)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("Multiple recipes for the same legacy SHA-256", proc.stderr)

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
