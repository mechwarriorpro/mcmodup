"""Compare the original Voltz plate and a locally built NeoForge executable preview."""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import traceback
from zipfile import ZipFile

from legacy_recipe_fixture import run_server
from mixed_legacy_pack_smoke import CCL, MULTIPART, note
from native_pack_smoke import run_gradle
from pack_release_smoke import boot
from updater_sample import fingerprints, run

from mcmodup.audit import ModMetadata
from mcmodup.classfiles import read_class
from mcmodup.icbm_plate import ARCHIVE, KERNEL, LEGACY_SHA256, MOD_ID, TEXTURE, checks
from mcmodup.method_ports import PROVENANCE
from mcmodup.native_pack import REPORT_NAME
from mcmodup.port import _build_gradle, _metadata, empty_test_structure, write_workspace
from mcmodup.runtime import file_digest


VOLTZ_SHA256 = "2eda607746a22edbf45612243adce37a876251e36d46c692a48344fe98eec84e"
FORGE_SHA256 = "72a0eb2d239a878ba0ef5c0d2a8e78ef162580e3ab285eb79fb995981d1c06c8"
SERVER_SHA256 = "f132f35de7202137ac86ab5b9829d191caeb286b79111cfc2b09df5789379c9b"
ICBM = "mods/icbm-1.4.1.344.jar"
PROVIDERS = ("mods/calclavia-core-development-1.2.0.6-main.jar", "mods/universal-electricity-3.1.0.74-core.jar",
             "mods/codechickencore 0.9.0.7.jar", "mods/notenoughitems 1.6.1.8.jar",
             "mods/1.6.4/CodeChickenLib-universal-1.6.4-1.0.0.61.jar", "mods/1.6.4/ForgeMultipart-universal-1.6.4-1.0.0.250.jar")
FIXTURES = Path(__file__).with_name("fixtures")


def unpack(pack: ZipFile, member: str, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(pack.read(member))


def legacy(root: Path) -> None:
    source = root / "voltz.zip"
    assert file_digest(source) == VOLTZ_SHA256
    server = root / "voltz-server"
    server.mkdir()
    with ZipFile(source) as pack:
        for member in ("Voltz.jar", "minecraft_server.1.6.4.jar", ICBM, *PROVIDERS):
            unpack(pack, member, server / member)
        for member in pack.namelist():
            if member.startswith("libraries/") and member.endswith(".jar"):
                assert not set(member.split("/")) & {"", ".", ".."}, member
                unpack(pack, member, server / member)
        config = pack.read("config/modstats.cfg").replace(b"B:AllowUpdates=true", b"B:AllowUpdates=false")
        assert b"B:AllowUpdates=false" in config
        for name in ("ICBM", "Calclavia", "UniversalElectricity", "CodeChickenCore", "multipart", "forge"):
            path = f"config/{name}.cfg"
            unpack(pack, path, server / path)
        (server / "config/modstats.cfg").write_bytes(config)
    assert file_digest(server / ICBM) == LEGACY_SHA256
    assert file_digest(server / "Voltz.jar") == FORGE_SHA256
    assert file_digest(server / "minecraft_server.1.6.4.jar") == SERVER_SHA256
    classes = root / "legacy-probe"
    classes.mkdir()
    jdk = Path(os.environ["JAVA_HOME"]) / "bin"
    version = subprocess.run([jdk / "java", "-version"], text=True, capture_output=True, check=True, timeout=15)
    assert 'version "1.7.' in (version.stderr + version.stdout), "Use JDK 7 for the original Voltz runtime"
    built = subprocess.run([jdk / "javac", "-proc:none", "-encoding", "UTF-8", "-classpath", str(server / "Voltz.jar"),
                            "-d", str(classes), str(FIXTURES / "LegacyIcbmPlateSmoke.java")], capture_output=True, text=True, timeout=60)
    assert built.returncode == 0, built.stderr[-2400:]
    with ZipFile(server / "mods/mcmodup-plate-check.jar", "x") as probe:
        for path in classes.rglob("*.class"):
            probe.write(path, path.relative_to(classes).as_posix())
    # The original FML sorts LaunchWrapper's active tweaker list with
    # Collections.sort; Java 8 invalidates the active iterator. Use Java 7
    # and the bundled launcher rather than patching any original JAR.
    observed = run_server(server, [str(jdk / "java"), "-Xmx1G", "-Djava.awt.headless=true", "-jar", "Voltz.jar", "nogui"],
                          legacy=True, success_marker="MCMODUP_PLATE_PASS", failure_marker="MCMODUP_PLATE_FAIL")
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
    assert launched.returncode == 0, (launched.stderr or launched.stdout)[-5300:]
    report = json.loads((out / REPORT_NAME).read_text())
    assert report["ready"] and report["staged"] and not report["parity_verified"] and not report["connector_required"], report
    assert report["selected"] == [label] and report["build_order"] == [label], report
    assert set(path.name for path in (out / "mods").glob("*.jar")) == {ARCHIVE, MULTIPART, CCL}
    rows = {Path(row["file"]).name: row for row in report["files"]}
    assert rows[ARCHIVE]["action"] == "build" and set(rows[ARCHIVE]["ids"]) == {MOD_ID}, rows
    for name in (MULTIPART, CCL):
        assert file_digest(out / "mods" / name) == rows[name]["sha256"] == before[target / "mods" / name]
    built = report["native_builds"][ARCHIVE]
    assert built["status"] == "preview" and built["gametests"]["passed"] == 2, built
    assert len(built["method_imports"][0]["methods"]) == 2 and built["original_sha256"] == LEGACY_SHA256, built
    (redirect,) = built["method_imports"][0]["call_redirects"]
    assert redirect["opcode"] == "getfield" and redirect["sites"] == 1 and "#registryId(" in redirect["target"], redirect
    assert built["legacy_ids"] == {"icbm": MOD_ID, "icbm|explosion": None, "icbm|sentry": None}
    with ZipFile(out / "mods" / ARCHIVE) as artifact, ZipFile(source / label) as original:
        proof = json.loads(artifact.read(PROVENANCE))
        assert proof["imports"] == built["method_imports"] and proof["preview"] and not proof["parity_verified"]
        assert artifact.read(f"assets/{MOD_ID}/textures/block/glass_pressure_plate.png") == original.read(TEXTURE)
        assert not any(name.startswith("icbm/") or name.endswith("PlateChecks.class") for name in artifact.namelist())
        info = read_class(artifact.read(f"{KERNEL}/PlateLogic.class"))
        assert {name for name, _ in info.members} == {"update", "delay"}
    assert fingerprints(source, target) == before
    boot(out, report["instance_runtime"]["launch"]["unix"])
    integration(root / "integration", out)
    note("Staged executable preview passed", "The exact ICBM glass plate update/recheck bodies ran on NeoForge. "
         "Two source GameTests and three staged-JAR GameTests passed. Native CB Multipart read 0 → 15 → 0 power. "
         "Both native dependency JARs were unchanged. This is not a full ICBM or mixed-pack port.")


def integration(project: Path, staged: Path) -> None:
    check = "mcmodup_plate_integration"
    package = "mcmodup.plateintegration"
    java = f"src/main/java/{package.replace('.', '/')}"
    write_workspace(project, {
        "settings.gradle": "rootProject.name = 'plate-integration-check'\n",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "src/main/resources/META-INF/neoforge.mods.toml": _metadata(ModMetadata(""), check, check, "1.0.0", "Unlicense"),
        f"src/main/resources/data/{check}/structure/empty.nbt": empty_test_structure(4),
        f"{java}/PlateChecks.java": checks(check).replace("package mcmodup.icbmplate;", f"package {package};"),
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
    assert "All 3 required tests passed" in result, result[-4000:]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("side", choices=("legacy", "modern"))
    args = parser.parse_args()
    try:
        {"legacy": legacy, "modern": modern}[args.side](args.root.resolve())
    except Exception:
        if os.environ.get("GITHUB_ACTIONS") == "true":
            note(f"ICBM plate {args.side} probe failed", traceback.format_exc()[-5500:], level="error")
        raise
