"""Generate the release-local uranium feature and replay the original Forge samples."""

import json

from .inputs import InputError
from .minechem_materials import TEMPLATES, observations


FEATURE_CLASS = "UraniumOreFeature"
CASES = ("default", "masked", "tuned", "empty_roll", "floor", "ceiling", "disabled", "negative_density")


def trace_name(prepaid: bool) -> str:
    return f"{'release394' if prepaid else 'final'}-uranium-worldgen.txt"


def project(namespace: str, package: str, prepaid: bool) -> dict[str, str]:
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
              .replace("@ENABLED@", enabled).replace("@DENSITY@", density).replace("@CLUSTER@", cluster))
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
    return {f"{java}/{FEATURE_CLASS}.java": source, **{key: json.dumps(value, indent=2) + "\n" for key, value in files.items()}}


def check_name(prepaid: bool) -> str:
    return "UraniumOreChecks394" if prepaid else "UraniumOreChecksFinal"


def features(prepaid: bool) -> dict[str, str]:
    prefix = "release394" if prepaid else "final"
    return {f"{prefix} original uranium vein coordinates, stone targets, config and dimension gates": f"{prefix}uraniumveins"}


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
            .replace("@SAMPLES@", ",\n".join(rows)))
