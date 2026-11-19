"""Load converted JARs and check machines and aspect crafting on a NeoForge server."""

import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

from mcmodup.audit import ModMetadata
from mcmodup import minechem_final_synthesis
from mcmodup.catalogs import RELEASE_394_SHA256, CatalogEntry
from mcmodup.machines import FINAL_LEGACY_SHA256, DecomposerMode, machine_recipes
from mcmodup.method_ports import PROVENANCE
from mcmodup.minechem_pair import FINAL, RELEASE_394, journal_clone_checks, release394_checks, synthesis394_checks
from mcmodup.port import _build_gradle, _metadata, empty_test_structure, write_workspace


FIXTURES = Path(__file__).with_name("fixtures")
NATIVE_CONTROL_SHA512 = {
    "CBMultipart-1.21.1-3.5.0.155.jar": "43ba1480586db6fac554e4cf5ade2f006a789a509a32f0824277e35310831d3dc7b90cee08d7baf228bb74132174dd4e41d2b1ac9dd23f9877b0969b718fa303",
    "CodeChickenLib-1.21.1-4.6.1.526.jar": "2d4efdc64531d5bb8133a247bc22a0728ab99dbcb2842ad0303b2242afd0ddc1b90cd43f8ed8562f651d8d9e228c95d15d73b4b63b3980286450de2df9c0e5ef",
}


def check_minechem_artifacts(final: Path, release394: Path) -> list[CatalogEntry]:
    def resources(jar):
        return {name for name in jar.namelist() if name.endswith(".class") or name.startswith("assets/") and not name.endswith("/")}

    with zipfile.ZipFile(final) as left, zipfile.ZipFile(release394) as right:
        catalog = json.loads(right.read("data/minechem_chemistry_394/mcmodup/catalog.json"))
        assert catalog["source_sha256"] == RELEASE_394_SHA256
        assert catalog["decomposer_mode"] == DecomposerMode.RELEASE_394 and not catalog["full_port"]
        assert sum("atomic_number" in entry for entry in catalog["entries"]) == 113
        assert sum(entry["new_id"].startswith("minechem_chemistry_394:molecule_") for entry in catalog["entries"]) == 173
        entries = [CatalogEntry(row["new_id"].split(":", 1)[1], "", "", (), row) for row in catalog["entries"]]
        linked = PROVENANCE in right.namelist()
        rules = machine_recipes(RELEASE_394.namespace, entries, mode=DecomposerMode.RELEASE_394, linked=linked)
        assert sum(rule.machine == "decomposer" for rule in rules) == (575 if linked else 568)
        assert sum(rule.machine == "synthesizer" for rule in rules) == 399
        assert catalog["machine_recipes"] == [rule.describe() for rule in rules]
        assert not (resources(left) & resources(right)), "Minechem releases share bytecode or named assets"
        assert not any(name.endswith(".jar") for archive in (left, right) for name in archive.namelist())
        assert not any("SynthesisFinalChecks" in name for name in left.namelist()), "Production FINAL JAR contains test code"
        assert not any("Release394Checks" in name or "Synthesis394Checks" in name for name in right.namelist()), "Production .394 JAR contains test code"
        for jar, profile in ((left, FINAL), (right, RELEASE_394)):
            assert not any("JournalCloneChecks" in name or "ChemistryGameplayChecks" in name for name in jar.namelist())
            effect_data = json.loads(jar.read(f"data/{profile.namespace}/mcmodup/pharmacology.json"))
            assert len(effect_data) == (52 if profile == RELEASE_394 else 147)
            assert all(id_.startswith(f"{profile.namespace}:") for id_ in effect_data)
            for key in ("weapon_coating", "food_spiking"):
                assert f"data/{profile.namespace}/recipe/{key}.json" in jar.namelist()
            assert f"assets/{profile.namespace}/textures/item/polytool.png" in jar.namelist()
            assert f"data/{profile.namespace}/recipe/journal_clone.json" in jar.namelist()
        return entries


def check_recipes_in_game(project: Path, outputs: list[Path], *, minechem_394: Path | None = None,
                          multipart_control: list[Path] | None = None, icbm_plate: Path | None = None,
                          atomic_thermometer: Path | None = None, mimicry: Path | None = None,
                          geostrata: Path | None = None) -> int:
    namespace = "mcmodup_smoke"
    assert len(outputs) in (1, 2), "Supply the FINAL-based (or fixture) JAR with an optional aspect JAR"
    include_aspects = len(outputs) == 2
    jars = [*outputs, *(path for path in (minechem_394, icbm_plate, mimicry, geostrata, atomic_thermometer) if path is not None)]
    # Load the separately built production JARs as runtime mods, not as source sets.
    inputs = {f"fixtures/{i}.jar": path.read_bytes() for i, path in enumerate(jars)}
    controls = {}
    if multipart_control:
        assert minechem_394 and {path.name for path in multipart_control} == NATIVE_CONTROL_SHA512.keys()
        for path in multipart_control:
            content = path.read_bytes()
            assert hashlib.sha512(content).hexdigest() == NATIVE_CONTROL_SHA512[path.name], f"Wrong control JAR: {path.name}"
            controls[f"fixtures/{path.name}"] = content
    runtime = ", ".join(f"'{relative}'" for relative in (*inputs, *controls))
    gradle = _build_gradle(namespace, "mcmodup.test", "1.0.0") + f"""
dependencies {{ runtimeOnly files({runtime}) }}
neoForge.runs.create('gameTestServer') {{
    type = 'gameTestServer'
    systemProperty 'neoforge.enabledGameTestNamespaces', '{namespace}'
}}
"""
    with zipfile.ZipFile(outputs[0]) as minechem:
        catalog = json.loads(minechem.read("data/minechem_chemistry/mcmodup/catalog.json"))
    synth = [recipe["id"] for recipe in catalog["machine_recipes"] if recipe["machine"] == "synthesizer"]
    water_index = synth.index("minechem_chemistry:molecule_water")  # Orders menu selections exactly like the generated class.
    fixture = (FIXTURES / "CatalogSmoke.java").read_text(encoding="utf-8")
    buffered = catalog["decomposer_mode"] == DecomposerMode.FINAL
    assert buffered == (catalog["source_sha256"] == FINAL_LEGACY_SHA256), "The buffered machine is bound to the exact original"
    fixture = fixture.replace("@WATER_INDEX@", str(water_index)).replace("@BUFFERED_DECOMPOSER@", str(buffered).lower())
    tests = {"src/main/java/mcmodup/test/CatalogSmoke.java": fixture}
    if include_aspects:
        tests["src/main/java/mcmodup/test/CatalogAspectSmoke.java"] = (FIXTURES / "CatalogAspectSmoke.java").read_bytes()
    research_rooms = {}
    if buffered:
        tests["src/main/java/mcmodup/test/JournalCloneChecks.java"] = journal_clone_checks("mcmodup.test", namespace, FINAL.namespace)
        tests["src/main/java/mcmodup/test/SynthesisFinalChecks.java"] = minechem_final_synthesis.checks(
            "mcmodup.test", namespace, minechem_final_synthesis.entries_from_catalog(catalog))
        research_rooms[f"src/main/resources/data/{namespace}/structure/synthesis_room.nbt"] = empty_test_structure(5)
    if minechem_394:
        assert buffered, "The paired tests require the actual FINAL-based build"
        entries = check_minechem_artifacts(outputs[0], minechem_394)
        tests["src/main/java/mcmodup/test/MinechemPairChecks.java"] = (FIXTURES / "MinechemPairChecks.java").read_bytes()
        tests["src/main/java/mcmodup/test/Release394Checks.java"] = release394_checks("mcmodup.test", namespace, entries)
        tests["src/main/java/mcmodup/test/Synthesis394Checks.java"] = synthesis394_checks("mcmodup.test", namespace, entries)
        tests["src/main/java/mcmodup/test/JournalCloneChecks394.java"] = journal_clone_checks(
            "mcmodup.test", namespace, RELEASE_394.namespace, "JournalCloneChecks394")
        research_rooms[f"src/main/resources/data/{namespace}/structure/journal_room.nbt"] = empty_test_structure(5)
    def integrate_fixture(filename, code, package, own_namespace):
        # The standalone fixtures normally own independent test mod IDs.
        name = filename.removesuffix(".java")
        tests[f"src/main/java/mcmodup/test/{filename}"] = (code
            .replace(f"package {package};", "package mcmodup.test;")
            .replace("import net.neoforged.fml.common.Mod;\n", "")
            .replace(f"@Mod({name}.ID)\n", "")
            .replace(f'"{own_namespace}"', f'"{namespace}"')
            .replace('template = "empty"', 'template = "test_room"'))

    if atomic_thermometer:
        assert controls, "Thermometer integration checks require the unchanged native controls"
    if controls:
        compile_inputs = [*controls]
        for artifact in (icbm_plate, atomic_thermometer):
            if artifact:
                compile_inputs.append(f"fixtures/{jars.index(artifact)}.jar")
        compile_jars = ", ".join(f"'{relative}'" for relative in compile_inputs)
        gradle += f"\ndependencies {{ compileOnly files({compile_jars}) }}\n"
        integrate_fixture("MultipartControlSmoke.java", (FIXTURES / "MultipartControlSmoke.java").read_text(encoding="utf-8"),
                          "mcmodup.multipartcheck", "mcmodup_multipart_control")
    platform_rooms = {}
    if icbm_plate:
        assert controls, "Plate integration checks require the unchanged native controls"
        from mcmodup.icbm_plate import building_checks, checks, platform_checks

        tests["src/main/java/mcmodup/test/PlatformChecks.java"] = platform_checks("mcmodup.test", namespace)
        tests["src/main/java/mcmodup/test/BuildingChecks.java"] = building_checks("mcmodup.test", namespace)
        platform_rooms[f"src/main/resources/data/{namespace}/structure/platform_room.nbt"] = empty_test_structure(5)

        integrate_fixture("PlateChecks.java", checks(namespace), "mcmodup.icbmplate", namespace)
        integrate_fixture("PlateMultipartSmoke.java", (FIXTURES / "PlateMultipartSmoke.java").read_text(encoding="utf-8"),
                          "mcmodup.plateintegration", "mcmodup_plate_integration")
    thermal_rooms = {}
    if atomic_thermometer:
        from mcmodup.atomic_thermometer import steam_checks, turbine_checks

        tests["src/main/java/mcmodup/test/TurbineChecks.java"] = turbine_checks("mcmodup.test", namespace)
        tests["src/main/java/mcmodup/test/SteamChecks.java"] = steam_checks("mcmodup.test", namespace)
        thermal_rooms[f"src/main/resources/data/{namespace}/structure/turbine_room.nbt"] = empty_test_structure(5)
        integrate_fixture("ThermometerMultipartSmoke.java", (FIXTURES / "ThermometerMultipartSmoke.java").read_text(encoding="utf-8"),
                          "mcmodup.atomicintegration", "mcmodup_atomic_integration")
    cross_voltz = icbm_plate is not None and atomic_thermometer is not None
    if cross_voltz:
        tests["src/main/java/mcmodup/test/VoltzBusSmoke.java"] = (FIXTURES / "VoltzBusSmoke.java").read_bytes()
    if mimicry:
        from mcmodup.mimicry_catalyst import PACKAGE, checks

        integrate_fixture("CatalystChecks.java", checks(namespace), PACKAGE, namespace)
    geostrata_rooms = {}
    if geostrata:
        from mcmodup.geostrata_rocks import checks

        gradle += f"\ndependencies {{ compileOnly files('fixtures/{jars.index(geostrata)}.jar') }}\n"

        tests["src/main/java/mcmodup/test/GeoRockChecks.java"] = checks("mcmodup.test", namespace)
        geostrata_rooms[f"src/main/resources/data/{namespace}/structure/rock_room.nbt"] = empty_test_structure(3)
        geostrata_rooms[f"src/main/resources/data/{namespace}/structure/vein_room.nbt"] = empty_test_structure(19)
    write_workspace(project, {
        "settings.gradle": "rootProject.name = 'catalog-smoke'\n",
        "build.gradle": gradle,
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        **tests,
        "src/main/resources/META-INF/neoforge.mods.toml": _metadata(ModMetadata(""), namespace, "Catalog smoke tests", "1.0.0", "Unlicense"),
        f"src/main/resources/data/{namespace}/structure/empty.nbt": empty_test_structure(),
        f"src/main/resources/data/{namespace}/structure/test_room.nbt": empty_test_structure(5),
        **inputs,
        **controls,
        **research_rooms,
        **geostrata_rooms,
        **thermal_rooms,
        **platform_rooms,
    })
    run = subprocess.run(["./gradlew", "--no-daemon", "--console=plain", "runGameTestServer"],
                         cwd=project, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=420)
    count = (7 + (1 + len(minechem_final_synthesis.FEATURES) if buffered else 0) + include_aspects + (8 if minechem_394 else 0) + bool(controls)
             + (6 if icbm_plate else 0) + (5 if atomic_thermometer else 0) + cross_voltz
             + (2 if mimicry else 0) + (4 if geostrata else 0))
    if run.returncode or f"All {count} required tests passed" not in run.stdout:
        issues = [line for line in run.stdout.splitlines() if any(word in line for word in ("error:", " failed!", "Exception:"))]
        raise AssertionError("\n".join(issues[:12]) + "\n" + run.stdout[-6500:])
    parsing_errors = [line for line in run.stdout.splitlines() if "Parsing error loading recipe" in line
                      and any(f"{mod}:" in line for mod in ("minechem_chemistry", "minechem_chemistry_394", "geostrata_rocks", "thaumcraft_aspects", "atomic_thermometer", "icbm_glass_plate"))]
    if parsing_errors:
        raise AssertionError("Generated recipe errors:\n" + "\n".join(parsing_errors[:5]))
    return count


if __name__ == "__main__":
    import argparse
    import tempfile

    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("jars", nargs=2, type=Path, help="generated fixture or release-derived NeoForge JARs")
    cli.add_argument("--minechem-394", type=Path, help="also load the isolated .394 partial JAR with the exact FINAL-based build")
    cli.add_argument("--multipart-control", nargs=2, type=Path, metavar=("MULTIPART", "CCL"),
                     help="run the native cover control in the same world, using the two pinned production JARs")
    args = cli.parse_args()
    if args.multipart_control and not args.minechem_394:
        cli.error("--multipart-control requires --minechem-394")
    with tempfile.TemporaryDirectory(prefix="mcmodup-smoke-") as scratch:
        check_recipes_in_game(Path(scratch) / "smoke", args.jars, minechem_394=args.minechem_394,
                              multipart_control=args.multipart_control)
    if args.multipart_control:
        print("::notice title=Unchanged native control passed with the paired previews::Pinned CB Multipart and CodeChickenLib "
              "crafted and serialized native stone covers in the same NeoForge server as both Minechem previews.")
    if args.minechem_394:
        print("::notice title=Paired Minechem GameTests passed::Both release-derived decomposers ran in one NeoForge world "
              "under distinct IDs with their own output items, energy rules, saved queues and jam behavior. Partial only.")
    print("NeoForge GameTests passed powered synthesis, decomposition, automation, saves and jams, "
          "microscope discovery, journal recipe transfer, and machine/aspect crafting.")
