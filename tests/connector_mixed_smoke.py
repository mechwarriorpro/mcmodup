"""Run exact originals with stock Legacy Connector as an oracle, not an updater output."""

import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import re
import subprocess
from zipfile import ZipFile

from connector_sanity import CONNECTOR, checked, gradle, notice
from mcmodup.audit import ModMetadata
from mcmodup.port import _build_gradle, _metadata, write_workspace
from mcmodup.runtime import file_digest
from mixed_legacy_pack_smoke import ORIGINALS, PACK_MODS, fingerprints, summary
from neoforge_smoke import NATIVE_CONTROL_SHA512, empty_test_structure


NAMESPACE = "mcmodup_connector_mixed"
VOLTZ_RUNTIME = (
    "mods/icbm-1.4.1.344.jar", "mods/atomicscience.jar",
    "mods/calclavia-core-development-1.2.0.6-main.jar", "mods/universal-electricity-3.1.0.74-core.jar",
)
# Native CB Multipart and CodeChickenLib are the only multipart libraries in this run.
REPORT = "run/logs/legacyconnector-report.txt"


def diagnostics(project: Path, names: set[str]) -> None:
    path = project / REPORT
    if not path.is_file():
        return
    rows = defaultdict(list)
    current = None
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if line and not line.startswith(" "):
            header, sep, _ = line.partition("  [")
            current = header if sep and header in names else None
        if current and (not line.startswith(" ") or re.search(
                r"^  (mod |MISSING DEPENDENCY:|\d+ unresolved link|Side-by-side|.*synthesi[sz]|translation failed:|not loaded|WARNING: ships)", line)):
            rows[current].append(line.strip()[:600])
    for name, lines in sorted(rows.items()):
        notice("Connector discovery, not gameplay", f"{name}: {'; '.join(lines[:12])}")


def oracle(root: Path, group: str) -> None:
    source, target = root / "legacy", root / "target"
    project = root / ("connector-" + group)
    before = fingerprints(source, target)
    connector = checked(root / "connector.jar", 37681773, "287d481e8229d446e901b142fe023eb3e943ea9d9af90bf502cfa5d4997ccaeb")
    files = {CONNECTOR.as_posix(): connector}
    by_label = {}
    selected = ORIGINALS if group == "mixed" else tuple(original for original in ORIGINALS if original.ids == ("mimicry",))
    if group == "mixed":
        archive = root / "voltz.zip"
        assert file_digest(archive) == "2eda607746a22edbf45612243adce37a876251e36d46c692a48344fe98eec84e"
        with ZipFile(archive) as pack:
            for member in VOLTZ_RUNTIME:
                by_label[f"mods/1.6.4/{Path(member).name}"] = pack.read(member)
        by_label.update((path.relative_to(source).as_posix(), path.read_bytes()) for path in sorted((source / "mods").glob("*/*")))
    else:
        label = selected[0].label
        by_label[label] = (source / label).read_bytes()
    expected = {}
    for item in selected:
        assert hashlib.sha256(by_label[item.label]).hexdigest() == item.sha256, f"Wrong original {item.label}"
        expected[item.sha256] = {"name": item.filename, "ids": sorted(item.ids)}
    assert len(expected) == len(selected)
    assert {path.name for path in (target / "mods").iterdir()} == PACK_MODS
    files.update({f"run/{label}": data for label, data in by_label.items()})
    for name, sha512 in NATIVE_CONTROL_SHA512.items():
        control = (target / "mods" / name).read_bytes()
        assert hashlib.sha512(control).hexdigest() == sha512, f"Wrong native control {name}"
        files[f"run/mods/{name}"] = control
    write_workspace(project, files | {
        "settings.gradle": "rootProject.name = 'connector-mixed-oracle'\n",
        "build.gradle": _build_gradle(NAMESPACE, "mcmodup.test", "1.0.0") + f"""
neoForge.runs.create('gameTestServer') {{
    type = 'gameTestServer'
    gameDirectory = project.file('run')
    systemProperty 'neoforge.enabledGameTestNamespaces', '{NAMESPACE}'
    jvmArgument '-Xmx3G'
}}
""",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "src/main/resources/META-INF/neoforge.mods.toml": _metadata(ModMetadata(""), NAMESPACE, "Connector input oracle", "1.0.0", "Unlicense"),
        f"src/main/resources/data/{NAMESPACE}/structure/empty.nbt": empty_test_structure(),
        "src/main/java/mcmodup/test/ConnectorMixedSmoke.java": (Path(__file__).parent / "fixtures/ConnectorMixedSmoke.java").read_bytes(),
        "run/original-identities.json": json.dumps(expected),
        "run/config/legacyconnector.properties": ("legacy.autoSortMods=false\ncoremods.downloadVanilla=false\n"
                                                  "versions.minecraft125=false\nexperimental.sideBySideLibraries=true\n"),
    })
    assert all(file_digest(project / "run/mods" / name) == before[target / "mods" / name] for name in PACK_MODS)
    summary([f"## Connector v0.5.0 runtime oracle ({group})", "",
             "This runs originals on NeoForge 21.1.267 with Connector, not as native ports. "
             "No cache, JAR, or world is published. A pass establishes discovery only.", "",
             *(f"- `{row['name']}` — `{sha}`" for sha, row in expected.items()), ""])
    try:
        output = gradle(project, "runGameTestServer")
        if "All 1 required tests passed" not in output:
            raise AssertionError("No complete registration probe:\n" + output[-6000:])
        notice("Exact inputs present at GameTest", f"All {len(selected)} requested archive identities were found by NeoForge. No gameplay or native parity claimed.")
    finally:
        diagnostics(project, {Path(label).name for label in by_label})
        log = project / "runGameTestServer.log"
        if log.is_file():
            lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
            identity = [line.partition("MCMODUP_CONNECTOR_IDENTITY ")[2] for line in lines if "MCMODUP_CONNECTOR_IDENTITY " in line]
            for row in identity:
                notice("Connector registry survey (not parity)", row[:900])
            interesting = [line for line in lines if re.search(
                r"Legacy mods loaded in |failed to load and are disabled|state is disabled|phase .* failed|"
                r"Duplicate legacy registration of minechem:|coremod hook|stopped at an API of another mod", line, re.I)]
            if interesting:
                notice("Connector lifecycle diagnostics", "\n".join(dict.fromkeys(line[:480] for line in interesting))[:4000])
        assert fingerprints(source, target) == before, "The oracle modified its inputs"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("group", choices=("mixed", "mimicry"))
    args = parser.parse_args()
    try:
        oracle(args.root.resolve(), args.group)
    except (AssertionError, OSError, subprocess.TimeoutExpired) as exc:
        notice("Exact Connector runtime oracle did not pass", str(exc)[-5000:], "error")
        raise
