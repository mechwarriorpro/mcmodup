"""Measure thermal, steam and non-firing ICBM tile behavior on exact Voltz originals."""

import argparse
from pathlib import Path
import traceback

from mcmodup.atomic_thermometer import LEGACY_SHA256 as ATOMIC
from mcmodup.icbm_plate import LEGACY_SHA256 as ICBM
from mixed_legacy_pack_smoke import note
from voltz_legacy import probe


def legacy(root: Path) -> None:
    evidence = probe(root, "LegacyVoltzInfrastructure", {"mods/atomicscience.jar": ATOMIC, "mods/icbm-1.4.1.344.jar": ICBM},
                     ("AtomicScience", "ICBM"), "MCMODUP_INFRA")
    for result in evidence.split(" | "):
        note("Original Voltz utility behavior", result)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    try:
        legacy(parser.parse_args().root.resolve())
    except Exception:
        note("Original Voltz infrastructure probe failed", traceback.format_exc()[-6000:], level="error")
        raise
