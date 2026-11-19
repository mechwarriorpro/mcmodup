"""Compare the original Voltz plate and exercise the staged ICBM infrastructure preview."""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import traceback
from zipfile import ZipFile

from mixed_legacy_pack_smoke import CCL, MULTIPART, note
from native_pack_smoke import run_gradle
from pack_release_smoke import boot
from updater_sample import fingerprints, run
from voltz_legacy import FIXTURES, FORGE_SHA256, VOLTZ_SHA256, probe, unpack

from mcmodup.audit import ModMetadata
from mcmodup.classfiles import read_class
from mcmodup.icbm_plate import ARCHIVE, KERNEL, LEGACY_SHA256, MOD_ID, PLATFORM, TEXTURES, checks, platform_checks
from mcmodup.method_ports import PROVENANCE
from mcmodup.native_pack import REPORT_NAME
from mcmodup.port import _build_gradle, _metadata, empty_test_structure, write_workspace
from mcmodup.runtime import file_digest


ICBM = "mods/icbm-1.4.1.344.jar"


def legacy(root: Path) -> None:
    observed = probe(root, "LegacyIcbmPlateSmoke", {ICBM: LEGACY_SHA256}, ("ICBM",), "MCMODUP_PLATE")
    note("Exact Voltz glass plate baseline", observed)


def modern(root: Path) -> None:
    pack = root / "voltz.zip"
    assert file_digest(pack) == VOLTZ_SHA256
    source, target, out = root / "original", root / "target", root / "staged"
    label = "mods/1.6.4/" + Path(ICBM).name
    with ZipFile(pack) as archive:
        unpack(archive, ICBM, source / label)
        unpack(archive, "Voltz.jar", source / "bin/modpack.jar")
    before = fingerprints(source, target)
    assert before[source / label] == LEGACY_SHA256
    assert before[source / "bin/modpack.jar"] == FORGE_SHA256
    prepared = root / "recipe"
    subprocess.run([sys.executable, "-m", "mcmodup", "prepare-icbm-plate", "-o", str(prepared)], check=True, timeout=30)
    manifest = prepared / "icbm-plate.json"
    args = ["update-pack", str(source), "--target", str(target), "--mod", label, "--source-ports", str(manifest)]
    status, blocked = run(*args)
    assert status == 1 and any("--allow-preview-ports" in problem for problem in blocked["problems"]), blocked
    launched = subprocess.run([sys.executable, "-m", "mcmodup", *args, "--allow-preview-ports", "--output-pack", str(out),
                               "--include-native", "cb_multipart", "--build-timeout", "600", "--json"],
                              text=True, capture_output=True, timeout=700)
    if launched.returncode:
        # Keep the JUnit assertion near the head; shutdown and stack traces occupy the Gradle tail.
        problems = json.loads(launched.stdout)["problems"] if not launched.stderr and launched.stdout.startswith("{") else []
        error = "\n".join(problems) or launched.stderr or launched.stdout
        raise AssertionError(error if len(error) <= 5200 else f"{error[:2400]}\n…\n{error[-2800:]}")
    report = json.loads((out / REPORT_NAME).read_text())
    assert report["ready"] and report["staged"] and not report["parity_verified"] and not report["connector_required"], report
    assert report["selected"] == [label] and report["build_order"] == [label], report
    assert set(path.name for path in (out / "mods").glob("*.jar")) == {ARCHIVE, MULTIPART, CCL}
    rows = {Path(row["file"]).name: row for row in report["files"]}
    assert rows[ARCHIVE]["action"] == "build" and set(rows[ARCHIVE]["ids"]) == {MOD_ID}, rows
    for name in (MULTIPART, CCL):
        assert file_digest(out / "mods" / name) == rows[name]["sha256"] == before[target / "mods" / name]
    check_artifact(out / "mods" / ARCHIVE, source / label, report["native_builds"][ARCHIVE])
    assert fingerprints(source, target) == before
    boot(out, report["instance_runtime"]["launch"]["unix"])
    integration(root / "integration", out)
    note("Staged executable preview passed", "Four source GameTests and five staged-JAR GameTests passed. The exact glass plate and passive "
         "Sentry platform methods ran on NeoForge. Native CB Multipart read 0 → 15 → 0. The platform forwarded 5 then 12 FE with no retained "
         "charge and restored 20-slot storage. Both native dependencies were unchanged. Not full ICBM.")


def check_artifact(path: Path, original_path: Path, built: dict) -> None:
    assert built["status"] == "preview" and built["gametests"]["passed"] == 4, built
    assert built["original_sha256"] == LEGACY_SHA256 == file_digest(original_path), built
    assert built["jar_sha256"] == file_digest(path)
    plate, sentry = built["method_imports"]
    assert len(plate["methods"]) == len(sentry["methods"]) == 2, built
    (redirect,) = plate["call_redirects"]
    assert redirect["opcode"] == "getfield" and redirect["sites"] == 1 and "#registryId(" in redirect["target"], redirect
    assert all(method.startswith(PLATFORM + "#") for method in sentry["methods"])
    assert sorted(row["sites"] for row in sentry["call_redirects"]) == [1, 1, 1, 2], sentry
    assert built["legacy_ids"] == {"icbm": MOD_ID, "icbm|explosion": None, "icbm|sentry": None}
    with ZipFile(path) as artifact, ZipFile(original_path) as original:
        proof = json.loads(artifact.read(PROVENANCE))
        assert proof["imports"] == built["method_imports"] and proof["preview"] and not proof["parity_verified"]
        assert all(artifact.read(new) == original.read(old) for old, new in TEXTURES.items())
        assert not any(name.startswith(("icbm/", "calclavia/", "universalelectricity/")) or
                       name.endswith((".jar", "platform_room.nbt")) or "PlatformChecks" in name or "PlateChecks" in name
                       for name in artifact.namelist())
        for name, methods in (("PlateLogic", {"update", "delay"}), ("SentryPlatformLogic", {"forward", "extract"})):
            info = read_class(artifact.read(f"{KERNEL}/{name}.class"))
            assert {method for method, _ in info.members} == methods


def integration(project: Path, staged: Path) -> None:
    check = "mcmodup_plate_integration"
    package = "mcmodup.plateintegration"
    java = f"src/main/java/{package.replace('.', '/')}"
    write_workspace(project, {
        "settings.gradle": "rootProject.name = 'plate-integration-check'\n",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "src/main/resources/META-INF/neoforge.mods.toml": _metadata(ModMetadata(""), check, check, "1.0.0", "Unlicense"),
        f"src/main/resources/data/{check}/structure/empty.nbt": empty_test_structure(4),
        f"src/main/resources/data/{check}/structure/platform_room.nbt": empty_test_structure(5),
        f"{java}/PlateChecks.java": checks(check).replace("package mcmodup.icbmplate;", f"package {package};"),
        f"{java}/PlatformChecks.java": platform_checks(package, check),
        f"{java}/PlateMultipartSmoke.java": (FIXTURES / "PlateMultipartSmoke.java").read_bytes(),
        "build.gradle": _build_gradle(check, package, "1.0.0") + f"""
dependencies {{ compileOnly fileTree(dir: 'run/mods', include: ['*.jar']) }}
neoForge.runs.create('gameTestServer') {{
    type = 'gameTestServer'
    gameDirectory = project.file('run')
    systemProperty 'neoforge.enabledGameTestNamespaces', '{check}'
}}
""",
    })
    shutil.copytree(staged / "mods", project / "run/mods")
    result = run_gradle(project, "runGameTestServer")
    assert "All 5 required tests passed" in result, result[-4000:]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("side", choices=("legacy", "modern"))
    args = parser.parse_args()
    try:
        {"legacy": legacy, "modern": modern}[args.side](args.root.resolve())
    except Exception:
        if os.environ.get("GITHUB_ACTIONS") == "true":
            note(f"Voltz {args.side} probe failed", traceback.format_exc()[-5500:], level="error")
        raise
