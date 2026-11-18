"""Load converted JARs and check machines and aspect crafting on a NeoForge server."""

import json
from pathlib import Path
import subprocess
import zipfile

from mcmodup.audit import ModMetadata
from mcmodup.catalogs import RELEASE_394_SHA256
from mcmodup.machines import FINAL_LEGACY_SHA256, DecomposerMode
from mcmodup.port import _build_gradle, _metadata, empty_test_structure, write_workspace


FIXTURES = Path(__file__).with_name("fixtures")


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


def check_recipes_in_game(project: Path, outputs: list[Path], *, minechem_394: Path | None = None) -> None:
    namespace = "mcmodup_smoke"
    jars = [*outputs, minechem_394] if minechem_394 else outputs
    # Load the separately built JARs as runtime mods, not as source sets.
    inputs = {f"fixtures/{i}.jar": path.read_bytes() for i, path in enumerate(jars)}
    runtime = ", ".join(f"'{relative}'" for relative in inputs)
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
    if minechem_394:
        assert buffered, "The paired tests require the actual FINAL-based build"
        check_minechem_artifacts(outputs[0], minechem_394)
        tests["src/main/java/mcmodup/test/MinechemPairChecks.java"] = (FIXTURES / "MinechemPairChecks.java").read_bytes()
    write_workspace(project, {
        "settings.gradle": "rootProject.name = 'catalog-smoke'\n",
        "build.gradle": gradle,
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        **tests,
        "src/main/resources/META-INF/neoforge.mods.toml": _metadata(ModMetadata(""), namespace, "Catalog smoke tests", "1.0.0", "Unlicense"),
        f"src/main/resources/data/{namespace}/structure/empty.nbt": empty_test_structure(),
        f"src/main/resources/data/{namespace}/structure/test_room.nbt": empty_test_structure(5),
        **inputs,
    })
    run = subprocess.run(["./gradlew", "--no-daemon", "--console=plain", "runGameTestServer"],
                         cwd=project, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=420)
    count = 10 if minechem_394 else 8
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
    args = cli.parse_args()
    with tempfile.TemporaryDirectory(prefix="mcmodup-smoke-") as scratch:
        check_recipes_in_game(Path(scratch) / "smoke", args.jars, minechem_394=args.minechem_394)
    if args.minechem_394:
        print("::notice title=Paired Minechem GameTests passed::Both release-derived decomposers ran in one NeoForge world "
              "under distinct IDs with their own output items, energy rules, saved queues and jam behavior. Partial only.")
    print("NeoForge GameTests passed powered synthesis, decomposition, automation, saves and jams, "
          "microscope discovery, journal recipe transfer, and machine/aspect crafting.")
