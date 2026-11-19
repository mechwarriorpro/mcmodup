"""Compare the original Voltz sensor and test the staged native thermal preview."""

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import traceback
from zipfile import ZipFile

from mcmodup.atomic_thermometer import ARCHIVE, KERNEL, LEGACY_SHA256, MOD_ID, TEXTURES, turbine_checks
from mcmodup.audit import ModMetadata
from mcmodup.method_ports import PROVENANCE
from mcmodup.native_pack import REPORT_NAME
from mcmodup.port import _build_gradle, _metadata, empty_test_structure, write_workspace
from mcmodup.runtime import file_digest
from mixed_legacy_pack_smoke import PACK_MODS, note
from native_pack_smoke import run_gradle
from pack_release_smoke import boot
from updater_sample import fingerprints, run
from voltz_legacy import FIXTURES, VOLTZ_SHA256, probe, unpack


MEMBER = "mods/atomicscience.jar"


def legacy(root: Path) -> None:
    observed = probe(root, "LegacyAtomicThermometer", {MEMBER: LEGACY_SHA256}, ("AtomicScience",), "MCMODUP_ATOMIC")
    note("Exact Atomic Science thermometer baseline", observed)


def modern(root: Path) -> None:
    source, target, out = root / "atomic-original", root / "target", root / "atomic-staged"
    pack = root / "voltz.zip"
    assert file_digest(pack) == VOLTZ_SHA256
    label = "mods/1.6.4/atomicscience.jar"
    original = source / label
    with ZipFile(pack) as archive:
        unpack(archive, MEMBER, original)
    assert file_digest(original) == LEGACY_SHA256
    before = fingerprints(source, target)
    prepared = root / "atomic-recipe"
    subprocess.run([sys.executable, "-m", "mcmodup", "prepare-atomic-thermometer", "-o", str(prepared)], check=True, timeout=30)
    args = ["update-pack", str(source), "--target", str(target), "--mod", label, "--source-ports", str(prepared / "atomic-thermometer.json"),
            "--include-native", "cb_multipart", "--output-pack", str(out)]
    status, blocked = run(*args)
    assert status == 1 and any("--allow-preview-ports" in problem for problem in blocked["problems"]) and not out.exists(), blocked["problems"]
    launched = subprocess.run([sys.executable, "-m", "mcmodup", *args, "--allow-preview-ports", "--build-timeout", "420", "--json"],
                              text=True, capture_output=True, timeout=450)
    if launched.returncode:
        if launched.stderr:
            raise AssertionError(launched.stderr[-2900:])
        raise AssertionError(json.loads(launched.stdout)["problems"])
    report = json.loads((out / REPORT_NAME).read_text())
    assert report["ready"] and report["staged"] and not report["parity_verified"] and not report["connector_required"], report["problems"]
    artifact = out / "mods" / ARCHIVE
    assert {path.name for path in (out / "mods").iterdir()} == {*PACK_MODS, ARCHIVE}
    assert all(file_digest(out / "mods" / name) == before[target / "mods" / name] for name in PACK_MODS)
    check_artifact(artifact, original, report["native_builds"][ARCHIVE])
    boot(out, report["instance_runtime"]["launch"]["unix"])
    staged_test(root / "atomic-integration", out)
    assert fingerprints(source, target) == before
    note("Staged Atomic Science thermal preview passed", "Four source and three staged-JAR GameTests passed. The stock server booted with "
         "only the preview and unchanged native Multipart/CCL. The turbine consumed steam and transferred FE; Multipart read heat grid "
         "and tracked capability signals. Not full Atomic Science.")


def check_artifact(artifact: Path, original: Path, evidence: dict) -> None:
    assert evidence["status"] == "preview" and evidence["gametests"]["passed"] == 4, evidence
    assert evidence["original_sha256"] == LEGACY_SHA256 and evidence["jar_sha256"] == file_digest(artifact), evidence
    assert evidence["legacy_ids"] == {"atomicscience": MOD_ID}, evidence
    with ZipFile(artifact) as jar, ZipFile(original) as old:
        proof = json.loads(jar.read(PROVENANCE))
        assert len(proof["imports"]) == 1 and len(proof["imports"][0]["methods"]) == 4
        assert proof["imports"] == evidence["method_imports"] and not proof["parity_verified"]
        assert all(jar.read(new) == old.read(src) for src, new in TEXTURES.items())
        assert all(f"{KERNEL}/{entry}.class" in jar.namelist() for entry in ("Thermometer", "HeatGrid", "Turbine"))
        assert not any("ThermometerChecks" in path or "TurbineChecks" in path or path.endswith("turbine_room.nbt") for path in jar.namelist())
        assert not any(name.startswith(("atomicscience/", "calclavia/", "universalelectricity/")) or name.endswith(".jar") for name in jar.namelist())


def staged_test(project: Path, staged: Path) -> None:
    namespace = "mcmodup_atomic_integration"
    package = "mcmodup.atomicintegration"
    fixture = FIXTURES / "ThermometerMultipartSmoke.java"
    write_workspace(project, {
        "settings.gradle": "rootProject.name = 'atomic-thermometer-integration'\n",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "src/main/resources/META-INF/neoforge.mods.toml": _metadata(ModMetadata(""), namespace, namespace, "1.0.0", "Unlicense"),
        f"src/main/resources/data/{namespace}/structure/empty.nbt": empty_test_structure(5),
        f"src/main/resources/data/{namespace}/structure/turbine_room.nbt": empty_test_structure(5),
        f"src/main/java/{package.replace('.', '/')}/{fixture.name}": fixture.read_bytes(),
        f"src/main/java/{package.replace('.', '/')}/TurbineChecks.java": turbine_checks(package, namespace),
        "build.gradle": _build_gradle(namespace, package, "1.0.0") + f"""
dependencies {{ compileOnly fileTree(dir: 'run/mods', include: ['*.jar']) }}
neoForge.runs.create('gameTestServer') {{
    type = 'gameTestServer'
    gameDirectory = project.file('run')
    systemProperty 'neoforge.enabledGameTestNamespaces', '{namespace}'
}}
""",
    })
    shutil.copytree(staged / "mods", project / "run/mods")
    log = run_gradle(project, "runGameTestServer")
    assert "All 3 required tests passed" in log, log[-2900:]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("side", choices=("legacy", "modern"))
    args = parser.parse_args()
    try:
        {"legacy": legacy, "modern": modern}[args.side](args.root.resolve())
    except Exception:
        note(f"Atomic Science {args.side} verification failed", traceback.format_exc()[-3900:], level="error")
        raise
