import gzip
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
from mcmodup.catalogs import (ASPECT, CATEGORY, CHEMICAL, ELEMENT, MOLECULE, PROFILES, RADIATION, TEXT,
                             CatalogEntry, recover_catalog)
from mcmodup.classfiles import read_class
from mcmodup.convert import _recipe_files, convert_catalog
from mcmodup.inputs import InputError
from mcmodup.pack import open_pack
from mcmodup.audit import ModMetadata
from mcmodup.port import _build_gradle, _metadata, write_workspace
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
    for symbol, number, label in (("H", 0, "Hydrogen"), ("O", 1, "Oxygen")):
        data.start()
        data.push(symbol)
        data.push(number)
        data.push(label)
        data.get(CATEGORY, "nonmetal")
        data.get(CATEGORY, "gas")
        data.get(RADIATION, "stable")
        data.declare(symbol, f"({TEXT}I{TEXT}L{CATEGORY};L{CATEGORY};L{RADIATION};)V", 0x4019)
    return data.bytes(invalid=invalid)


def molecule_class():
    data = CatalogClass(MOLECULE)
    for index, (name, parts) in enumerate((
        ("water", ((ELEMENT, "H", 2), (ELEMENT, "O", 1))),
        ("nested", ((MOLECULE, "water", 3),)),
    )):
        data.start()
        data.push(name)
        data.push(index)
        data.push(index)
        data.push(len(parts))
        data.opcode(0xBD, data.cls(CHEMICAL))
        for i, (owner, constant, count) in enumerate(parts):
            obj = "minechem/item/element/Element" if owner == ELEMENT else "minechem/item/molecule/Molecule"
            data.opcode(0x59)
            data.push(i)
            data.opcode(0xBB, data.cls(obj))
            data.opcode(0x59)
            data.get(owner, constant)
            if count != 1:
                data.push(count)
            desc = f"(L{owner};{'I' if count != 1 else ''})V"
            data.opcode(0xB7, data.ref(10, obj, "<init>", desc))
            data.opcode(0x53)
        data.declare(name, f"({TEXT}II[L{CHEMICAL};)V", 0x4019)
    return data.bytes()


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
        classes = {owner: read_class(data, collect_references=False, capture_initializer=True)
                   for owner, data in ((ELEMENT, element_class()), (MOLECULE, molecule_class()), (ASPECT, aspect_class()))}
        entries = recover_catalog(PROFILES["minechem-chemistry"], classes)
        self.assertEqual([row.path for row in entries], ["element_h", "element_o", "molecule_water", "molecule_nested"])
        self.assertEqual(entries[1].legacy["legacy_metadata"], 1)
        self.assertEqual(entries[1].legacy["atomic_number"], 2)
        self.assertEqual(entries[2].components, (("element_h", 2), ("element_o", 1)))
        self.assertEqual(entries[3].components, (("molecule_water", 3),))
        entries = recover_catalog(PROFILES["thaumcraft-aspects"], classes)
        self.assertEqual(entries[-1].path, "aspect_lux")
        self.assertEqual(entries[-1].legacy["components"], ["aer", "ignis"])
        self.assertEqual(entries[-1].legacy["color"], 0xFFF663)

    def test_recipes_skip_ambiguous_and_oversized_inputs(self):
        entries = [CatalogEntry(path, "Test", "", (), {}, ingredients) for path, ingredients in (
            ("first", (("x", 1), ("y", 1))), ("second", (("y", 1), ("x", 1))),
            ("third", (("x", 10),)), ("fourth", (("x", 2),)),
        )]
        files, statuses = _recipe_files("test", entries)
        self.assertEqual(statuses, {"first": "ambiguous_ingredients", "second": "ambiguous_ingredients",
                                    "third": "too_many_crafting_slots", "fourth": "crafting_shapeless"})
        self.assertEqual(len(files), 1)
        recipe = json.loads(files["src/main/resources/data/test/recipe/combine/fourth.json"])
        self.assertEqual(recipe["ingredients"], [{"item": "test:x"}] * 2)
        self.assertEqual(recipe["result"], {"id": "test:fourth", "count": 1})

    def test_branches_rejected_instead_of_guessing_catalog(self):
        with self.assertRaisesRegex(InputError, "unsupported control flow"):
            read_class(element_class(invalid=True), capture_initializer=True)

    def test_full_port_requires_explicit_partial_opt_in(self):
        output = self.root / "out.jar"
        proc = run_cli("convert-jar", self.root / "old.jar", "--pack", self.root / "pack.zip",
                       "--profile", "minechem-chemistry", "-o", output)
        self.assertEqual(proc.returncode, 1)
        self.assertIn("Full automatic conversion is not supported", proc.stderr)
        self.assertFalse(output.exists())

    def test_catalog_provider_is_selected_from_pack_without_copying_old_bytecode(self):
        profile = PROFILES["minechem-chemistry"]
        consumer = jar(self.root / "legacy.jar", **{
            "entry/Mod.class": fixture_class("entry/Mod", refs=(ELEMENT, MOLECULE)),
            "mcmod.info": '[{"modid":"minechem","mcversion":"1.6.4","requiredMods":["catalog_api"]}]',
        })
        library = jar(self.root / "provider.jar", **{
            f"{ELEMENT}.class": element_class(),
            f"{MOLECULE}.class": molecule_class(),
            "assets/minechem/textures/items/filledTestTube.png": PNG,
            "assets/minechem/textures/items/filledMolecule.png": PNG,
            "mcmod.info": '[{"modid":"catalog_api","mcversion":"1.6.4"}]',
        })
        pack_file = jar(self.root / "pack.zip", **{"mods/provider.jar": library.read_bytes()})
        output = self.root / "out.jar"

        def fake_gradle(workspace, timeout):
            # Local unit test of generation/publication, not a claim to compile a NeoForge mod.
            files = {p.relative_to(workspace / "src/main/resources").as_posix(): p.read_bytes()
                     for p in (workspace / "src/main/resources").rglob("*") if p.is_file()}
            code = next((workspace / "src/main/java").rglob("*.java")).read_text()
            self.assertIn('registerSimpleItem("element_o"', code)
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
            self.assertEqual(result.entries, 4)
            self.assertEqual(result.recipes, 2)
            with self.assertRaisesRegex(InputError, "Existing files"):
                convert_catalog(pack, chosen, profile, output, 15)
        with zipfile.ZipFile(output) as archive:
            doc = json.loads(archive.read("data/minechem_chemistry/mcmodup/catalog.json"))
            self.assertFalse(doc["full_port"])
            self.assertEqual(doc["source_sha256"], hashlib.sha256(consumer.read_bytes()).hexdigest())
            self.assertEqual(doc["dependencies_observed"]["declared"][0]["status"], "present")
            self.assertFalse(doc["dependencies_observed"]["bundled_legacy_dependencies"])
            self.assertNotIn(f"{ELEMENT}.class", archive.namelist())
            self.assertNotIn(f"{MOLECULE}.class", archive.namelist())
            self.assertEqual(archive.read("assets/minechem_chemistry/textures/item/catalog/0.png"), PNG)
            recipe = json.loads(archive.read("data/minechem_chemistry/recipe/combine/molecule_water.json"))
            self.assertEqual([value["item"] for value in recipe["ingredients"]],
                             ["minechem_chemistry:element_h"] * 2 + ["minechem_chemistry:element_o"])

    @unittest.skipUnless(os.environ.get("MCMODUP_TEST_CONVERT_BUILD") and shutil.which("javac"),
                         "set MCMODUP_TEST_CONVERT_BUILD with JDK 21 and Gradle network access")
    def test_compiles_two_neoforge_jars_from_synthetic_inputs(self):
        sources = {
            ELEMENT + ".java": '''package minechem.item.element;
import minechem.radiation.RadiationEnum;
public enum ElementEnum {
    H("Hydrogen", ElementClassificationEnum.nonmetal, ElementClassificationEnum.gas, RadiationEnum.stable),
    O("Oxygen", ElementClassificationEnum.nonmetal, ElementClassificationEnum.gas, RadiationEnum.stable);
    ElementEnum(String text, ElementClassificationEnum category, ElementClassificationEnum state, RadiationEnum radiation) {}
}''',
            CATEGORY + ".java": "package minechem.item.element; public enum ElementClassificationEnum {nonmetal, gas}",
            RADIATION + ".java": "package minechem.radiation; public enum RadiationEnum {stable}",
            CHEMICAL + ".java": "package minechem.potion; public class PotionChemical {}",
            "minechem/item/element/Element.java": """package minechem.item.element;
public class Element extends minechem.potion.PotionChemical {
    public Element(ElementEnum element) {}
    public Element(ElementEnum element, int amount) {}
}""",
            MOLECULE + ".java": """package minechem.item.molecule;
import minechem.item.element.Element;
import minechem.item.element.ElementEnum;
import minechem.potion.PotionChemical;
public enum MoleculeEnum {
    water(0, 0, 0, 1, 0, 0.5f, 1, new Element(ElementEnum.H, 2), new Element(ElementEnum.O));
    MoleculeEnum(int id, float r, float g, float b, float r2, float g2, float b2, PotionChemical... parts) {}
}""",
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
        outputs = {}
        for profile in PROFILES.values():
            with self.subTest(profile=profile.name):
                entries = {path.relative_to(classes).as_posix(): path.read_bytes()
                           for path in (classes / profile.mod_id).rglob("*.class")}
                entries["mcmod.info"] = json.dumps([{"modid": profile.mod_id, "mcversion": profile.version}])
                textures = ([f"assets/minechem/textures/items/{name}.png" for name in ("filledTestTube", "filledMolecule")]
                            if profile.mod_id == "minechem" else [f"assets/thaumcraft/textures/aspects/{key}.png"
                                                                 for key in ("aer", "ignis", "lux")])
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
                    self.assertEqual(len(raw["entries"]), 3)
                    self.assertEqual(raw["entries"][-1]["recipe_status"], "crafting_shapeless")
                    self.assertTrue(built.read("META-INF/MCModUp-NOTICE.txt").startswith(b"PARTIAL PORT"))
                outputs[profile.namespace] = output
        if len(outputs) == len(PROFILES):
            self.check_recipes_in_game(outputs)

    def check_recipes_in_game(self, outputs):
        namespace = "mcmodup_smoke"
        project = self.root / "smoke"
        # Register the separately built JARs as runtime mods, not as source sets.
        runtime = ", ".join(f"'fixtures/{path.name}'" for path in outputs.values())
        gradle = _build_gradle(namespace, "mcmodup.test", "1.0.0") + f"""
dependencies {{ runtimeOnly files({runtime}) }}
neoForge.runs.create('gameTestServer') {{
    type = 'gameTestServer'
    systemProperty 'neoforge.enabledGameTestNamespaces', '{namespace}'
}}
"""
        # Minimal, empty 1x1x1 structure in named NBT. GameTest requires a template even when
        # the test only exercises registries and recipes.
        nbt = (b"\x0a\x00\x00"                         # root compound
               b"\x03\x00\x0bDataVersion" + struct.pack(">i", 3955)
               + b"\x09\x00\x04size\x03" + struct.pack(">4i", 3, 1, 1, 1)
               + b"\x09\x00\x07palette\x0a\x00\x00\x00\x01\x08\x00\x04Name\x00\x0dminecraft:air\x00"
               + b"\x09\x00\x06blocks\x0a\x00\x00\x00\x00"
               + b"\x09\x00\x08entities\x0a\x00\x00\x00\x00\x00")
        fixture = (Path(__file__).with_name("fixtures") / "CatalogSmoke.java").read_bytes()
        write_workspace(project, {
            "settings.gradle": "rootProject.name = 'catalog-smoke'\n",
            "build.gradle": gradle,
            "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
            "src/main/java/mcmodup/test/CatalogSmoke.java": fixture,
            "src/main/resources/META-INF/neoforge.mods.toml": _metadata(ModMetadata(""), namespace, "Catalog smoke tests", "1.0.0", "Unlicense"),
            f"src/main/resources/data/{namespace}/structure/empty.nbt": gzip.compress(nbt, mtime=0),
            **{f"fixtures/{path.name}": path.read_bytes() for path in outputs.values()},
        })
        run = subprocess.run(["./gradlew", "--no-daemon", "--console=plain", "runGameTestServer"],
                             cwd=project, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=420)
        self.assertEqual(run.returncode, 0, run.stdout[-8000:])
        self.assertIn("All 2 required tests passed", run.stdout, run.stdout[-8000:])
