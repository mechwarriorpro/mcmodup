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
JAVA = ("PolytoolItem", "PolytoolRules", "PolytoolMenu", "PolytoolScreen")
LIMIT = ("Polytool supports measured infusion/alloy rules, iron vein mining, oxygen air and sulfur ignition. "
         "Only FINAL has a native Lithium charge adapter; the other accepted ranks have no module effect yet. "
         "No legacy NBT migration, UE/RF item charging API, or original Polytool orbit animation.")


def trace_name(release_394: bool) -> str:
    return f"{'release394' if release_394 else 'final'}-polytool.txt"


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
    files = {f"{java}/{name}.java": (TEMPLATES / f"{name}.java").read_text(encoding="utf-8")
             .replace("@PACKAGE@", package).replace("@NAMESPACE@", namespace)
             .replace("@RELEASE_394@", str(release_394).lower()).replace("@INFUSIONS@", ",\n".join(cases))
             for name in JAVA}
    gui = {"infuse": "Infuse", "prompt": "64 tubes", "title": "Polytool · " + (".394" if release_394 else "FINAL"),
           "sword": "Sword: %s", "ore": "Ores: %s", "stone": "Stone: %s", "axe": "Axe: %s", "shovel": "Shovel: %s",
           "total": "Infusions: %s", "charge": "Charge: %s FE", "bonus": "Lithium melee bonus: +%s",
           "usage": "Hold in your main hand and use to infuse",
           "help": "Left-click here with 64 matching element tubes",
           "shift": "Shift-click a full stack to infuse" if release_394 else "FINAL uses left-click, not shift-click",
           "wrong_input": "Needs 64 tubes of an eligible element from this release",
           "reject": "Infusion rejected: a stat would reach zero or the rank limit was reached",
           "alloy": "Alloy: changes the five tool stats",
           "rank": "%s · rank %s", "rank_next": "%s: rank %s → %s", "more": "… and %s more elements",
           "air": "Oxygen: restores air when mining", "vein": "Iron: mines adjacent matching ores",
           "fire": "Sulfur: ignites targets on melee hits", "lithium": "Lithium: enables FE charging and a melee bonus",
           "inactive": "Rank is saved; this module effect isn't ported"}
    return files, {f"gui.{namespace}.polytool.{key}": value for key, value in gui.items()}


def features(release_394: bool) -> dict[str, str]:
    prefix = "release394" if release_394 else "final"
    return {"Full-stack Polytool infusion, invalid inputs, and safe held-item binding": prefix + "polytoolinfusion",
            "All observed Polytool inputs and alloy speeds, stat floor and saved ranks": prefix + "polytoolalloysandsave",
            "Polytool ore, air and hit hooks; release-local charging": prefix + "polytoolminingandcharge"}


def checks(package: str, test_namespace: str, namespace: str, entries: list[CatalogEntry], release_394: bool) -> str:
    prefix = "release394" if release_394 else "final"
    cases = [json.dumps(f"{row.id}|{','.join(map(str, row.mining or (8,) * 12))}")
             for row in infusions(namespace, entries, release_394)]
    return ((TEMPLATES / "PolytoolChecks.java").read_text(encoding="utf-8")
            .replace("@PACKAGE@", package).replace("@TEST_NAMESPACE@", test_namespace).replace("@NAMESPACE@", namespace)
            .replace("@RELEASE_394@", str(release_394).lower()).replace("@PREFIX@", prefix)
            .replace("@CLASS@", "PolytoolChecks394" if release_394 else "PolytoolChecksFinal")
            .replace("@CASES@", ",\n".join(f"        {case}" for case in cases)))
