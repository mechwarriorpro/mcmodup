"""Observe the pinned GeoStrata/DragonAPI pair, without replacing either archive."""

import argparse
from collections import Counter
from pathlib import Path
import traceback
from zipfile import ZipFile

from mcmodup.classfiles import read_class
from mcmodup.geostrata_rocks import VEINS, VEIN_SIZE
from mixed_legacy_pack_smoke import ORIGINALS, note
from voltz_legacy import probe


GEOSTRATA = next(entry for entry in ORIGINALS if entry.ids == ("geostrata",))
DRAGONAPI = "DragonAPI 1.6 v23b.zip"
DRAGONAPI_SHA256 = "bf30330e9ada3efa3810747ac3ed096cd7ada35371dd8554a357a36c8703c86f"  # FTB Monster 1.1.2


def check_terrain(properties: str, generated: str, terrain: str) -> None:
    assert terrain.startswith(f"base=24; vein size={VEIN_SIZE}; dimensions={{0=true, -1=false, 1=false, 7=true, 42=true}}; "), terrain
    profiles = {name.lower(): row.split(",") for name, row in (entry.split("=", 1) for entry in properties.split(";"))}
    settings = {name.lower(): row.split("/") for name, row in (entry.split("=", 1) for entry in
                terrain.split("; density/effective ceiling/plains-desert-snow-lava={", 1)[1].removesuffix("}").split(", "))}
    assert profiles.keys() == settings.keys() == VEINS.keys(), (profiles.keys(), settings.keys())
    masks = {"any_overworld": "1111", "not_desert": "1011", "snowy": "0010", "adjacent_lava": "0001"}
    bounds: Counter[int] = Counter()
    for name, vein in VEINS.items():
        assert tuple(map(int, profiles[name][3:5])) == (vein.min_y, vein.max_y), name
        assert settings[name][1:] == [str(vein.attempts), masks[vein.condition]], (name, settings[name])
        bounds[16] += 2 * vein.attempts
        bounds[vein.max_y - vein.min_y] += vein.attempts
    calls = {int(bound): int(count) for bound, count in (entry.split("=", 1) for entry in
             generated.split("; direct generator RNG bounds={", 1)[1].removesuffix("}").split(", "))}
    assert calls == bounds, (calls, bounds)


def run(root: Path) -> None:
    original = root / GEOSTRATA.filename
    with ZipFile(original) as archive:
        names = [path for path in archive.namelist() if path.endswith("/BlockConnectedRock.class")]
        assert len(names) == 1, names
        methods = read_class(archive.read(names[0])).members
        note("Original connected rock method descriptors", "; ".join(f"{name}{descriptor}" for name, descriptor in methods if "Edge" in name))
    evidence = probe(root, "LegacyGeoRockSurvey", {}, (), "MCMODUP_GEO", providers=(), supplied={
        f"mods/{GEOSTRATA.filename}": (original, GEOSTRATA.sha256),
        f"mods/{DRAGONAPI}": (root / DRAGONAPI, DRAGONAPI_SHA256),
    })
    cases, properties, generated, terrain, connected = evidence.split(" | ")
    check_terrain(properties, generated, terrain)
    note("Exact GeoStrata v23b rock survey on Forge", cases)
    note("Original hardness, blast resistance, pickaxe tier, height bounds and rarity", properties)
    note("Exact GeoStrata controlled stone replacement", generated)
    note("Exact GeoStrata origin rules and vein settings", terrain)
    note("Exact GeoStrata v23b connected-rock masks", connected)
    with ZipFile(original) as archive:
        textures = sorted(path for path in archive.namelist()
                          if path.lower().endswith(".png") and any(name in path.lower() for name in ("granite", "opal", "/connected/")))
    note("Original rock texture paths (names only)", "; ".join(textures[:45]))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, help="Scratch directory with the Voltz Forge bundle and both original ZIPs; set JAVA_HOME to Java 7")
    try:
        run(parser.parse_args().root.resolve())
    except Exception:
        note("Original GeoStrata probe failed", traceback.format_exc()[-4100:], level="error")
        raise
