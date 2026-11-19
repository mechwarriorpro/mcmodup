"""Release-local Polytool profiles, observed against the exact Forge binaries."""

from dataclasses import dataclass
import json
from pathlib import Path
import re

from .catalogs import CatalogEntry
from .inputs import InputError
from .minechem_final_synthesis import chemical_ids
from .minechem_materials import observations


TEMPLATES = Path(__file__).with_name("templates") / "minechem"
JAVA = ("PolytoolItem", "PolytoolRules", "PolytoolMenu", "PolytoolScreen", "PolytoolMining", "PolytoolCombat", "PolytoolWorld")
LIMIT = ("Polytool ports the original module roster with native combat, inventories, drops and world hooks. "
         "Modern armor, cooldowns, FE, explosions and guarded fire placement differ; no legacy NBT, UE/CoFH "
         "item API or reference-client pixel comparison. The infuser uses the original orbit geometry with native rendering.")


def layout_trace_name(release_394: bool) -> str:
    return f"{'release394' if release_394 else 'final'}-polytool-layout.txt"


def trace_name(release_394: bool) -> str:
    return f"{'release394' if release_394 else 'final'}-polytool.txt"


def mining_trace_name(release_394: bool) -> str:
    return f"{'release394' if release_394 else 'final'}-polytool-mining.txt"


def combat_trace_name(release_394: bool) -> str:
    return f"{'release394' if release_394 else 'final'}-polytool-combat.txt"


def asset_paths(namespace: str) -> dict[str, str]:
    return {f"assets/minechem/textures/{old}/polytool.png": f"assets/{namespace}/textures/{new}/polytool.png"
            for old, new in (("items", "item"), ("gui", "gui"))}


@dataclass(frozen=True)
class Infusion:
    atomic: int
    id: str
    alloy: tuple[int, ...] = ()
    mining: tuple[int, ...] = ()


def infusions(namespace: str, entries: list[CatalogEntry], release_394: bool) -> tuple[Infusion, ...]:
    ids = chemical_ids(namespace, entries)
    found = []
    for key, row in observations(trace_name(release_394)).items():
        if not re.fullmatch(r"e[1-9]\d{0,2}", key):
            continue
        atomic = int(key[1:])
        metadata = atomic if release_394 else atomic - 1
        item = ids.get(f"e{metadata}")
        if item is None:
            raise InputError(f"Could not map original Minechem Polytool element {key}.")
        if row == "module":
            found.append(Infusion(atomic, item))
            continue
        try:
            kind, delta, panel, mining = row.split(";")
            raw = tuple(map(int, delta.split(",")))
            blade, ore, stone, axe, shovel = raw
            base = (8, 8 + ore, 8 + stone, 8 + axe, 8 + shovel)
            labels = (8 + blade, *base[1:]) if release_394 else base
            sample = (*base, base[2], base[1], base[3], base[4], 8, 8, 8)
            if (kind != "alloy" or any(value not in (-4, -2, 0, 8) for value in raw) or raw.count(8) != 1 or
                    tuple(map(int, panel.split(","))) != labels or tuple(map(int, mining.split(","))) != sample):
                raise ValueError("Unexpected alloy or sample data")
        except ValueError as exc:
            raise InputError(f"Invalid Polytool alloy trace at {key}: {row!r}") from exc
        found.append(Infusion(atomic, item, raw, sample))
    if (len(found) != (48 if release_394 else 49) or sum(bool(row.alloy) for row in found) != 15 or
            (3 in {row.atomic for row in found}) == release_394):
        raise InputError("Incomplete or mixed Minechem Polytool observations")
    return tuple(found)


def project(namespace: str, package: str, entries: list[CatalogEntry], release_394: bool) -> tuple[dict[str, str], dict[str, str]]:
    java = "src/main/java/" + package.replace(".", "/")
    cases = []
    for row in infusions(namespace, entries, release_394):
        vector = f"new Stats({', '.join(map(str, row.alloy))})" if row.alloy else "null"
        cases.append(f"        new Infusion({row.atomic}, ResourceLocation.parse({json.dumps(row.id)}), {vector})")
    gold = observations(combat_trace_name(release_394))["gold_transfer_roll0"].split("/")
    if (len(gold) != 5 or gold[0] != "1" or gold[1] != ("true" if release_394 else "UNKNOWN") or gold[3] != "false"
            or not re.fullmatch(r"[1-9][0-9]{0,9}", gold[2]) or int(gold[2]) >= 2**31):
        raise InputError("Invalid original Gold transfer observation")
    layout = observations(layout_trace_name(release_394))
    if (layout["sheet"] != "176x218" or not re.fullmatch(r"[1-9]\d?/[1-9]\d?", layout["radius"])
            or not re.fullmatch(r"[1-9]\d?/[1-9]\d?", layout["center"])
            or not re.fullmatch(r"0\.0[1-9]", layout["step"]) or (layout["help"] == "-") == release_394):
        raise InputError("Invalid original Polytool layout observation")
    center_x, center_y = layout["center"].split("/")
    inner, outer = layout["radius"].split("/")
    files = {f"{java}/{name}.java": (TEMPLATES / f"{name}.java").read_text(encoding="utf-8")
             .replace("@PACKAGE@", package).replace("@NAMESPACE@", namespace)
             .replace("@RELEASE_394@", str(release_394).lower()).replace("@INFUSIONS@", ",\n".join(cases))
             .replace("@GOLD_REQUEST@", gold[2]).replace("@ORBIT_X@", center_x).replace("@ORBIT_Y@", center_y)
             .replace("@ORBIT_INNER@", inner).replace("@ORBIT_OUTER@", outer).replace("@ORBIT_STEP@", layout["step"])
             for name in JAVA}
    gui = {"infuse": "Infuse", "prompt": "64 tubes", "title": "Polytool · " + (".394" if release_394 else "FINAL"),
           "sword": "Sword: %s", "ore": "Ores: %s", "stone": "Stone: %s", "axe": "Axe: %s", "shovel": "Shovel: %s",
           "total": "Infusions: %s", "charge": "Charge: %s FE", "bonus": "Lithium melee bonus: +%s",
           "usage": "Hold in your main hand and use to infuse",
           "help": "Left-click here with 64 matching element tubes",
           "shift": "Shift-click a full stack to infuse" if release_394 else "FINAL uses left-click, not shift-click",
           "wrong_input": "Needs 64 tubes of an eligible element from this release",
           "reject": "Infusion rejected: a stat would reach zero or the rank limit was reached",
           "alloy": "Alloy: trades one tool stat against others",
           "rank": "%s · rank %s", "rank_next": "%s: rank %s → %s", "more": "… and %s more elements",
           "air": "Oxygen: restores air when mining",
           "vein": "Iron: adjacent coal/diamond need Carbon to drop items" if release_394 else "Iron: mines adjacent matching ores",
           "carbon": "Carbon: boosts coal and diamond drops",
           "fluorine": "Fluorine: chance to restore hunger when mining",
           "sodium": "Sodium: chance to light a dark spot at your feet" if release_394 else "Sodium: chance to place a torch at your feet",
           "chromium": "Chromium: chance to recolor mined wool",
           "nickel": "Nickel: mining attracts nearby dropped items",
           "bromine": "Bromine: turns mined gold ore into ingots",
           "mercury": "Mercury: warns of lava below a mined block", "lava_warning": "WARNING: LAVA UNDERNEATH",
           "fire": "Sulfur: ignites targets on melee hits", "lithium": "Lithium: enables FE charging and a melee bonus",
           "hydrogen": "Hydrogen: a burning mob explodes when hit",
           "helium": "Helium: the last target lifts while the tool is carried",
           "beryllium": "Beryllium: eligible kills drop heads above rank 5",
           "boron": "Boron: extra melee damage against spiders",
           "nitrogen": "Nitrogen: zombie kills yield cooked beef instead of rotten flesh",
           "neon": "Neon: melee hits grant brief night vision",
           "magnesium": "Magnesium: blinds nearby living entities on a hit",
           "silicon": "Silicon: chance to decompose loot on melee kills",
           "phosphorus": "Phosphorus: lights nearby supported fire when mining flammable blocks",
           "chlorine": "Chlorine: poisons nearby living entities on a hit",
           "argon": "Argon: suffocation damage attempt under cover",
           "calcium": "Calcium: boosts bones already dropped by a skeleton",
           "titanium": "Titanium: struck mobs are more likely to drop their held item" if release_394 else
                       "Titanium: eligible kills drop equipped items",
           "zinc": "Zinc: rare diamond when harvesting dirt",
           "krypton": "Krypton: damage attempt against players above 23 armor",
           "rubidium": "Rubidium: wet targets or underwater mining cause an explosion",
           "silver": "Silver: extra melee damage against Endermen",
           "caesium": "Caesium: causes an explosion when mining stone",
           "platinum": "Platinum: chance to gain experience on a hit",
           "gold": "Gold: carried tool may summon lightning and charge compatible FE items",
           "lead": "Lead: drives nearby targets downward",
           "francium": "Francium: hits and mining cause explosions in rainy weather",
           "uranium": "Uranium: withers nearby living entities on a hit",
           "inactive": "Rank is saved; this module effect isn't ported"}
    if release_394:
        gui.update(help_tab="Polytool help", help_page=layout["help"])
    return files, {f"gui.{namespace}.polytool.{key}": value for key, value in gui.items()}


def features(release_394: bool) -> dict[str, str]:
    prefix = "release394" if release_394 else "final"
    return {"Full-stack Polytool infusion, invalid inputs, and safe held-item binding": prefix + "polytoolinfusion",
            "All observed Polytool inputs and alloy speeds, stat floor and saved ranks": prefix + "polytoolalloysandsave",
            "Polytool ore, air and hit hooks; release-local charging": prefix + "polytoolminingandcharge",
            "Polytool drops, separate Iron and Carbon harvest, Silk Touch": prefix + "polytooldrops",
            "Polytool torch and food rolls, item attraction and lava warnings": prefix + "polytoolutility",
            "Polytool protected breaks, drop cancellation and creative isolation": prefix + "polytoolprotection",
            "Polytool damage and hurt resistance against spiders, Endermen and covered targets": prefix + "polytoolcombatdamage",
            "Polytool attack effects, launch target persistence and carried lightning": prefix + "polytoolcarriedeffects",
            "Polytool release-local melee drops, equipment, and decomposition rolls": prefix + "polytoolcombatdrops",
            "Polytool blast, protected fire, diamond and experience effects": prefix + "polytoolworldeffects"}


def checks(package: str, test_namespace: str, namespace: str, entries: list[CatalogEntry], release_394: bool) -> str:
    prefix = "release394" if release_394 else "final"
    cases = [json.dumps(f"{row.id}|{','.join(map(str, row.mining or (8,) * 12))}")
             for row in infusions(namespace, entries, release_394)]
    mining = observations(mining_trace_name(release_394))
    mining_rows = [f"        Map.entry({json.dumps(key)}, {json.dumps(value)})" for key, value in mining.items()]
    combat = observations(combat_trace_name(release_394))
    combat_rows = [f"        Map.entry({json.dumps(key)}, {json.dumps(value)})" for key, value in combat.items()]
    return ((TEMPLATES / "PolytoolChecks.java").read_text(encoding="utf-8")
            .replace("@PACKAGE@", package).replace("@TEST_NAMESPACE@", test_namespace).replace("@NAMESPACE@", namespace)
            .replace("@RELEASE_394@", str(release_394).lower()).replace("@PREFIX@", prefix)
            .replace("@CLASS@", "PolytoolChecks394" if release_394 else "PolytoolChecksFinal")
            .replace("@CASES@", ",\n".join(f"        {case}" for case in cases))
            .replace("@MINING_CASES@", ",\n".join(mining_rows))
            .replace("@COMBAT_CASES@", ",\n".join(combat_rows)))
