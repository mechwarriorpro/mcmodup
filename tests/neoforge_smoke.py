"""Load converted JARs and check machines and aspect crafting on a NeoForge server."""

import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

from mcmodup.audit import ModMetadata
from mcmodup.catalogs import RELEASE_394_SHA256
from mcmodup.machines import FINAL_LEGACY_SHA256, DecomposerMode
from mcmodup.port import _build_gradle, _metadata, empty_test_structure, write_workspace


FIXTURES = Path(__file__).with_name("fixtures")
NATIVE_CONTROL_SHA512 = {
    "CBMultipart-1.21.1-3.5.0.155.jar": "43ba1480586db6fac554e4cf5ade2f006a789a509a32f0824277e35310831d3dc7b90cee08d7baf228bb74132174dd4e41d2b1ac9dd23f9877b0969b718fa303",
    "CodeChickenLib-1.21.1-4.6.1.526.jar": "2d4efdc64531d5bb8133a247bc22a0728ab99dbcb2842ad0303b2242afd0ddc1b90cd43f8ed8562f651d8d9e228c95d15d73b4b63b3980286450de2df9c0e5ef",
}


def check_minechem_artifacts(final: Path, release394: Path) -> None:
    def resources(jar):
        return {name for name in jar.namelist() if name.endswith(".class") or name.startswith("assets/") and not name.endswith("/")}

    with zipfile.ZipFile(final) as left, zipfile.ZipFile(release394) as right:
        catalog = json.loads(right.read("data/minechem_chemistry_394/mcmodup/catalog.json"))
        assert catalog["source_sha256"] == RELEASE_394_SHA256
        assert catalog["decomposer_mode"] == DecomposerMode.RELEASE_394 and not catalog["full_port"]
        assert sum("atomic_number" in entry for entry in catalog["entries"]) == 113
        assert sum(entry["new_id"].startswith("minechem_chemistry_394:molecule_") for entry in catalog["entries"]) == 173
        assert len(catalog["machine_recipes"]) == 1
        assert not (resources(left) & resources(right)), "Minechem releases share bytecode or named assets"
        assert not any(name.endswith(".jar") for archive in (left, right) for name in archive.namelist())


def check_recipes_in_game(project: Path, outputs: list[Path], *, minechem_394: Path | None = None,
                          multipart_control: list[Path] | None = None, icbm_plate: Path | None = None,
                          atomic_thermometer: Path | None = None) -> None:
    namespace = "mcmodup_smoke"
    assert len(outputs) in (1, 2), "Supply the FINAL-based (or fixture) JAR with an optional aspect JAR"
    include_aspects = len(outputs) == 2
    jars = [*outputs, *(path for path in (minechem_394, icbm_plate, atomic_thermometer) if path is not None)]
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
    if minechem_394:
        assert buffered, "The paired tests require the actual FINAL-based build"
        check_minechem_artifacts(outputs[0], minechem_394)
        tests["src/main/java/mcmodup/test/MinechemPairChecks.java"] = (FIXTURES / "MinechemPairChecks.java").read_bytes()
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
        if atomic_thermometer:
            compile_inputs.append(f"fixtures/{len(jars) - 1}.jar")
        compile_jars = ", ".join(f"'{relative}'" for relative in compile_inputs)
        gradle += f"\ndependencies {{ compileOnly files({compile_jars}) }}\n"
        integrate_fixture("MultipartControlSmoke.java", (FIXTURES / "MultipartControlSmoke.java").read_text(encoding="utf-8"),
                          "mcmodup.multipartcheck", "mcmodup_multipart_control")
    if icbm_plate:
        assert controls, "Plate integration checks require the unchanged native controls"
        from mcmodup.icbm_plate import checks

        integrate_fixture("PlateChecks.java", checks(namespace), "mcmodup.icbmplate", namespace)
        integrate_fixture("PlateMultipartSmoke.java", (FIXTURES / "PlateMultipartSmoke.java").read_text(encoding="utf-8"),
                          "mcmodup.plateintegration", "mcmodup_plate_integration")
    if atomic_thermometer:
        integrate_fixture("ThermometerMultipartSmoke.java", (FIXTURES / "ThermometerMultipartSmoke.java").read_text(encoding="utf-8"),
                          "mcmodup.atomicintegration", "mcmodup_atomic_integration")
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
    })
    run = subprocess.run(["./gradlew", "--no-daemon", "--console=plain", "runGameTestServer"],
                         cwd=project, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=420)
    count = 7 + include_aspects + (2 if minechem_394 else 0) + bool(controls) + (3 if icbm_plate else 0) + bool(atomic_thermometer)
    if run.returncode or f"All {count} required tests passed" not in run.stdout:
        raise AssertionError(run.stdout[-8000:])
    parsing_errors = [line for line in run.stdout.splitlines() if "Parsing error loading recipe" in line
                      and any(f"{mod}:" in line for mod in ("minechem_chemistry", "minechem_chemistry_394", "thaumcraft_aspects"))]
    if parsing_errors:
        raise AssertionError("Generated recipe errors:\n" + "\n".join(parsing_errors[:5]))


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
