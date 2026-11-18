"""Opt-in Legacy Connector 0.5.0 checks; use official JARs, never export its cache."""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
from zipfile import ZipFile

from mcmodup.audit import ModMetadata
from mcmodup.port import _build_gradle, _metadata, write_workspace
from neoforge_smoke import empty_test_structure


NAMESPACE = "mcmodup_connector_smoke"
CONNECTOR = Path("run/mods/legacyconnector-0.5.0.jar")
VOLTZ_INPUTS = ("atomicscience.jar", "icbm-1.4.1.344.jar", "calclavia-core-development-1.2.0.6-main.jar",
                "universal-electricity-3.1.0.74-core.jar")


def gradle_config() -> str:
    return _build_gradle(NAMESPACE, "mcmodup.test", "1.0.0") + '''
repositories { mavenCentral() }
configurations { connectorProbe }
dependencies {
    connectorProbe 'org.ow2.asm:asm-commons:9.10.1'
    connectorProbe 'org.ow2.asm:asm-analysis:9.10.1'
    connectorProbe 'com.google.code.gson:gson:2.10.1'
    connectorProbe 'org.slf4j:slf4j-api:2.0.9'
}
// Reuse the pinned release's code and generated shim index instead of building upstream.
['thaumcraft', 'minechem', 'voltz'].each { groupName ->
    tasks.register("probe_${groupName}", JavaExec) {
        mainClass = 'org.kylepitcock.legacyconnector.loader.tools.CheckJar'
        classpath = files('run/mods/legacyconnector-0.5.0.jar', 'probe/runtime.jar') + configurations.connectorProbe + configurations.runtimeClasspath
        args file('probe/shim-index.txt').absolutePath, '@' + file("probe/${groupName}.txt").absolutePath
        systemProperty 'java.io.tmpdir', file('probe/tmp').absolutePath
        systemProperty 'legacyconnector.check.json', file("probe/${groupName}.json").absolutePath
        systemProperty 'legacyconnector.check.bridges', 'false'  // real Baubles is part of the test set
        systemProperty 'legacyconnector.check.assets', 'false'
        maxHeapSize = '2g'
    }
}
neoForge.runs.create('gameTestServer') {
    type = 'gameTestServer'
    gameDirectory = project.file('run')
    systemProperty 'neoforge.enabledGameTestNamespaces', 'mcmodup_connector_smoke'
    jvmArgument '-Xmx3G'
}
'''


def checked(path: Path, size: int, digest: str = "", algorithm: str = "sha256") -> bytes:
    if path.stat().st_size != size:
        raise AssertionError(f"Unexpected size for {path.name}; supply the exact test release")
    data = path.read_bytes()
    if digest and hashlib.new(algorithm, data).hexdigest() != digest:
        raise AssertionError(f"{algorithm} mismatch for {path.name}")
    print(f"{path.name}: sha256 {hashlib.sha256(data).hexdigest()}", flush=True)
    return data


def prepare(project: Path, args: argparse.Namespace) -> dict[str, tuple[str, ...]]:
    connector = checked(args.connector, 37681773, "287d481e8229d446e901b142fe023eb3e943ea9d9af90bf502cfa5d4997ccaeb")
    thaumcraft = checked(args.thaumcraft, 12565807)
    baubles = checked(args.baubles, 95415)
    minechem = checked(args.minechem, 1410748, "fd14166ff62e11701978c468976980f2", "md5")
    with args.voltz.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != "2eda607746a22edbf45612243adce37a876251e36d46c692a48344fe98eec84e":
            raise AssertionError("Supply the original Voltz Server 3.0.0c ZIP")
    with ZipFile(io.BytesIO(connector)) as jar:
        runtime = jar.read("META-INF/legacyconnector/legacyconnector-runtime.jar")
    with ZipFile(io.BytesIO(runtime)) as jar:
        index = jar.read("legacyconnector/shim-index.txt")
    with ZipFile(args.voltz) as pack:
        voltz = {f"probe/mods/1.6.4/{name}": pack.read(f"mods/{name}") for name in VOLTZ_INPUTS}
    tc = "run/mods/1.7.10/Thaumcraft-1.7.10-4.2.3.5.jar"
    bb = "run/mods/1.7.10/Baubles-1.7.10-1.0.1.10.jar"
    mc = "probe/mods/1.6.4/Minechem-1.6.4-5.0.5.FINAL.jar"
    inputs = {"thaumcraft": (tc, bb), "minechem": (mc, f"probe/mods/1.6.4/{VOLTZ_INPUTS[-1]}"), "voltz": tuple(voltz)}
    write_workspace(project, {
        "settings.gradle": "rootProject.name = 'connector-sanity'\n",
        "build.gradle": gradle_config(),
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "src/main/java/mcmodup/test/ConnectorSmoke.java": (Path(__file__).parent / "fixtures/ConnectorSmoke.java").read_bytes(),
        "src/main/resources/META-INF/neoforge.mods.toml": _metadata(ModMetadata(""), NAMESPACE, "Connector smoke tests", "1.0.0", "Unlicense"),
        f"src/main/resources/data/{NAMESPACE}/structure/empty.nbt": empty_test_structure(),
        CONNECTOR.as_posix(): connector,
        "run/config/legacyconnector.properties": "legacy.autoSortMods=false\ncoremods.downloadVanilla=false\nversions.minecraft125=false\n",
        "probe/runtime.jar": runtime,
        "probe/shim-index.txt": index,
        tc: thaumcraft, bb: baubles, mc: minechem,
        **voltz,
        **{f"probe/{key}.txt": "\n".join(str(project / name) for name in members) + "\n"
           for key, members in inputs.items()},
    })
    (project / "probe/tmp").mkdir()
    return {key: tuple(Path(member).name for member in members) for key, members in inputs.items()}


def gradle(project: Path, *tasks: str) -> str:
    # No runner tokens, JAVA_TOOL_OPTIONS or workflow command files in the tested process.
    allow = {"PATH", "LANG", "LC_ALL", "JAVA_HOME", "HOME", "GRADLE_USER_HOME", "TMPDIR", "TEMP", "TMP"}
    env = {key: value for key, value in os.environ.items() if key in allow}
    log = project / (tasks[0] + ".log")
    with log.open("wb") as stream:
        run = subprocess.run(["./gradlew", "--no-daemon", "--console=plain", "--max-workers=2", *tasks], cwd=project,
                             env=env, stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT, timeout=480)
    text = log.read_text(encoding="utf-8", errors="replace")
    if run.returncode:
        raise AssertionError(f"{tasks[0]} exited {run.returncode}:\n" + text[-6000:])
    return text


def notice(title: str, message: str, level: str = "notice") -> None:
    if os.getenv("GITHUB_ACTIONS") == "true":
        escaped = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::{level} title={title}::{escaped}", flush=True)
    else:
        print(f"{title}: {message}", flush=True)


def check_links(project: Path, inputs: dict[str, tuple[str, ...]]) -> None:
    gradle(project, *("probe_" + key for key in inputs))
    expected = {"thaumcraft": "1.7.10", "minechem": "1.6.4", "voltz": "1.6.4"}
    errors = []
    for key, members in inputs.items():
        records = {row["jar"]: row for row in json.loads((project / f"probe/{key}.json").read_text())}
        for member in members:
            row = records.get(member, {})
            notice("Connector offline check", f"{key}/{member}: {row.get('result', 'no record')} for Minecraft {row.get('mc', '?')}; "
                   f"{row.get('unresolved', '?')} unresolved; {row.get('synthesizedClasses', '?')} synthesized classes; "
                   f"{row.get('synthesizedMembers', '?')} synthesized members; {row.get('verifyFailures', '?')} verifier failures."
                   + (" First missing: " + "; ".join(row["missing"][:3]) if row.get("missing") else ""))
            if row.get("mc") != expected[key] or row.get("result") not in ("links", "unresolved") or "verify" not in row:
                errors.append(f"{key}/{member}")
    if errors:
        raise AssertionError("Expected offline translation/verification was not reported for: " + ", ".join(errors))


def check_game(project: Path) -> None:
    text = gradle(project, "runGameTestServer")
    if "All 2 required tests passed" not in text:
        raise AssertionError("Thaumcraft runtime assertions were not confirmed:\n" + text[-6000:])
    notice("Connector gameplay smoke tests", "Original Thaumcraft 4.2.3.5 registered items and entities, and a placed crucible accepted water, on NeoForge 21.1.267.")


def main() -> int:
    cli = argparse.ArgumentParser(description=__doc__)
    for name in ("connector", "thaumcraft", "baubles", "minechem", "voltz"):
        cli.add_argument("--" + name, required=True, type=Path)
    cli.add_argument("--phase", choices=("both", "links", "game"), default="both")
    args = cli.parse_args()
    failed = False
    with tempfile.TemporaryDirectory(prefix="mcmodup-connector-check-") as scratch:
        project = Path(scratch) / "project"
        inputs = prepare(project, args)
        jobs = {"links": lambda: check_links(project, inputs), "game": lambda: check_game(project)}
        for phase, check in jobs.items():
            if args.phase not in ("both", phase):
                continue
            try:
                check()
            except (AssertionError, OSError, subprocess.TimeoutExpired) as exc:
                failed = True
                notice(f"Connector {phase} check failed", str(exc), "error")
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
