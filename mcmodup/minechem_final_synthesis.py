"""Bounded recipe observations and vanilla item identities for exact Minechem FINAL."""

from dataclasses import dataclass
import json
from pathlib import Path
import re

from .assets import PNG_SIGNATURE
from .catalogs import CatalogEntry
from .inputs import InputError


TRACE = Path(__file__).with_name("templates") / "minechem/final-synthesis.txt"
FEATURES = {
    "FINAL ghost placement, shaped and shapeless matching, exact power and click vs shift-click": "finalghostsandpayment",
    "FINAL microscope journal load and removal, large ghosts and reload": "finaljournalandroundtrip",
    "FINAL sided storage, configured paid automation and survival synthesizer craft": "finalsidesandcraft",
    "All supported canonical FINAL synthesis registrations replay in order": "finalorderedrecipes",
}

# Legacy numeric ItemStack output IDs and damage values observed with the pinned config.
# Keep this table scoped to FINAL; none of these is a general metadata conversion rule.
_VANILLA = {
    1: "stone", 2: "grass_block", 3: "dirt", 4: "cobblestone", 12: "sand", 13: "gravel",
    20: "glass", 21: "lapis_ore", 22: "lapis_block", 30: "cobweb", 37: "dandelion",
    39: "brown_mushroom", 40: "red_mushroom", 41: "gold_block", 42: "iron_block", 46: "tnt",
    49: "obsidian", 57: "diamond_block", 72: "oak_pressure_plate", 81: "cactus", 86: "pumpkin",
    87: "netherrack", 88: "soul_sand", 89: "glowstone", 102: "glass_pane", 103: "melon",
    110: "mycelium", 121: "end_stone", 129: "emerald_ore", 133: "emerald_block", 152: "redstone_block",
    173: "coal_block", 260: "apple", 262: "arrow", 264: "diamond", 265: "iron_ingot", 266: "gold_ingot",
    280: "stick", 287: "string", 288: "feather", 289: "gunpowder", 297: "bread", 318: "flint",
    322: "golden_apple", 324: "oak_door", 326: "water_bucket", 331: "redstone", 332: "snowball",
    334: "leather", 336: "brick", 337: "clay_ball", 338: "sugar_cane", 339: "paper", 341: "slime_ball",
    348: "glowstone_dust", 352: "bone", 353: "sugar", 360: "melon_slice", 366: "cooked_chicken",
    367: "rotten_flesh", 368: "ender_pearl", 369: "blaze_rod", 370: "ghast_tear", 372: "nether_wart",
    373: "potion", 375: "spider_eye", 376: "fermented_spider_eye", 377: "blaze_powder",
    378: "magma_cream", 382: "glistering_melon_slice", 388: "emerald", 391: "carrot", 392: "potato",
    396: "golden_carrot", 399: "nether_star", 405: "nether_brick", 406: "quartz", 422: "hopper_minecart",
    2256: "music_disc_13", 2257: "music_disc_cat", 2259: "music_disc_chirp", 2260: "music_disc_far",
    2261: "music_disc_mall", 2262: "music_disc_mellohi", 2263: "music_disc_stal", 2264: "music_disc_strad",
    2265: "music_disc_ward",
}
_COLORS = ("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
           "purple", "blue", "brown", "green", "red", "black")
_WOODS = ("oak", "spruce", "birch", "jungle")
_VARIANTS = {
    5: tuple(f"{wood}_planks" for wood in _WOODS),
    6: tuple(f"{wood}_sapling" for wood in _WOODS),
    17: tuple(f"{wood}_log" for wood in _WOODS),
    24: ("sandstone", "chiseled_sandstone", "cut_sandstone"),
    31: (None, "short_grass"),
    35: tuple(f"{color}_wool" for color in _COLORS),
    171: tuple(f"{color}_carpet" for color in _COLORS),
    263: ("coal", "charcoal"),
    351: ("ink_sac", "red_dye", "green_dye", "cocoa_beans", "lapis_lazuli", "purple_dye", "cyan_dye", "light_gray_dye",
          "gray_dye", "pink_dye", "lime_dye", "yellow_dye", "light_blue_dye", "magenta_dye", "orange_dye", "bone_meal"),
}


@dataclass(frozen=True)
class ObservedRule:
    key: str
    count: int
    energy: int
    shaped: bool
    pattern: tuple[tuple[str, int] | None, ...]
    canonical: str


def observations() -> tuple[ObservedRule, ...]:
    rows = []
    seen = set()
    for line in TRACE.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(r"(m\d{1,3}|v\d{1,4}:\d{1,2});([1-9]\d?);([1-9]\d{0,6});(shaped|shapeless);([^;]+);(paid|power|shadow:\d{1,3})", line)
        if match is None:
            raise InputError(f"Invalid FINAL synthesis observation: {line!r}")
        key, count, energy, kind, tokens, status = match.groups()
        parts = []
        for token in tokens.split(","):
            if token == "_":
                parts.append(None)
                continue
            part = re.fullmatch(r"([em]\d{1,3})x([1-9]\d{0,2})", token)
            if not part or int(part[2]) > 9 * 64:
                raise InputError(f"Invalid FINAL pattern part for {key}: {token!r}")
            parts.append((part[1], int(part[2])))
        if (key in seen or int(count) > 64 or not 1 <= len(parts) <= 9 or not any(parts)
                or status.startswith("shadow:") and int(status.split(":")[1]) >= len(rows)
                or (status == "power") != (int(energy) > 1_022_220)):
            raise InputError(f"Unrepresentable FINAL synthesis observation: {line!r}")
        seen.add(key)
        rows.append(ObservedRule(key, int(count), int(energy), kind == "shaped", tuple(parts), status))
    if len(rows) != 301:
        raise InputError("The ordered FINAL synthesis trace is incomplete.")
    return tuple(rows)


def chemical_ids(namespace: str, entries: list[CatalogEntry]) -> dict[str, str]:
    result = {}
    for entry in entries:
        prefix = "e" if entry.path.startswith("element_") else "m"
        value = entry.legacy.get("legacy_metadata")
        key = f"{prefix}{value}"
        if not isinstance(value, int) or key in result:
            raise InputError(f"Invalid or duplicate FINAL chemical metadata for {entry.path}")
        result[key] = f"{namespace}:{entry.path}"
    return result


def resolve(key: str, namespace: str, ids: dict[str, str]) -> tuple[str, bool]:
    if key.startswith(("e", "m")):
        if key not in ids:
            raise InputError(f"Missing FINAL chemical metadata {key}")
        return ids[key], True
    if key == "v4360:0":
        # Unported Polytool: keep its earlier match in the selector so a large
        # diamond ghost pattern can't fall through to a cheaper output.
        return f"{namespace}:polytool", False
    numeric, damage = map(int, key[1:].split(":"))
    variants = _VARIANTS.get(numeric, ())
    item = variants[damage] if damage < len(variants) else (_VANILLA.get(numeric) if damage == 0 else None)
    if item is None:
        raise InputError(f"No verified FINAL vanilla output mapping for {key}")
    return f"minecraft:{item}", True


def asset_paths(namespace: str) -> dict[str, str]:
    return {f"assets/minechem/textures/{old}.png": f"assets/{namespace}/textures/{new}.png" for old, new in (
        ("gui/SynthesisGUI", "gui/final_synthesis"), ("blocks/blockSynthesis", "block/synthesizer"),
        ("items/AtomicManipulator", "item/atomic_manipulator"),
    )}


def read_assets(source, paths: dict[str, str]) -> dict[str, bytes]:
    textures = {name: source.read(name, 1024 * 1024) for name in paths}
    for name, data in textures.items():
        if not data.startswith(PNG_SIGNATURE) or f"{name}.mcmeta" in source.files:
            raise InputError(f"Expected a standalone FINAL synthesizer PNG: {name!r}")
    return textures


def checks(package: str, test_namespace: str, entries: list[CatalogEntry]) -> str:
    namespace = "minechem_chemistry"
    ids = chemical_ids(namespace, entries)
    cases = []
    for row in observations():
        output, native = resolve(row.key, namespace, ids)
        pattern = ",".join(f"{ids[part[0]]}@{part[1]}" if part else "_" for part in row.pattern)
        cases.append("        " + json.dumps(f"{output};{row.count};{row.energy};{pattern};{row.canonical};{str(native).lower()}"))
    return (TRACE.with_name("SynthesisFinalChecks.java").read_text(encoding="utf-8")
            .replace("@PACKAGE@", package).replace("@TEST_NAMESPACE@", test_namespace).replace("@TRACE@", ",\n".join(cases)))


def entries_from_catalog(catalog: dict) -> list[CatalogEntry]:
    return [CatalogEntry(row["new_id"].split(":", 1)[1], "", "", (), row) for row in catalog["entries"]]
