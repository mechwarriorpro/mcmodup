"""Boot a private one-JAR build without the individual production JARs or legacy APIs."""

import argparse
import json
from pathlib import Path
import shutil
import traceback
from zipfile import ZipFile

from mcmodup.audit import ModMetadata
from mcmodup.legacy_testbed import AUDIT, COMPONENTS, JAR_NAME, REPORT_NAME, build_testbed
from mcmodup.port import _build_gradle, _metadata, empty_test_structure, write_workspace
from mcmodup.source_ports import digest
from mixed_legacy_pack_smoke import note, summary
from native_pack_smoke import run_gradle
from pack_release_smoke import boot


CHECK = "mcmodup_testbed_check"


def staged(root: Path, jar: Path) -> str:
    from mcmodup import atomic_thermometer, geostrata_rocks, icbm_plate, minechem_pair

    package = "mcmodup.testbedcheck"
    source = f"src/main/java/{package.replace('.', '/')}"
    resources = "src/main/resources"
    files = {
        "settings.gradle": "rootProject.name = 'legacy-testbed-check'\n",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "build.gradle": _build_gradle(CHECK, package, "1.0.0") + f"""
dependencies {{ compileOnly files('run/mods/{JAR_NAME}') }}
neoForge.runs.create('gameTestServer') {{
    type = 'gameTestServer'
    gameDirectory = project.file('run')
    systemProperty 'neoforge.enabledGameTestNamespaces', '{CHECK}'
}}
""",
        f"{resources}/META-INF/neoforge.mods.toml": _metadata(ModMetadata(""), CHECK, "Staged JAR test probe", "1.0.0", "Unlicense"),
        f"run/mods/{JAR_NAME}": jar.read_bytes(),
        f"{source}/GeoCrystalChecks.java": geostrata_rocks.crystal_checks(package, CHECK),
        f"{source}/GeoRockChecks.java": geostrata_rocks.checks(package, CHECK),
        f"{source}/LaunchChecks.java": icbm_plate.launch_checks(package, CHECK),
        f"{source}/BuildingChecks.java": icbm_plate.building_checks(package, CHECK),
        f"{source}/AcceleratorChecks.java": atomic_thermometer.accelerator_checks(package, CHECK),
        f"{source}/SteamChecks.java": atomic_thermometer.steam_checks(package, CHECK),
        f"{source}/JournalCloneChecks.java": minechem_pair.journal_clone_checks(package, CHECK, minechem_pair.FINAL.namespace),
        f"{source}/JournalCloneChecks394.java": minechem_pair.journal_clone_checks(package, CHECK, minechem_pair.RELEASE_394.namespace, "JournalCloneChecks394"),
    }
    for room, size in {"rock_room": 3, "vein_room": 19, "crystal_room": 9, "empty": 5, "test_room": 5, "turbine_room": 5, "platform_room": 5, "accel_room": 15, "launch_room": 17}.items():
        files[f"{resources}/data/{CHECK}/structure/{room}.nbt"] = empty_test_structure(size)
    write_workspace(root, files)
    result = run_gradle(root, "runGameTestServer")
    marker = next((line.split("required tests passed", 1)[0].rsplit("All ", 1)[-1].strip()
                   for line in result.splitlines() if "All " in line and "required tests passed" in line), None)
    assert marker == "12", result[-5400:]
    assert digest(root / "run/mods" / JAR_NAME) == digest(jar), "Staged test mutated the JAR"
    return marker


def check(root: Path) -> None:
    import tomllib

    paths = (root / "Minechem-1.6.4-5.0.5.FINAL.jar", root / "Minechem-1.7.10-5.0.5.394.jar",
             root / "voltz.zip", root / "GeoStrata 1.6 v23b.zip")
    hashes = {p: digest(p) for p in paths}
    jar = build_testbed(*paths, root / "output", allow_preview=True, timeout=420)
    report = json.loads((jar.parent / REPORT_NAME).read_bytes())
    assert {p: digest(p) for p in paths} == hashes
    assert {p.name for p in jar.parent.iterdir()} == {JAR_NAME, REPORT_NAME}
    assert report["jar_sha256"] == digest(jar) and not report["parity_verified"]
    with ZipFile(jar) as zipped:
        names = zipped.namelist()
        assert len(set(names)) == len(names) and not any(n.endswith(".jar") or n.startswith(("minechem/", "icbm/", "atomicscience/", "calclavia/", "Reika/")) for n in names)
        assert {m["modId"]: m["version"] for m in tomllib.loads(zipped.read("META-INF/neoforge.mods.toml").decode())["mods"]} == COMPONENTS
        assert json.loads(zipped.read(AUDIT))["components"] == report["components"]
        assert len(json.loads(zipped.read("data/minecraft/tags/block/mineable/pickaxe.json"))["values"]) > 153
    mods = root / "server/mods"
    mods.mkdir(exist_ok=True)
    assert not tuple(mods.iterdir())
    shutil.copyfile(jar, mods / jar.name)
    boot(root / "server", "bash run.sh nogui")
    count = staged(root / "staged", jar)
    source = sum(part["gametests"]["passed"] for part in report["components"].values())
    summary([f"**Single test JAR**: `{jar.name}` (SHA-256 `{digest(jar)}`)", "",
             f"{source} source and {count} staged test cases passed. A stock server booted with only this mod JAR.",
             "Exact originals were scanned and left unchanged. No legacy libraries or public GeoStrata asset artifact."])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    try:
        check(parser.parse_args().root.resolve())
    except Exception:
        note("One-JAR testbed failed", traceback.format_exc()[-9000:], level="error")
        raise
