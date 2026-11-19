import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from mcmodup.api import ApiContext
from mcmodup.catalogs import (ASPECT, CATEGORY, CHEMICAL, ELEMENT, MOLECULE, PROFILES, RADIATION, RELEASE_394_PROFILE,
                             RELEASE_394_SHA256, ROOM_STATE, TEXT, CatalogEntry, recover_catalog)
from mcmodup.classfiles import read_class
from mcmodup.convert import _project_files, _recipe_files, convert_catalog
from mcmodup.inputs import InputError
from mcmodup.minechem_final_synthesis import observations
from mcmodup.machines import FINAL_LEGACY_SHA256, JAVA_CLASSES, DecomposerMode, machine_classes, machine_recipes, release394_trace
from mcmodup.pack import open_pack
from neoforge_smoke import check_recipes_in_game
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

    def declare(self, field, descriptor, access, *, factory=None):
        self.opcode(0xB8 if factory else 0xB7, self.ref(10, self.name, factory or "<init>", descriptor))
        self.opcode(0xB3, self.ref(9, self.name, field, self.type))
        self.fields.append(u2(access, self.utf(field), self.utf(self.type), 0))

    def bytes(self, *, invalid=False):
        header = u2(1, self.cls(self.name), self.cls("java/lang/Object"), 0)
        instructions = (b"\xA7\x00\x00" if invalid else b"") + b"".join(self.ops) + b"\xb1"
        code = u2(16, 0) + struct.pack(">I", len(instructions)) + instructions + u2(0, 0)
        method = u2(8, self.utf("<clinit>"), self.utf("()V"), 1, self.utf("Code")) + struct.pack(">I", len(code)) + code
        return (b"\xca\xfe\xba\xbe" + u2(0, 52, len(self.pool) + 1) + b"".join(self.pool) + header
                + u2(len(self.fields)) + b"".join(self.fields) + u2(1) + method + u2(0))


def element_class(*, invalid=False, factory=False):
    data = CatalogClass(ELEMENT)
    state = ROOM_STATE if factory else CATEGORY
    for symbol, number, label in (("H", 0, "Hydrogen"), ("O", 1, "Oxygen")):
        if factory:
            data.push(number + 1)
            data.push(symbol)
        else:
            data.start()
            data.push(symbol)
            data.push(number)
        data.push(label)
        data.get(CATEGORY, "nonmetal")
        data.get(state, "gas")
        data.get(RADIATION, "stable")
        args = f"L{CATEGORY};L{state};L{RADIATION};"
        if factory:
            data.declare(symbol, f"(I{TEXT}{TEXT}{args})L{ELEMENT};", 0x19, factory="addElement")
        else:
            data.declare(symbol, f"({TEXT}I{TEXT}{args})V", 0x4019)
    return data.bytes(invalid=invalid)


def molecule_class(*, factory=False, factory_name="addMolecule", second_metadata=4):
    data = CatalogClass(MOLECULE)
    for index, (name, parts) in enumerate((
        ("water", ((ELEMENT, "H", 2), (ELEMENT, "O", 1))),
        ("nested", ((MOLECULE, "water", 3),)),
    )):
        if not factory:
            data.start()
        data.push(name)
        data.push(second_metadata if factory and index else index)
        if factory:
            if index == 0:
                for op in (0x0B, 0x0B, 0x0C, 0x0B, 0x0B, 0x0C):
                    data.opcode(op)
            data.get(ROOM_STATE, "liquid")
        else:
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
        if factory:
            colors = "FFFFFF" if index == 0 else ""
            data.declare(name, f"({TEXT}I{colors}L{ROOM_STATE};[L{CHEMICAL};)L{MOLECULE};", 0x19, factory=factory_name)
        else:
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

    def test_394_static_factories_preserve_explicit_ids_without_running_code(self):
        profile = PROFILES[RELEASE_394_PROFILE]
        element = read_class(element_class(factory=True), capture_initializer=True)
        molecule = read_class(molecule_class(factory=True), capture_initializer=True)
        rows = recover_catalog(profile, {ELEMENT: element, MOLECULE: molecule})
        self.assertEqual([row.legacy["legacy_metadata"] for row in rows], [1, 2, 0, 4])
        self.assertEqual(rows[1].legacy["atomic_number"], 2)
        self.assertEqual(rows[2].legacy["state"], "liquid")
        self.assertEqual(rows[2].legacy["render_colors"], [0.0, 0.0, 1.0, 0.0, 0.0, 1.0])
        self.assertEqual(rows[3].components, (("molecule_water", 3),))
        for declaration in (molecule_class(factory=True, factory_name="unreviewed"),
                            molecule_class(factory=True, second_metadata=0)):
            broken = read_class(declaration, capture_initializer=True)
            with self.assertRaisesRegex(InputError, "Unsupported Minechem molecule declaration"):
                recover_catalog(profile, {ELEMENT: element, MOLECULE: broken})

    def test_394_profile_is_hash_bound_and_does_not_duplicate_final_recipes(self):
        profile = PROFILES[RELEASE_394_PROFILE]
        trace = release394_trace()
        symbols = [f"e{i}" for i in range(1, 114)] + [key for key in trace if re.fullmatch(r"m\d+", key)]
        entries = [CatalogEntry({"e26": "element_fe", "m1": "molecule_water"}.get(key,
                                ("element_" if key[0] == "e" else "molecule_") + key[1:]),
                                key, "test.png", (), {"legacy_metadata": int(key[1:])}) for key in symbols]
        files, count = _project_files(profile, entries, {"test.png": PNG}, RELEASE_394_SHA256, {}, [])
        self.assertEqual(count, 1538)
        self.assertEqual(len([key for key in trace if re.fullmatch(r"m\d+", key)]), 173)
        with self.assertRaisesRegex(InputError, "catalog does not match"):
            _project_files(profile, [row for row in entries if row.path != "molecule_water"], {"test.png": PNG}, RELEASE_394_SHA256, {}, [])
        with self.assertRaisesRegex(InputError, "Could not map original Minechem chemical metadata e26"):
            _project_files(profile, [row for row in entries if row.path != "element_fe"], {"test.png": PNG}, RELEASE_394_SHA256, {}, [])
        package = "src/main/java/mcmodup/generated/minechem_chemistry_394"
        self.assertEqual({Path(name).stem for name in files if name.startswith(package + "/") and name.endswith(".java")},
                         {"CatalogPort", *machine_classes(DecomposerMode.RELEASE_394)})
        self.assertIn("PREPAID_DECOMPOSER = true;", files[f"{package}/ChemistryMachines.java"])
        self.assertIn("ChemistryResearch.register", files[f"{package}/CatalogPort.java"])
        self.assertIn(f"{package}/ResearchScreens.java", files)
        resources = "src/main/resources/data/minechem_chemistry_394"
        self.assertIn(f"{resources}/recipe/synthesizer.json", files)
        self.assertEqual(json.loads(files[f"{resources}/recipe/chemist_journal.json"])["ingredients"],
                         [{"item": "minecraft:book"}, {"item": "minecraft:glass_pane"}])
        self.assertIn("Synthesis394Rules.ALL.stream()", files[f"{package}/ChemistryRecipes.java"])
        catalog = json.loads(files[f"{resources}/mcmodup/catalog.json"])
        self.assertFalse(catalog["full_port"])
        self.assertEqual(catalog["decomposer_mode"], DecomposerMode.RELEASE_394)
        rows = {row["id"]: row for row in catalog["machine_recipes"] if row["machine"] == "decomposer"}
        self.assertEqual({row["energy_fe"] for row in rows.values()}, {1_000})
        synth = {row["id"]: row for row in catalog["machine_recipes"] if row["machine"] == "synthesizer"}
        self.assertEqual(len(synth), 399)
        self.assertIn("minechem_chemistry_394:molecule_140", synth)
        self.assertIn("minechem_chemistry_394:molecule_143", synth)
        self.assertEqual(json.loads(files[f"{resources}/recipe/journal_clone.json"]),
                         {"type": "minechem_chemistry_394:journal_clone", "category": "misc"})
        for block in ("decomposer", "synthesizer", "microscope"):
            recipe = json.loads(files[f"{resources}/recipe/{block}.json"])
            self.assertEqual(recipe["key"]["I"], {"item": "minecraft:copper_ingot"})
        self.assertEqual(synth["minecraft:iron_ingot"]["energy_fe"], 3_000)
        self.assertEqual(synth["minecraft:iron_ingot"]["consumption"], "on_extraction")
        self.assertTrue(synth["minecraft:glass"]["shaped"])
        self.assertIsNone(synth["minecraft:glass"]["ghost_pattern"][1])
        self.assertEqual(synth["minecraft:stone"]["outputs"], [{"id": "minecraft:stone", "count": 16}])
        self.assertEqual(rows["minecraft:iron_ingot"]["outputs"], [{"id": "minechem_chemistry_394:element_fe", "count": 16}])
        self.assertEqual(rows["minecraft:iron_block"]["outputs"][0]["count"], 144)
        self.assertEqual(rows["minecraft:water_bucket"]["outputs"], [
            {"id": "minechem_chemistry_394:molecule_water", "count": 16},
            {"id": "minechem_chemistry_394:element_fe", "count": 48},
        ])
        self.assertEqual(rows["minecraft:water"]["fluid_input"], {"id": "minecraft:water", "millibuckets": 1000})
        self.assertFalse(rows["minecraft:water"]["inputs"])
        self.assertEqual(next(row for row in catalog["entries"] if row["new_id"].endswith(":molecule_water"))["recipe_status"],
                         "powered_decomposition")
        self.assertTrue(rows["minecraft:stone"]["material_rule"].startswith("S0.2!"))
        for digest in (FINAL_LEGACY_SHA256, "0" * 64):
            with self.assertRaisesRegex(InputError, "requires the exact original archive"):
                _project_files(profile, entries, {}, digest, {}, [])
        counterfeit = jar(self.root / "Minechem-1.7.10-5.0.5.394.jar", **{
            "mcmod.info": '[{"modid":"minechem","mcversion":"1.7.10","version":"5.0.5.394"}]',
            f"{ELEMENT}.class": element_class(factory=True),
            f"{MOLECULE}.class": molecule_class(factory=True),
        })
        output = self.root / "counterfeit.jar"
        result = run_cli("convert-jar", counterfeit, "--pack", counterfeit, "--profile", RELEASE_394_PROFILE,
                         "--allow-partial", "-o", output)
        self.assertEqual(result.returncode, 1)
        self.assertIn("requires the exact original archive", result.stderr)
        self.assertFalse(output.exists())

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

    def test_machine_recipes_keep_large_stacks_and_ambiguous_formulas(self):
        entries = [CatalogEntry(path, "Test", "", (), {}, (("element_h", 65),))
                   for path in ("molecule_foo", "molecule_bar")]
        rules = machine_recipes("test", entries)
        self.assertEqual(len(rules), 4)
        self.assertEqual(rules[0].id, "test:molecule_bar")
        self.assertEqual(rules[1].outputs, (("test:element_h", 65),))
        self.assertEqual(rules[1].energy, 65)
        self.assertEqual(rules[2].inputs, rules[0].inputs)

    def test_buffered_decomposer_is_bound_to_exact_final_archive(self):
        profile = PROFILES["minechem-chemistry"]
        path = "src/main/java/mcmodup/generated/minechem_chemistry/ChemistryMachines.java"
        report = "src/main/resources/data/minechem_chemistry/mcmodup/catalog.json"
        for checksum, mode, enabled in ((FINAL_LEGACY_SHA256, DecomposerMode.FINAL, "true"),
                                         ("1" * 64, DecomposerMode.BATCH, "false")):
            with self.subTest(mode=mode):
                keys = {row.key for row in observations() if row.key.startswith("m")}
                keys |= {f"e{i}" for i in range(113)}
                entries = [CatalogEntry(("element_" if key[0] == "e" else "molecule_") + key[1:], key, "test.png", (),
                                        {"legacy_metadata": int(key[1:])}) for key in sorted(keys)] if mode == DecomposerMode.FINAL else []
                files, _ = _project_files(profile, entries, {"test.png": PNG} if entries else {}, checksum, {}, [])
                java = path.rsplit("/", 1)[0] + "/"
                self.assertEqual({Path(name).stem for name in files if name.startswith(java) and name.endswith(".java")},
                                 {"CatalogPort", *machine_classes(mode)})
                expected = path.replace("ChemistryMachines", "SynthesizerFinal")
                self.assertEqual(expected in files, mode == DecomposerMode.FINAL)
                self.assertIn(f"ORIGINAL_DECOMPOSER = {enabled};", files[path])
                self.assertEqual(json.loads(files[report])["decomposer_mode"], mode)
                self.assertFalse(json.loads(files[report])["full_port"])

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
            code = next((workspace / "src/main/java").rglob("CatalogPort.java")).read_text()
            self.assertIn('registerSimpleItem("element_o"', code)
            owner = f"mcmodup/generated/{profile.namespace}/CatalogPort"
            files[f"{owner}.class"] = fixture_class(owner, refs=("net/neoforged/fml/common/Mod",))
            for name in JAVA_CLASSES:
                generated = f"mcmodup/generated/{profile.namespace}/{name}"
                files[f"{generated}.class"] = fixture_class(generated)
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
            self.assertEqual(result.recipes, 5)
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
            self.assertIn("Journal cloning requires the exact FINAL release", doc["not_ported"])
            self.assertEqual(result.profile.not_ported, doc["not_ported"])
            self.assertNotIn("data/minechem_chemistry/recipe/journal_clone.json", archive.namelist())
            self.assertNotIn("data/minechem_chemistry/recipe/combine/molecule_water.json", archive.namelist())
            for item in ("microscope", "chemist_journal"):
                recipe_data = json.loads(archive.read(f"data/minechem_chemistry/recipe/{item}.json"))
                self.assertEqual(recipe_data["result"], {"id": f"minechem_chemistry:{item}", "count": 1})
                self.assertIn(f"assets/minechem_chemistry/models/item/{item}.json", archive.namelist())
            recipe = next(r for r in doc["machine_recipes"] if r["id"].endswith(":molecule_water")
                          and r["machine"] == "synthesizer")
            self.assertEqual(recipe["energy_fe"], 500)
            self.assertEqual(recipe["inputs"], [{"id": "minechem_chemistry:element_h", "count": 2},
                                                {"id": "minechem_chemistry:element_o", "count": 1}])

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
    water(0, 0, 0, 1, 0, 0.5f, 1, new Element(ElementEnum.H, 2), new Element(ElementEnum.O)),
    hydrogenPeroxide(1, 0, 0, 1, 0, 0.5f, 1, new Element(ElementEnum.H, 2), new Element(ElementEnum.O, 2));
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
        outputs = []
        fixtures = [profile for profile in PROFILES.values() if profile.source_sha256 is None]
        for profile in fixtures:
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
                    self.assertEqual(len(raw["entries"]), 4 if profile.mod_id == "minechem" else 3)
                    self.assertEqual(raw["entries"][-1]["recipe_status"],
                                     "powered_synthesis" if profile.mod_id == "minechem" else "crafting_shapeless")
                    self.assertTrue(built.read("META-INF/MCModUp-NOTICE.txt").startswith(b"PARTIAL PORT"))
                outputs.append(output)
        if len(outputs) == len(fixtures):
            check_recipes_in_game(self.root / "smoke", outputs)
