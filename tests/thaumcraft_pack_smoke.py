"""Load the staged production Thaumcraft preview and pack-local library in NeoForge."""

import argparse
import json
from pathlib import Path
import shutil
import tempfile

from mcmodup.audit import ModMetadata
from mcmodup.native_pack import REPORT_NAME
from mcmodup.port import _build_gradle, _metadata, write_workspace
from mcmodup.source_ports import digest
from native_pack_smoke import run_gradle


CLIENT_ID = "mcmodup_thaumcraft_client"
PACKAGE = "mcmodup.thaumcraftcheck"
FIXTURES = Path(__file__).with_name("fixtures")


def check_client(staged: Path, project: Path, report: dict, with_tinkerer: bool) -> None:
    expected = {Path(row["file"]).name: row["sha256"] for row in report["files"]}
    write_workspace(project, {
        "settings.gradle": "rootProject.name = 'thaumcraft-client-test'\n",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "build.gradle": _build_gradle(CLIENT_ID, PACKAGE, "1.0.0") + f"""
neoForge.runs.named('client') {{
    gameDirectory = project.file('run')
    jvmArgument '-Xmx3G'
    systemProperty 'mcmodup.withTinkerer', '{str(with_tinkerer).lower()}'
    programArguments.addAll '--width', '1024', '--height', '576'
}}
""",
        "src/main/resources/META-INF/neoforge.mods.toml": _metadata(ModMetadata(""), CLIENT_ID, CLIENT_ID, "1.0.0", "Unlicense"),
        f"src/main/java/{PACKAGE.replace('.', '/')}/ThaumcraftClientSmoke.java": (FIXTURES / "ThaumcraftClientSmoke.java").read_bytes(),
    })
    mods = project / "run/mods"
    shutil.copytree(staged / "mods", mods)
    assert {file.name: digest(file) for file in mods.iterdir()} == expected
    log = run_gradle(project, "runClient")
    markers = ["MCMD_CLIENT_PASSED", "MCMD_CLIENT_BOOK_RENDERED", "MCMD_CLIENT_WARP_RENDERED_RELOADED_CLEARED"]
    if with_tinkerer:
        markers.append("MCMD_CLIENT_TINKERER_RENDERED")
    assert all(marker in log for marker in markers), log[-12000:]
    assert "Unable to render warp post chain" not in log and "MCMD_CLIENT_FAILED" not in log, log[-12000:]
    assert (project / "run/saves/mcmodup-runtime-check/level.dat").is_file(), "Client did not save its fresh world"
    assert {file.name: digest(file) for file in mods.iterdir()} == expected
    print("Staged production JARs passed a fresh NeoForge client world: the Thaumonomicon opened and rendered, "
          "all four Warp post chains rendered, survived a pack reload and cleared, and the world saved." +
          (" The staged Osmotic Enchanter opened and rendered its GUI." if with_tinkerer else ""))
from neoforge_smoke import empty_test_structure


def check(staged: Path, project: Path, with_tinkerer: bool = False, client: bool = False) -> None:
    report = json.loads((staged / REPORT_NAME).read_text(encoding="utf-8"))
    built = {mod_id: row["file"] for row in report["files"] if row["action"] == "build" for mod_id in row["ids"]}
    base_file = Path(built["thaumcraft"]).name
    base = report["native_builds"][base_file]
    assert base["status"] == "preview" and base["gametests"]["passed"] >= 539
    assert base["original_jar_checks"]["passed"] == 5
    assert base["original_jar_checks"]["original_sha256"] == base["original_sha256"]
    assert base["legacy_assets"]["original_count"] == 1091 and not base["legacy_assets"]["unmapped"], base["legacy_assets"]
    assert any(row["action"] == "dependency" and "architectury" in row["ids"] for row in report["files"])
    namespace = "mcmodup_thaumcraft_check"
    package = "mcmodup.thaumcraftcheck"
    write_workspace(project, {
        "settings.gradle": "rootProject.name = 'thaumcraft-staged-test'\n",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "build.gradle": _build_gradle(namespace, package, "1.0.0") + f"""
neoForge.runs.create('gameTestServer') {{
    type = 'gameTestServer'
    gameDirectory = project.file('run')
    systemProperty 'neoforge.enabledGameTestNamespaces', '{namespace}'
}}
""",
        "src/main/resources/META-INF/neoforge.mods.toml": _metadata(ModMetadata(""), namespace, namespace, "1.0.0", "Unlicense"),
        f"src/main/resources/data/{namespace}/structure/empty.nbt": empty_test_structure(),
        f"src/main/java/{package.replace('.', '/')}/ThaumcraftPackSmoke.java":
            (Path(__file__).with_name("fixtures") / "ThaumcraftPackSmoke.java").read_bytes(),
    })
    add_on = None
    if with_tinkerer:
        assert set(built) == {"thaumcraft", "thaumictinkerer"}
        assert any(name.endswith("ThaumicTinkerer-2.13.0.jar") for name in report["auto_addons"])
        add_on = report["native_builds"][Path(built["thaumictinkerer"]).name]
        assert add_on["gametests"]["passed"] >= 371
        assert add_on["dependencies_sha256"][base_file] == base["jar_sha256"]
        java_dir = project / f"src/main/java/{package.replace('.', '/')}"
        shutil.copyfile(Path(__file__).with_name("fixtures") / "TinkererPackSmoke.java", java_dir / "TinkererPackSmoke.java")
        with (project / "build.gradle").open("a", encoding="utf-8") as settings:
            settings.write(f"\ndependencies {{ compileOnly files('run/mods/{base_file}') }}\n")
    shutil.copytree(staged / "mods", project / "run/mods")
    log = run_gradle(project, "runGameTestServer")
    tests = 2 if with_tinkerer else 1
    if f"All {tests} required tests passed" not in log:
        raise AssertionError(log[-12000:])
    print(f"Thaumcraft preview: {base['gametests']['passed']} source GameTests; "
          f"{base['original_jar_checks']['passed']} original-JAR checks; "
          f"{len(base.get('converted_locales', []))} restored locale files; "
          f"asset audit: {base['legacy_assets']['retained']} retained, {base['legacy_assets']['adapted']} mapped adaptations, "
          f"{base['legacy_assets']['unmapped']} unverified of {base['legacy_assets']['original_count']} original paths.")
    print("Staged production Thaumcraft and Architectury passed the native NeoForge crucible smoke test without Connector.")
    if add_on:
        print(f"Thaumic Tinkerer: {add_on['gametests']['passed']} NeoForge source GameTests; "
              "the staged add-on's osmotic enchanter uses crafted obsidian tiles through the staged base mod's API.")
    if client:
        check_client(staged, project.with_name("client"), report, with_tinkerer)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("staged", type=Path)
    parser.add_argument("--with-tinkerer", action="store_true")
    parser.add_argument("--client", action="store_true", help="also launch a graphical client (requires a display)")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="mcmodup-thaumcraft-staged-test-") as directory:
        check(args.staged, Path(directory) / "project", args.with_tinkerer, args.client)
