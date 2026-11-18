import hashlib
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import tempfile
from threading import Thread
import unittest
import urllib.error
import urllib.request

from mcmodup.inputs import InputError
from mcmodup.native_pack import REPORT_NAME, inventory, matches, plan, stage
from mcmodup.pack_ui import handler
from test_cli import run_cli
from test_pack import fixture_class, jar


def setup_platform(root, version="1.21.1", neoforge="21.1.267"):
    (root / "mods").mkdir(parents=True, exist_ok=True)
    jar(root / "bin/minecraft.jar", **{"version.json": json.dumps({"id": version})})
    if version == "1.21.1":
        jar(root / f"bin/neoforge-{neoforge}-universal.jar", **{
            "net/neoforged/neoforge/common/NeoForge.class": fixture_class("net/neoforged/neoforge/common/NeoForge"),
        })
    else:
        jar(root / f"bin/forge-{version}-universal.jar", **{
            "net/minecraftforge/common/MinecraftForge.class": fixture_class("net/minecraftforge/common/MinecraftForge"),
        })


def legacy(root, file, modid, *, name=None, requirements=(), refs=(), minecraft="", owner=None):
    owner = owner or f"legacy/demo/{modid.title().replace('|', 'Sub')}"
    return jar(root / file, **{
        f"{owner}.class": fixture_class(owner, refs=refs, annotation={
            "modid": modid, "name": name or modid, "version": "1.0", "dependencies": ";".join(requirements),
        }),
        "mcmod.info": json.dumps([{"modid": modid, "name": name or modid, "version": "1.0", "mcversion": minecraft}]),
    })


def native(root, file, modid, *, version="2.0", requirements=(), refs=(), mc="[1.21.1]", neoforge="[21.1,21.2)", more=None):
    meta = f'''modLoader="javafml"
loaderVersion="[4,)"
license="Test fixture"
[[mods]]
modId="{modid}"
version="{version}"
'''
    for row in (("minecraft", mc), ("neoforge", neoforge), *requirements):
        dep, spec = row[:2]
        kind = row[2] if len(row) > 2 else "required"
        meta += f'''[[dependencies.{modid}]]
modId="{dep}"
type="{kind}"
versionRange="{spec}"
'''
    owner = f"modern/demo/{modid.title()}"
    return jar(root / file, **{"META-INF/neoforge.mods.toml": meta, f"{owner}.class": fixture_class(owner, refs=refs), **(more or {})})


class NativePackTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.src, self.target, self.out = (self.root / x for x in ("legacy", "modern", "staged"))
        setup_platform(self.src, "1.6.4")
        setup_platform(self.target)

    def working_stack(self):
        legacy(self.src, "mods/Tech.jar", "OldTech", name="Tech Base", owner="tech/old/Base")
        legacy(self.src, "mods/addons/Tools.zip", "OldTools", name="Tech Tools", refs=("tech/old/Base",))
        legacy(self.src, "mods/Minechem.jar", "minechem", name="Minechem")
        legacy(self.src, "mods/1.7.10/Other.jar", "othermod", name="Independent mod")
        (self.src / "mcmodup-updates.json").write_text('{"oldtech":"techbase", "oldtools":"techtools"}', encoding="utf-8")
        tech = native(self.src, "lib/Tech-1.21.1.jar", "techbase", requirements=(("energylib", "[2.1,3)"),))
        addon = native(self.src, "bin/Tools-1.21.1.jar", "techtools", requirements=(("techbase", "[2,3)"),))
        energy = native(self.target, "lib/Energy.jar", "energylib", version="2.1", requirements=(("commonlib", "[2.0]"),))
        common = native(self.target, "libraries/lib/Common.jar", "commonlib")
        installed = native(self.target, "mods/Quality.jar", "qualitymod")
        (self.target / "mods/old.jar.disabled").write_bytes(b"not enabled")
        return tech, addon, energy, common, installed

    def test_stage_whole_selection_transitive_deps_and_addons(self):
        files = self.working_stack()
        before = {p: p.read_bytes() for base in (self.src, self.target) for p in base.rglob("*") if p.is_file()}
        result = run_cli("update-pack", self.src, "--target", self.target, "--mod", "mods/Tech.jar", "-o", self.out, "--json")
        self.assertEqual(result.returncode, 0, result.stderr or result.stdout)
        report = json.loads((self.out / REPORT_NAME).read_text())
        self.assertTrue(report["ready"])
        self.assertFalse(report["connector_required"])
        self.assertEqual(report["source_bin"]["minecraft"][0]["version"], "1.6.4")
        self.assertIn("mods/addons/Tools.zip", report["auto_addons"])
        actions = {entry["file"]: entry["action"] for entry in report["files"]}
        self.assertEqual(actions["mods/Energy.jar"], "dependency")
        self.assertEqual(actions["mods/Common.jar"], "dependency")
        self.assertEqual(actions["mods/Tools-1.21.1.jar"], "update")
        self.assertEqual(actions["mods/Quality.jar"], "keep")
        for file in files:
            self.assertEqual((self.out / "mods" / file.name).read_bytes(), file.read_bytes())
            row = next(row for row in report["files"] if row["file"] == f"mods/{file.name}")
            self.assertEqual(row["sha256"], hashlib.sha256(file.read_bytes()).hexdigest())
        self.assertEqual({p: p.read_bytes() for p in before}, before)
        self.assertFalse((self.out / "bin").exists())
        self.assertEqual((self.out / "mods/old.jar.disabled").read_bytes(), b"not enabled")

    def test_incompatible_addon_blocks_parent_and_partial_is_not_full(self):
        _, addon, *_ = self.working_stack()
        addon.unlink()
        state = inventory(self.src, self.target)
        catalog = {row["id"]: row for row in state.catalog()["rows"]}
        self.assertEqual(catalog["mods/Minechem.jar"]["status"], "partial")
        update = plan(state, ["mods/Tech.jar"])
        self.assertFalse(update.report["ready"])
        self.assertIn("techtools", " ".join(update.problems))
        with self.assertRaises(InputError):
            stage(update, self.out)
        self.assertFalse(self.out.exists())

    def test_chooses_only_compatible_local_dependency_and_preserves_existing_dependents(self):
        self.working_stack()
        old = native(self.target, "mods/Energy-1.0.jar", "energylib", version="1.0")
        update = plan(inventory(self.src, self.target), ["mods/Tech.jar"])
        self.assertTrue(update.report["ready"], update.problems)
        self.assertTrue(any(name.endswith(old.name) for name in update.removed))
        native(self.target, "mods/Meter.jar", "energymeter", requirements=(("energylib", "[1.0,2)"),))
        update = plan(inventory(self.src, self.target), ["mods/Tech.jar"])
        self.assertFalse(update.report["ready"])
        self.assertIn("[1.0,2)", " ".join(update.problems))

    def test_ambiguous_and_connector_only_candidates_do_not_stage(self):
        self.working_stack()
        alt = native(self.target, "bin/AltEnergy.jar", "energylib", version="2.5")
        update = plan(inventory(self.src, self.target), ["mods/Tech.jar"])
        self.assertTrue(any("Multiple compatible" in issue for issue in update.problems))
        alt.unlink()
        native(self.target, "lib/Energy.jar", "energylib", version="2.1", refs=("legacyconnector/api/Bootstrap",))
        native(self.target, "lib/EnergyCache.jar", "energylib", version="2.1", more={"META-INF/legacyconnector/provenance.json": "{}"})
        native(self.target, "lib/EnergyDeclared.jar", "energylib", version="2.1", requirements=(("legacyconnector", "[0.5,)", "optional"),))
        state = inventory(self.src, self.target)
        update = plan(state, ["mods/Tech.jar"])
        self.assertTrue(any("energylib" in issue for issue in update.problems))
        self.assertTrue(any("refers to Legacy Connector" in warn for warn in state.warnings))
        self.assertTrue(any("cached derivatives" in warn for warn in state.warnings))
        self.assertTrue(any("declares a dependency" in warn for warn in state.warnings))
        original = native(self.target, "mods/ActiveConnector.jar", "alienruntime", more={"META-INF/legacyconnector/legacyconnector-runtime.jar": b""})
        self.assertTrue(original.exists())
        self.assertIn("Legacy Connector", " ".join(inventory(self.src, self.target).target_issues))

    def test_mixed_target_replaces_selected_legacy_and_keeps_disabled_files(self):
        legacy(self.target, "mods/Tech.jar", "techbase", minecraft="1.7.10")
        native(self.target, "bin/Tech-new.jar", "techbase")
        legacy(self.target, "mods/Tools.jar", "techtools", minecraft="1.7.10", requirements=("required-after:techbase",))
        native(self.target, "lib/Tools-new.jar", "techtools", requirements=(("techbase", "[2,3)"),))
        update = plan(inventory(self.target, self.target), ["mods/Tech.jar"])
        self.assertTrue(update.report["ready"], update.problems)
        self.assertEqual(set(update.removed), {"mods/Tech.jar", "mods/Tools.jar"})
        addon_only = plan(inventory(self.target, self.target), ["mods/Tools.jar"])
        self.assertTrue(addon_only.report["ready"], addon_only.problems)
        self.assertEqual(set(addon_only.removed), set(update.removed))
        stage(update, self.out)
        self.assertEqual({p.name for p in (self.out / "mods").iterdir()}, {"Tech-new.jar", "Tools-new.jar"})
        self.assertTrue((self.target / "mods/Tech.jar").exists())

    def test_unsupported_platform_and_changed_archive_leave_no_output(self):
        self.working_stack()
        version_file = self.target / "bin/minecraft.jar"
        jar(version_file, **{"version.json": '{"id":"1.21.4"}'})
        with self.assertRaisesRegex(InputError, "Target bin/"):
            inventory(self.src, self.target)
        setup_platform(self.target)
        update = plan(inventory(self.src, self.target), ["mods/Tech.jar"])
        (self.src / "lib/Tech-1.21.1.jar").write_bytes(b"changed")
        with self.assertRaisesRegex(InputError, "changed during staging"):
            stage(update, self.out)
        self.assertFalse(self.out.exists())
        with self.assertRaisesRegex(InputError, "outside"):
            stage(update, self.target / "newmods")

    def test_connector_in_embedded_jar_and_partial_alias_are_not_replacements(self):
        legacy(self.src, "mods/Example.jar", "oldexample")
        legacy(self.src, "mods/Other.jar", "oldother")
        (self.src / "mcmodup-updates.json").write_text('{"oldexample":"newexample","oldother":"newother"}')
        embedded = jar(self.root / "embedded.jar", **{"META-INF/legacyconnector/provenance.json": "{}"})
        native(self.target, "lib/Example.jar", "newexample", more={"META-INF/jarjar/embedded.jar": embedded.read_bytes()})
        native(self.target, "mods/PartialOther.jar", "newother", more={"META-INF/MCModUp-NOTICE.txt": "PARTIAL PORT"})
        state = inventory(self.src, self.target)
        self.assertTrue(any("Could not verify embedded" in warning for warning in state.warnings))
        for archive in ("mods/Example.jar", "mods/Other.jar"):
            self.assertFalse(plan(state, [archive]).report["ready"])

    def test_optional_and_incompatible_dependencies(self):
        self.working_stack()
        native(self.src, "lib/Tech-1.21.1.jar", "techbase", requirements=(
            ("suggested", "[2,)", "optional"), ("qualitymod", "[2.0]", "incompatible"),
        ))
        native(self.src, "lib/Suggested.jar", "suggested")
        update = plan(inventory(self.src, self.target), ["mods/Tech.jar"])
        self.assertFalse(any("suggested" in mod.ids for mod in update.mods))
        self.assertTrue(any("incompatibility" in issue for issue in update.problems))

    def test_ui_catalog_plan_and_stage(self):
        self.working_stack()
        bound = ThreadingHTTPServer(("127.0.0.1", 0), handler(inventory(self.src, self.target), self.out))
        thread = Thread(target=bound.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(bound.server_close)
        self.addCleanup(bound.shutdown)
        url = f"http://127.0.0.1:{bound.server_port}"
        with urllib.request.urlopen(url, timeout=5) as r:
            self.assertIn(b"Mods to update", r.read())
        with urllib.request.urlopen(url + "/api/catalog", timeout=5) as r:
            listing = json.load(r)
        self.assertEqual(sum(row["status"] == "available" for row in listing["rows"]), 2)
        body = json.dumps({"selected": ["mods/Tech.jar"]}).encode()
        headers = {"Content-Type": "application/json"}
        with self.assertRaises(urllib.error.HTTPError) as refused:
            urllib.request.urlopen(urllib.request.Request(url + "/api/stage", body, headers), timeout=5)
        self.assertEqual(refused.exception.code, 403)
        self.assertFalse(self.out.exists())
        with self.assertRaises(urllib.error.HTTPError) as refused:
            urllib.request.urlopen(urllib.request.Request(url + "/api/catalog", headers={"Host": "untrusted.local"}), timeout=5)
        self.assertEqual(refused.exception.code, 403)
        headers["X-Mcmodup-Token"] = listing["token"]
        with urllib.request.urlopen(urllib.request.Request(url + "/api/plan", body, headers), timeout=5) as r:
            self.assertTrue(json.load(r)["ready"])
        self.assertFalse(self.out.exists())
        with urllib.request.urlopen(urllib.request.Request(url + "/api/stage", body, headers), timeout=5) as r:
            self.assertEqual(Path(json.load(r)["output"]), self.out)
        self.assertTrue((self.out / "mods/Energy.jar").is_file())
        self.assertTrue((self.target / "mods/Quality.jar").is_file())

    def test_ranges(self):
        self.assertTrue(matches("21.1.267", "[21.1,21.2)"))
        self.assertTrue(matches("2.1.4+1.21.1", "[2.0,3)"))
        self.assertTrue(matches("1.21.1", "[1.21.1]"))
        self.assertFalse(matches("21.2", "[21.1,21.2)"))
        self.assertFalse(matches("2.1", "(,2],[3,4)"))
