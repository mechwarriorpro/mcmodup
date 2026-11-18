"""Load two converted JARs and check machines and aspect crafting on a NeoForge server."""

import gzip
import json
from pathlib import Path
import struct
import subprocess
import zipfile

from mcmodup.audit import ModMetadata
from mcmodup.port import _build_gradle, _metadata, write_workspace


def empty_test_structure(size: int = 1) -> bytes:
    """Minimal, empty cubic structure in named NBT for registry and gameplay checks."""
    nbt = (b"\x0a\x00\x00"                         # root compound
           b"\x03\x00\x0bDataVersion" + struct.pack(">i", 3955)
           + b"\x09\x00\x04size\x03" + struct.pack(">4i", 3, size, size, size)
           + b"\x09\x00\x07palette\x0a\x00\x00\x00\x01\x08\x00\x04Name\x00\x0dminecraft:air\x00"
           + b"\x09\x00\x06blocks\x0a\x00\x00\x00\x00"
           + b"\x09\x00\x08entities\x0a\x00\x00\x00\x00\x00")
    return gzip.compress(nbt, mtime=0)


def check_recipes_in_game(project: Path, outputs: list[Path]) -> None:
    namespace = "mcmodup_smoke"
    # Load the separately built JARs as runtime mods, not as source sets.
    inputs = {f"fixtures/{i}.jar": path.read_bytes() for i, path in enumerate(outputs)}
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
    fixture = (Path(__file__).with_name("fixtures") / "CatalogSmoke.java").read_text(encoding="utf-8")
    fixture = fixture.replace("@WATER_INDEX@", str(water_index))
    write_workspace(project, {
        "settings.gradle": "rootProject.name = 'catalog-smoke'\n",
        "build.gradle": gradle,
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "src/main/java/mcmodup/test/CatalogSmoke.java": fixture,
        "src/main/resources/META-INF/neoforge.mods.toml": _metadata(ModMetadata(""), namespace, "Catalog smoke tests", "1.0.0", "Unlicense"),
        f"src/main/resources/data/{namespace}/structure/empty.nbt": empty_test_structure(),
        **inputs,
    })
    run = subprocess.run(["./gradlew", "--no-daemon", "--console=plain", "runGameTestServer"],
                         cwd=project, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=420)
    if run.returncode or "All 6 required tests passed" not in run.stdout:
        raise AssertionError(run.stdout[-8000:])
    parsing_errors = [line for line in run.stdout.splitlines() if "Parsing error loading recipe" in line
                      and ("minechem_chemistry:" in line or "thaumcraft_aspects:" in line)]
    if parsing_errors:
        raise AssertionError("Generated recipe errors:\n" + "\n".join(parsing_errors[:5]))


if __name__ == "__main__":
    import argparse
    import tempfile

    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("jars", nargs=2, type=Path, help="generated fixture or release-derived NeoForge JARs")
    args = cli.parse_args()
    with tempfile.TemporaryDirectory(prefix="mcmodup-smoke-") as scratch:
        check_recipes_in_game(Path(scratch) / "smoke", args.jars)
    print("NeoForge GameTest passed powered synthesis, decomposition, automation, in-flight save/reload, jam handling, and machine/aspect crafting.")
