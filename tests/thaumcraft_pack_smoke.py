"""Load the staged production Thaumcraft preview and pack-local library in NeoForge."""

import argparse
import json
from pathlib import Path
import shutil
import tempfile

from mcmodup.audit import ModMetadata
from mcmodup.native_pack import REPORT_NAME
from mcmodup.port import _build_gradle, _metadata, write_workspace
from native_pack_smoke import run_gradle
from neoforge_smoke import empty_test_structure


def check(staged: Path, project: Path, with_tinkerer: bool = False) -> None:
    report = json.loads((staged / REPORT_NAME).read_text(encoding="utf-8"))
    built = {mod_id: row["file"] for row in report["files"] if row["action"] == "build" for mod_id in row["ids"]}
    base_file = Path(built["thaumcraft"]).name
    base = report["native_builds"][base_file]
    assert base["status"] == "preview" and base["gametests"]["passed"] >= 539
    assert base["original_jar_checks"]["passed"] == 5
    assert base["original_jar_checks"]["original_sha256"] == base["original_sha256"]
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


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("staged", type=Path)
    parser.add_argument("--with-tinkerer", action="store_true")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="mcmodup-thaumcraft-staged-test-") as directory:
        check(args.staged, Path(directory) / "project", args.with_tinkerer)
