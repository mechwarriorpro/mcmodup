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
from minechem_terrain import fixtures as terrain_fixtures
from mixed_legacy_pack_smoke import note, summary
from native_pack_smoke import run_gradle
from pack_release_smoke import boot


CHECK = "mcmodup_testbed_check"
SETTINGS = "mcmodup_testbed_settings"


def _run_case(root: Path, task: str, expected: int) -> None:
    try:
        report = run_gradle(root, task)
    except AssertionError as error:
        report = str(error)
    if f"All {expected} required tests passed" not in report:
        lines = report.splitlines()
        hints = [line for line in lines if any(key in line for key in (
            " failed at ", "Caused by:", "ModLoadingException:", "Exception:", "Failure message:", "Mod File:",
            "Missing or unsupported", "Mod ID:", "ERROR]", "error:", "Failed test cases:")) and not line.lstrip().startswith("at ")]
        # NeoForge's dev launcher can exit successfully despite a loading error and has a long stack trace.
        raise AssertionError(f"{task}: expected {expected} passed cases\n" + "\n".join(hints[-22:])[:4600] + "\n" + report[-6500:])


def staged(root: Path, jar: Path) -> tuple[int, int]:
    from mcmodup import (atomic_thermometer, geostrata_rocks, icbm_plate, minechem_final_synthesis, minechem_materials,
                        minechem_matter, minechem_ore, minechem_pair, minechem_polytool, minechem_reactors)

    with ZipFile(jar) as archive:
        entries = minechem_final_synthesis.entries_from_catalog(json.loads(archive.read("data/minechem_chemistry/mcmodup/catalog.json")))
        entries394 = minechem_final_synthesis.entries_from_catalog(json.loads(archive.read("data/minechem_chemistry_394/mcmodup/catalog.json")))
    package = "mcmodup.testbedcheck"
    source = f"src/main/java/{package.replace('.', '/')}"
    resources = "src/main/resources"
    variants = {"Settings": "run-settings", "SettingsPaid": "run-settings-paid"}
    settings_runs = "\n".join(f"""neoForge.runs.create('gameTestServer{suffix}') {{
    type = 'gameTestServer'
    gameDirectory = project.file('{directory}')
    systemProperty 'neoforge.enabledGameTestNamespaces', '{SETTINGS}'
    systemProperty 'mcmodup.testbed.paid', '{str(suffix.endswith("Paid")).lower()}'
}}""" for suffix, directory in variants.items())
    jar_data = jar.read_bytes()
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
{settings_runs}
""",
        f"{resources}/META-INF/neoforge.mods.toml": _metadata(ModMetadata(""), CHECK, "Staged JAR test probe", "1.0.0", "Unlicense"),
        f"run/mods/{JAR_NAME}": jar_data,
        f"{resources}/data/{SETTINGS}/structure/empty.nbt": empty_test_structure(5),
        f"{resources}/data/{SETTINGS}/structure/reactor_room.nbt": empty_test_structure(29),
        **terrain_fixtures(package, CHECK),
        **minechem_ore.verification_tags(),
        f"{source}/GeoCrystalChecks.java": geostrata_rocks.crystal_checks(package, CHECK),
        f"{source}/GeoRockChecks.java": geostrata_rocks.checks(package, CHECK),
        f"{source}/LaunchChecks.java": icbm_plate.launch_checks(package, CHECK),
        f"{source}/BuildingChecks.java": icbm_plate.building_checks(package, CHECK),
        f"{source}/AcceleratorChecks.java": atomic_thermometer.accelerator_checks(package, CHECK),
        f"{source}/SteamChecks.java": atomic_thermometer.steam_checks(package, CHECK),
        f"{source}/ReactorChecksFinal.java": minechem_reactors.checks(package, CHECK, minechem_pair.FINAL.namespace, False, "ReactorChecksFinal"),
        f"{source}/ReactorChecks394.java": minechem_reactors.checks(package, CHECK, minechem_pair.RELEASE_394.namespace, True, "ReactorChecks394"),
        f"{source}/Sided394Checks.java": minechem_pair.sided394_checks(package, CHECK),
        f"{source}/FinalWaterChecks.java": minechem_pair.final_water_checks(package, CHECK),
        f"{source}/ChemistryGameplayChecksFinal.java": minechem_pair.chemistry_gameplay_checks(package, CHECK, False),
        f"{source}/ChemistryGameplayChecks394.java": minechem_pair.chemistry_gameplay_checks(package, CHECK, True),
        f"{source}/Synthesis394Checks.java": minechem_pair.synthesis394_checks(package, CHECK, entries394),
        f"{source}/SynthesisFinalChecks.java": minechem_final_synthesis.checks(package, CHECK, entries),
        f"{source}/PolytoolChecksFinal.java": minechem_polytool.checks(package, CHECK, minechem_pair.FINAL.namespace, entries, False),
        f"{source}/PolytoolChecks394.java": minechem_polytool.checks(package, CHECK, minechem_pair.RELEASE_394.namespace, entries394, True),
        f"{source}/JournalCloneChecks.java": minechem_pair.journal_clone_checks(package, CHECK, minechem_pair.FINAL.namespace),
        f"{source}/JournalCloneChecks394.java": minechem_pair.journal_clone_checks(package, CHECK, minechem_pair.RELEASE_394.namespace, "JournalCloneChecks394"),
    }
    for prepaid, rows, profile in ((False, entries, minechem_pair.FINAL), (True, entries394, minechem_pair.RELEASE_394)):
        suffix = "394" if prepaid else "Final"
        ids = minechem_final_synthesis.chemical_ids(profile.namespace, rows)
        files[f"{source}/MatterChecks{suffix}.java"] = minechem_matter.checks(package, CHECK, profile.namespace, ids, prepaid)
        files[f"{source}/{minechem_ore.check_name(prepaid)}.java"] = minechem_ore.checks(package, CHECK, profile.namespace, prepaid, ids)
    for room, size in {"rock_room": 3, "vein_room": 19, "crystal_room": 9, "water_room": 5, "synthesis_room": 5, "journal_room": 5, "fluid_room": 15, "empty": 5, "test_room": 5, "polytool_room": 11, "turbine_room": 5, "platform_room": 5, "accel_room": 15, "launch_room": 17, "reactor_room": 29}.items():
        files[f"{resources}/data/{CHECK}/structure/{room}.nbt"] = empty_test_structure(size)
    power = minechem_materials.observations("release394-power-config.txt")
    keys = {"costDecomposition": "costDecomposition", "synthesisMultiplier": "costSythesisMultiplier",
            "fissionMultiplier": "costFissionMultiplier", "fusionMultiplier": "costFusionMultiplier",
            **{key: key for key in ("energyPacketSize", "maxDecomposerStorage", "maxSynthesizerStorage", "maxFissionStorage", "maxFusionStorage")}}
    values = "".join(f"{name} = {int(power[key])}\n" for key, name in keys.items())
    for suffix, directory in variants.items():
        enabled = "true" if suffix.endswith("Paid") else power["boot_enabled"]
        files[f"{directory}/mods/{JAR_NAME}"] = jar_data
        files[f"{directory}/config/minechem_chemistry-crafting.toml"] = "uraniumOreCraftable = false\n"
        files[f"{directory}/config/minechem_chemistry_394-power.toml"] = f"enable = {enabled}\n" + values
    expected_settings = 0
    for name in ("MinechemConfigSmoke", "MinechemPowerConfigSmoke"):
        text = (Path(__file__).with_name("fixtures") / f"{name}.java").read_text(encoding="utf-8")
        for key, value in minechem_reactors._blueprint_replacements().items():
            text = text.replace(f"@{key}@", value)
        text = text.replace("@ROWS@", ",\n".join(f"        Map.entry({json.dumps(key)}, {json.dumps(value)})" for key, value in power.items()))
        expected_settings += text.count("@GameTest(")
        files[f"{source}/{name}.java"] = text
    write_workspace(root, files)
    expected = sum(content.count("@GameTest(") for name, content in files.items() if name.endswith(".java")) - expected_settings
    _run_case(root, "runGameTestServer", expected)
    for suffix in variants:
        _run_case(root, f"runGameTestServer{suffix}", expected_settings)
    for profile in ("run", *variants.values()):
        assert digest(root / profile / "mods" / JAR_NAME) == digest(jar), "Staged test mutated the JAR"
    return expected, expected_settings * len(variants)


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
        uranium = {f"{ns}:uranium_ore" for ns in ("minechem_chemistry", "minechem_chemistry_394")}
        for tag in ("c/tags/item/ores/uranium", "c/tags/block/ores/uranium", "minecraft/tags/block/needs_iron_tool"):
            merged = json.loads(zipped.read(f"data/{tag}.json"))
            assert merged["replace"] is False and uranium <= set(merged["values"]), tag
    mods = root / "server/mods"
    mods.mkdir(exist_ok=True)
    assert not tuple(mods.iterdir())
    shutil.copyfile(jar, mods / jar.name)
    boot(root / "server", "bash run.sh nogui")
    count, settings_count = staged(root / "staged", jar)
    source = sum(part["gametests"]["passed"] for part in report["components"].values())
    client_evidence = None
    if client:
        from geostrata_client_smoke import check_client
        client_evidence = check_client(root / "client", jar, testbed=True)
    verified = {"jar": jar.name, "jar_sha256": digest(jar), "source_gametests": source, "staged_gametests": count,
                "config_gametests": settings_count,
                "stock_server_ready": True, "staged_client": client_evidence, "complete_parity": False}
    (root / "testbed-verification.json").write_text(json.dumps(verified, indent=2) + "\n", encoding="utf-8")
    note("One-JAR verification passed", json.dumps(verified))
    summary([f"**Single test JAR**: `{jar.name}` (SHA-256 `{digest(jar)}`)", "",
             f"{source} source, {count} staged and {settings_count} separate config test cases passed. A stock server booted with only this mod JAR.",
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
        marker = next((line for line in details if "expected " in line and "passed cases" in line), "")
        if marker:
            end = details.index(marker) + 1
            diagnosis = "\n".join([marker, *details[end:end + 22]])
        else:
            named = [line for line in details if "Failed test cases:" in line or " failed at " in line or "Caused by:" in line or " error:" in line]
            diagnosis = "\n".join(named or details[:22])
        note("One-JAR testbed failed", (diagnosis or traceback.format_exc()[-3500:])[:3800], level="error")
        raise
