"""Exercise the literal recipe boundary without downloading Minecraft or running JVM code."""

from io import BytesIO
import json
from pathlib import Path
import shutil
import struct
import tempfile
import unittest
from zipfile import ZipFile

from mcmodup.inputs import InputError
from mcmodup.native_pack import inventory, plan, stage
from mcmodup.recipe_conversion import PROVENANCE, analyze
from mcmodup.recipe_symbols import VanillaNames
from mcmodup.classfiles import Symbol
from test_convert import CatalogClass
from test_native_pack import native, setup_platform
from test_pack import jar, u2


MOD = "Lcpw/mods/fml/common/Mod;"
EVENTS = "cpw/mods/fml/common/event"
GAME_REGISTRY = "cpw/mods/fml/common/registry/GameRegistry"
STACK = "net/minecraft/item/ItemStack"
ITEM = "net/minecraft/item/Item"
ITEMS = "net/minecraft/init/Items"
BLOCKS = "net/minecraft/init/Blocks"
BLOCK = "net/minecraft/block/Block"


def attribute(index, body):
    return u2(index) + struct.pack(">I", len(body)) + body


class RecipeClass(CatalogClass):
    """Encode tiny Java 6 classfiles with the same API call shapes as legacy mods."""

    def __init__(self, mod_id="RecipeTest", source_version=""):
        super().__init__("fixtures/LiteralRecipes")
        self.methods = []
        options = {"modid": mod_id, "name": "Original Recipes", "version": "2.0.1"}
        if source_version:
            options["acceptedMinecraftVersions"] = f"[{source_version}]"
        self.annotations = {MOD: options}
        self.opcode(0x2A)  # aload_0
        self.call(0xB7, "java/lang/Object", "<init>", "()V")
        self.method("<init>", "()V")

    def annotation(self, rows):
        payload = u2(len(rows))
        for owner, options in rows.items():
            payload += u2(self.utf(owner), len(options))
            for key, value in options.items():
                payload += u2(self.utf(key))
                if isinstance(value, bool):
                    payload += b"Z" + u2(self.entry(3, struct.pack(">i", value)))
                else:
                    payload += b"s" + u2(self.utf(value))
        return attribute(self.utf("RuntimeVisibleAnnotations"), payload)

    def call(self, opcode, owner, name, desc):
        self.opcode(opcode, self.ref(10, owner, name, desc))

    def field(self, owner, name, desc, opcode=0xB2):
        self.opcode(opcode, self.ref(9, owner, name, desc))

    def method(self, name, desc, annotations=None, flags=1):
        instructions = b"".join(self.ops) + b"\xb1"
        code = u2(16, 2) + struct.pack(">I", len(instructions)) + instructions + u2(0, 0)
        attrs = [attribute(self.utf("Code"), code)]
        if annotations:
            attrs.append(self.annotation(annotations))
        self.methods.append(u2(flags, self.utf(name), self.utf(desc), len(attrs)) + b"".join(attrs))
        self.ops.clear()

    def init(self, version):
        handler = "$Init" if version == "1.5.2" else "$EventHandler"
        self.method("init", f"(L{EVENTS}/FMLInitializationEvent;)V", {MOD[:-1] + handler + ";": {}})

    def bytes(self):
        header = u2(0x21, self.cls(self.name), self.cls("java/lang/Object"), 0)
        annotations = self.annotation(self.annotations)
        return (b"\xca\xfe\xba\xbe" + u2(0, 50, len(self.pool) + 1) + b"".join(self.pool) + header
                + u2(len(self.fields)) + b"".join(self.fields)
                + u2(len(self.methods)) + b"".join(self.methods) + u2(1) + annotations)

    def smelt(self, version, *, xp=1.0, input_id=367):
        stack, item, leather = ("wm", "wk", "aG") if version == "1.5.2" else (STACK, ITEM, "field_77770_aF")
        if version == "1.6.4" and input_id == 367:
            self.field(item, "field_77737_bm", f"L{item};")
            self.field(item, "field_77779_bT", "I", opcode=0xB4)
        else:
            self.push(input_id)
        self.opcode(0xBB, self.cls(stack))
        self.opcode(0x59)
        self.field(item, leather, f"L{item};")
        self.push(1)
        self.call(0xB7, stack, "<init>", f"(L{item};I)V")
        self.opcode(0x13, self.entry(4, struct.pack(">f", xp)))
        self.call(0xB8, GAME_REGISTRY, "addSmelting", f"(IL{stack};F)V")

    def craft(self, *, shape=None, wool_damage=None, count=1):
        self.opcode(0xBB, self.cls(STACK))
        self.opcode(0x59)
        self.field(ITEMS, "field_151141_av", f"L{ITEM};")
        self.push(count)
        self.call(0xB7, STACK, "<init>", f"(L{ITEM};I)V")
        args = (*shape, 'char:W', 'wool', 'char:L', 'leather') if shape else ('wool', 'leather', 'leather')
        self.push(len(args))
        self.opcode(0xBD, self.cls("java/lang/Object"))
        for i, value in enumerate(args):
            self.opcode(0x59)
            self.push(i)
            if value.startswith("char:"):
                self.push(ord(value[-1]))
                self.call(0xB8, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;")
            elif value == "wool":
                if wool_damage is not None:
                    self.opcode(0xBB, self.cls(STACK))
                    self.opcode(0x59)
                self.field(BLOCKS, "field_150325_L", f"L{BLOCK};")
                if wool_damage is not None:
                    # The old ingredient stack's count is not a match predicate.
                    self.push(3)
                    self.push(wool_damage)
                    self.call(0xB7, STACK, "<init>", f"(L{BLOCK};II)V")
            elif value == "leather":
                self.field(ITEMS, "field_151116_aA", f"L{ITEM};")
            else:
                self.push(value)
            self.opcode(0x53)
        if shape:
            self.call(0xB8, GAME_REGISTRY, "addShapedRecipe", f"(L{STACK};[Ljava/lang/Object;)Lnet/minecraft/item/crafting/IRecipe;")
            self.opcode(0x57)
        else:
            self.call(0xB8, GAME_REGISTRY, "addShapelessRecipe", f"(L{STACK};[Ljava/lang/Object;)V")


def recipe_archive(path, fixture, **members):
    return jar(path, **{f"{fixture.name}.class": fixture.bytes(), **members})


class RecipeConversionTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)

    def test_legacy_numeric_smelting_and_classless_deterministic_jar(self):
        for version, xp in (("1.5.2", 2.0), ("1.6.4", 0.0)):
            with self.subTest(version=version):
                fixture = RecipeClass()
                fixture.annotations["Lcpw/mods/fml/common/network/NetworkMod;"] = {"clientSideRequired": False, "serverSideRequired": True}
                fixture.smelt(version, xp=xp)
                fixture.init(version)
                path = recipe_archive(self.root / f"{version}.zip", fixture, **{"LICENSE.txt": "Original license"})
                a, b = analyze(path, version), analyze(path, version)
                self.assertEqual((a.jar, a.sha256), (b.jar, b.sha256))
                self.assertEqual(a.recipes[0].legacy_experience, xp)
                self.assertEqual(a.report()["legacy_network_flags"], {"clientSideRequired": False, "serverSideRequired": True})
                with ZipFile(BytesIO(a.jar)) as archive:
                    self.assertFalse(any(name.endswith(".class") for name in archive.namelist()))
                    self.assertEqual(archive.read("LICENSE.txt"), b"Original license")
                    self.assertEqual(json.loads(archive.read(PROVENANCE)), a.report())
                    self.assertEqual(json.loads(archive.read("data/recipetest/recipe/legacy_001.json")), {
                        "type": "minecraft:smelting", "ingredient": {"item": "minecraft:rotten_flesh"}, "experience": min(xp, 1.0),
                        "result": {"id": "minecraft:leather", "count": 1}, "cookingtime": 200,
                    })

    def test_shaped_shapeless_counts_and_wool_variants(self):
        fixture = RecipeClass()
        fixture.craft(shape=("WL", "L "))
        fixture.craft(wool_damage=14)
        fixture.init("1.7.10")
        a = analyze(recipe_archive(self.root / "Craft.zip", fixture), "1.7.10")
        shaped, shapeless = (recipe.data() for recipe in a.recipes)
        self.assertEqual(shaped["pattern"], ["WL", "L "])
        self.assertEqual(shaped["result"], {"id": "minecraft:saddle", "count": 1})
        self.assertEqual(len(shaped["key"]["W"]), 16)
        self.assertIn({"item": "minecraft:black_wool"}, shaped["key"]["W"])
        self.assertEqual(shapeless["ingredients"], [{"item": "minecraft:red_wool"}, *[{"item": "minecraft:leather"}] * 2])

    def test_blocks_unknown_effect_code_resource_and_release(self):
        fixture = RecipeClass()
        fixture.smelt("1.5.2")
        fixture.opcode(0x00)  # The translator does not recognize even a JVM nop.
        fixture.init("1.5.2")
        with self.assertRaisesRegex(InputError, r"unsupported recipe opcode 0x00"):
            analyze(recipe_archive(self.root / "BadCode.jar", fixture), "1.5.2")
        fixture = RecipeClass(source_version="1.5.2")
        fixture.smelt("1.5.2")
        fixture.init("1.5.2")
        with self.assertRaisesRegex(InputError, "unsupported resource"):
            analyze(recipe_archive(self.root / "Assets.jar", fixture, **{"assets/example/lang/en_us.lang": "test"}), "1.5.2")
        with self.assertRaisesRegex(InputError, "unsupported FML"):
            analyze(recipe_archive(self.root / "WrongRelease.jar", fixture), "1.6.4")
        with self.assertRaisesRegex(InputError, "no verified vanilla item mapping"):
            VanillaNames("1.5.2").item(Symbol("net/minecraft/init/Items", "field_151116_aA", f"L{ITEM};"))
        with self.assertRaisesRegex(InputError, "no verified vanilla item mapping"):
            VanillaNames("1.5.2").item(Symbol("wk", "aG", "I"))

    def test_blocks_ambiguous_recipes_and_invalid_counts(self):
        for shapes, count, error in [((("WL",), ("LW",)), 1, "duplicate"),
                                     ((("WL",),), 2, "result count")]:
            with self.subTest(shapes=shapes, count=count):
                fixture = RecipeClass()
                for shape in shapes:
                    fixture.craft(shape=shape, count=count)
                fixture.init("1.7.10")
                with self.assertRaisesRegex(InputError, error):
                    analyze(recipe_archive(self.root / "Craft.jar", fixture), "1.7.10")

    def test_actual_annotation_id_and_version_win_over_inactive_metadata(self):
        fixture = RecipeClass(mod_id="CaSeReCiPe")
        fixture.smelt("1.6.4", xp=0.0)
        fixture.init("1.6.4")
        path = self.root / "Legacy.zip"
        rows = [{"modid": "placeholder", "mcversion": "1.6.4"},
                {"modid": "CaSeReCiPe", "version": "0.1", "dependencies": ["mod_MinecraftForge"]}]
        recipe_archive(path, fixture, **{"mcmod.info": json.dumps(rows)})
        inferred = analyze(path, "1.6.4")
        self.assertEqual((inferred.mod_id, inferred.legacy_id, inferred.version), ("caserecipe", "CaSeReCiPe", "2.0.1"))
        self.assertEqual(inferred.unbound_metadata_ids, ("placeholder",))
        self.assertIn("mod_MinecraftForge", inferred.inactive_metadata_dependencies[0])
        rows[1]["useDependencyInformation"] = True
        recipe_archive(path, fixture, **{"mcmod.info": json.dumps(rows)})
        with self.assertRaisesRegex(InputError, "opts into metadata dependencies"):
            analyze(path, "1.6.4")

    def test_pack_preview_requires_opt_in_and_never_becomes_native_provider(self):
        source, target, out = (self.root / folder for folder in ("source", "target", "preview"))
        setup_platform(source, "1.7.10")
        setup_platform(target)
        fixture = RecipeClass()
        fixture.craft(shape=("WL",))
        fixture.init("1.7.10")
        label = "mods/1.7.10/Recipe.jar"
        original = recipe_archive(source / label, fixture, **{"mcmod.info": '[{"modid":"examplemod"}]'})
        before = original.read_bytes()
        state = inventory(source, target)
        row, = state.catalog()["rows"]
        self.assertEqual((row["status"], row["mods"]), ("recipe-preview", ["recipetest"]))
        implicit = plan(state, [label], output_pack=True)
        self.assertFalse(implicit.report["ready"])
        self.assertIn("--allow-recipe-preview", " ".join(implicit.problems))
        opted_in = inventory(source, target, allow_recipe_preview=True)
        self.assertFalse(plan(opted_in, [label]).report["ready"], "Recipe previews cannot replace an installed mods folder")
        self.assertNotIn("recipetest", opted_in.providers)
        native(target, "mods/Control.jar", "control")
        mixed = plan(inventory(source, target, allow_recipe_preview=True), [label], output_pack=True, include_native=["control"])
        self.assertFalse(mixed.report["ready"], "A native inclusion is still a second selection")
        self.assertTrue(any("Select this archive alone" in reason for reason in mixed.problems))
        candidate = plan(opted_in, [label], output_pack=True)
        self.assertTrue(candidate.report["ready"], candidate.problems)
        self.assertFalse(candidate.report["parity_verified"])
        row, = candidate.report["files"]
        self.assertEqual(row["action"], "convert")
        stage(candidate, out)
        self.assertEqual(original.read_bytes(), before)
        self.assertIn("preview", (out / "INSTALL.txt").read_text())
        shutil.copyfile(out / row["file"], target / row["file"])
        self.assertNotIn("recipetest", inventory(source, target).providers)
        self.assertFalse(plan(inventory(source, target), [label], output_pack=True).report["ready"])
        original.write_bytes(before + b"changed")
        with self.assertRaisesRegex(InputError, "changed during staging"):
            stage(candidate, self.root / "stale")
        self.assertFalse((self.root / "stale").exists())

    def test_explicit_recipe_release_is_not_replaced_by_a_different_runtime(self):
        source, target = self.root / "legacy", self.root / "target"
        setup_platform(source, "1.6.4")
        setup_platform(target)
        typed = RecipeClass(source_version="1.7.10")
        typed.craft(shape=("WL",))
        typed.init("1.7.10")
        plain = RecipeClass(mod_id="undated")
        plain.craft(shape=("WL",))
        plain.init("1.7.10")
        label = "mods/1.7.10/Exact.jar"
        ungrouped = "mods/Ungrouped.jar"
        undated = "mods/1.7.10/Undated.jar"
        for path, fixture in ((label, typed), (ungrouped, typed), (undated, plain)):
            recipe_archive(source / path, fixture)
        state = inventory(source, target, allow_recipe_preview=True)
        self.assertEqual(state.source_platform.legacy, "1.6.4")
        self.assertEqual(state.conversions[label].conversion.source_version, "1.7.10")
        self.assertTrue(plan(state, [label], output_pack=True).report["ready"])
        for path in (ungrouped, undated):
            self.assertIn("exact original Minecraft version", state.conversion_issues[path])
            self.assertFalse(plan(state, [path], output_pack=True).report["ready"])

    def test_version_evidence_and_real_native_provider_take_priority(self):
        source, target = self.root / "legacy", self.root / "target"
        setup_platform(target)
        fixture = RecipeClass()
        fixture.craft(shape=("WL",))
        fixture.init("1.7.10")
        label = "mods/Recipe.1.7.10.jar"
        recipe_archive(source / label, fixture)
        scan = inventory(source, target, allow_recipe_preview=True)
        self.assertIn("exact original Minecraft version", scan.conversion_issues[label])
        self.assertFalse(plan(scan, [label], output_pack=True).report["ready"])
        setup_platform(source, "1.7.10")
        existing = native(target, "lib/Native.jar", "recipetest")
        update = plan(inventory(source, target, allow_recipe_preview=True), [label], output_pack=True)
        self.assertTrue(update.report["ready"], update.problems)
        self.assertEqual(update.mods[0].path, existing)
        self.assertEqual(update.report["files"][0]["action"], "update")
