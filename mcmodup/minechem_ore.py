"""Generate the release-local uranium feature and replay the original Forge samples."""

import json
import re

from .inputs import InputError
from .minechem_materials import PRODUCT, TEMPLATES, chemical_id, observations


FEATURE_CLASS = "UraniumOreFeature"
CRAFT_CLASS = "UraniumOreCraft"
DICTIONARY_CLASS = "OreDictionary394"
ORE_MATRIX = "release394-ore-forms.txt"
# Priority is deterministic in native tags, not a claim about historical provider registration order.
FORMS = {
    "oreNether": ("ores", "c:ores_in_ground/netherrack"), "ore": ("ores", None),
    "ingot": ("ingots", None), "nugget": ("nuggets", None), "dustDirty": ("dirty_dusts", None),
    "dustSmall": ("small_dusts", None), "dust": ("dusts", None), "plate": ("plates", None),
    "gem": ("gems", None), "crystal": ("crystals", None), "block": ("storage_blocks", None),
}
MATERIALS = ("Aluminium Aluminum Amethyst Antimony Ardite Bitumen Chrome Cobalt Copper Diamond Gold Iridium Iron Lead Limestone "
             "Manganese NetherQuartz Nickel Obsidian Osmium Peridot Phosphorus Platinum Plutonium Potash Prometheum Ruby Salpeter Salt "
             "Sapphire Silver Sulfur Tanzanite Thorium Tin Titanium Topaz Tungsten Uranium Zinc").split()
SPECIAL = {"oreSaphire": ("c:ores/sapphire", None), "crystalSaphire": ("c:crystals/sapphire", None),
           "gemApatite": ("c:gems/apatite", None), "plateSilicon": ("c:plates/silicon", None)}
# Legacy ore events can synthesize variants or identify crops/xychorium from their exact provider.
# That part is measured, not enabled without a reviewed modern provider.

# Extra tags are supplied only by the staged verification mod, never by a production port.
TEST_ITEMS = {
    "ores/uranium": ["minecraft:light", "minecraft:barrier"],
    # Test exact-lookup precedence without making iron ore a valid FINAL crafting ingredient.
    "ingots/uranium": ["minecraft:iron_ore"],
    "ores_in_ground/netherrack": ["minecraft:barrier", "minecraft:repeating_command_block"],
    "storage_blocks/uranium": ["minecraft:structure_void"],
    "ores/copper": ["minecraft:command_block", "minecraft:repeating_command_block"],
    "dirty_dusts/silver": ["minecraft:chain_command_block"],
    "ores/ruby": ["minecraft:jigsaw"],
    "plates/silicon": ["minecraft:structure_block"],
    "small_dusts/saltpeter": ["minecraft:debug_stick"],
}
MATRIX_TESTS = {"oreCopper": "minecraft:command_block", "oreNetherCopper": "minecraft:repeating_command_block",
                "dustDirtySilver": "minecraft:chain_command_block", "oreRuby": "minecraft:jigsaw",
                "plateSilicon": "minecraft:structure_block"}


def products(row: str) -> list[tuple[str, int]]:
    if not re.fullmatch(r"\[[em]\d+x[1-9]\d*(, [em]\d+x[1-9]\d*)*\]", row):
        raise InputError(f"Invalid .394 tagged ore products: {row}")
    result = [(key, int(count)) for key, count in PRODUCT.findall(row)]
    if len({key for key, _ in result}) != len(result) or any(count > 576 for _, count in result):
        raise InputError(f"Invalid .394 ore product counts: {row}")
    return result


def ore_rules() -> dict[str, tuple[str, str | None, list[tuple[str, int]]]]:
    matrix = observations(ORE_MATRIX)
    baseline = observations("release394-ore-interop.txt")
    if (baseline["craft_recipes"] != "0" or baseline["blockUranium"] != "-"
            or len(matrix) != 466 or matrix.get("dustSmallSalpeter") != "-"):
        raise InputError("Unexpected .394 ore matrix declarations")
    mapped = {}
    for prefix, (form, host) in FORMS.items():
        for material in MATERIALS:
            label = prefix + material
            row = matrix.get(label)
            if row is None:
                raise InputError(f"Missing .394 default handler observation {label}")
            reference = "external_uranium_after_scan" if label == "oreUranium" else label
            if reference in baseline and sorted(products(row) if row != "-" else []) != sorted(
                    products(baseline[reference]) if baseline[reference] != "-" else []):
                raise InputError(f".394 ore traces disagree at {label}")
            if row != "-":
                # Use the earlier measured order for the already shipped fallbacks.
                name = "saltpeter" if material == "Salpeter" else material.lower()
                mapped[label] = (f"c:{form}/{name}", host, products(baseline.get(reference, row)))
    for label, (tag, qualifier) in SPECIAL.items():
        mapped[label] = (tag, qualifier, products(matrix[label]))
    return mapped


def verification_tags() -> dict[str, str]:
    return {f"src/main/resources/data/c/tags/item/{tag}.json": json.dumps({"replace": False, "values": ids})
            for tag, ids in TEST_ITEMS.items()}

CASES = ("default", "masked", "tuned", "empty_roll", "floor", "ceiling", "disabled", "negative_density")


def trace_name(prepaid: bool) -> str:
    return f"{'release394' if prepaid else 'final'}-uranium-worldgen.txt"


def project(namespace: str, package: str, prepaid: bool, ids: dict[str, str]) -> dict[str, str]:
    trace = observations(trace_name(prepaid))
    enabled, density, cluster = trace["defaults"].split("/")
    prefix = "release394" if prepaid else "final"
    if (trace["defaults"] != observations(f"{prefix}-radiation.txt")["worldgen"] or enabled not in {"true", "false"}
            or not -1 <= int(density) <= 64 or not -9 <= int(cluster) <= 32):
        raise InputError("Invalid or inconsistent original Minechem ore defaults")
    data = "src/main/resources/data"
    ore = f"{namespace}:uranium_ore"
    java = f"src/main/java/{package.replace('.', '/')}"
    source = ((TEMPLATES / f"{FEATURE_CLASS}.java").read_text(encoding="utf-8")
              .replace("@PACKAGE@", package).replace("@RELEASE_394@", str(prepaid).lower())
              .replace("@ENABLED@", enabled).replace("@DENSITY@", density).replace("@CLUSTER@", cluster)
              .replace("@CRAFT_REGISTER@", "" if prepaid else f"        {CRAFT_CLASS}.register(bus, mod);"))
    files = {
        f"{data}/c/tags/block/ores/uranium.json": {"replace": False, "values": [ore]},
        f"{data}/c/tags/item/ores/uranium.json": {"replace": False, "values": [ore]},
        f"{data}/minecraft/tags/block/needs_iron_tool.json": {"replace": False, "values": [ore]},
        f"{data}/{namespace}/worldgen/configured_feature/uranium_ore.json": {"type": f"{namespace}:uranium_veins", "config": {}},
        # This is one call per participating chunk; it owns the whole legacy random stream.
        f"{data}/{namespace}/worldgen/placed_feature/uranium_ore.json": {"feature": ore, "placement": []},
        f"{data}/{namespace}/neoforge/biome_modifier/uranium_ore.json": {"type": "neoforge:add_features", "biomes": "#minecraft:is_overworld",
                                                                    "features": ore, "step": "underground_ores"},
    }
    if prepaid:
        rules = []
        for priority, (tag, qualifier, parts) in enumerate(ore_rules().values()):
            outputs = ", ".join(f"new Part({json.dumps(chemical_id(key, ids))}, {count})" for key, count in parts)
            constraint = f"tag({json.dumps(qualifier)})" if qualifier else "null"
            rules.append(f"        new Rule({priority}, tag({json.dumps(tag)}), {constraint}, List.of({outputs}))")
        name = DICTIONARY_CLASS
        interop = (TEMPLATES / f"{name}.java").read_text(encoding="utf-8").replace("@RULES@", ",\n".join(rules))
    else:
        craft = observations("final-ore-interop.txt")
        disabled = observations("final-ore-disabled.txt")
        if (craft["craft_recipes"], craft["craft_setting"], craft["craft_foreign"], disabled["craft_setting"], disabled["craft_recipes"]) != (
                "1", "true", "nativex1:0", "false", "0"):
            raise InputError("Unverified FINAL uranium crafting config")
        excluded = f"{namespace}:other_release_uranium_ore"
        files[f"{data}/{namespace}/tags/item/other_release_uranium_ore.json"] = {"replace": False,
            "values": [{"id": "minechem_chemistry_394:uranium_ore", "required": False}]}
        files[f"{data}/{namespace}/recipe/uranium_ore_conversion.json"] = {
            "type": "minecraft:crafting_shapeless", "category": "misc",
            "neoforge:conditions": [{"type": f"{namespace}:uranium_ore_craftable"}],
            "ingredients": [{"type": "neoforge:difference", "base": {"tag": "c:ores/uranium"}, "subtracted": {"tag": excluded}}],
            "result": {"id": ore, "count": 1},
        }
        name = CRAFT_CLASS
        interop = (TEMPLATES / f"{name}.java").read_text(encoding="utf-8").replace("@CRAFTABLE@", craft["craft_setting"])
    return {f"{java}/{FEATURE_CLASS}.java": source, f"{java}/{name}.java": interop.replace("@PACKAGE@", package),
            **{key: json.dumps(value, indent=2) + "\n" for key, value in files.items()}}


def check_name(prepaid: bool) -> str:
    return "UraniumOreChecks394" if prepaid else "UraniumOreChecksFinal"


def features(prepaid: bool) -> dict[str, str]:
    prefix = "release394" if prepaid else "final"
    return {f"{prefix} original uranium vein coordinates, stone targets, config and dimension gates": f"{prefix}uraniumveins",
            f"{prefix} ore tag inputs, local uranium identity and original interop rule": f"{prefix}uraniuminterop"}


def checks(package: str, test_namespace: str, namespace: str, prepaid: bool, ids: dict[str, str] | None = None) -> str:
    trace = observations(trace_name(prepaid))
    rows = []
    for name in CASES:
        coords, settings, _, _, positions = trace[name].split("|")
        cx, cz = map(int, coords.split(","))
        enabled, density, cluster, mask = settings.split(",")
        if {enabled, mask} - {"true", "false"}:
            raise InputError(f"Invalid original Minechem ore case {name}")
        rows.append(f"        new Sample({json.dumps(name)}, {cx}, {cz}, {enabled}, {int(density)}, {int(cluster)}, {mask}, {json.dumps(positions)})")
    tagged = []
    if prepaid and test_namespace != namespace and ids:
        staged = ore_rules()
        for key, item in MATRIX_TESTS.items():
            value = ", ".join(f"{json.dumps(chemical_id(chemical, ids))}, {count}" for chemical, count in staged[key][2])
            tagged.append(f"        new OreInput({json.dumps(item)}, Map.of({value}))")
    return ((TEMPLATES / "UraniumOreChecks.java").read_text(encoding="utf-8")
            .replace("@PACKAGE@", package).replace("@TEST_NAMESPACE@", test_namespace).replace("@NAMESPACE@", namespace)
            .replace("@CLASS@", check_name(prepaid)).replace("@PREFIX@", "release394" if prepaid else "final")
            .replace("@RELEASE_394@", str(prepaid).lower()).replace("@SEED@", str(int(trace["seed"])))
            .replace("@STAGED@", str(test_namespace != namespace).lower())
            .replace("@SAMPLES@", ",\n".join(rows)).replace("@TAGGED_SAMPLES@", ",\n".join(tagged)))
