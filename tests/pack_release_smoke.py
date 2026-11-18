"""Verify pinned local release inputs and run focused native pack smoke tests in CI."""

import argparse
from collections import deque
from pathlib import Path
import shlex
import shutil
import subprocess
from threading import Thread

from mcmodup.audit import ModMetadata
from mcmodup.native_pack import inventory, plan, stage
from mcmodup.port import _build_gradle, _metadata, write_workspace
from mcmodup.source_ports import digest
from native_pack_smoke import run_gradle
from neoforge_smoke import empty_test_structure

ORIGINAL_TE = "mods/1.7.10/addons/thaumicenergistics-1.1.3.0.jar"
ORIGINAL_FMP = "coremods/ForgeMultipart-1.7.10-1.2.0.345-universal.jar"
CHECK = "mcmodup_energistics_check"
PACKAGE = "mcmodup.energisticscheck"


def stage_releases(old: Path, target: Path, out: Path, ids: Path) -> dict:
    state = inventory(old, target, id_map=ids)
    assert state.source_platform.legacy == "1.7.10", state.source_platform.report()
    assert state.source_platform.report()["forge_files"] == ["bin/modpack.jar"]
    before = {path: digest(path) for root in (old, target) for path in root.rglob("*") if path.is_file()}
    update = plan(state, [ORIGINAL_TE, ORIGINAL_FMP], output_pack=True)
    assert update.report["ready"], (update.problems, state.warnings)
    deps = {r["id"]: r for r in update.report["native_dependencies"]}
    legacy = [r for r in update.report["legacy_requirements"] if r["consumer"] == ORIGINAL_TE and r["id"].lower() == "appliedenergistics2"]
    assert any(r["provider"] and r["provider"]["version"] == "rv3-beta-6" and r["native_binding"]["version"] == "19.2.18"
               for r in legacy), legacy
    assert deps["thaumcraft"]["provider"]["version"] == "1.1.3"
    assert deps["codechickenlib"]["provider"]["version"] == "4.6.1.526"
    assert {"cb_multipart", "cb_microblock", "cb_multipart_minecraft"} <= {key for artifact in update.mods for key in artifact.ids}
    assert update.runtime.layout == "neoforge-server", update.runtime
    stage(update, out)
    assert {path: digest(path) for path in before} == before, "Staging changed an installed input"
    assert not (out / "eula.txt").exists() and not (out / "bin").exists()
    assert len(list((out / "mods").glob("*.jar"))) == 8
    assert not (out / "mods/appliedenergistics2-rv3-beta-6.jar").exists()
    print("::notice title=Pack selection checked::Staged official 1.7.10 baselines' selected native counterparts, AE2, CB Multipart, and transitive local dependencies with a stock NeoForge runtime.", flush=True)
    return update.runtime.launch


def boot(out: Path, launch: str) -> None:
    # Test-runner settings only; the pack writer never copies or creates an EULA acceptance.
    (out / "eula.txt").write_text("eula=true\n")
    (out / "server.properties").write_text("server-ip=127.0.0.1\nserver-port=0\nview-distance=3\nsimulation-distance=3\n")
    recent = deque(maxlen=90)
    started = False
    with subprocess.Popen(shlex.split(launch), cwd=out, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          stdin=subprocess.PIPE, text=True, bufsize=1) as process:
        def observe():
            nonlocal started
            for line in process.stdout:
                recent.append(line)
                if "Done (" in line and 'For help, type "help"' in line:
                    started = True
                    process.stdin.write("stop\n")
                    process.stdin.flush()

        reader = Thread(target=observe, daemon=True)
        reader.start()
        try:
            result = process.wait(timeout=250)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
            result = -1
        reader.join(timeout=10)
        if result != 0 or not started:
            raise AssertionError(f"Copied NeoForge runtime did not start/stop successfully ({result}):\n" + "".join(recent))
    print("::notice title=Fresh pack booted::Copied NeoForge 21.1.267 installation reached the dedicated server ready state and shut down cleanly.", flush=True)


def integration(out: Path, workspace: Path) -> None:
    fixture = Path(__file__).with_name("fixtures") / "EnergisticsPackSmoke.java"
    write_workspace(workspace, {
        "settings.gradle": "rootProject.name = 'energistics-pack-check'\n",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "src/main/resources/META-INF/neoforge.mods.toml": _metadata(ModMetadata(""), CHECK, CHECK, "1.0.0", "Unlicense"),
        f"src/main/resources/data/{CHECK}/structure/empty.nbt": empty_test_structure(),
        f"src/main/java/{PACKAGE.replace('.', '/')}/{fixture.name}": fixture.read_bytes(),
        "build.gradle": _build_gradle(CHECK, PACKAGE, "1.0.0") + f"""
dependencies {{ compileOnly fileTree(dir: 'run/mods', include: ['*.jar']) }}
neoForge.runs.create('gameTestServer') {{
    type = 'gameTestServer'
    gameDirectory = project.file('run')
    systemProperty 'neoforge.enabledGameTestNamespaces', '{CHECK}'
}}
""",
    })
    shutil.copytree(out / "mods", workspace / "run/mods")
    result = run_gradle(workspace, "runGameTestServer")
    if "All 2 required tests passed" not in result:
        raise AssertionError(result[-12000:])
    print("::notice title=Native cross-mod checks passed::Thaumic Energistics put Thaumcraft fire essentia into an AE2-backed cell, serialized it and extracted it after reload; CB Microblock item and saw recipe also registered.", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scratch", type=Path)
    directory = parser.parse_args().scratch.resolve()
    staged = directory / "new-instance"
    launch = stage_releases(directory / "old", directory / "server", staged, directory / "ids.json")
    boot(staged, launch["unix"])
    integration(staged, directory / "integration")
