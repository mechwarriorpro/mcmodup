"""Boot a private one-JAR build without the individual production JARs or legacy APIs."""

import argparse
import json
import re
from pathlib import Path
import shutil
import traceback
from zipfile import ZipFile

from mcmodup.audit import ModMetadata
from mcmodup.legacy_testbed import AUDIT, COMPONENTS, JAR_NAME, REPORT_NAME, build_testbed, fetch_originals
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
        f"{source}/Sided394Checks.java": minechem_pair.sided394_checks(package, CHECK),
        f"{source}/FinalWaterChecks.java": minechem_pair.final_water_checks(package, CHECK),
        f"{source}/JournalCloneChecks.java": minechem_pair.journal_clone_checks(package, CHECK, minechem_pair.FINAL.namespace),
        f"{source}/JournalCloneChecks394.java": minechem_pair.journal_clone_checks(package, CHECK, minechem_pair.RELEASE_394.namespace, "JournalCloneChecks394"),
    }
    for room, size in {"rock_room": 3, "vein_room": 19, "crystal_room": 9, "water_room": 5, "journal_room": 5, "empty": 5, "test_room": 5, "turbine_room": 5, "platform_room": 5, "accel_room": 15, "launch_room": 17}.items():
        files[f"{resources}/data/{CHECK}/structure/{room}.nbt"] = empty_test_structure(size)
    write_workspace(root, files)
    result = run_gradle(root, "runGameTestServer")
    marker = next((line.split("required tests passed", 1)[0].rsplit("All ", 1)[-1].strip()
                   for line in result.splitlines() if "All " in line and "required tests passed" in line), None)
    assert marker == "16", result[-5400:]
    assert digest(root / "run/mods" / JAR_NAME) == digest(jar), "Staged test mutated the JAR"
    return marker


def check(root: Path, *, fetch: bool = False, client: bool = False) -> None:
    import tomllib

    paths = fetch_originals(root / "originals") if fetch else (root / "Minechem-1.6.4-5.0.5.FINAL.jar", root / "Minechem-1.7.10-5.0.5.394.jar",
             root / "voltz.zip", root / "GeoStrata 1.6 v23b.zip")
    hashes = {p: digest(p) for p in paths}
    jar = build_testbed(*paths, root / "output", allow_preview=True, timeout=420)
    report = json.loads((jar.parent / REPORT_NAME).read_bytes())
    assert {p: digest(p) for p in paths} == hashes
    assert {p.name for p in jar.parent.iterdir()} == {JAR_NAME, REPORT_NAME}
    assert report["jar_sha256"] == digest(jar) and not report["parity_verified"]
    with ZipFile(jar) as zipped:
        names = zipped.namelist()
        assert not any("Checks" in name and name.endswith(".class") or name.endswith("_room.nbt") for name in names), "Test fixture in production JAR"
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
    client_evidence = None
    if client:
        from geostrata_client_smoke import check_client
        client_evidence = check_client(root / "client", jar, testbed=True)
    verified = {"jar": jar.name, "jar_sha256": digest(jar), "source_gametests": source, "staged_gametests": int(count),
                "stock_server_ready": True, "staged_client": client_evidence, "complete_parity": False}
    (root / "testbed-verification.json").write_text(json.dumps(verified, indent=2) + "\n", encoding="utf-8")
    note("One-JAR verification passed", json.dumps(verified))
    summary([f"**Single test JAR**: `{jar.name}` (SHA-256 `{digest(jar)}`)", "",
             f"{source} source and {count} staged test cases passed. A stock server booted with only this mod JAR.",
             *([client_evidence] if client_evidence else []),
             "Exact originals were scanned and left unchanged. No legacy libraries or public GeoStrata asset artifact."])



if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--fetch-originals", action="store_true")
    parser.add_argument("--client", action="store_true", help="also exercise client models and world loading under a display")
    args = parser.parse_args()
    try:
        check(args.root.resolve(), fetch=args.fetch_originals, client=args.client)
    except Exception as error:
        details = re.sub(r"\x1b\[[0-9;]*m", "", str(error)).splitlines()
        key_lines = ["\n".join(details[i:i+5]) for i, line in enumerate(details)
                     if " error:" in line or "Failed test cases:" in line or "AssertionError:" in line]
        note("One-JAR testbed failed", ("\n".join(key_lines) if key_lines else traceback.format_exc()[-3500:])[:3500], level="error")
        raise
