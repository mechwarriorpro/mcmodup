"""Release-isolated Minechem fluids and radiation, generated from pinned observations."""

from dataclasses import dataclass
import json
import re

from .inputs import InputError
from .minechem_materials import PRODUCT, TEMPLATES, chemical_id, observations


JAVA = ("ChemistryFluids", "FluidPresentation", "ChemicalBucketRecipe", "ChemistryRadiation",
        "ShieldedStorage", "ShieldedStorageScreen", "FinalFluidCollector")
RESOURCES = "src/main/resources"
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


def asset_paths(namespace: str, prepaid: bool) -> dict[str, str]:
    mapping = [("blocks/leadedchest", "block/leaded_chest"), ("blocks/uraniumOre", "block/uranium_ore"),
               ("gui/LeadedChestGUI", "gui/leaded_chest")]
    if prepaid:
        mapping.extend((("items/TestTube", "item/test_tube"), ("items/bucketEmpty", "item/bucket_empty"),
                        ("items/bucketContent", "item/bucket_content")))
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
    files: dict[str, str] = {}
    for name in JAVA:
        if name == "FinalFluidCollector" and prepaid:
            continue
        files[f"{java}/{name}.java"] = ((TEMPLATES / f"{name}.java").read_text(encoding="utf-8")
            .replace("@PACKAGE@", package).replace("@NAMESPACE@", namespace).replace("@RELEASE_394@", str(prepaid).lower())
            .replace("@FLUID_ROWS@", ",\n".join(fluid_java)).replace("@RADIATION_ROWS@", ",\n".join(rad_java))
            .replace("@HALF_LIFE@", percent).replace("@DECAY_SAFE@", safe).replace("@FLUID_EFFECTS@", contact)
            .replace("@WATER_BUCKET_CRAFT@", observations(trace_name(True, "fluids")).get("water", "false").split("/", 1)[0]))
    locales = {f"gui.{namespace}.tank_empty": "Empty", f"item.{namespace}.radioactive": "Radioactivity %s · %s s to decay"}
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
        files[f"{assets}/models/block/chemical_fluid.json"] = out({"textures": {"particle": "minecraft:block/water_still"}})
    files[f"{data}/mcmodup/chemical_fluids.json"] = out({row.fluid: {"legacy_name": row.legacy_name, "chemical": row.chemical,
        "density": row.density, "viscosity": row.viscosity, "tint": row.tint, "placeable": row.placeable,
        **({"bucket": row.bucket, "tube_volume": 125} if row.placeable else {})} for row in fluids})
    files[f"{data}/mcmodup/radiation.json"] = out({"default_multiplier": int(percent), "decay_safe_machines": safe == "true",
        "rules": {item: {"tier": tier, "products": dict(parts)} for item, tier, parts in radiation}})
    files.update(ore_generation(namespace, out, prepaid))
    return files, locales


def ore_generation(namespace: str, out, prepaid: bool) -> dict[str, str]:
    enabled, density, cluster = observations(trace_name(prepaid, "radiation"))["worldgen"].split("/")
    data = f"{RESOURCES}/data/{namespace}"
    ore = f"{namespace}:uranium_ore"
    # The old feature rolled size 0 through cluster+9. Vanilla 1.21's terrain and
    # vein algorithm differ; use its midpoint size, with the original attempts and absolute Y band.
    result = {
        f"{RESOURCES}/data/c/tags/block/ores/uranium.json": out({"values": [ore]}),
        f"{RESOURCES}/data/c/tags/item/ores/uranium.json": out({"values": [ore]}),
        f"{RESOURCES}/data/minecraft/tags/block/needs_iron_tool.json": out({"values": [ore]}),
        f"{data}/worldgen/configured_feature/uranium_ore.json": out({"type": "minecraft:ore", "config": {
            "size": (int(cluster) + 9) // 2, "discard_chance_on_air_exposure": 0,
            "targets": [{"target": {"predicate_type": "minecraft:block_match", "block": "minecraft:stone"}, "state": {"Name": ore}}]}}),
        f"{data}/worldgen/placed_feature/uranium_ore.json": out({"feature": ore, "placement": [
            {"type": "minecraft:count", "count": int(density) + 1}, {"type": "minecraft:in_square"},
            {"type": "minecraft:height_range", "height": {"type": "minecraft:uniform", "min_inclusive": {"absolute": 0}, "max_inclusive": {"absolute": 49}}},
            {"type": "minecraft:biome"}]}),
    }
    if enabled == "true":
        result[f"{data}/neoforge/biome_modifier/uranium_ore.json"] = out({"type": "neoforge:add_features", "biomes": "#minecraft:is_overworld",
            "features": ore, "step": "underground_ores"})
    return result
