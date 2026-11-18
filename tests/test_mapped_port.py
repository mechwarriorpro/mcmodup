import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

from mcmodup.classfiles import Symbol, read_class
from mcmodup.decompile import SPECIALSOURCE_ENTRYPOINT, VINEFLOWER_ENTRYPOINT
from mcmodup.inputs import InputError
from mcmodup.mcp import McpConfig
from test_cli import run_cli
from test_pack import fixture_class, jar


CLIENT = """CL: a net/minecraft/src/Game
FD: a/x net/minecraft/src/Game/field_123_a
MD: a/a (I)I net/minecraft/src/Game/func_123_a (I)I
MD: a/a ([La;)[La; net/minecraft/src/Game/func_124_a ([Lnet/minecraft/src/Game;)[Lnet/minecraft/src/Game;
"""
SERVER = CLIENT.replace("CL: a ", "CL: b ").replace(" a/", " b/").replace("[La;", "[Lb;")
METHODS = "searge,name,side,desc\nfunc_123_a,count,0,\nfunc_123_a,serverCount,1,\nfunc_124_a,copy,2,\n"
FIELDS = "searge,name,side,desc\nfield_123_a,total,2,\n"


def mcp_files(version, *, joined=False, packages=False):
    result = {"version.cfg": f"[VERSION]\nMCPVersion = test-{version}\nClientVersion = {version}\nServerVersion = {version}\n",
              "methods.csv": METHODS, "fields.csv": FIELDS}
    result.update({"joined.srg": CLIENT} if joined else {"client.srg": CLIENT, "server.srg": SERVER})
    if packages:
        result["packages.csv"] = "class,package\nGame,net/minecraft/example\n"
    return result


class MappedPortTests(unittest.TestCase):
    def setUp(self):
        env = tempfile.TemporaryDirectory()
        self.addCleanup(env.cleanup)
        self.root = Path(env.name)
        self.pack = self.root / "instance"
        self.mods = self.pack / "mods"
        self.catalog = self.root / "mcp"
        self.mcp = self.catalog / "1.7.10.zip"
        jar(self.mcp, **{"mcp/conf/" + key: data for key, data in mcp_files("1.7.10", joined=True).items()})

    def mod(self, *, file="Example.jar", mc="1.7.10", version="2.3.4", **classes):
        metadata = {"modid": "example", "name": "Example", "version": version,
                    "mcversion": mc, "requiredMods": ["Other@[3.0,)"]}
        main = {"demo/Main.class": fixture_class("demo/Main", refs=("cpw/mods/fml/common/registry/GameRegistry",))}
        return jar(self.mods / file, **{"mcmod.info": json.dumps([metadata]), **(classes or main)})

    def report(self, *, filename="Example.jar", **fields):
        path = self.root / "probe.json"
        row = {"jar": filename, "mc": "1.7.10", "result": "links", "unresolved": 0,
               "mods": [{"id": "Example", "name": "Example", "version": "2.3.4"}]}
        path.write_text(json.dumps([row | fields]), encoding="utf-8")
        return path

    def cli_plan(self, *options):
        return run_cli("plan-port", self.pack, "--mod", "Example.jar", *options, "--json")

    def test_exact_catalog_entry_and_split_aliases(self):
        for version in ("1.5.2", "1.6.4"):
            folder = self.catalog / version / "conf"
            folder.mkdir(parents=True)
            for name, text in mcp_files(version, packages=True).items():
                (folder / name).write_text(text)
            client = McpConfig.load(self.catalog, version, "client")
            server = McpConfig.load(self.catalog, version, "server")
            self.assertEqual(client.srg_file, "client.srg")
            self.assertEqual(server.srg_file, "server.srg")
            self.assertNotIn("a", server.names.classes)
            self.assertEqual(client.names.spellings(Symbol.parse("a#a(I)I"))[-1].key, "net/minecraft/example/Game#count(I)I")
            self.assertEqual(server.names.spellings(Symbol.parse("b#a(I)I"))[-1].key, "net/minecraft/example/Game#serverCount(I)I")
            self.assertEqual(client.names.spellings(Symbol.parse("a#a([La;)[La;"))[-1].key,
                             "net/minecraft/example/Game#copy([Lnet/minecraft/example/Game;)[Lnet/minecraft/example/Game;")
            self.assertEqual(client.evidence()["sha256"]["fields.csv"], hashlib.sha256(FIELDS.encode()).hexdigest())
        joined = McpConfig.load(self.catalog, "1.7.10", "server")
        self.assertEqual(joined.srg_file, "mcp/conf/joined.srg")
        self.assertEqual(joined.names.method_aliases["func_123_a"], "serverCount")
        self.assertRaisesRegex(InputError, "not exact Minecraft 1.5.2", McpConfig.load, self.mcp, "1.5.2", "client")
        self.assertRaisesRegex(InputError, "no other version", McpConfig.load, self.root, "1.5.2", "client")

    def test_standalone_original_does_not_need_an_additional_selector(self):
        mod = self.mod()
        result = run_cli("plan-port", mod, "--json")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["input"]["archive"], "Example.jar")
        self.assertIn("Select an original archive", run_cli("plan-port", self.pack).stderr)

    def test_no_filename_fallback_or_override_of_declared_version(self):
        self.mod()
        other = self.mod(file="Old-1.7.10.jar", mc="")
        result = run_cli("plan-port", self.pack, "--mod", other.name, "--mcp", self.catalog)
        self.assertEqual(result.returncode, 1)
        self.assertIn("No exact Minecraft version", result.stderr)
        result = run_cli("plan-port", self.pack, "--mod", other.name, "--mcp", self.catalog, "--source-version", "1.7.10", "--json")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["static"]["version_evidence"][-1]["kind"], "pack assumption")
        result = self.cli_plan("--source-version", "1.6.4", "--mcp", self.mcp)
        self.assertEqual(result.returncode, 1)
        self.assertIn("Conflicting Minecraft version evidence", result.stderr)

    def test_static_linkage_and_gameplay_are_separate(self):
        mod = self.mod(version="1.7.10-2.3.4")
        jar(self.mods / "Other.jar", **{"other/Main.class": fixture_class("other/Main"),
                                        "mcmod.info": '[{"modid":"other","version":"3.1"}]'})
        report = self.report(synthesizedClasses=2, synthesizedMembers=1, stubbed=["Owner.fake()V"],
                             optional=["Other.tryThis()V"], verifyFailures=1,
                             verify={"findings": 5, "groups": [{"problem": "bad owner", "sites": 5, "examples": ["demo.Main.run"]}]},
                             assets={"missingModels": 1, "examples": ["assets/example/test.json"]})
        notes = self.root / "mods.json"
        example = {"name": "Example", "version": "2.3.4", "mc": "1.7.10", "source": "client", "status": "perfect", "notes": "UI opened; saving not checked."}
        notes.write_text(json.dumps({"mods": [example, example | {"source": "probe", "notes": "unrelated"},
                                              example | {"version": "2.4.0", "notes": "wrong version"}]}), encoding="utf-8")
        proc = self.cli_plan("--mcp", self.catalog, "--connector-report", report, "--connector-gameplay", notes)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        plan = json.loads(proc.stdout)
        self.assertEqual(plan["input"]["sha256"], hashlib.sha256(mod.read_bytes()).hexdigest())
        self.assertEqual(plan["mcp"]["minecraft"], "1.7.10")
        self.assertEqual(plan["native"], {"gameplay_verified": False, "parity_verified": False})
        self.assertFalse(plan["connector"]["linkage"]["binary_identity_verified"])
        self.assertEqual(plan["connector"]["linkage"]["record"]["result"], "links")
        self.assertEqual(plan["connector"]["gameplay"]["observations"], [example])
        self.assertEqual([row["priority"] for row in plan["work"]], sorted(row["priority"] for row in plan["work"]))
        rows = {row["id"]: row for row in plan["work"]}
        self.assertEqual(rows["provider-order"]["examples"], ["mods/Other.jar"])
        self.assertEqual(rows["connector-verifier"]["examples"], ["bad owner (demo.Main.run)"])
        self.assertEqual(rows["connector-placeholders"]["examples"], ["Owner.fake()V"])
        self.assertEqual(rows["connector-optional"]["priority"], "P2")
        self.assertIn("connector-assets", rows)
        self.assertIn("native-registries", rows)

    def test_mismatched_ambiguous_and_unmeasured_checkjar(self):
        self.mod()
        for fields, expected in [({"mc": "1.6.4"}, "does not match selected Minecraft"),
                                 ({"mods": [{"id": "example", "version": "2.3.5"}]}, "does not match local release"),
                                 ({"missing": "bad"}, "expected a string list"),
                                 ({"filename": "Other.jar"}, "no record for selected JAR")]:
            proc = self.cli_plan("--connector-report", self.report(**fields))
            self.assertEqual(proc.returncode, 1)
            self.assertIn(expected, proc.stderr)
        report = self.report()
        report.write_text(json.dumps(json.loads(report.read_text()) * 2))
        self.assertIn("ambiguous records", self.cli_plan("--connector-report", report).stderr)
        plan = json.loads(self.cli_plan().stdout)
        self.assertIsNone(plan["connector"]["linkage"])
        self.assertIn("required-dependencies", {row["id"] for row in plan["work"]})
        self.assertFalse(plan["native"]["gameplay_verified"])

    def test_remap_rules_include_srg_overrides_but_not_unrelated_classes(self):
        mcp = McpConfig.load(self.catalog, "1.7.10", "client")
        game = "net/minecraft/example/Game"
        addon = "demo/Main"
        classes = {c.owner: c for c in [read_class(fixture_class(addon, parent="api/Parent", refs=(f"{game}#field_123_a:I",),
                                                                methods=(("func_123_a", "(I)I", 1),))),
                                        read_class(fixture_class("demo/Other", methods=(("func_123_a", "(I)I", 1),)))]}
        parent = read_class(fixture_class("api/Parent", parent=game))
        output = self.root / "map.srg"
        mcp.remap_file(output, classes, {info.owner: info.parents for info in [*classes.values(), parent]})
        text = output.read_text()
        self.assertIn("MD: a/a (I)I net/minecraft/src/Game/count (I)I\n", text)
        self.assertIn(f"FD: {game}/field_123_a {game}/total\n", text)
        self.assertIn(f"MD: {addon}/func_123_a (I)I {addon}/count (I)I\n", text)
        self.assertNotIn("demo/Other/func_123_a", text)

    def test_local_runner_is_class_only_and_emits_a_checklist(self):
        mod = self.mod(**{"demo/Main.class": fixture_class("demo/Main", parent="other/Provider", refs=("a#a(I)I",),
                                                           methods=(("func_123_a", "(I)I", 1),)),
                          "demo/Main$Nested.class": fixture_class("demo/Main$Nested"),
                          "demo/Unrelated.class": fixture_class("demo/Unrelated"), "assets/state.json": "{}"})
        jar(self.mods / "Library.jar", **{"other/Provider.class": fixture_class("other/Provider", parent="net/minecraft/src/Game"),
                                          "mcmod.info": '[{"modid":"library","mcversion":"1.7.10"}]'})
        original = mod.read_bytes()
        tool = jar(self.root / "ss.jar", **{"META-INF/MANIFEST.MF": f"Main-Class: {SPECIALSOURCE_ENTRYPOINT}\n\n"})
        flower = jar(self.root / "vf.jar", **{"META-INF/MANIFEST.MF": f"Main-Class: {VINEFLOWER_ENTRYPOINT}\n\n"})
        runner = self.root / "java"
        runner.write_text(f"#!{sys.executable}\n" + '''import os, pathlib, shutil, sys, zipfile
assert 'JAVA_TOOL_OPTIONS' not in os.environ
args = sys.argv
if '--srg-in' in args:
    input = args[args.index('--in-jar') + 1]
    names = pathlib.Path(args[args.index('--srg-in') + 1]).read_text()
    assert 'MD: a/a (I)I net/minecraft/src/Game/count (I)I' in names
    assert 'MD: demo/Main/func_123_a (I)I demo/Main/count (I)I' in names
    with zipfile.ZipFile(input) as z:
        assert sorted(z.namelist()) == ['demo/Main$Nested.class', 'demo/Main.class']
    shutil.copyfile(input, args[args.index('--out-jar') + 1])
else:
    data, out = map(pathlib.Path, args[-2:])
    assert not (data / 'demo/Unrelated.class').exists()
    (out / 'demo').mkdir()
    (out / 'demo/Main.java').write_text('package demo; class Main {}')
    (out / 'not-selected.java').write_text('not a planned source')
''')
        runner.chmod(0o755)
        out = self.root / "refs"
        argv = ["decompile-mod", self.pack, "--mod", "Example.jar", "--mcp", self.catalog, "--specialsource", tool,
                "--vineflower", flower, "--java", runner, "--class", "demo.Main"]
        blocked = run_cli(*argv, "-o", self.pack / "refs")
        self.assertIn("outside the modpack", blocked.stderr)
        blocked = run_cli(*argv, "-o", out, "--max-classes", "1")
        self.assertIn("including enclosing/nested classes", blocked.stderr)
        self.assertFalse(out.exists())
        proc = run_cli(*argv, "-o", out)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(mod.read_bytes(), original)
        self.assertEqual({p.name for p in out.iterdir()}, {".gitignore", "PORTING.md", "port-plan.json", "decompile-report.json",
                                                         "decompiler.log", "remapper.log", "sources"})
        self.assertEqual(json.loads((out / "decompile-report.json").read_text())["source_files"], ["demo/Main.java"])
        self.assertFalse((out / "sources/not-selected.java").exists())
        self.assertIn("not supplied (unmeasured, not passing)", (out / "PORTING.md").read_text())
        self.assertFalse(list(out.rglob("*.jar")))

    @unittest.skipUnless(shutil.which("javac") and os.environ.get("MCMODUP_VINEFLOWER_JAR") and os.environ.get("MCMODUP_SPECIALSOURCE_JAR"),
                         "install a full JDK and set MCMODUP_{VINEFLOWER,SPECIALSOURCE}_JAR for real-tool verification")
    def test_real_bytecode_is_remapped_before_vineflower(self):
        src = self.root / "src"
        src.mkdir()
        sources = {
            "a.java": "public class a { public int x; public int a(int n) { return n; } public a[] a(a[] xs) { return xs; } }",
            "Raw.java": "public class Raw extends a { public int a(int n) { return super.a(n) + x; } "
                        "public a[] read(a[] xs) { return a(xs); } public String literal() { return \"func_123_a\"; } "
                        "public static class Nested {} }",
            "Game.java": "package net.minecraft.example; public class Game { public int field_123_a; "
                         "public int func_123_a(int n) { return n; } }",
            "Main.java": "package demo; import net.minecraft.example.Game; public class Main extends Game { "
                         "public int func_123_a(int n) { return super.func_123_a(n) + field_123_a; } }",
        }
        for name, text in sources.items():
            (src / name).write_text(text, encoding="utf-8")
        classes = self.root / "classes"
        subprocess.run(["javac", "--release", "8", "-d", str(classes), *(str(src / name) for name in sources)],
                       check=True, timeout=30)
        self.mod(**{name: (classes / name).read_bytes() for name in ("Raw.class", "Raw$Nested.class", "demo/Main.class")})
        out = self.root / "mapped"
        proc = run_cli("decompile-mod", self.pack, "--mod", "Example.jar", "--mcp", self.mcp,
                       "--specialsource", os.environ["MCMODUP_SPECIALSOURCE_JAR"],
                       "--vineflower", os.environ["MCMODUP_VINEFLOWER_JAR"], "-o", out)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        raw = (out / "sources/Raw.java").read_text()
        srg = (out / "sources/demo/Main.java").read_text()
        self.assertIn("extends Game", raw)
        self.assertIn("Game[] read(Game[]", raw)
        self.assertIn("this.copy(", raw)
        self.assertIn('return "func_123_a";', raw)  # no Java/string replacement
        for code in (raw, srg):
            self.assertIn("int count(int ", code)
            self.assertIn("super.count(", code)
            self.assertIn("this.total", code)
        self.assertFalse((out / "sources/net").exists())
        manifest = json.loads((out / "decompile-report.json").read_text())
        self.assertEqual(manifest["remaining_srg_symbols"], [])
        self.assertFalse(manifest["classes_without_matching_output"])


if __name__ == "__main__":
    unittest.main()
