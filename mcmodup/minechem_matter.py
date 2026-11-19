"""Release-isolated Minechem fluids and radiation, generated from pinned observations."""

from dataclasses import dataclass
import json
import re

from . import minechem_ore
from .inputs import InputError
from .minechem_materials import PRODUCT, TEMPLATES, chemical_id, observations


JAVA = ("ChemistryFluids", "FluidPresentation", "ChemicalBucketRecipe", "ChemistryRadiation",
        "ShieldedStorage", "ShieldedStorageScreen", "FinalFluidCollector", "TubeWorldUse", "FluidDispensing", "FluidReactions",
        "ChemicalFuels", "ChemicalFlow394")
GENERATED_CLASSES = (*JAVA, minechem_ore.FEATURE_CLASS, minechem_ore.CRAFT_CLASS, minechem_ore.DICTIONARY_CLASS)
FINAL_ONLY = {"FinalFluidCollector", minechem_ore.CRAFT_CLASS}
RELEASE_394_ONLY = {minechem_ore.DICTIONARY_CLASS, "TubeWorldUse", "FluidDispensing", "FluidReactions", "ChemicalFuels", "ChemicalFlow394"}
AUTOMATION_TRACE = "release394-fluid-automation.txt"
RESOURCES = "src/main/resources"
ANIMATED_PNGS = {"assets/minechem/textures/blocks/fluid_still.png": (16, 128),
                 "assets/minechem/textures/blocks/fluid_flow.png": (32, 512)}
LIVES = (0, 1_728_000, 864_000, 432_000, 216_000, 72_000)
DAMAGE = (0, 1, 2, 6, 8, 16)


@dataclass(frozen=True)
class Fluid:
    key: str
    legacy_name: str
    chemical: str
    fluid: str
    density: int
    viscosity: int
    tint: int
    placeable: bool

    @property
    def bucket(self) -> str:
        namespace, path = self.chemical.split(":", 1)
        return f"{namespace}:bucket_{path}"


def trace_name(prepaid: bool, kind: str) -> str:
    return f"{'release394' if prepaid else 'final'}-{kind}.txt"


def fluid_rows(prepaid: bool, ids: dict[str, str]) -> tuple[Fluid, ...]:
    rows = observations(trace_name(prepaid, "fluids"))
    rows = {key: row for key, row in rows.items() if re.fullmatch(r"[em]\d{1,3}", key)}
    expected = ids.keys() - ({"m1"} if prepaid else set())  # .394 water uses the vanilla fluid, not a custom block.
    if rows.keys() != expected:
        raise InputError("The Minechem fluid registry does not match the recovered chemical catalog")
    result = []
    for key, row in rows.items():
        name, gaseous, density, viscosity, color, placed, tube = row.split("|")
        density, viscosity, color = int(density), int(viscosity), int(color)
        if not (name and gaseous == ("true" if density < 0 else "false") and -10 <= density <= 10 and
                400 <= viscosity <= 1200 and -(1 << 31) <= color < 1 << 31 and
                placed == str(prepaid).lower() and tube == (f"{name}:125" if prepaid else "-")):
            raise InputError(f"Invalid Minechem fluid row {key}={row}")
        chem = chemical_id(key, ids)
        namespace, path = chem.split(":", 1)
        result.append(Fluid(key, name, chem, f"{namespace}:fluid_{path}", density, viscosity, color, prepaid))
    return tuple(result)


def radiation_rows(prepaid: bool, ids: dict[str, str], fluids: tuple[Fluid, ...]) -> list[tuple[str, int, tuple[tuple[str, int], ...]]]:
    rows = observations(trace_name(prepaid, "radiation"))
    percent, safe_machines, contact = rows.get("config", "100/false/false").split("/")
    for index, (life, damage) in enumerate(zip(LIVES, DAMAGE)):
        if rows[f"tier{index}"] != f"{life * int(percent) // 100}/{damage}":
            raise InputError(f"Invalid lifetime or damage table for Minechem tier {index}")
    result = []
    buckets = {row.key: row.bucket for row in fluids if row.placeable}
    for key, raw in rows.items():
        if not re.fullmatch(r"[em]\d{1,3}", key):
            continue
        item = chemical_id(key, ids)
        tier, _, parts = raw.partition("/")
        if not tier.isdigit() or not 1 <= int(tier) < len(LIVES) or (bool(parts) != key.startswith("m")):
            raise InputError(f"Invalid Minechem decay rule {key}={raw}")
        if not parts:
            products = ((chemical_id(f"e{int(key[1:]) - 1}", ids), 1),)
        else:
            products = tuple((chemical_id(part[1], ids), int(part[2]))
                             for part in (PRODUCT.fullmatch(token) for token in parts.split(",")) if part)
            if not products or len(products) != len(parts.split(",")):
                raise InputError(f"Invalid Minechem decay products {key}={raw}")
        result.append((item, int(tier), products))
        if key in buckets:
            result.append((buckets[key], int(tier), products))
    return result


def automation_rows(ids: dict[str, str]) -> dict:
    rows = observations(AUTOMATION_TRACE)
    item_contact, fluid_contact = rows["config"].split("/")
    if {item_contact, fluid_contact} - {"true", "false"} or rows["fire_sources"] != "fire,flowing_lava,lava":
        raise InputError("Invalid Minechem reaction triggers")
    reactions, ignition, fuels = [], {}, {}
    for key, value in rows.items():
        if key.startswith("rule_"):
            a, b = key[5:].split("+")
            power, parts = value.split(";")
            products = parts.split(",")
            if not (a < b and 0 <= float(power) <= 4 and 1 <= len(products) <= 4):
                raise InputError(f"Invalid Minechem fluid reaction {key}={value}")
            reactions.append({"inputs": (chemical_id(a, ids), chemical_id(b, ids)), "power": float(power),
                              "products": [chemical_id(part, ids) for part in products]})
        elif key.startswith("ignite_") and re.fullmatch(r"[em]\d{1,3}", key[7:]):
            power = float(value)
            if not 0 < power <= 4:
                raise InputError(f"Invalid Minechem ignition strength {key}={value}")
            ignition[chemical_id(key[7:], ids)] = power
        elif key.startswith("fuel_"):
            chemical = key[5:]
            name = chemical_id(chemical.removeprefix("b"), ids)
            if chemical.startswith("b"):
                namespace, path = name.split(":", 1)
                name = f"{namespace}:bucket_{path}"
            if not 0 < int(value) <= 64_000:
                raise InputError(f"Invalid Minechem furnace fuel {key}={value}")
            fuels[name] = int(value)
    return {"reactionItemMeetFluid": item_contact == "true", "reactionFluidMeetFluid": fluid_contact == "true",
            "reactions": reactions, "ignition": ignition, "furnace_fuels": fuels}


REACTION_HOOKS = """        @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
            super.onPlace(state, level, pos, old, moved);
            if (!level.isClientSide()) level.scheduleTick(pos, this, 1);
        }
        @Override protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos changed, boolean moved) {
            super.neighborChanged(state, level, pos, neighbor, changed, moved);
            if (!level.isClientSide()) level.scheduleTick(pos, this, 1);
        }
        @Override protected void tick(BlockState state, net.minecraft.server.level.ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random) {
            FluidReactions.touch(level, pos);
        }
        @Override public void onBlockExploded(BlockState state, Level level, BlockPos pos, net.minecraft.world.level.Explosion explosion) {
            super.onBlockExploded(state, level, pos, explosion);
            if (level instanceof net.minecraft.server.level.ServerLevel server && !level.getBlockState(pos).is(this))
                FluidReactions.onExplosion(server, pos, type.chemical());
        }"""


def fuel_override(indent: int) -> str:
    return (" " * indent + "@Override public int getBurnTime(ItemStack stack, net.minecraft.world.item.crafting.RecipeType<?> type) {\n"
            + " " * (indent + 4) + "return ChemicalFuels.burnTime(stack);\n" + " " * indent + "}")


def asset_paths(namespace: str, prepaid: bool) -> dict[str, str]:
    mapping = [("blocks/leadedchest", "block/leaded_chest"), ("blocks/uraniumOre", "block/uranium_ore"),
               ("gui/LeadedChestGUI", "gui/leaded_chest")]
    if prepaid:
        mapping.extend((("items/TestTube", "item/test_tube"), ("items/bucketEmpty", "item/bucket_empty"),
                        ("items/bucketContent", "item/bucket_content"), ("blocks/fluid_still", "block/fluid_still"),
                        ("blocks/fluid_flow", "block/fluid_flow")))
    return {f"assets/minechem/textures/{old}.png": f"assets/{namespace}/textures/{new}.png" for old, new in mapping}


def project(namespace: str, package: str, ids: dict[str, str], names: dict[str, str], prepaid: bool) -> tuple[dict, dict]:
    def out(value: dict) -> str:
        return json.dumps(value, indent=2) + "\n"

    assets, data = f"{RESOURCES}/assets/{namespace}", f"{RESOURCES}/data/{namespace}"
    java = f"src/main/java/{package.replace('.', '/')}"
    fluids = fluid_rows(prepaid, ids)
    radiation = radiation_rows(prepaid, ids, fluids)
    percent, safe, contact = observations(trace_name(prepaid, "radiation")).get("config", "100/false/false").split("/")
    if not (0 <= int(percent) <= 100 and safe in ("true", "false") and contact in ("true", "false")):
        raise InputError("Invalid Minechem radiation config observation")
    fluid_java = []
    rad_java = []
    for fluid in fluids:
        fluid_java.append(f"        new Chemical({json.dumps(fluid.fluid.split(':')[1])}, {json.dumps(fluid.chemical)}, "
                          f"{fluid.density}, {fluid.viscosity}, {fluid.tint}, {str(fluid.placeable).lower()})")
    for item, tier, products in radiation:
        parts = ", ".join(f"new ChemistryRecipes.Part({json.dumps(product)}, {amount})" for product, amount in products)
        rad_java.append(f"        Map.entry({json.dumps(item)}, new Rule({tier}, List.of({parts})))")
    automation = automation_rows(ids) if prepaid else {}
    files: dict[str, str] = {}
    for name in JAVA:
        if (name == "FinalFluidCollector" and prepaid) or (name in RELEASE_394_ONLY and not prepaid):
            continue
        files[f"{java}/{name}.java"] = ((TEMPLATES / f"{name}.java").read_text(encoding="utf-8")
            .replace("@PACKAGE@", package).replace("@NAMESPACE@", namespace).replace("@RELEASE_394@", str(prepaid).lower())
            .replace("@FLUID_ROWS@", ",\n".join(fluid_java)).replace("@RADIATION_ROWS@", ",\n".join(rad_java))
            .replace("@HALF_LIFE@", percent).replace("@DECAY_SAFE@", safe).replace("@FLUID_EFFECTS@", contact)
            .replace("@STILL_TEXTURE@", f"{namespace}:block/fluid_still" if prepaid else "minecraft:block/water_still")
            .replace("@FLOW_TEXTURE@", f"{namespace}:block/fluid_flow" if prepaid else "minecraft:block/water_flow")
            .replace("@REGISTER_EMPTY_TUBE@", '        buckets.add(items.registerItem("test_tube", p -> new TubeWorldUse.TubeItem(p, true)));' if prepaid else "")
            .replace("@WATER_BUCKET_CRAFT@", observations(trace_name(True, "fluids")).get("water", "false").split("/", 1)[0])
            .replace("@FLUID_REACTION_HOOKS@", REACTION_HOOKS if prepaid else "")
            .replace("@SOURCE_TYPE@", "new ChemicalFlow394(properties, spec.density(), true)" if prepaid else "new BaseFlowingFluid.Source(properties)")
            .replace("@FLOWING_TYPE@", "new ChemicalFlow394(properties, spec.density(), false)" if prepaid else "new BaseFlowingFluid.Flowing(properties)")
            .replace("@BUCKET_FUEL_OVERRIDE@", fuel_override(8) if automation.get("furnace_fuels") and
                     any(":bucket_" in key for key in automation["furnace_fuels"]) else ""))
    if prepaid:
        rules = []
        for row in automation["reactions"]:
            a, b = map(json.dumps, row["inputs"])
            products = ", ".join(map(json.dumps, row["products"]))
            rules.append(f"        Map.entry(Pair.of({a}, {b}), new Rule({row['power']}F, List.of({products})))")
        files[f"{java}/FluidReactions.java"] = (files[f"{java}/FluidReactions.java"]
            .replace("@REACTION_RULES@", ",\n".join(rules))
            .replace("@IGNITION_RULES@", ",\n".join(f"        Map.entry({json.dumps(key)}, {power}F)" for key, power in automation["ignition"].items()))
            .replace("@ITEM_CONTACT@", str(automation["reactionItemMeetFluid"]).lower())
            .replace("@FLUID_CONTACT@", str(automation["reactionFluidMeetFluid"]).lower()))
        files[f"{java}/ChemicalFuels.java"] = files[f"{java}/ChemicalFuels.java"].replace("@FURNACE_FUELS@", ",\n".join(
            f"        Map.entry(ResourceLocation.parse({json.dumps(key)}), {ticks})" for key, ticks in automation["furnace_fuels"].items()))
        files[f"{data}/mcmodup/fluid_automation.json"] = out(automation)
    warning = "Radiation Warning: Element %s decayed into %s" + (" after %s." if prepaid else ".")
    locales = {f"gui.{namespace}.tank_empty": "Empty", f"item.{namespace}.radioactive": "Radioactivity %s · %s s to decay",
               f"message.{namespace}.radiation_decay": warning}
    blocks = [("leaded_chest", "Leaded Chest", "leaded_chest"), ("uranium_ore", "Uranium Ore", "uranium_ore")]
    if not prepaid:
        blocks.append(("chemical_storage", "Chemical Storage", "leaded_chest"))
    for block, title, texture in blocks:
        id_ = f"{namespace}:{block}"
        model = f"{namespace}:block/{block}"
        locales[f"block.{id_.replace(':', '.')}"] = title
        files[f"{assets}/blockstates/{block}.json"] = out({"variants": {"": {"model": model}}})
        files[f"{assets}/models/block/{block}.json"] = out({"parent": "minecraft:block/cube_all", "textures": {"all": f"{namespace}:block/{texture}"}})
        files[f"{assets}/models/item/{block}.json"] = out({"parent": model})
        files[f"{data}/loot_table/blocks/{block}.json"] = out({"type": "minecraft:block", "pools": [{"rolls": 1,
            "conditions": [{"condition": "minecraft:survives_explosion"}], "entries": [{"type": "minecraft:item", "name": id_}]}]})
    lead = chemical_id("e82" if prepaid else "e81", ids)
    files[f"{data}/recipe/leaded_chest.json"] = out({"type": "minecraft:crafting_shaped", "category": "misc", "pattern": ["LLL", "LCL", "LLL"],
        "key": {"L": {"item": lead}, "C": {"item": "minecraft:chest"}}, "result": {"id": f"{namespace}:leaded_chest"}})
    if not prepaid:
        files[f"{data}/recipe/convert_chemical_storage.json"] = out({"type": "minecraft:crafting_shapeless", "category": "misc",
            "ingredients": [{"item": f"{namespace}:chemical_storage"}], "result": {"id": f"{namespace}:leaded_chest"}})
    else:
        locales[f"item.{namespace}.test_tube"] = "Empty Test Tube"
        files[f"{assets}/models/item/test_tube.json"] = out({"parent": "minecraft:item/generated", "textures": {"layer0": f"{namespace}:item/test_tube"}})
        files[f"{data}/recipe/test_tube.json"] = out({"type": "minecraft:crafting_shaped", "category": "misc", "pattern": ["P P", " P "],
            "key": {"P": {"item": "minecraft:glass_pane"}}, "result": {"id": f"{namespace}:test_tube"}})
        for kind in ("chemical_bucket_fill", "chemical_bucket_empty"):
            files[f"{data}/recipe/{kind}.json"] = out({"type": f"{namespace}:{kind}", "category": "misc"})
    for fluid in fluids:
        path = fluid.fluid.split(":")[1]
        title = names.get(fluid.chemical, fluid.legacy_name)
        locales[f"fluid_type.{namespace}.{path}"] = title
        if not fluid.placeable:
            continue
        bucket = fluid.bucket.split(":")[1]
        locales[f"item.{namespace}.{bucket}"] = title + " Bucket"
        locales[f"block.{namespace}.{path}"] = title
        files[f"{assets}/blockstates/{path}.json"] = out({"variants": {"": {"model": f"{namespace}:block/chemical_fluid"}}})
        files[f"{assets}/models/item/{bucket}.json"] = out({"parent": "minecraft:item/generated",
            "textures": {"layer0": f"{namespace}:item/bucket_empty", "layer1": f"{namespace}:item/bucket_content"}})
    if prepaid:
        files[f"{assets}/models/block/chemical_fluid.json"] = out({"textures": {"particle": f"{namespace}:block/fluid_still"}})
        # Keep the original PNG frame layout, without the comment lines in Forge's old metadata.
        files[f"{assets}/textures/block/fluid_still.png.mcmeta"] = out({"animation": {"frames": [{"index": 0, "time": 20}, *range(1, 7)]}})
        files[f"{assets}/textures/block/fluid_flow.png.mcmeta"] = out({"animation": {}})
    files[f"{data}/mcmodup/chemical_fluids.json"] = out({row.fluid: {"legacy_name": row.legacy_name, "chemical": row.chemical,
        "density": row.density, "viscosity": row.viscosity, "tint": row.tint, "placeable": row.placeable,
        **({"bucket": row.bucket, "tube_volume": 125} if row.placeable else {})} for row in fluids})
    files[f"{data}/mcmodup/radiation.json"] = out({"default_multiplier": int(percent), "decay_safe_machines": safe == "true",
        "rules": {item: {"tier": tier, "products": dict(parts)} for item, tier, parts in radiation}})
    files.update(minechem_ore.project(namespace, package, prepaid, ids))
    return files, locales


def features(prepaid: bool) -> dict[str, str]:
    prefix = "release394" if prepaid else "final"
    return {f"{prefix} fluid registry and release-specific tank behavior": f"{prefix}fluidregistryandtank",
            f"{prefix} shielded storage, save, and uranium ore yield": f"{prefix}storageandore",
            f"{prefix} radioactive lifetime boundary and catch-up": f"{prefix}decayboundaryandcatchup",
            f"{prefix} radiation tick phase and repeat suppression": f"{prefix}decayscheduler",
            f"{prefix} decay warnings, survival dose, armor, and cursor eligibility": f"{prefix}decayplayerandchest",
            f"{prefix} open-machine dose and storage slot boundaries": f"{prefix}decaymachineslots",
            **({".394 radioactive bucket daughter and byproduct conservation": "release394decaybuckets",
                ".394 crafting, no-loss fluid transfer, and contact effects": "release394tubebucketandcontact",
                ".394 split tube payment and radioactive source reload": "release394radioactivetubesource",
                ".394 dispenser transfer, split payment, and vanilla fallback": "release394dispensertransfers",
                ".394 block fluid reactions, source/flow output and ignition": "release394fluidreactions",
                ".394 dropped chemical bucket contact and config guards": "release394itemcontact",
                ".394 upward gas, downward liquid and stationary solid flow": "release394directionalflow",
                ".394 original furnace fuel values and actual furnace consumption": "release394furnacefuels"} if prepaid else
               {"FINAL does not inherit .394 furnace fuels": "finalfurnaceisolation"})}


def checks(package: str, test_namespace: str, namespace: str, ids: dict[str, str], prepaid: bool, *, reactors: bool = True) -> str:
    lifetime = observations(trace_name(prepaid, "radiation"))["tier1"].split("/")[0]
    flows = ",\n".join(f"        {json.dumps(row.fluid.split(':', 1)[1])}" for row in fluid_rows(prepaid, ids))
    return ((TEMPLATES / "MatterChecks.java").read_text(encoding="utf-8")
            .replace("@PACKAGE@", package).replace("@NAMESPACE@", namespace).replace("@TEST_NAMESPACE@", test_namespace)
            .replace("@CLASS@", "MatterChecks394" if prepaid else "MatterChecksFinal")
            .replace("@PREFIX@", "release394" if prepaid else "final").replace("@RELEASE_394@", str(prepaid).lower())
            .replace("@LIFETIME@", lifetime).replace("@FLUIDS@", flows)
            .replace("@PLAYER_DECAY_CHECKS@", player_decay_checks(ids, prepaid, reactors))
            .replace("@BUCKET_CHECKS@", (TEMPLATES / "Matter394Checks.java").read_text(encoding="utf-8") if prepaid else "")
            .replace("@AUTOMATION_CHECKS@", automation_checks(ids) if prepaid else FINAL_FUEL_CHECK))


def player_decay_checks(ids: dict[str, str], prepaid: bool, reactors: bool) -> str:
    observations_ = observations(trace_name(prepaid, "player-decay"))

    def part(encoded: str) -> tuple[str, int]:
        match = re.fullmatch(r"(b?)([em]\d+)x([1-9]\d*)", encoded)
        if match is None:
            raise InputError(f"Invalid Minechem player decay product {encoded!r}")
        bucket, key, count = match.groups()
        path = chemical_id(key, ids).split(":", 1)[1]
        return (f"bucket_{path}" if bucket else path), int(count)

    cases, buckets = [], []
    for label, row in observations_.items():
        if "|" not in row or label.endswith("_alternate") or not reactors and label.startswith(("fusion_", "fission_")):
            continue
        result, damage, warning = row.split("|", 2)
        path, count = part(result)
        if warning == "[]":
            elapsed = "null"
        elif prepaid:
            match = re.fullmatch(r"\[Radiation Warning: Element .+ decayed into .+ after ([\w ]+)\.\]", warning)
            if match is None:
                raise InputError(f"Invalid .394 warning observation {warning!r}")
            elapsed = json.dumps(match[1])
        else:
            elapsed = '""'  # FINAL's warning has no time argument.
        if not re.fullmatch(r"\d+\.0", damage):
            raise InputError(f"Invalid Minechem player radiation dose {damage!r}")
        cases.append(f"        Map.entry({json.dumps(label)}, new Exposure({json.dumps(path)}, "
                     f"{1 if result.startswith('b') else count}, {damage}F, {elapsed}))")
        if not label.endswith("_bucket"):
            continue
        origin, age = {"element_bucket": ("e92", "LIFE"), "m109_bucket": ("m109", "LIFE / 2"),
                       "m153_bucket": ("m153", "LIFE / 8")}[label]
        other = observations_[f"{label}_byproducts"].removeprefix("[").removesuffix("]")
        products = dict(part(token) for token in other.split(", ")) if other else {}
        if count > 1:
            products[path] = count - 1  # Preserve the native one-filled-bucket stack limit.
        expected = ", ".join(f"{json.dumps(item)}, {amount}" for item, amount in products.items())
        bucket = "bucket_" + chemical_id(origin, ids).split(":", 1)[1]
        args = f"test, {json.dumps(label)}, {json.dumps(bucket)}, {age} + 1201, Map.of({expected})"
        buckets.append(f"        decayBucket({args}, false);")
        if count > 1:
            buckets.append(f"        decayBucket({args}, true);")
    source = (TEMPLATES / "PlayerDecayChecks.java").read_text(encoding="utf-8")
    bucket_source = (TEMPLATES / "BucketDecayChecks.java").read_text(encoding="utf-8") if prepaid else ""
    reactor_cases = """            decayMachine(test, "fission", "fission_core", new BlockPos(3, 1, 1), 0, 9, 10);
            decayMachine(test, "fusion", "fusion_core", new BlockPos(1, 1, 2), NEWER ? 0 : 1, 9, 10);""" if reactors else ""
    return (source.replace("@PREFIX@", "release394" if prepaid else "final").replace("@PLAYER_CASES@", ",\n".join(cases))
            .replace("@REACTOR_DECAY_CASES@", reactor_cases)
            .replace("@BUCKET_DECAY_CHECKS@", bucket_source.replace("@BUCKET_CASES@", "\n".join(buckets))))


def automation_checks(ids: dict[str, str]) -> str:
    fuels = automation_rows(ids)["furnace_fuels"]
    fuel_checks = "\n".join(f"        fuel(test, {json.dumps(key.split(':', 1)[1])}, {ticks});" for key, ticks in fuels.items())
    return (TEMPLATES / "FluidAutomation394Checks.java").read_text(encoding="utf-8").replace("@FUEL_CHECKS@", fuel_checks)


FINAL_FUEL_CHECK = """
    @GameTest(template = "test_room")
    public static void finalFurnaceIsolation(GameTestHelper test) {
        test.assertTrue(item("element_c", 1).getBurnTime(RecipeType.SMELTING) == 0 &&
            item("molecule_tnt", 1).getBurnTime(RecipeType.SMELTING) == 0,
            "The .394 fuel registry leaked into FINAL");
        test.succeed();
    }
"""
