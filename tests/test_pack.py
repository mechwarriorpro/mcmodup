import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

from mcmodup.api import ApiIndex
from mcmodup.classfiles import Symbol, read_class
from mcmodup.decompile import VINEFLOWER_ENTRYPOINT
from mcmodup.inputs import InputError
from test_cli import EXAMPLE, ROOT, run_cli


def u2(*values: int) -> bytes:
    return struct.pack(f">{len(values)}H", *values)


def fixture_class(owner, *, parent="java/lang/Object", refs=(), fields=(), methods=(), annotation=None, access=1):
    """A minimal classfile fixture for signatures, constant-pool references and @Mod metadata."""
    pool = []

    def entry(tag, payload):
        pool.append(bytes([tag]) + payload)
        return len(pool)

    def utf(text):
        data = text.encode("utf-8")
        return entry(1, u2(len(data)) + data)

    def cls(text):
        return entry(7, u2(utf(text)))

    def members(rows):
        return u2(len(rows)) + b"".join(u2(flags, utf(name), utf(descriptor), 0) for name, descriptor, flags in rows)

    header = u2(access, cls(owner), cls(parent) if parent else 0, 0)
    for text in refs:
        ref = Symbol.parse(text)
        declaring_class = cls(ref.owner)
        if ref.name:
            name_and_type = entry(12, u2(utf(ref.name), utf(ref.descriptor)))
            entry(9 if ref.kind == "field" else 10, u2(declaring_class, name_and_type))
    body = header + members(fields) + members(methods)
    if annotation:
        name = utf("RuntimeVisibleAnnotations")
        payload = u2(1, utf("Lcpw/mods/fml/common/Mod;"), len(annotation))
        for key, value in annotation.items():
            payload += u2(utf(key)) + b"s" + u2(utf(value))
        body += u2(1, name) + struct.pack(">I", len(payload)) + payload
    else:
        body += u2(0)
    return b"\xca\xfe\xba\xbe" + u2(0, 52, len(pool) + 1) + b"".join(pool) + body


def jar(path: Path, **entries) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
    return path


class PackTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.pack = self.root / "instance"
        self.mods = self.pack / "mods"
        self.library = jar(self.mods / "1.7.10/Library.zip", **{
            "provider/Widget.class": fixture_class("provider/Widget", methods=(("open", "()V", 1),)),
            "provider/Widget$Nested.class": fixture_class("provider/Widget$Nested"),
            "provider/Unreferenced.class": fixture_class("provider/Unreferenced"),
            "mcmod.info": '[{"modid":"Widgets","mcversion":"1.7.10"}]',
        })

    def consumer(self, *, refs=(), version="1.7.10", dependency="required-after:widgets@[1.0,);required-before:Absent;after:forge"):
        return jar(self.mods / "Consumer.jar", **{
            "consumer/Main.class": fixture_class("consumer/Main", refs=refs, annotation={
                "modid": "consumer", "name": "Consumer", "acceptedMinecraftVersions": f"[{version}]",
                "dependencies": dependency,
            }),
        })

    def scan(self, *args):
        proc = run_cli("scan-pack", self.pack, "--json", *args)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return json.loads(proc.stdout)

    def test_subfolders_version_and_declared_and_binary_dependencies(self):
        self.consumer(refs=("provider/Widget#open()V", "missing/Foo", "java/lang/String"))
        jar(self.pack / "coremods/core.jar", **{"META-INF/MANIFEST.MF": "FMLCorePlugin: com.example.Patcher\n"})
        jar(self.pack / "resourcepacks/art.zip", **{"not-scanned.txt": "not a mod"})
        (self.mods / "bad.zip").write_bytes(b"bad zip")
        doc = self.scan()
        self.assertEqual(doc["archives_indexed"], 3)
        self.assertEqual(doc["archive_errors"][0]["archive"], "mods/bad.zip")
        by_path = {mod["archive"]: mod for mod in doc["archives"]}
        self.assertEqual(by_path["coremods/core.jar"]["manifest_findings"][0]["area"], "coremod")
        result = by_path["mods/Consumer.jar"]
        self.assertEqual(result["source_version"], "1.7.10")
        self.assertEqual(result["mods"][0]["mod_id"], "consumer")
        self.assertEqual(result["binary_dependencies"], [{"archive": "mods/1.7.10/Library.zip", "classes": ["provider/Widget"]}])
        self.assertEqual(result["unknown_external_classes"]["examples"], ["missing/Foo"])
        declared = {entry["id"]: entry for entry in result["declared_dependencies"]}
        self.assertEqual(declared["widgets"]["status"], "present")
        self.assertTrue(declared["Absent"]["required"])
        self.assertEqual(declared["Absent"]["status"], "not_found")
        self.assertEqual(declared["forge"]["status"], "loader")
        self.assertTrue(any(c["symbol"] == "cpw/mods/fml/common/Mod" for c in result["api_checks"]))

    def test_distinguishes_networking_generations_and_checks_target_candidates(self):
        target_owner = "net/neoforged/neoforge/network/event/RegisterPayloadHandlersEvent"
        target = jar(self.root / "target.jar", **{target_owner + ".class": fixture_class(target_owner)})
        early = "cpw/mods/fml/common/network/IPacketHandler"
        later = "cpw/mods/fml/common/network/simpleimpl/IMessage"
        for version, expected, unexpected in (("1.5.2", early, later), ("1.6.4", early, later), ("1.7.10", later, early)):
            with self.subTest(version=version):
                self.consumer(refs=(early, later), version=version)
                report = self.scan("--mod", "Consumer.jar", "--target-api", target)["archives"][0]
                api = {row["symbol"]: row for row in report["api_checks"]}
                self.assertEqual(api[expected]["status"], "rewrite")
                self.assertEqual(api[unexpected]["guidance"], [])
                candidates = {item["symbol"]: item["target"]["status"] for item in api[expected]["guidance"][0]["alternatives"]}
                self.assertEqual(candidates[target_owner], "found")

    def test_maps_actual_descriptors_and_legacy_declarations(self):
        item, tag = "net/minecraft/item/Item", "net/minecraft/nbt/NBTTagCompound"
        raw, srg = "a#m(Lb;)I", f"{item}#func_123(L{tag};)I"
        raw_field = "a#f:Lb;"
        self.consumer(refs=(raw, srg, raw_field))
        legacy = jar(self.root / "old-api.jar", **{
            "a.class": fixture_class("a", methods=(("m", "(Lb;)I", 1),), fields=(("f", "Lb;", 1),)),
            "b.class": fixture_class("b"),
        })
        new = "net/minecraft/world/item/Item"
        target = jar(self.root / "new-api.jar", **{f"{new}.class": fixture_class(new, methods=(("newName", "(I)I", 1),))})
        joined = self.root / "joined.srg"
        joined.write_text(f"CL: a {item}\nCL: b {tag}\nFD: a/f {item}/field_77\nMD: a/m (Lb;)I {item}/func_123 (L{tag};)I\n")
        methods = self.root / "methods.csv"
        methods.write_text("searge,name,side,desc\nfunc_123,oldName,2,description\n")
        fields = self.root / "fields.csv"
        fields.write_text("searge,name,side,desc\nfield_77,data,2,description\n")
        review = self.root / "review.json"
        review.write_text(json.dumps({"from_version": "1.7.10", "to_version": "1.21.1", "entries": [
            {"from": f"{item}#oldName(L{tag};)I", "to": f"{new}#newName(I)I", "note": "Check argument behavior."},
            {"from": f"{item}#data:L{tag};", "to": f"{new}#data:I"},
        ]}))
        flags = ["--mod", "mods/Consumer.jar", "--source-version", "1.7.10", "--legacy-api", legacy,
                 "--legacy-srg", joined, "--legacy-methods", methods, "--legacy-fields", fields,
                 "--target-api", target, "--port-map", review]
        checks = {entry["symbol"]: entry for entry in self.scan(*flags)["archives"][0]["api_checks"]}
        self.assertEqual(checks[raw]["target_lookup"]["symbol"], f"{new}#newName(I)I")
        self.assertEqual(checks[raw]["status"], "mapped_signature_found")
        self.assertEqual(checks[raw]["legacy_lookup"]["declared_by"], "a")
        self.assertEqual(checks[srg]["legacy_lookup"]["status"], "found")
        self.assertTrue(checks[srg]["legacy_lookup"]["normalized_index"])
        self.assertEqual(checks[srg]["mapping_note"], "Check argument behavior.")
        self.assertEqual(checks[raw_field]["status"], "mapping_unverified")
        self.assertEqual(checks[raw_field]["target_lookup"]["status"], "signature_not_in_index")
        self.assertEqual(checks[raw_field]["legacy_names"][-1], f"{item}#data:L{tag};")
        self.consumer(refs=(raw,), version="1.6.4")
        mismatched = self.scan(*flags)["archives"][0]
        self.assertNotIn(raw, {entry["symbol"] for entry in mismatched["api_checks"]})
        self.assertIn("profile was not applied", "\n".join(mismatched["warnings"]))

    def test_reports_ambiguous_providers_and_ignores_mislabeled_bytecode(self):
        self.consumer(refs=("provider/Widget#open()V", "broken/Problem"))
        jar(self.mods / "Duplicates.jar", **{
            "provider/Widget.class": fixture_class("provider/Widget"),
            "broken/Problem.class": fixture_class("actual/Problem"),
            "mcmod.info": '[{"modid":"widgets"}]',
        })
        report = self.scan("--mod", "Consumer.jar")
        result = report["archives"][0]
        self.assertEqual(result["ambiguous_dependency_classes"], ["provider/Widget"])
        self.assertEqual(result["binary_dependencies"], [])
        self.assertIn("broken/Problem", result["unknown_external_classes"]["examples"])
        self.assertEqual(result["declared_dependencies"][-1]["status"], "ambiguous")
        self.assertEqual(len(report["duplicate_mod_ids"]["widgets"]), 2)

    @unittest.skipUnless(sys.platform != "win32", "fake Java executable uses a POSIX shebang")
    def test_decompile_stages_only_referenced_dependency_and_nested_classes(self):
        consumer = self.consumer(refs=("provider/Widget#open()V", "a"))
        before = {path: path.read_bytes() for path in (consumer, self.library)}
        tool = jar(self.root / "vineflower.jar", **{
            "META-INF/MANIFEST.MF": f"Manifest-Version: 1.0\nMain-Class: {VINEFLOWER_ENTRYPOINT}\n\n",
        })
        runner = self.root / "java"
        runner.write_text(f"#!{sys.executable}\n" + '''import os, pathlib, sys
classes, output = map(pathlib.Path, sys.argv[-2:])
assert '--folder' in sys.argv
assert sorted(p.relative_to(classes).as_posix() for p in classes.rglob('*') if p.is_file()) == [
    'provider/Widget$Nested.class', 'provider/Widget.class']
assert 'GITHUB_TOKEN' not in os.environ and 'JAVA_TOOL_OPTIONS' not in os.environ
path = output / 'provider/Widget.java'
path.parent.mkdir()
path.write_text('package provider; public class Widget {}')
print('simulated decompiler output')
''')
        runner.chmod(0o755)
        out = self.root / "refs"
        args = ["decompile-deps", self.pack, "--mod", "Consumer.jar", "--vineflower", tool, "--java", runner, "--class", "provider.Widget"]
        capped = run_cli(*args, "-o", out, "--max-classes", "1")
        self.assertIn("exceeds 1 classes", capped.stderr)
        self.assertFalse(out.exists())
        result = run_cli(*args, "-o", out)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual({path: path.read_bytes() for path in before}, before)
        self.assertEqual((out / "sources/provider/Widget.java").read_text(), "package provider; public class Widget {}")
        self.assertEqual(json.loads((out / "decompile-report.json").read_text())["staged_class_count"], 2)
        self.assertIn("*", (out / ".gitignore").read_text())

    def test_decompile_rejects_mod_jar_as_executable(self):
        consumer = self.consumer(refs=("provider/Widget",))
        out = self.root / "refs"
        result = run_cli("decompile-deps", self.pack, "--mod", "Consumer.jar", "--vineflower", consumer, "-o", out)
        self.assertIn("outside the modpack", result.stderr)
        self.assertFalse(out.exists())

    def test_version_scope_is_required_for_legacy_mappings(self):
        self.consumer()
        old_map = self.root / "map.srg"
        old_map.write_text("CL: a mapped/One\n")
        result = run_cli("scan-pack", self.pack, "--legacy-srg", old_map)
        self.assertEqual(result.returncode, 1)
        self.assertIn("require --source-version", result.stderr)

    def test_optional_workflow_for_generated_output(self):
        out = self.root / "port"
        reply = run_cli("port", EXAMPLE, "-o", out, "--github-actions")
        self.assertEqual(reply.returncode, 0, reply.stderr)
        workflow = (out / ".github/workflows/build.yml").read_text()
        self.assertIn("java-version: '21'", workflow)
        self.assertIn("./gradlew --no-daemon build", workflow)
        self.assertIn("if-no-files-found: error", workflow)
        self.assertNotIn("git push", workflow)

    def test_inherited_overloads_and_access_are_checked(self):
        api = ApiIndex.load([jar(self.root / "api.jar", **{
            "api/Parent.class": fixture_class("api/Parent", methods=(("call", "(I)V", 1), ("call", "()V", 4))),
            "api/Child.class": fixture_class("api/Child", parent="api/Parent"),
        })])
        self.assertEqual(api.lookup(Symbol.parse("api/Child#call(I)V")).declared_by, "api/Parent")
        self.assertEqual(api.lookup(Symbol.parse("api/Child#call()V")).status, "access_review")
        self.assertEqual(api.lookup(Symbol.parse("api/Child#call(Z)V")).status, "signature_not_in_index")

    def test_same_descriptor_still_flags_static_to_instance_changes(self):
        owner = "net/minecraft/example/Type"
        member = f"{owner}#run()V"
        self.consumer(refs=(member,))
        old = jar(self.root / "old.jar", **{f"{owner}.class": fixture_class(owner, methods=(("run", "()V", 9),))})
        new = jar(self.root / "new.jar", **{f"{owner}.class": fixture_class(owner, methods=(("run", "()V", 1),))})
        report = self.scan("--mod", "Consumer.jar", "--source-version", "1.7.10", "--legacy-api", old, "--target-api", new)
        check = next(entry for entry in report["archives"][0]["api_checks"] if entry["symbol"] == member)
        self.assertEqual(check["status"], "signature_found_abi_review")
        self.assertEqual(check["abi_changes"], ["static/instance invocation changed"])

    def test_truncated_and_real_class_files(self):
        with self.assertRaises(InputError):
            read_class(b"\xca\xfe\xba\xbe")
        bundled = ROOT / "mcmodup/templates/gradle/wrapper/gradle-wrapper.jar"
        with zipfile.ZipFile(bundled) as archive:
            classes = [read_class(archive.read(entry)) for entry in archive.namelist() if entry.endswith(".class")]
        main = next(info for info in classes if info.owner == "org/gradle/wrapper/GradleWrapperMain")
        self.assertIn(("main", "([Ljava/lang/String;)V"), main.members)
        self.assertTrue(any(ref.owner == "java/lang/String" for ref in main.references))

    @unittest.skipUnless(shutil.which("javac"), "full JDK not present")
    def test_real_compiler_bytecode_references(self):
        source = self.root / "Consumer.java"
        source.write_text('''package sample;
public class Consumer {
    public Runnable task() { return () -> System.out.println("Hello!"); }
}
''')
        subprocess.run(["javac", "-d", str(self.root), str(source)], check=True)
        info = read_class((self.root / "sample/Consumer.class").read_bytes())
        self.assertIn(Symbol.parse("java/io/PrintStream#println(Ljava/lang/String;)V"), info.references)
        self.assertIn(Symbol("java/lang/invoke/LambdaMetafactory"), info.references)


if __name__ == "__main__":
    unittest.main()
