"""Optional integration with the historical Voltz 3.0.0c server archive (never executed)."""

import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
import zipfile

from test_cli import run_cli


VOLTZ = os.environ.get("MCMODUP_VOLTZ_ARCHIVE")
VINEFLOWER = os.environ.get("MCMODUP_VINEFLOWER_JAR")
CORE = "mods/calclavia-core-development-1.2.0.6-main.jar"
MODS = (
    ("mods/atomicscience.jar", {"AtomicScience"}, "calclavia/lib/prefab/tile/TileElectricalInventory"),
    ("mods/icbm-1.4.1.344.jar", {"ICBM", "ICBM|Explosion", "ICBM|Sentry"}, "universalelectricity/api/vector/EulerAngle"),
)


@unittest.skipUnless(VOLTZ, "set MCMODUP_VOLTZ_ARCHIVE to the pinned historical Voltz 3.0.0c ZIP")
class VoltzExampleTests(unittest.TestCase):
    def test_resolves_actual_1_6_4_pack_inputs(self):
        archive = Path(VOLTZ).resolve()
        with archive.open("rb") as stream:
            self.assertEqual(hashlib.file_digest(stream, "sha256").hexdigest(),
                             "2eda607746a22edbf45612243adce37a876251e36d46c692a48344fe98eec84e")
        with tempfile.TemporaryDirectory(prefix="mcmodup-voltz-test-") as scratch, zipfile.ZipFile(archive) as pack:
            scratch = Path(scratch)
            for member, mod_ids, example_class in MODS:
                with self.subTest(member=member):
                    selected = scratch / Path(member).name
                    selected.write_bytes(pack.read(member))
                    proc = run_cli("scan-pack", archive, "--mod-jar", selected, "--source-version", "1.6.4", "--json")
                    self.assertEqual(proc.returncode, 0, proc.stderr)
                    report = json.loads(proc.stdout)
                    self.assertEqual(report["archives_indexed"], 36)
                    self.assertFalse(report["archive_errors"])
                    result, = report["archives"]
                    self.assertEqual(result["archive"], member)  # Same bytes are not indexed as a second mod.
                    self.assertEqual({item["mod_id"] for item in result["mods"]}, mod_ids)
                    self.assertTrue(any(d["id"] == "CalclaviaCore" and d["status"] == "present" and d["archives"] == [CORE]
                                        for d in result["declared_dependencies"]))
                    deps = {d["archive"]: d["classes"] for d in result["binary_dependencies"]}
                    self.assertIn(CORE, deps)
                    self.assertFalse(any(c.startswith("net/minecraft/") for names in deps.values() for c in names))
                    self.assertTrue(any(c["guidance"] and "universal_electricity" in {r["area"] for r in c["guidance"]}
                                        for c in result["api_checks"]))
                    if VINEFLOWER:
                        out = scratch / f"references-{selected.stem}"
                        decompile = run_cli("decompile-deps", archive, "--mod-jar", selected, "--source-version", "1.6.4",
                                            "--vineflower", VINEFLOWER, "--class", example_class, "-o", out)
                        self.assertEqual(decompile.returncode, 0, decompile.stderr)
                        self.assertTrue((out / "sources" / f"{example_class}.java").is_file())
