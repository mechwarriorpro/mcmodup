"""Observe the pinned GeoStrata/DragonAPI pair, without replacing either archive."""

import argparse
from pathlib import Path
import traceback

from mcmodup.runtime import file_digest
from mixed_legacy_pack_smoke import ORIGINALS, note
from voltz_legacy import probe


GEOSTRATA = next(entry for entry in ORIGINALS if entry.ids == ("geostrata",))
DRAGONAPI = "DragonAPI 1.6 v23b.zip"
DRAGONAPI_SHA1 = "2dbd889f480fe39c094575c8045b4f4652a33aa3"  # FTB Monster 1.1.2


def run(root: Path) -> None:
    dragon = root / DRAGONAPI
    assert file_digest(dragon, "sha1") == DRAGONAPI_SHA1, "Use the matched original DragonAPI v23b"
    dragon_sha256 = file_digest(dragon)
    evidence = probe(root, "LegacyGeoRockSurvey", {}, (), "MCMODUP_GEO", providers=(), supplied={
        f"mods/{GEOSTRATA.filename}": (root / GEOSTRATA.filename, GEOSTRATA.sha256),
        f"mods/{DRAGONAPI}": (dragon, dragon_sha256),
    })
    note("Exact GeoStrata v23b rock survey on Forge", evidence)
    note("Matched DragonAPI v23b SHA-256", dragon_sha256)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, help="Scratch directory with the Voltz Forge bundle and both original ZIPs; set JAVA_HOME to Java 7")
    try:
        run(parser.parse_args().root.resolve())
    except Exception:
        note("Original GeoStrata probe failed", traceback.format_exc()[-4100:], level="error")
        raise
