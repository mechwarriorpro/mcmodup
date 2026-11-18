"""Test bytecode selection and staged adapter checks without a legacy runtime."""

from dataclasses import replace
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

from mcmodup.classfiles import Symbol, read_class
from mcmodup.icbm_plate import KERNEL, LEGACY_SHA256, MOD_ID, OWNER as PLATE, TEXTURE, prepare as prepare_plate
from mcmodup.inputs import InputError
from mcmodup.method_ports import PROVENANCE, parse_method_ports, transplant, verify
from mcmodup.native_pack import REPORT_NAME, inventory, plan, stage
from mcmodup.source_ports import BuildOptions, digest, load_ports
from test_convert import CatalogClass
from test_native_pack import legacy, native, setup_platform
from test_pack import fixture_class, jar, u2
from test_recipe_conversion import attribute
from test_source_ports import recipe, write_manifest


OWNER, WORLD = "old/Machine", "old/World"
ACCESS, NATIVE_WORLD, LOGIC = (f"mcmodup_kernel/techbase/{name}" for name in ("Access", "World", "Logic"))
HASH_BOUND = "mods/1.6.4/Machine.jar"
CALL, FIELD = f"{OWNER}#sense(L{WORLD};I)I", f"{WORLD}#level:I"
UPDATE = f"{OWNER}#update(L{WORLD};I)I"
DELAY = f"{OWNER}#delay()I"


def group():
    return {"owner": OWNER, "output": LOGIC, "types": {OWNER: ACCESS, WORLD: NATIVE_WORLD},
            "methods": {UPDATE: "process", DELAY: "checkDelay"},
            "members": {CALL: f"{ACCESS}#getPower(L{NATIVE_WORLD};I)I", FIELD: f"{NATIVE_WORLD}#getLevel:I"}}


class Machine(CatalogClass):
    def __init__(self):
        super().__init__(OWNER)
        self.decls = []

    def append(self, name, desc, code, *, access, locals, nested=b""):
        payload = u2(3, locals) + struct.pack(">I", len(code)) + code + u2(0, 1 if nested else 0) + nested
        self.decls.append(u2(access, self.utf(name), self.utf(desc), 1) + attribute(self.utf("Code"), payload))

    def bytes(self, *, branch=10, delay=b"\x10\x0a\xac", frame_offset=11, include_update=True):
        parent = u2(0x21, self.cls(OWNER), self.cls("old/DontLoadBase"), 0)
        self.append("<clinit>", "()V", b"\xb8" + u2(self.ref(10, "old/DontInit", "init", "()V")) + b"\xb1", access=8, locals=0)
        self.append("delay", "()I", delay, access=4, locals=1)
        if include_update:
            code = (b"\x1c\x99" + u2(branch) + b"\x2a\x2b\x1c\xb6" + u2(self.ref(10, OWNER, "sense", f"(L{WORLD};I)I"))
                    + b"\xac\x2b\xb4" + u2(self.ref(9, WORLD, "level", "I")) + b"\xac")
            frames = u2(1) + b"\xff" + u2(frame_offset, 3) + b"\x07" + u2(self.cls(OWNER)) + b"\x07" + u2(self.cls(WORLD)) + b"\x01" + u2(0)
            self.append("update", f"(L{WORLD};I)I", code, access=4, locals=3, nested=attribute(self.utf("StackMapTable"), frames))
        return (b"\xca\xfe\xba\xbe" + u2(0, 50, len(self.pool) + 1) + b"".join(self.pool) + parent + u2(0, len(self.decls))
                + b"".join(self.decls) + u2(0))


def adapters(*, flags=1):
    return {f"{ACCESS}.class": fixture_class(ACCESS, methods=(("getPower", f"(L{NATIVE_WORLD};I)I", flags),)),
            f"{NATIVE_WORLD}.class": fixture_class(NATIVE_WORLD, fields=(("getLevel", "I", 1),))}


class MethodPortTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)

    def parse(self, *groups):
        return parse_method_ports(list(groups), {"techbase": "2"})[0]

    def test_uses_same_local_slots_and_remapped_stack_frames_without_carrying_any_legacy_class(self):
        port = self.parse(group())
        original = Machine().bytes()
        result = transplant(original, port)
        self.assertEqual(transplant(original, port).bytecode, result.bytecode)
        self.assertEqual(result.path, f"{LOGIC}.class")
        info = read_class(result.bytecode)
        self.assertEqual(info.parents, ("java/lang/Object",))
        self.assertEqual(info.members, {("checkDelay", f"(L{ACCESS};)I"): 9, ("process", f"(L{ACCESS};L{NATIVE_WORLD};I)I"): 9})
        self.assertIn(b"StackMapTable", result.bytecode)
        self.assertIn(b"\x1c\x99\x00\x0a\x2a\x2b\x1c\xb6", result.bytecode)
        self.assertIn(b"\x10\x0a\xac", result.bytecode)
        self.assertNotIn(b"old/", result.bytecode)
        self.assertEqual({symbol.key for symbol in result.uses}, set(group()["members"].values()))
        self.assertEqual(result.report()["methods"], sorted([UPDATE, DELAY]))

    def test_transplant_rejects_unmapped_instructions_and_incomplete_code(self):
        base, raw = group(), Machine().bytes()
        port = self.parse(base)
        for bad, error in (
            (Machine().bytes(delay=b"\xbb\x00\x00\xac"), "Unsupported opcode 0xbb"),
            (Machine().bytes(branch=7), "Invalid branch target"),
            (Machine().bytes(frame_offset=8), "Invalid method stack-map"),
            (Machine().bytes(include_update=False), "Missing/extra method import"),
            (raw[:-1], "truncated"), (raw[:6] + u2(55) + raw[8:], "Java 5–8"),
        ):
            with self.subTest(error=error), self.assertRaisesRegex(InputError, error):
                transplant(bad, port)
        with self.assertRaisesRegex(InputError, "Unmapped bytecode member"):
            transplant(raw, replace(port, members={Symbol.parse(CALL): Symbol.parse(base["members"][CALL])}))
        with self.assertRaisesRegex(InputError, "Missing/extra method import bindings"):
            transplant(raw, replace(port, members=port.members | {Symbol.parse(DELAY): Symbol.parse(f"{ACCESS}#getDelay()I")}))

    def test_rejects_foreign_abi_targets_overlapping_types_and_constructor_selection(self):
        base = group()
        for row, error in (
            (base | {"output": "net/minecraft/block/Foreign"}, "distinct"),
            (base | {"types": {OWNER: ACCESS, WORLD: ACCESS}}, "one-to-one"),
            (base | {"methods": {f"{OWNER}#<init>()V": "init"}}, "ordinary method"),
            (base | {"members": {CALL: f"{ACCESS}#getPower(L{NATIVE_WORLD};J)I"}}, "descriptor"),
            (base | {"members": {FIELD: f"{ACCESS}#getLevel:I"}}, "receiver"),
        ):
            with self.subTest(error=error), self.assertRaisesRegex(InputError, error):
                self.parse(row)
        with self.assertRaisesRegex(InputError, "distinct"):
            self.parse(base, base)

    def test_field_and_virtual_calls_can_redirect_to_explicit_static_helpers(self):
        helpers = f"mcmodup_kernel/techbase/Helpers"
        sense = f"{helpers}#sense(L{ACCESS};L{NATIVE_WORLD};I)I"
        get = f"{helpers}#readLevel(L{NATIVE_WORLD};)I"
        put = f"{helpers}#writeLevel(L{NATIVE_WORLD};I)V"
        source = Machine()
        source.append("set", f"(L{WORLD};I)V", b"\x2b\x1c\xb5" + u2(source.ref(9, WORLD, "level", "I")) + b"\xb1",
                      access=2, locals=3)
        raw = source.bytes()
        row = group() | {"members": {}, "redirects": {"invokevirtual": {CALL: sense}, "getfield": {FIELD: get},
                                                      "putfield": {FIELD: put}}}
        row["methods"][f"{OWNER}#set(L{WORLD};I)V"] = "setLevel"
        result = transplant(raw, self.parse(row))
        self.assertEqual(result.uses, {Symbol.parse(key): {0xb8} for key in (sense, get, put)})
        self.assertEqual({binding["opcode"]: binding["sites"] for binding in result.report()["call_redirects"]},
                         {"invokevirtual": 1, "getfield": 1, "putfield": 1})
        self.assertIn(b"\x1c\x99\x00\x0a\x2a\x2b\x1c\xb8", result.bytecode)
        self.assertNotIn(b"old/", result.bytecode)
        jar_path = self.root / "translated.jar"
        files = adapters() | {result.path: result.bytecode}
        for flags in (1, 9):
            stub = fixture_class(helpers, methods=tuple((s.name, s.descriptor, flags) for s in result.uses))
            jar(jar_path, **(files | {f"{helpers}.class": stub}))
            if flags == 9:
                verify(jar_path, (result,))
            else:
                with self.assertRaisesRegex(InputError, "callable native adapter member"):
                    verify(jar_path, (result,))

        for rules, error in (
            ({"invokespecial": {CALL: sense}}, "supported bytecode opcode"),
            ({"getfield": {FIELD: put}}, "mapped stack shape"),
            ({"putfield": {FIELD: put.replace(helpers, "external/Helper")}}, "local static helper"),
            ({"getfield": {FIELD: get}, "invokevirtual": {CALL: sense}}, "Unmapped bytecode member.*putfield"),
            (row["redirects"] | {"getstatic": {f"{WORLD}#absent:I": f"{helpers}#absent()I"}}, "Unused bytecode redirects"),
        ):
            with self.subTest(error=error), self.assertRaisesRegex(InputError, error):
                transplant(raw, self.parse(row | {"redirects": rules}))

    def test_built_jar_must_keep_exact_code_and_public_adapter_abi(self):
        linked = transplant(Machine().bytes(), self.parse(group()))
        artifact = self.root / "built.jar"
        files = adapters() | {linked.path: linked.bytecode}
        verify(jar(artifact, **files), (linked,))
        for members, error in (
            (files | {linked.path: Machine().bytes()}, "generated method class"),
            ({name: value for name, value in files.items() if not name.startswith(NATIVE_WORLD)}, "missing native adapter class"),
            (files | adapters(flags=2), "missing a callable native adapter member"),
            (files | adapters(flags=9), "missing a callable native adapter member"),
        ):
            with self.subTest(error=error), self.assertRaisesRegex(InputError, error):
                verify(jar(artifact, **members), (linked,))

    def test_stage_receipt_and_preview_not_usable_as_provider(self):
        source, target, output = (self.root / name for name in ("old", "target", "staged"))
        setup_platform(source, "1.6.4")
        setup_platform(target)
        meta = json.dumps([{"modid": "Machine", "version": "1"}, {"modid": "Machine|Omitted", "version": "1"}])
        original = jar(source / HASH_BOUND, **{f"{OWNER}.class": Machine().bytes(), "mcmod.info": meta})
        project = jar(self.root / "source.zip", **{"settings.gradle": "rootProject.name='example'"})
        mod = recipe(original, project, "techbase", status="preview", limitations=["Second module omitted"],
                     legacy_ids={"machine": "techbase", "machine|omitted": None}, method_imports=[group()])
        manifest = write_manifest(self.root / "ports.json", mod)
        unapproved = plan(inventory(source, target, BuildOptions(manifest)), [HASH_BOUND], output_pack=True)
        self.assertIn("--allow-preview-ports", " ".join(unapproved.problems))
        admitted = inventory(source, target, BuildOptions(manifest, allow_preview=True))
        self.assertIn("fresh --output-pack", " ".join(plan(admitted, [HASH_BOUND]).problems))
        self.assertNotIn("techbase", admitted.providers)
        control = native(target, "mods/Dependent.jar", "dependent", requirements=(("techbase", "[2]"),))
        admitted = inventory(source, target, BuildOptions(manifest, allow_preview=True))
        for selection in ([], [HASH_BOUND]):
            self.assertFalse(plan(admitted, selection, output_pack=True, include_native=["dependent"]).report["ready"])
        control.unlink()
        source_addon = legacy(source, "mods/Addon.jar", "addon", refs=(OWNER,))
        addon = recipe(source_addon, project, "addon")
        write_manifest(manifest, mod, addon)
        admitted = inventory(source, target, BuildOptions(manifest, allow_preview=True))
        self.assertIn("preview cannot replace its legacy provider", " ".join(plan(admitted, [HASH_BOUND], output_pack=True).problems))
        source_addon.unlink()
        admitted = inventory(source, target, BuildOptions(manifest, allow_preview=True))
        planned = plan(admitted, [HASH_BOUND], output_pack=True)
        self.assertTrue(planned.report["ready"], planned.problems)

        def compile_stub(port, workspace, *_args):
            resources = workspace / "src/main/resources"
            files = {path.relative_to(resources).as_posix(): path.read_bytes() for path in resources.rglob("*") if path.is_file()}
            self.assertEqual(set(files), {f"{LOGIC}.class", PROVENANCE})
            self.assertFalse(any(path.suffix == ".jar" for path in workspace.rglob("*")))
            native(workspace, port.artifact, "techbase", more=files | adapters())
            (workspace / port.game_report).write_text('<testsuite><testcase name="nativecase"/></testsuite>', encoding="utf-8")

        with patch("mcmodup.native_pack.require_jdk", return_value="unused"), patch("mcmodup.source_ports._gradle", compile_stub):
            stage(planned, output)
        report = json.loads((output / REPORT_NAME).read_text())
        details = report["native_builds"]["techbase.jar"]
        self.assertEqual(details["original_sha256"], digest(original))
        self.assertEqual(details["legacy_ids"], mod["legacy_ids"])
        self.assertEqual(details["status"], "preview")
        self.assertFalse(report["parity_verified"])
        self.assertEqual(details["method_imports"][0]["class"], LOGIC)
        with ZipFile(output / "mods/techbase.jar") as artifact:
            proof = json.loads(artifact.read(PROVENANCE))
            self.assertEqual(proof["imports"], details["method_imports"])
            self.assertIn("machine|omitted", proof["legacy_ids"])
        candidate = target / "lib/Preview.jar"
        candidate.parent.mkdir()
        candidate.write_bytes((output / "mods/techbase.jar").read_bytes())
        self.assertNotIn("techbase", inventory(source, target).providers)
        for mapping, status, error in (({"machine": "techbase"}, "preview", "account for every"),
                                       (mod["legacy_ids"], "reviewed", "null only"),
                                       ({"machine": "techbase"}, "reviewed", "require preview")):
            with self.subTest(error=error):
                write_manifest(manifest, mod | {"legacy_ids": mapping, "status": status, "limitations": mod["limitations"] if status == "preview" else []})
                if status == "reviewed":
                    with self.assertRaisesRegex(InputError, error):
                        load_ports(BuildOptions(manifest))
                else:
                    updated = inventory(source, target, BuildOptions(manifest, True))
                    self.assertIn(error, " ".join(plan(updated, [HASH_BOUND], output_pack=True).problems))

    def test_plate_recipe_prepares_stable_source_only_zip(self):
        one = prepare_plate(self.root / "one")
        two = prepare_plate(self.root / "two")
        self.assertEqual(one.read_bytes(), two.read_bytes())
        with self.assertRaisesRegex(InputError, "Choose a new directory"):
            prepare_plate(one.parent)
        port = load_ports(BuildOptions(one))[LEGACY_SHA256]
        self.assertEqual(set(port.ids), {MOD_ID})
        self.assertTrue(port.isolated_preview)
        self.assertEqual(set(port.imports), {TEXTURE})
        self.assertEqual(port.legacy_ids, {"icbm": MOD_ID, "icbm|explosion": None, "icbm|sentry": None})
        with ZipFile(port.archive) as src:
            self.assertIn(f"src/main/java/{KERNEL}/GlassPlate.java", src.namelist())
            self.assertNotIn(f"src/main/resources/{KERNEL}/PlateLogic.class", src.namelist())
            self.assertNotIn(port.imports[TEXTURE], src.namelist())

    @unittest.skipUnless(os.environ.get("MCMODUP_VOLTZ_ARCHIVE"), "exact Voltz archive not supplied")
    def test_actual_voltz_bytes_transplant_the_expected_two_bodies(self):
        with ZipFile(os.environ["MCMODUP_VOLTZ_ARCHIVE"]) as voltz:
            original = self.root / "icbm.jar"
            original.write_bytes(voltz.read("mods/icbm-1.4.1.344.jar"))
        self.assertEqual(digest(original), LEGACY_SHA256)
        port = load_ports(BuildOptions(prepare_plate(self.root / "recipe")))[LEGACY_SHA256]
        [group] = port.method_imports
        with ZipFile(original) as artifact:
            result = transplant(artifact.read(PLATE + ".class"), group)
        self.assertEqual(result.report()["sha256"], "007274c0d2fcf80984e36d2cddfa8d0910df26da774e7a29012a518c4bc8c1d8")
        self.assertEqual(result.report()["call_redirects"][0]["sites"], 1)
