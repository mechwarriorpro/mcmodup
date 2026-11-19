"""Observe the exact Mimicry catalyst in isolation on the original Forge server."""

import argparse
from pathlib import Path
import traceback
from zipfile import ZipFile

from mcmodup.runtime import file_digest
from mixed_legacy_pack_smoke import ORIGINALS, note
from voltz_legacy import probe


MIMICRY = next(entry for entry in ORIGINALS if entry.ids == ("mimicry",))


def check(root: Path) -> None:
    original = root / MIMICRY.filename
    assert file_digest(original) == MIMICRY.sha256
    observed = probe(root, "LegacyMimicryCatalyst", {}, (), "MCMODUP_MIMICRY",
                     supplied={MIMICRY.label: (original, MIMICRY.sha256)}, providers=())
    note("Exact Mimicry catalyst on Forge", observed)
    # Record names only. Do not publish the ZIP, game data, or its textures.
    with ZipFile(original) as archive:
        items = sorted(path for path in archive.namelist()
                       if path.startswith("assets/") and "/textures/items/" in path and path.endswith(".png"))
    note("Mimicry source texture paths", "; ".join(items[:30]))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    try:
        check(parser.parse_args().root.resolve())
    except Exception:
        note("Original Mimicry probe failed", traceback.format_exc()[-3200:], level="error")
        raise
