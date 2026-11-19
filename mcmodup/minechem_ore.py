"""Generate the release-local uranium feature and replay the original Forge samples."""

import json
import re

from .inputs import InputError
from .minechem_materials import PRODUCT, TEMPLATES, chemical_id, observations


FEATURE_CLASS = "UraniumOreFeature"
CRAFT_CLASS = "UraniumOreCraft"
DICTIONARY_CLASS = "OreDictionary394"
# The first match wins. A nether ore may carry both the host-stone and element tags.
ORE_FORMS = {
    "oreNetherUranium": ("c:ores/uranium", "c:ores_in_ground/netherrack"),
    "external_uranium_after_scan": ("c:ores/uranium", None),
    "ingotUranium": ("c:ingots/uranium", None), "nuggetUranium": ("c:nuggets/uranium", None),
    "dustDirtyUranium": ("c:dirty_dusts/uranium", None), "dustSmallUranium": ("c:small_dusts/uranium", None),
    "dustUranium": ("c:dusts/uranium", None), "plateUranium": ("c:plates/uranium", None),
    "gemUranium": ("c:gems/uranium", None), "crystalUranium": ("c:crystals/uranium", None),
    "oreObsidian": ("c:ores/obsidian", None), "oreRuby": ("c:ores/ruby", None), "oreSalpeter": ("c:ores/saltpeter", None),
}

# Extra tags are supplied only by the staged verification mod, never by a production port.
TEST_ITEMS = {
    "ores/uranium": ["minecraft:light", "minecraft:barrier", "minecraft:iron_ore"],
    "ores_in_ground/netherrack": ["minecraft:barrier"],
    "storage_blocks/uranium": ["minecraft:structure_void"],
}


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
        trace = observations("release394-ore-interop.txt")
        if trace["craft_recipes"] != "0" or trace["blockUranium"] != "-":
            raise InputError("Unexpected .394 uranium recipe declarations")
        rules = []
        for label, (tag, qualifier) in ORE_FORMS.items():
            row = trace[label]
            if not re.fullmatch(r"\[[em]\d+x[1-9]\d*(, [em]\d+x[1-9]\d*)*\]", row):
                raise InputError(f"Invalid .394 tagged ore products {label}: {row}")
            parts = []
            for token in row[1:-1].split(", "):
                match = PRODUCT.fullmatch(token)
                if match is None:
                    raise InputError(f"Invalid .394 ore product {token}")
                key, count = match.groups()
                parts.append(f"new Part({json.dumps(chemical_id(key, ids))}, {count})")
            constraint = f"tag({json.dumps(qualifier)})" if qualifier else "null"
            rules.append(f"        new Rule(tag({json.dumps(tag)}), {constraint}, List.of({', '.join(parts)}))")
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


def checks(package: str, test_namespace: str, namespace: str, prepaid: bool) -> str:
    trace = observations(trace_name(prepaid))
    rows = []
    for name in CASES:
        coords, settings, _, _, positions = trace[name].split("|")
        cx, cz = map(int, coords.split(","))
        enabled, density, cluster, mask = settings.split(",")
        if {enabled, mask} - {"true", "false"}:
            raise InputError(f"Invalid original Minechem ore case {name}")
        rows.append(f"        new Sample({json.dumps(name)}, {cx}, {cz}, {enabled}, {int(density)}, {int(cluster)}, {mask}, {json.dumps(positions)})")
    return ((TEMPLATES / "UraniumOreChecks.java").read_text(encoding="utf-8")
            .replace("@PACKAGE@", package).replace("@TEST_NAMESPACE@", test_namespace).replace("@NAMESPACE@", namespace)
            .replace("@CLASS@", check_name(prepaid)).replace("@PREFIX@", "release394" if prepaid else "final")
            .replace("@RELEASE_394@", str(prepaid).lower()).replace("@SEED@", str(int(trace["seed"])))
            .replace("@STAGED@", str(test_namespace != namespace).lower())
            .replace("@SAMPLES@", ",\n".join(rows)))
