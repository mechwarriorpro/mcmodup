import base64
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import tomllib
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[1]
EXAMPLE = ROOT / "examples/retro-ruby"
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jO1QAAAAASUVORK5CYII=")


def run_cli(*args: str | Path) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, "-m", "mcmodup", *map(str, args)], cwd=ROOT,
                          capture_output=True, encoding="utf-8", timeout=10)


def write(root: Path, name: str, content: str) -> Path:
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


class CommandTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "legacy"
        self.out = self.root / "modern"

    def check_success(self, result: subprocess.CompletedProcess) -> str:
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout

    def create_source(self, version: str, mod_id: str = "oldmod") -> Path:
        write(self.source, "src/main/java/Old.java", "class Old {}")
        return write(self.source, "src/main/resources/mcmod.info", json.dumps({"modList": [{
            "modid": mod_id, "name": 'Old "Mod" ✨', "version": "2.5", "mcversion": version,
            "description": "First line\nSecond line", "authorList": ["One", "Two"],
            "requiredMods": ["legacy_dep"], "license": "MIT",
        }]}))

    def test_inspect_example_and_report_locations(self):
        result = json.loads(self.check_success(run_cli("inspect", EXAMPLE, "--json")))
        self.assertEqual(result["source_version"], "1.7.10")
        self.assertEqual(result["item_candidates"], ["ruby"])
        self.assertTrue(any(hint["symbol"].endswith("#registerItem") and "registries" == hint["area"]
                            for hint in result["api_hints"]))
        areas = {row["area"]: row for row in result["findings"]}
        self.assertIn("lifecycle", areas)
        self.assertTrue(areas["registries"]["examples"][0].endswith("RetroRuby.java:13"))

    def test_generate_from_each_source_release(self):
        for version in ("1.5.2", "1.6.4", "1.7.10"):
            with self.subTest(version=version):
                self.create_source(version)
                out = self.out / version
                self.check_success(run_cli("port", self.source, "-o", out))
                metadata = tomllib.loads((out / "src/main/resources/META-INF/neoforge.mods.toml").read_text(encoding="utf-8"))
                self.assertEqual(metadata["license"], "MIT")
                self.assertEqual(metadata["mods"][0]["displayName"], 'Old "Mod" ✨')
                self.assertEqual(metadata["mods"][0]["description"], "First line\nSecond line")
                self.assertEqual(metadata["mods"][0]["authors"], "One, Two")
                deps = {row["modId"]: row["versionRange"] for row in metadata["dependencies"]["oldmod"]}
                self.assertEqual(deps, {"minecraft": "[1.21.1]", "neoforge": "[21.1.267,21.2)"})
                self.assertEqual(list((out / "src/main/java").rglob("*.java")),
                                 [out / "src/main/java/mcmodup/generated/mod_oldmod/PortedMod.java"])
                gradle = (out / "build.gradle").read_text()
                self.assertIn("JavaLanguageVersion.of(21)", gradle)
                self.assertIn("version = '21.1.267'", gradle)
                report = (out / "PORTING.md").read_text(encoding="utf-8")
                self.assertIn(f"Source Minecraft: {version}", report)
                self.assertIn("legacy_dep", report)
                self.assertIn("not a finished port", report)
                wrapper = out / "gradle/wrapper/gradle-wrapper.jar"
                self.assertEqual(hashlib.sha256(wrapper.read_bytes()).hexdigest(),
                                 "423cb469ccc0ecc31f0e4e1c309976198ccb734cdcbb7029d4bda0f18f57e8d9")
                if os.name == "posix":
                    self.assertTrue(os.access(out / "gradlew", os.X_OK))

    def test_item_resources_and_conflicts(self):
        self.create_source("1.7.10", "OldMod")
        lang = write(self.source, "assets/OldMod/lang/en_US.lang", "# comment\nitem.ruby.name=Rubí = a gem\nold.conflict=1\nold.ok=value\nold.escape=\\u1234\n")
        original = lang.read_bytes()
        write(self.source, "mods/OldMod/lang/en_us.lang", "old.conflict=2\n")
        write(self.source, "assets/othermod/lang/en_US.lang", "foreign.key=Do not copy\n")
        path = self.source / "assets/OldMod/textures/items/ruby.png"
        path.parent.mkdir(parents=True)
        path.write_bytes(PNG)
        self.check_success(run_cli("port", self.source, "-o", self.out, "--mod-id", "oldmod",
                                   "--plain-item", "ruby", "--plain-item", "raw_gem"))
        self.assertEqual(lang.read_bytes(), original)
        assets = self.out / "src/main/resources/assets/oldmod"
        translations = json.loads((assets / "lang/en_us.json").read_text(encoding="utf-8"))
        self.assertEqual(translations, {"item.ruby.name": "Rubí = a gem", "item.oldmod.ruby": "Rubí = a gem",
                                        "item.oldmod.raw_gem": "Raw Gem", "old.ok": "value"})
        self.assertEqual((assets / "textures/item/ruby.png").read_bytes(), PNG)
        for item, expected in (("ruby", "oldmod:item/ruby"), ("raw_gem", "minecraft:item/paper")):
            model = json.loads((assets / f"models/item/{item}.json").read_text())
            self.assertEqual(model["textures"]["layer0"], expected)
        java = next((self.out / "src/main/java").rglob("*.java")).read_text()
        self.assertIn('ITEMS.registerSimpleItem("ruby");', java)
        self.assertIn("ITEMS.register(modBus);", java)
        report = (self.out / "PORTING.md").read_text(encoding="utf-8")
        self.assertIn("1 conflicting translation key", report)
        self.assertIn("Mod ID changed", report)
        self.assertIn("placeholder texture: raw_gem", report)

    def test_unversioned_mcp_tree_requires_an_explicit_version(self):
        self.source = self.root / "oldmod-1.5.2"
        write(self.source, "src/mod_Old.java", "public class mod_Old extends BaseMod {}")
        write(self.source, "lang/en_US.lang", "old.key=Old name\n")
        result = json.loads(self.check_success(run_cli("inspect", self.source, "--json")))
        self.assertIsNone(result["source_version"])
        self.assertEqual(result["version_evidence"][0]["kind"], "filename hint")
        fail = run_cli("port", self.source, "-o", self.out, "--mod-id", "oldmod")
        self.assertIn("--source-version", fail.stderr)
        self.assertFalse(self.out.exists())
        self.check_success(run_cli("port", self.source, "-o", self.out, "--mod-id", "oldmod", "--source-version", "1.5.2"))
        self.assertTrue((self.out / "src/main/resources/assets/oldmod/lang/en_us.json").is_file())

    def test_gradle_version_hint_and_conflicts(self):
        write(self.source, "Old.java", '/* @Mod(modid="wrong", acceptedMinecraftVersions="[1.6.4]") */ class Old {}')
        write(self.source, "build.gradle", 'minecraft {\n    version = "1.7.10-10.13.4.1614-1.7.10"\n}\n')
        result = json.loads(self.check_success(run_cli("inspect", self.source, "--json")))
        self.assertEqual(result["source_version"], "1.7.10")
        self.assertEqual(result["mods"], [])
        self.create_source("1.6.4")
        result = json.loads(self.check_success(run_cli("inspect", self.source, "--json")))
        self.assertIsNone(result["source_version"])
        self.assertIn("Conflicting declared", "\n".join(result["warnings"]))
        self.check_success(run_cli("port", self.source, "-o", self.out, "--source-version", "1.7.10"))
        self.assertIn("overrides conflicting", (self.out / "PORTING.md").read_text(encoding="utf-8"))

    def test_compiled_jar_is_inspected_not_repacked(self):
        jar = self.root / "legacy-1.6.4.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("mcmod.info", '[{"modid":"oldmod","mcversion":"1.6.4"}]')
            archive.writestr("Old.class", b"\xca\xfe\xba\xbe")
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nFMLCorePlugin: legacy.Plugin\n")
            archive.writestr("../outside.java", "not source to read")
            link = zipfile.ZipInfo("Link.java")
            link.create_system = 3
            link.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(link, "../outside.java")
        original = jar.read_bytes()
        report = json.loads(self.check_success(run_cli("inspect", jar, "--json")))
        self.assertEqual(report["source_version"], "1.6.4")
        self.assertEqual(report["class_files"], 1)
        self.assertEqual(report["java_files"], 0)
        self.assertEqual(report["findings"][0]["area"], "coremod")
        self.assertIn("unsafe archive", "\n".join(report["warnings"]))
        error = run_cli("port", jar, "-o", self.out)
        self.assertNotEqual(error.returncode, 0)
        self.assertIn("requires readable Java source", error.stderr)
        self.assertEqual(jar.read_bytes(), original)
        self.assertFalse((self.root / "outside.java").exists())
        self.assertFalse(self.out.exists())

    def test_source_zip_with_legacy_annotation(self):
        saved = self.root / "source.zip"
        with zipfile.ZipFile(saved, "w") as archive:
            archive.writestr("oldrepo/src/Mod.java", '@Mod(modid="ancient", name="Ancient", acceptedMinecraftVersions="[1.6.4]") class Old {}')
            archive.writestr("oldrepo/resources/mods/ancient/lang/en_US.lang", "old.key=Old name\n")
        self.check_success(run_cli("port", saved, "-o", self.out))
        self.assertIn('modId="ancient"', (self.out / "src/main/resources/META-INF/neoforge.mods.toml").read_text())
        self.assertTrue((self.out / "src/main/resources/assets/ancient/lang/en_us.json").is_file())

    def test_guards_existing_output_nested_output_and_unsafe_id(self):
        self.create_source("1.7.10")
        write(self.out, "sentinel.txt", "do not overwrite")
        for out, args, error in ((self.out, [], "already exists"),
                                  (self.source / "generated", [], "outside the input"),
                                  (self.root / "new", ["--mod-id", "../bad"], "valid --mod-id"),
                                  (self.root / "new", ["--plain-item", "x/y"], "Invalid --plain-item"),
                                  (self.root / "new", ["--mod-version", "x'"], "Invalid --mod-version")):
            with self.subTest(args=args, out=out):
                reply = run_cli("port", self.source, "-o", out, *args)
                self.assertEqual(reply.returncode, 1)
                self.assertIn(error, reply.stderr)
        self.assertEqual((self.out / "sentinel.txt").read_text(), "do not overwrite")
        self.assertFalse((self.source / "generated").exists())
        self.assertFalse((self.root / "new").exists())

    def test_requires_selection_for_multi_mod_inputs(self):
        meta = self.create_source("1.7.10")
        with meta.open(encoding="utf-8") as handle:
            data = json.load(handle)
        data["modList"].append({"modid": "anothermod"})
        meta.write_text(json.dumps(data), encoding="utf-8")
        write(self.source, "assets/anothermod/lang/en_US.lang", "other.key=Other mod\n")
        self.assertIn("Multiple legacy mod IDs", run_cli("port", self.source, "-o", self.out).stderr)
        self.check_success(run_cli("port", self.source, "-o", self.out, "--mod-id", "oldmod"))
        self.assertFalse((self.out / "src/main/resources/assets/anothermod").exists())

    def test_invalid_zip_has_a_friendly_error(self):
        archive = write(self.root, "bad.jar", "not a zip")
        result = run_cli("inspect", archive)
        self.assertEqual(result.returncode, 1)
        self.assertIn("Not a readable ZIP/JAR archive", result.stderr)
        self.assertNotIn("Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main()
