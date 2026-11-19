"""Observe the pinned GeoStrata/DragonAPI pair, without replacing either archive."""

import argparse
from pathlib import Path
import traceback
from zipfile import ZipFile

from mixed_legacy_pack_smoke import ORIGINALS, note
from voltz_legacy import probe


GEOSTRATA = next(entry for entry in ORIGINALS if entry.ids == ("geostrata",))
DRAGONAPI = "DragonAPI 1.6 v23b.zip"
DRAGONAPI_SHA256 = "bf30330e9ada3efa3810747ac3ed096cd7ada35371dd8554a357a36c8703c86f"  # FTB Monster 1.1.2


def run(root: Path) -> None:
    original = root / GEOSTRATA.filename
    evidence = probe(root, "LegacyGeoRockSurvey", {}, (), "MCMODUP_GEO", providers=(), supplied={
        f"mods/{GEOSTRATA.filename}": (original, GEOSTRATA.sha256),
        f"mods/{DRAGONAPI}": (root / DRAGONAPI, DRAGONAPI_SHA256),
    })
    cases, properties, generated, terrain = evidence.split(" | ")
    note("Exact GeoStrata v23b rock survey on Forge", cases)
    note("Original hardness, blast resistance, pickaxe tier, height bounds and rarity", properties)
    note("Exact GeoStrata controlled stone replacement", generated)
    note("Exact GeoStrata origin rules and vein settings", terrain)
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
