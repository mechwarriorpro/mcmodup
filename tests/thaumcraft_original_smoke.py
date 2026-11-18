"""Run scoped Thaumcraft parity oracles on the exact original Forge mod and Baubles."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import traceback
from zipfile import ZipFile

from legacy_recipe_fixture import java8, legacy_command, prepare, run_server
from mixed_legacy_pack_smoke import note
from mcmodup.runtime import file_digest


TC = "Thaumcraft-1.7.10-4.2.3.5.jar"
TC_HASH = "3dba9786966974701578a658d1bb369bf35bdf5f363079f5ac9c4910a39113be"
BAUBLES = "Baubles-1.7.10-1.0.1.10.jar"
BAUBLES_SHA1 = "b6e6ce2d0476d1d53686440f2fc70d2a27e289c2"
FORGE_SHA1 = "25fd97f72beca728112256938e03e8105b1b78cc"


def original(root: Path) -> None:
    source, server = root / "original", root / "server"
    assert file_digest(source / "mods" / TC) == TC_HASH
    assert file_digest(source / "bin/modpack.jar", "sha1") == FORGE_SHA1
    assert file_digest(root / BAUBLES, "sha1") == BAUBLES_SHA1
    forge = prepare(server, source, "1.7.10")
    shutil.copyfile(root / BAUBLES, server / "mods" / BAUBLES)
    probe = root / "probe"
    probe.mkdir()
    fixture = Path(__file__).with_name("fixtures") / "LegacyThaumcraftParity.java"
    subprocess.run([java8("javac"), "-proc:none", "-encoding", "UTF-8", "-classpath", str(forge),
                    "-d", str(probe), str(fixture)], check=True, timeout=60)
    with ZipFile(server / "mods/mcmodup-tc4oracle.jar", "x") as jar:
        for compiled in probe.rglob("*.class"):
            jar.write(compiled, compiled.relative_to(probe).as_posix())
    evidence = run_server(server, legacy_command("1.7.10", []), legacy=True,
                          success_marker="MCMODUP_TC4_ORIGINAL_PASS", failure_marker="MCMODUP_TC4_ORIGINAL_FAIL")
    assert file_digest(server / "mods" / TC) == TC_HASH
    note("Original TC4 behavior on Forge", evidence)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    try:
        original(parser.parse_args().root.resolve())
    except Exception:
        if os.environ.get("GITHUB_ACTIONS") == "true":
            note("Original TC4 behavior check failed", traceback.format_exc()[-5500:], level="error")
        raise
