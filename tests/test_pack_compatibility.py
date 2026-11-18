import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from mcmodup.inputs import InputError
from mcmodup.native_pack import REPORT_NAME, inventory, plan, stage
from mcmodup.private_libraries import PRIVATE_METADATA
from mcmodup.runtime import NEO_CLASS, NEO_LIB_DIR
from mcmodup.source_ports import digest
from mcmodup.versions import matches, ranges_disjoint
from test_cli import run_cli
from test_native_pack import legacy, native, setup_platform
from test_pack import fixture_class, jar


def relocation(consumer: str, version: str) -> tuple[str, str]:
    manifest = {"schema_version": 1, "libraries": [{"consumer": consumer, "id": "bitcodec", "version": version,
                                                     "original_package": "old/codec", "source_sha256": version[0] * 64}]}
    return f"mcmodup_private/{consumer}/bitcodec/v{version.replace('.', '_')}", json.dumps(manifest)


class PackCompatibilityTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.src = self.root / "legacy"
        self.target = self.root / "target"
        self.out = self.root / "new-pack"
        setup_platform(self.target)

    def launcher_source(self):
        jar(self.src / "bin/minecraft.jar", **{"aab.class": fixture_class("aab", methods=(("a", "()I", 1),))})
        jar(self.src / "bin/modpack.jar", **{"cpw/mods/fml/common/Loader.class": fixture_class("cpw/mods/fml/common/Loader"),
                                           "fmlversion.properties": "fmlbuild.mcversion=1.7.10\nfmlbuild.major.number=7\n"})

    def test_launcher_bin_is_api_provenance_even_when_coremods_is_the_only_loader_folder(self):
        self.launcher_source()
        fixture = legacy(self.src, "coremods/wrapper/deep/Consumer.zip", "consumer", refs=(
            "aab#a()I", "cpw/mods/fml/common/Loader", "loose/api/Codec",
        ))
        native(self.target, "lib/Consumer.jar", "consumer")
        jar(self.src / "lib/api.jar", **{"loose/api/Codec.class": fixture_class("loose/api/Codec")})
        result = run_cli("scan-pack", self.src, "--mod", fixture.name, "--json")
        self.assertEqual(result.returncode, 0, result.stderr)
        scan = json.loads(result.stdout)
        [entry] = scan["archives"]
        self.assertEqual(entry["source_version"], "1.7.10")
        self.assertIn({"origin": "bin/modpack.jar", "kind": "local runtime", "version": "1.7.10"}, entry["version_evidence"])
        self.assertEqual(scan["local_runtime"]["forge_files"], ["bin/modpack.jar"])
        api = {row["symbol"]: row["legacy_lookup"] for row in entry["api_checks"]}
        self.assertEqual(api["aab#a()I"]["status"], "found")
        self.assertTrue(api["aab#a()I"]["artifact"].endswith("bin/minecraft.jar"))
        self.assertTrue(api["cpw/mods/fml/common/Loader"]["artifact"].endswith("bin/modpack.jar"))
        result = run_cli("plan-port", self.src, "--mod", fixture.name, "--json")
        self.assertEqual(result.returncode, 0, result.stderr)
        state = inventory(self.src, self.target)
        update = plan(state, ["coremods/wrapper/deep/Consumer.zip"], output_pack=True)
        self.assertTrue(update.report["ready"], update.problems)
        [local] = update.report["legacy_class_providers"]["coremods/wrapper/deep/Consumer.zip"]["providers"]
        self.assertEqual(local["archive"], "lib/api.jar")
        self.assertEqual(local["sha256"], digest(self.src / "lib/api.jar"))
        stage(update, self.out)
        self.assertEqual({p.name for p in (self.out / "mods").iterdir()}, {"Consumer.jar"})
        self.assertFalse((self.out / "bin/modpack.jar").exists())

    def pair(self, old_id="ForgeMultipart"):
        self.launcher_source()
        first = legacy(self.src, "coremods/parts/Multipart-old.jar", old_id, version="1.1.2.331", owner="old/codec/Codec")
        second = legacy(self.src, "mods/1.7.10/Multipart-new.zip", old_id, version="1.2.0.345", owner="old/codec/Codec")
        legacy(self.src, "mods/1.6.4/dormant-backup.jar", old_id, version="1.1.2.331", owner="old/codec/Codec")
        alpha = "mods/1.7.10/one/Alpha.jar"
        beta = "mods/1.7.10/two/Beta.jar"
        legacy(self.src, alpha, "alpha", requirements=(f"required-after:{old_id}@[1.1.2.331]",), refs=("old/codec/Codec",))
        legacy(self.src, beta, "beta", requirements=(f"required-after:{old_id}@[1.2.0.345]",), refs=("old/codec/Codec",))
        (self.src / "mcmodup-updates.json").write_text(json.dumps({old_id: "cb_multipart" if old_id != "bitcodec" else "bitcodec"}))
        return first, second, alpha, beta

    def test_respects_per_consumer_legacy_version_and_uses_one_compatible_modern_release(self):
        first, second, alpha, beta = self.pair()
        shared = native(self.target, "lib/Multipart-3.5.jar", "cb_multipart", version="3.5.0.155")
        native(self.target, "mods/Multipart-3.4.jar", "cb_multipart", version="3.4")
        native(self.target, "lib/Multipart-3.6.jar", "cb_multipart", version="3.6")
        native(self.src, "lib/Alpha-port.jar", "alpha", requirements=(("cb_multipart", "[3.4,3.6)"),))
        native(self.target, "lib/Beta-port.jar", "beta", requirements=(("cb_multipart", "[3.5,3.7)", "REQUIRED"),))
        state = inventory(self.src, self.target)
        selected_provider = plan(state, [first.relative_to(self.src).as_posix()])
        self.assertEqual(set(selected_provider.addons), {alpha})
        update = plan(state, [alpha, beta])
        self.assertTrue(update.report["ready"], update.problems)
        [conflict] = update.report["legacy_version_conflicts"]
        self.assertEqual(conflict["status"], "disjoint_legacy_ranges")
        self.assertEqual({row["provider"]["sha256"] for row in conflict["requirements"]}, {digest(first), digest(second)})
        self.assertTrue(all(row["status"] == "present" for row in conflict["requirements"]))
        for row in conflict["requirements"]:
            [binary_provider] = update.report["legacy_class_providers"][row["consumer"]]["providers"]
            self.assertEqual(binary_provider["sha256"], row["provider"]["sha256"])
            self.assertEqual(row["native_binding"]["version"], "3.5.0.155")
        [resolution] = update.report["native_dependencies"]
        self.assertEqual(resolution["status"], "shared_native_provider")
        self.assertEqual(resolution["provider"]["sha256"], digest(shared))
        self.assertEqual(update.removed, {"mods/Multipart-3.4.jar": "target/lib/Multipart-3.5.jar"})
        self.assertFalse(update.report["parity_verified"])

    def test_disjoint_native_requirements_block_even_if_both_jars_are_present(self):
        _, _, alpha, beta = self.pair()
        native(self.target, "lib/Alpha.jar", "alpha", requirements=(("cb_multipart", "[3.4]"),))
        native(self.target, "lib/Beta.jar", "beta", requirements=(("cb_multipart", "[3.5]"),))
        native(self.target, "lib/Multipart-3.4.jar", "cb_multipart", version="3.4")
        native(self.target, "lib/Multipart-3.5.jar", "cb_multipart", version="3.5")
        result = run_cli("update-pack", self.src, "--target", self.target, "--mod", alpha, "--mod", beta,
                         "--output-pack", self.out, "--json")
        self.assertEqual(result.returncode, 1, result.stderr)
        report = json.loads(result.stdout)
        [conflict] = report["native_dependencies"]
        self.assertEqual(conflict["status"], "disjoint_native_ranges")
        self.assertEqual({r["range"] for r in conflict["requirements"]}, {"[3.4]", "[3.5]"})
        self.assertIn("No shared version", " ".join(report["problems"]))
        self.assertFalse(self.out.exists())

    def test_exact_pin_and_backtracking_replace_an_incompatible_installed_choice_without_orphans(self):
        old = legacy(self.src, "mods/Old.jar", "widget")
        native(self.target, "mods/Widget-1.jar", "widget", version="1", requirements=(("obsolete", "[1]"),))
        native(self.target, "lib/Obsolete.jar", "obsolete", version="1")
        chosen = native(self.target, "lib/Widget-2.jar", "widget", version="2", requirements=(("native_lib", "[2]"),))
        alternate = native(self.target, "lib/Widget-3.jar", "widget", version="3", requirements=(("native_lib", "[2]"),))
        native(self.target, "lib/NativeLibrary.jar", "native_lib", version="2")
        native(self.target, "mods/Retained.jar", "retained", requirements=(("widget", "[2,4)"),))
        state = inventory(self.src, self.target)
        ambiguity = plan(state, ["mods/Old.jar"])
        self.assertIn("Multiple compatible", ambiguity.problems[0])
        result = run_cli("update-pack", self.src, "--target", self.target, "--mod", "mods/Old.jar",
                         "--provider", f"widget={digest(chosen)}", "-o", self.out, "--json")
        self.assertEqual(result.returncode, 0, result.stderr or result.stdout)
        self.assertEqual({p.name for p in (self.out / "mods").iterdir()}, {"Widget-2.jar", "NativeLibrary.jar", "Retained.jar"})
        self.assertTrue(old.is_file() and alternate.is_file())

    def test_transitive_constraints_backtrack_past_a_preferred_installed_provider(self):
        legacy(self.src, "mods/Main.jar", "mainmod")
        native(self.target, "lib/Main.jar", "mainmod", requirements=(("z_runtime", "[2]"),))
        native(self.target, "mods/Aux-old.jar", "a_aux", requirements=(("z_runtime", "[1]"),))
        native(self.target, "lib/Aux-new.jar", "a_aux", requirements=(("z_runtime", "[2]"),))
        native(self.target, "lib/Z-old.jar", "z_runtime", version="1")
        native(self.target, "lib/Z-new.jar", "z_runtime", version="2")
        update = plan(inventory(self.src, self.target), ["mods/Main.jar"])
        self.assertTrue(update.report["ready"], update.problems)
        self.assertEqual(update.removed, {"mods/Aux-old.jar": "target/lib/Aux-new.jar"})

    def test_separate_output_pack_copies_only_selected_native_files_and_modern_runtime(self):
        self.launcher_source()
        selected = legacy(self.src, "mods/deep/Old.zip", "oldmod")
        base = native(self.target, "mods/Base.jar", "oldmod", requirements=(("required_lib", "[2]"),))
        required = native(self.target, "libraries/Required.jar", "required_lib")
        native(self.target, "mods/Unselected.jar", "unselected")
        (self.target / "mods/broken.jar").write_bytes(b"unrelated broken mod")
        (self.target / "mods/disabled.jar.disabled").touch()
        (self.target / "saves/world").mkdir(parents=True)
        (self.target / "saves/world/player.dat").touch()
        (self.target / "options.txt").touch()
        (self.target / "eula.txt").touch()
        jar(self.target / "bin/modpack.jar", **{"net/minecraftforge/common/MinecraftForge.class": fixture_class("net/minecraftforge/common/MinecraftForge")})
        before = {p: digest(p) for root in (self.src, self.target) for p in root.rglob("*") if p.is_file()}
        blocked = plan(inventory(self.src, self.target), [selected.relative_to(self.src).as_posix()])
        self.assertFalse(blocked.report["ready"], "Staging a complete mods swap still checks all active target files")
        output = run_cli("update-pack", self.src, "--target", self.target, "--mod", "mods/deep/Old.zip", "--output-pack", self.out, "--json")
        self.assertEqual(output.returncode, 0, output.stderr or output.stdout)
        report = json.loads(output.stdout)
        self.assertEqual(report["instance_runtime"]["layout"], "launcher-game-directory")
        self.assertFalse(report["instance_runtime"]["boot_verified"])
        self.assertTrue(report["staged"])
        self.assertEqual({p.relative_to(self.out).as_posix() for p in self.out.rglob("*") if p.is_file()}, {
            "mods/Base.jar", "mods/Required.jar", "bin/minecraft.jar", "bin/neoforge-21.1.267-universal.jar", "INSTALL.txt", REPORT_NAME,
        })
        for path in (base, required):
            self.assertEqual(digest(self.out / "mods" / path.name), digest(path))
        self.assertEqual({p: digest(p) for p in before}, before)
        refused = run_cli("update-pack", self.src, "--target", self.target, "--mod", "mods/deep/Old.zip", "--output-pack", self.target / "nested")
        self.assertEqual(refused.returncode, 1)
        self.assertIn("outside", refused.stderr)

    def test_server_layout_copies_relative_runtime_closure_and_fails_cleanly_on_mutation(self):
        shutil.rmtree(self.target / "bin")
        version = "21.1.267"
        base = f"{NEO_LIB_DIR}/{version}"
        neo = f"{base}/neoforge-{version}-universal.jar"
        neoform = "20240808.144430"
        mc = f"libraries/net/minecraft/server/1.21.1-{neoform}/server-1.21.1-{neoform}-extra.jar"
        helper = "libraries/example/runtime/1.0/runtime-1.0.jar"
        jar(self.target / neo, **{NEO_CLASS: fixture_class(NEO_CLASS.removesuffix(".class"))})
        jar(self.target / mc, **{"version.json": '{"id":"1.21.1"}'})
        jar(self.target / mc.replace("-extra.jar", "-srg.jar"), **{"srg.txt": "Minecraft names"})
        jar(self.target / neo.replace("-universal.jar", "-server.jar"), **{"patches.txt": "NeoForge patched Minecraft"})
        jar(self.target / helper, **{"marker.txt": "runtime library"})
        for os_name in ("unix", "win"):
            (self.target / f"{base}/{os_name}_args.txt").write_text(
                f"-p {helper}\n-DlegacyClassPath={helper}\n-DlibraryDirectory=libraries\n"
                f"cpw.mods.bootstraplauncher.BootstrapLauncher\n--launchTarget forgeserver\n--fml.mcVersion 1.21.1\n"
                f"--fml.neoForgeVersion {version}\n--fml.neoFormVersion {neoform}\n")
        (self.target / "run.sh").write_text("not stock; must not be copied")
        legacy(self.src, "mods/Base.jar", "base_mod")
        native(self.target, "lib/Base.jar", "base_mod")
        update = plan(inventory(self.src, self.target), ["mods/Base.jar"], output_pack=True)
        self.assertTrue(update.report["ready"], update.problems)
        self.assertEqual(update.report["instance_runtime"]["layout"], "neoforge-server")
        self.assertEqual(len(update.runtime.files), 7)
        stage(update, self.out)
        self.assertFalse((self.out / "run.sh").exists())
        self.assertIn(f"@{base}/unix_args.txt", (self.out / "INSTALL.txt").read_text())
        (self.target / helper).write_bytes(b"changed")
        other = self.root / "must-not-exist"
        with self.assertRaisesRegex(InputError, "changed during staging"):
            stage(update, other)
        self.assertFalse(other.exists())
        (self.target / f"{base}/unix_args.txt").write_text(
            (self.target / f"{base}/win_args.txt").read_text().replace(f"-DlegacyClassPath={helper}", f"-DlegacyClassPath={helper}:libraries/../external.jar"))
        rejected = plan(inventory(self.src, self.target), ["mods/Base.jar"], output_pack=True)
        self.assertIn("relative installer library paths", " ".join(rejected.problems))

    def test_legacy_rv3_range_is_not_applied_to_modern_ae2(self):
        old = legacy(self.src, "mods/1.7.10/thaumicenergistics.jar", "thaumicenergistics", version="1.1.3.0",
                     requirements=("required-after:appliedenergistics2@[rv3-beta-1,)", "required-after:Thaumcraft@[4.2.3.5,)"))
        legacy(self.src, "mods/1.7.10/ae2-old.jar", "appliedenergistics2", version="rv3-beta-6")
        legacy(self.src, "mods/Thaumcraft.jar", "Thaumcraft", version="4.2.3.5")
        native(self.target, "lib/AE2.jar", "ae2", version="19.2.18", requirements=(("guideme", "[21.1.1,)", "REQUIRED"),))
        native(self.target, "lib/GuideME.jar", "guideme", version="21.1.22")
        native(self.target, "lib/TC-Reforged.jar", "thaumcraft", version="1.1.3")
        native(self.target, "lib/TE-Reforged.jar", "thaumicenergistics", version="1.1", requirements=(
            ("ae2", "[19.2.18,20)"), ("thaumcraft", "[1.0,)")
        ))
        mapping = self.root / "energistics.json"
        mapping.write_text('{"appliedenergistics2":"ae2"}')
        state = inventory(self.src, self.target, id_map=mapping)
        update = plan(state, [old.relative_to(self.src).as_posix()])
        self.assertTrue(update.report["ready"], update.problems)
        data = {r["id"].lower(): r for r in update.report["legacy_requirements"]}
        self.assertEqual(data["appliedenergistics2"]["provider"]["version"], "rv3-beta-6")
        self.assertEqual(data["appliedenergistics2"]["native_binding"]["version"], "19.2.18")
        self.assertEqual(data["thaumcraft"]["native_binding"]["version"], "1.1.3")
        self.assertEqual({mod.path.name for mod in update.mods}, {"AE2.jar", "GuideME.jar", "TE-Reforged.jar", "TC-Reforged.jar"})
        self.assertTrue(matches("rv3-beta-6", "[rv3-beta-1,)") and not matches("rv2-stable-10", "[rv3-beta-1,)"))
        self.assertTrue(ranges_disjoint(["[3,4)", "[4,5)"]))
        self.assertFalse(ranges_disjoint(["[3,4]", "[4,5)"]))

    def test_unknown_legacy_version_is_not_guessed_from_its_filename(self):
        self.launcher_source()
        library = legacy(self.src, "coremods/Library-1.2.jar", "library", version="${version}")
        legacy(self.src, "mods/Consumer.jar", "consumer", requirements=("required-after:library@[1.2]",))
        native(self.target, "lib/Consumer.jar", "consumer")
        update = plan(inventory(self.src, self.target), ["mods/Consumer.jar"])
        [row] = update.report["legacy_requirements"]
        self.assertEqual(row["status"], "unknown_version")
        self.assertIsNone(row["provider"])
        self.assertEqual(row["candidates"][0]["sha256"], digest(library))

    def test_private_native_libraries_retain_different_versions_with_no_global_provider(self):
        _, _, alpha, beta = self.pair("bitcodec")
        for consumer, version in (("alpha", "1.0"), ("beta", "2.0")):
            namespace, info = relocation(consumer, version)
            native(self.target, f"lib/{consumer}.jar", consumer, refs=(namespace + "/Codec#decode()I",), more={
                PRIVATE_METADATA: info,
                namespace + "/Codec.class": fixture_class(namespace + "/Codec", methods=(("decode", "()I", 9),)),
                namespace + "/codec.txt": f"dictionary for {version}",
            })
        update = plan(inventory(self.src, self.target), [alpha, beta], output_pack=True)
        self.assertTrue(update.report["ready"], update.problems)
        stage(update, self.out)
        report = json.loads((self.out / REPORT_NAME).read_text())
        versions = {lib["version"] for item in report["files"] for lib in item["private_libraries"]}
        self.assertEqual(versions, {"1.0", "2.0"})
        self.assertEqual({r["native_binding"]["status"] for r in report["legacy_requirements"]}, {"private_native_library"})
        self.assertEqual({p.name for p in (self.out / "mods").iterdir()}, {"alpha.jar", "beta.jar"})

    def test_rejects_unrelocated_bytecode_and_consumer_links_outside_private_jar(self):
        _, _, alpha, beta = self.pair("bitcodec")
        prefix, info = relocation("alpha", "1.0")
        private = prefix + "/Codec"
        for label, contents in (
            ("legacy FML", {private + ".class": fixture_class(private, refs=("cpw/mods/fml/common/Loader",))}),
            ("not a private Java library", {private + ".class": fixture_class(private, refs=("net/minecraft/world/item/Item",))}),
            ("must not register global services", {"META-INF/services/java.util.ServiceLoader": private.replace("/", ".")}),
            ("Unrelocated", {"old/codec/Codec.class": fixture_class("old/codec/Codec")}),
        ):
            with self.subTest(label=label):
                native(self.target, "lib/Alpha.jar", "alpha", refs=(private,), more={
                    PRIVATE_METADATA: info, private + ".class": fixture_class(private), **contents,
                })
                state = inventory(self.src, self.target)
                self.assertIn(label, " ".join(state.warnings))
                self.assertFalse(plan(state, [alpha]).report["ready"])
        native(self.target, "lib/Alpha.jar", "alpha", refs=(private,), more={PRIVATE_METADATA: info, private + ".class": fixture_class(private)})
        native(self.target, "lib/Beta.jar", "beta", refs=(private,))
        self.assertIn("outside its own JAR", " ".join(plan(inventory(self.src, self.target), [alpha, beta]).problems))
        for name in ("alpha", "beta"):
            native(self.target, f"lib/{name.title()}.jar", name, more={"old/codec/Codec.class": fixture_class("old/codec/Codec")})
        update = plan(inventory(self.src, self.target), [alpha, beta])
        self.assertIn("Duplicate native class 'old/codec/Codec'", " ".join(update.problems))

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "full JDK needed to exercise two relocated versions")
    def test_two_real_native_library_versions_execute_with_their_own_resources(self):
        _, _, alpha, beta = self.pair("bitcodec")
        classes = self.root / "classes"
        sources = self.root / "java"
        sources.mkdir()
        authored = []
        for name, version, signature, argument in (("alpha", "1.0", "int n", '4'), ("beta", "2.0", "String n", '"five"')):
            namespace, info = relocation(name, version)
            java_package = namespace.replace("/", ".")
            file = sources / f"{namespace}/Codec.java"
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(f'''package {java_package};
public final class Codec {{
    public static String decode({signature}) throws Exception {{
        try (var in = Codec.class.getResourceAsStream("codec.txt")) {{ return new String(in.readAllBytes()) + n; }}
    }}
}}
''')
            consumer = sources / f"{name.title()}.java"
            consumer.write_text(f"package modern.demo; public class {name.title()} {{ public static String call() throws Exception {{ return {java_package}.Codec.decode({argument}); }} }}")
            authored.append((name, namespace, info))
        subprocess.run(["javac", "-d", str(classes), *(str(f) for f in sources.rglob("*.java"))], check=True, timeout=30)
        for name, ns, info in authored:
            main = f"modern/demo/{name.title()}.class"
            native(self.target, f"lib/{name}.jar", name, more={PRIVATE_METADATA: info, main: (classes / main).read_bytes(),
                   ns + "/Codec.class": (classes / ns / "Codec.class").read_bytes(), ns + "/codec.txt": name + ":"})
        update = plan(inventory(self.src, self.target), [alpha, beta], output_pack=True)
        self.assertTrue(update.report["ready"], update.problems)
        stage(update, self.out)
        probe = self.root / "Check.java"
        probe.write_text('public class Check { public static void main(String[] args) throws Exception {\n'
                         'if (!modern.demo.Alpha.call().equals("alpha:4") || !modern.demo.Beta.call().equals("beta:five")) throw new AssertionError(); }}')
        jars = os.pathsep.join(str(p) for p in sorted((self.out / "mods").glob("*.jar")))
        subprocess.run(["java", "-cp", jars, str(probe)], check=True, timeout=30)
