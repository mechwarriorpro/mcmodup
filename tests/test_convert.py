import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from mcmodup.api import ApiContext
from mcmodup.catalogs import ASPECT, CATEGORY, ELEMENT, PROFILES, RADIATION, TEXT, recover_catalog
from mcmodup.classfiles import read_class
from mcmodup.convert import convert_catalog
from mcmodup.inputs import InputError
from mcmodup.pack import open_pack
from test_cli import PNG, ROOT, run_cli
from test_pack import fixture_class, jar, u2


class CatalogClass:
    """Build small classfiles with the original declaration shapes, without a javac dependency."""

    def __init__(self, name):
        self.name = name
        self.type = f"L{name};"
        self.pool = []
        self.ops = []
        self.fields = []

    def entry(self, tag, data):
        self.pool.append(bytes([tag]) + data)
        return len(self.pool)

    def utf(self, text):
        data = text.encode("utf-8")
        return self.entry(1, u2(len(data)) + data)

    def cls(self, name):
        return self.entry(7, u2(self.utf(name)))

    def ref(self, tag, owner, name, typ):
        return self.entry(tag, u2(self.cls(owner), self.entry(12, u2(self.utf(name), self.utf(typ)))))

    def opcode(self, number, operand=None):
        self.ops.append(bytes([number]) + (u2(operand) if operand is not None else b""))

    def push(self, value):
        if isinstance(value, str):
            cp = self.entry(8, u2(self.utf(value)))
        else:
            cp = self.entry(3, struct.pack(">i", value))
        self.opcode(0x13, cp)  # ldc_w

    def get(self, owner, name):
        self.opcode(0xB2, self.ref(9, owner, name, f"L{owner};"))

    def start(self):
        self.opcode(0xBB, self.cls(self.name))
        self.opcode(0x59)

    def declare(self, field, descriptor, access):
        self.opcode(0xB7, self.ref(10, self.name, "<init>", descriptor))
        self.opcode(0xB3, self.ref(9, self.name, field, self.type))
        self.fields.append(u2(access, self.utf(field), self.utf(self.type), 0))

    def bytes(self, *, invalid=False):
        header = u2(1, self.cls(self.name), self.cls("java/lang/Object"), 0)
        instructions = (b"\xA7\x00\x00" if invalid else b"") + b"".join(self.ops) + b"\xb1"
        code = u2(16, 0) + struct.pack(">I", len(instructions)) + instructions + u2(0, 0)
        method = u2(8, self.utf("<clinit>"), self.utf("()V"), 1, self.utf("Code")) + struct.pack(">I", len(code)) + code
        return (b"\xca\xfe\xba\xbe" + u2(0, 52, len(self.pool) + 1) + b"".join(self.pool) + header
                + u2(len(self.fields)) + b"".join(self.fields) + u2(1) + method + u2(0))


def element_class(*, invalid=False):
    data = CatalogClass(ELEMENT)
    for symbol, number, label in (("H", 0, "Hydrogen"), ("He", 1, "Helium")):
        data.start()
        data.push(symbol)
        data.push(number)
        data.push(label)
        data.get(CATEGORY, "nonmetal")
        data.get(CATEGORY, "gas")
        data.get(RADIATION, "stable")
        data.declare(symbol, f"({TEXT}I{TEXT}L{CATEGORY};L{CATEGORY};L{RADIATION};)V", 0x4019)
    return data.bytes(invalid=invalid)


def aspect_class():
    data = CatalogClass(ASPECT)
    for field, key, color in (("AIR", "aer", 0xFFFF7E), ("FIRE", "ignis", 0xFF5A01)):
        data.start()
        data.push(key)
        data.push(color)
        data.push("e")
        data.push(1)
        data.declare(field, f"({TEXT}I{TEXT}I)V", 0x19)
    data.start()
    data.push("lux")
    data.push(0xFFF663)
    data.push(2)
    data.opcode(0xBD, data.cls(ASPECT))
    for index, member in enumerate(("AIR", "FIRE")):
        data.opcode(0x59)
        data.push(index)
        data.get(ASPECT, member)
        data.opcode(0x53)
    data.declare("LIGHT", f"({TEXT}I[L{ASPECT};)V", 0x19)
    return data.bytes()


class ConvertTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)

    def test_literal_catalog_shapes(self):
        info = read_class(element_class(), collect_references=False, capture_initializer=True)
        entries = recover_catalog(PROFILES["minechem-elements"], info)
        self.assertEqual([row.path for row in entries], ["element_h", "element_he"])
        self.assertEqual(entries[1].legacy["legacy_metadata"], 1)
        self.assertEqual(entries[1].legacy["atomic_number"], 2)
        info = read_class(aspect_class(), collect_references=False, capture_initializer=True)
        entries = recover_catalog(PROFILES["thaumcraft-aspects"], info)
        self.assertEqual(entries[-1].path, "aspect_lux")
        self.assertEqual(entries[-1].legacy["components"], ["aer", "ignis"])
        self.assertEqual(entries[-1].legacy["color"], 0xFFF663)

    def test_branches_rejected_instead_of_guessing_catalog(self):
        with self.assertRaisesRegex(InputError, "unsupported control flow"):
            read_class(element_class(invalid=True), capture_initializer=True)

    def test_full_port_requires_explicit_partial_opt_in(self):
        output = self.root / "out.jar"
        proc = run_cli("convert-jar", self.root / "old.jar", "--pack", self.root / "pack.zip",
                       "--profile", "minechem-elements", "-o", output)
        self.assertEqual(proc.returncode, 1)
        self.assertIn("Full automatic conversion is not supported", proc.stderr)
        self.assertFalse(output.exists())

    def test_catalog_provider_is_selected_from_pack_without_copying_old_bytecode(self):
        profile = PROFILES["minechem-elements"]
        consumer = jar(self.root / "legacy.jar", **{
            "entry/Mod.class": fixture_class("entry/Mod", refs=(ELEMENT,)),
            "mcmod.info": '[{"modid":"minechem","mcversion":"1.6.4","requiredMods":["catalog_api"]}]',
        })
        library = jar(self.root / "provider.jar", **{
            f"{ELEMENT}.class": element_class(),
            "assets/minechem/textures/items/filledTestTube.png": PNG,
            "mcmod.info": '[{"modid":"catalog_api","mcversion":"1.6.4"}]',
        })
        pack_file = jar(self.root / "pack.zip", **{"mods/provider.jar": library.read_bytes()})
        output = self.root / "out.jar"

        def fake_gradle(workspace, timeout):
            # Local unit test of generation/publication, not a claim to compile a NeoForge mod.
            files = {p.relative_to(workspace / "src/main/resources").as_posix(): p.read_bytes()
                     for p in (workspace / "src/main/resources").rglob("*") if p.is_file()}
            code = next((workspace / "src/main/java").rglob("*.java")).read_text()
            self.assertIn('registerSimpleItem("element_he"', code)
            owner = f"mcmodup/generated/{profile.namespace}/CatalogPort"
            files[f"{owner}.class"] = fixture_class(owner, refs=("net/neoforged/fml/common/Mod",))
            return jar(workspace / "build/libs/output.jar", **files)

        with open_pack(pack_file, ApiContext(profile="1.6.4"), consumer) as pack:
            chosen = pack.select(pack.selected_archive)
            with patch("mcmodup.convert._gradle", side_effect=InputError("build failed")):
                with self.assertRaisesRegex(InputError, "build failed"):
                    convert_catalog(pack, chosen, profile, output, 15)
            self.assertFalse(output.exists())
            with patch("mcmodup.convert._gradle", side_effect=fake_gradle):
                result = convert_catalog(pack, chosen, profile, output, 15)
            self.assertEqual(result.entries, 2)
            with self.assertRaisesRegex(InputError, "Existing files"):
                convert_catalog(pack, chosen, profile, output, 15)
        with zipfile.ZipFile(output) as archive:
            doc = json.loads(archive.read("data/minechem_elements/mcmodup/catalog.json"))
            self.assertFalse(doc["full_port"])
            self.assertEqual(doc["source_sha256"], hashlib.sha256(consumer.read_bytes()).hexdigest())
            self.assertEqual(doc["dependencies_observed"]["declared"][0]["status"], "present")
            self.assertFalse(doc["dependencies_observed"]["bundled_legacy_dependencies"])
            self.assertNotIn(f"{ELEMENT}.class", archive.namelist())
            self.assertEqual(archive.read("assets/minechem_elements/textures/item/catalog/0.png"), PNG)

    @unittest.skipUnless(os.environ.get("MCMODUP_TEST_CONVERT_BUILD") and shutil.which("javac"),
                         "set MCMODUP_TEST_CONVERT_BUILD with JDK 21 and Gradle network access")
    def test_compiles_two_real_neoforge_catalog_jars(self):
        sources = {
            ELEMENT + ".java": '''package minechem.item.element;
import minechem.radiation.RadiationEnum;
public enum ElementEnum {
    H("Hydrogen", ElementClassificationEnum.nonmetal, ElementClassificationEnum.gas, RadiationEnum.stable),
    He("Helium", ElementClassificationEnum.nonmetal, ElementClassificationEnum.gas, RadiationEnum.stable);
    ElementEnum(String text, ElementClassificationEnum category, ElementClassificationEnum state, RadiationEnum radiation) {}
}''',
            CATEGORY + ".java": "package minechem.item.element; public enum ElementClassificationEnum {nonmetal, gas}",
            RADIATION + ".java": "package minechem.radiation; public enum RadiationEnum {stable}",
            ASPECT + ".java": '''package thaumcraft.api.aspects;
public class Aspect {
    public static final Aspect AIR = new Aspect("aer", 0xffff7e, "e", 1);
    public static final Aspect FIRE = new Aspect("ignis", 0xff5a01, "c", 1);
    public static final Aspect LIGHT = new Aspect("lux", 0xfff663, new Aspect[]{AIR, FIRE});
    public Aspect(String tag, int color, String chat, int blend) {}
    public Aspect(String tag, int color, Aspect[] components) {}
}''',
        }
        to_compile = []
        for name, source in sources.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            to_compile.append(str(path))
        classes = self.root / "classes"
        subprocess.run(["javac", "--release", "8", "-d", str(classes), *to_compile], check=True, timeout=30)
        library = jar(self.root / "pack.jar", **{"mods/dep.jar": jar(self.root / "dep.jar", **{
            "fake/Dep.class": fixture_class("fake/Dep")}).read_bytes()})
        for profile in PROFILES.values():
            with self.subTest(profile=profile.name):
                entries = {path.relative_to(classes).as_posix(): path.read_bytes()
                           for path in (classes / profile.mod_id).rglob("*.class")}
                entries["mcmod.info"] = json.dumps([{"modid": profile.mod_id, "mcversion": profile.version}])
                textures = ("assets/minechem/textures/items/filledTestTube.png",) if profile.mod_id == "minechem" else (
                    f"assets/thaumcraft/textures/aspects/{key}.png" for key in ("aer", "ignis", "lux"))
                entries.update({path: PNG for path in textures})
                mod = jar(self.root / f"{profile.mod_id}.jar", **entries)
                output = self.root / f"{profile.namespace}-partial.jar"
                proc = subprocess.run([os.sys.executable, "-m", "mcmodup", "convert-jar", str(mod), "--pack", str(library),
                                       "--profile", profile.name, "--allow-partial", "-o", str(output)],
                                      cwd=ROOT, text=True, capture_output=True, timeout=720)
                self.assertEqual(proc.returncode, 0, proc.stderr)
                self.assertIn("Built partial NeoForge 1.21.1 JAR", proc.stdout)
                with zipfile.ZipFile(output) as built:
                    raw = json.loads(built.read(f"data/{profile.namespace}/mcmodup/catalog.json"))
                    self.assertEqual(len(raw["entries"]), 2 if profile.mod_id == "minechem" else 3)
                    self.assertTrue(built.read("META-INF/MCModUp-NOTICE.txt").startswith(b"PARTIAL PORT"))
