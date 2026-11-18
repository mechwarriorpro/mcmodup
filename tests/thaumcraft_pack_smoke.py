"""Load the staged production Thaumcraft preview and pack-local libraries in NeoForge."""

import argparse
import json
from pathlib import Path
import shutil
import tempfile
from zipfile import ZipFile

from mcmodup.audit import ModMetadata
from mcmodup.native_pack import REPORT_NAME
from mcmodup.port import _build_gradle, _metadata, write_workspace
from mcmodup.source_ports import digest
from native_pack_smoke import run_gradle
from neoforge_smoke import empty_test_structure
from prepare_tinkerer_preview import TT_ROOT
from tinkerer_computercraft import CC_CASES, CC_SHA512, CC_VERSION


CLIENT_ID = "mcmodup_thaumcraft_client"
SERVER_ID = "mcmodup_thaumcraft_check"
PACKAGE = "mcmodup.thaumcraftcheck"
FIXTURES = Path(__file__).with_name("fixtures")


def mod_hashes(mods: Path) -> dict[str, str]:
    return {file.name: digest(file) for file in mods.iterdir()}


def copy_mods(staged: Path, project: Path, expected: dict[str, str]) -> Path:
    mods = project / "run/mods"
    mods.mkdir(parents=True)
    for name in expected:
        shutil.copyfile(staged / "mods" / name, mods / name)
    assert mod_hashes(mods) == expected, "A staged runtime input changed"
    return mods


def check_client(staged: Path, project: Path, expected: dict[str, str], with_tinkerer: bool, with_cc: bool) -> None:
    write_workspace(project, {
        "settings.gradle": "rootProject.name = 'thaumcraft-client-test'\n",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "build.gradle": _build_gradle(CLIENT_ID, PACKAGE, "1.0.0") + f"""
neoForge.runs.named('client') {{
    gameDirectory = project.file('run')
    jvmArgument '-Xmx3G'
    systemProperty 'mcmodup.withTinkerer', '{str(with_tinkerer).lower()}'
    systemProperty 'mcmodup.withComputercraft', '{str(with_cc).lower()}'
    programArguments.addAll '--width', '1024', '--height', '576'
}}
""",
        "src/main/resources/META-INF/neoforge.mods.toml": _metadata(ModMetadata(""), CLIENT_ID, CLIENT_ID, "1.0.0", "Unlicense"),
        f"src/main/java/{PACKAGE.replace('.', '/')}/ThaumcraftClientSmoke.java": (FIXTURES / "ThaumcraftClientSmoke.java").read_bytes(),
    })
    mods = copy_mods(staged, project, expected)
    log = run_gradle(project, "runClient")
    markers = ["MCMD_CLIENT_PASSED", "MCMD_CLIENT_BOOK_RENDERED", "MCMD_CLIENT_WARP_RENDERED_RELOADED_CLEARED"]
    if with_tinkerer:
        markers.extend(("MCMD_CLIENT_TINKERER_RENDERED", "MCMD_CLIENT_BEDROCK_PORTAL_FOG_CLOUDS"))
    assert all(marker in log for marker in markers), log[-12000:]
    assert "Unable to render warp post chain" not in log and "MCMD_CLIENT_FAILED" not in log, log[-12000:]
    assert (project / "run/saves/mcmodup-runtime-check/level.dat").is_file(), "Client did not save its fresh world"
    assert mod_hashes(mods) == expected, "A client runtime input changed"
    print("Staged production JARs passed a fresh NeoForge client world: the Thaumonomicon opened and rendered, "
          "all four Warp post chains rendered, survived a pack reload and cleared, and the world saved." +
          (" The Osmotic Enchanter opened and rendered, and the Bedrock portal reached its dimension with legacy base fog and clouds."
           if with_tinkerer else ""))


def cc_test_sources(source: Path, source_sha256: str) -> dict[str, bytes]:
    """Reuse the reviewed integration fixtures as a separate mod; no port source goes on the runtime classpath."""
    assert digest(source) == source_sha256, "The fixtures must come from the exact source used for the staged add-on"
    files = {}
    with ZipFile(source) as archive:
        for module, name in (("common", "ComputerCraftRuntimeChecks"), ("common", "LuaComputer"),
                             ("neoforge", "ComputerCraftGameTests"), ("neoforge", "ComputerCraftNeoForgeChecks")):
            package = "thaumic/tinkerer/" + ("neoforge/" if module == "neoforge" else "") + "testing"
            path = f"java/{package}/{name}.java"
            data = archive.read(f"{TT_ROOT}/{module}/src/gameTest/{path}")
            if name == "ComputerCraftGameTests":
                assert data.count(b"@GameTest(") == len(CC_CASES)
                data = data.replace(b"@PrefixGameTestTemplate(true)",
                                    f'@net.neoforged.neoforge.gametest.GameTestHolder("{SERVER_ID}")\n@PrefixGameTestTemplate(true)'.encode())
                data = data.replace(b'templateNamespace = "thaumictinkerer"', f'templateNamespace = "{SERVER_ID}"'.encode())
            files[f"src/main/{path}"] = data
        template = "structure/computercraftgametests.empty.nbt"
        files[f"src/main/resources/data/{SERVER_ID}/{template}"] = archive.read(
            f"{TT_ROOT}/neoforge/src/gameTest/resources/data/thaumictinkerer/{template}")
    return files


def check_server(staged: Path, project: Path, expected: dict[str, str], with_tinkerer: bool, cc_fixtures: dict[str, bytes]) -> None:
    java = f"src/main/java/{PACKAGE.replace('.', '/')}"
    gradle = _build_gradle(SERVER_ID, PACKAGE, "1.0.0") + f"""
neoForge.runs.create('gameTestServer') {{
    type = 'gameTestServer'
    gameDirectory = project.file('run')
    systemProperty 'neoforge.enabledGameTestNamespaces', '{SERVER_ID}'
    systemProperty 'mcmodup.withComputercraft', '{str(bool(cc_fixtures)).lower()}'
}}
"""
    files = {
        "settings.gradle": "rootProject.name = 'thaumcraft-staged-test'\n",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "src/main/resources/META-INF/neoforge.mods.toml": _metadata(ModMetadata(""), SERVER_ID, SERVER_ID, "1.0.0", "Unlicense"),
        f"src/main/resources/data/{SERVER_ID}/structure/empty.nbt": empty_test_structure(),
        f"{java}/ThaumcraftPackSmoke.java": (FIXTURES / "ThaumcraftPackSmoke.java").read_bytes(),
        **cc_fixtures,
    }
    if with_tinkerer:
        files[f"{java}/TinkererPackSmoke.java"] = (FIXTURES / "TinkererPackSmoke.java").read_bytes()
        gradle += "\ndependencies { compileOnly fileTree(dir: 'run/mods', include: ['*.jar']) }\n"
    files["build.gradle"] = gradle
    write_workspace(project, files)
    mods = copy_mods(staged, project, expected)
    log = run_gradle(project, "runGameTestServer")
    tests = 1 + int(with_tinkerer) + (len(CC_CASES) if cc_fixtures else 0)
    assert f"All {tests} required tests passed" in log, log[-12000:]
    assert mod_hashes(mods) == expected, "A server runtime input changed"
    print("Staged production Thaumcraft and Architectury passed the native NeoForge crucible smoke test without Connector.")
    if cc_fixtures:
        print(f"CC: Tweaked {CC_VERSION}: {len(CC_CASES)} integration GameTests passed against the production JARs, "
              "including live Lua calls to the peripherals, golem control, turtle crafting and gas removal.")


def check(staged: Path, project: Path, with_tinkerer: bool = False, client: bool = False, cc_source: Path | None = None) -> None:
    report = json.loads((staged / REPORT_NAME).read_text(encoding="utf-8"))
    rows = {mod_id: row for row in report["files"] for mod_id in row["ids"]}
    expected = {Path(row["file"]).name: row["sha256"] for row in report["files"]}
    assert mod_hashes(staged / "mods") == expected, "Staged mods do not match the build report"
    built = {key: Path(row["file"]).name for key, row in rows.items() if row["action"] == "build"}
    base_file = built["thaumcraft"]
    base = report["native_builds"][base_file]
    assert base["status"] == "preview" and base["gametests"]["passed"] >= 539
    assert base["original_jar_checks"]["passed"] == 5
    assert base["original_jar_checks"]["original_sha256"] == base["original_sha256"]
    assert base["legacy_assets"]["original_count"] == 1091 and not base["legacy_assets"]["unmapped"], base["legacy_assets"]
    assert rows["architectury"]["action"] == "dependency"
    assert ("computercraft" in rows) == (cc_source is not None), "Supply --computercraft-source for the CC: Tweaked stage"
    add_on, cc_fixtures = None, {}
    if with_tinkerer:
        assert set(built) == {"thaumcraft", "thaumictinkerer"}
        assert any(name.endswith("ThaumicTinkerer-2.13.0.jar") for name in report["auto_addons"])
        add_on = report["native_builds"][built["thaumictinkerer"]]
        assert add_on["gametests"]["passed"] >= 371
        assert add_on["legacy_assets"]["original_count"] == 319 and not add_on["legacy_assets"]["unmapped"], add_on["legacy_assets"]
        assert add_on["dependencies_sha256"][base_file] == base["jar_sha256"]
    if cc_source is not None:
        assert add_on is not None and rows["computercraft"]["ids"]["computercraft"] == CC_VERSION
        cc_file = staged / rows["computercraft"]["file"]
        assert digest(cc_file, "sha512") == CC_SHA512
        assert add_on["dependencies_sha256"][cc_file.name] == expected[cc_file.name]
        assert add_on["gametests"]["passed"] >= 371 + len(CC_CASES)
        assert all(add_on["gametests"]["covered_features"][key] == f"computercraftgametests.{value}" for key, value in CC_CASES.items())
        with ZipFile(staged / "mods" / built["thaumictinkerer"]) as jar:
            names = jar.namelist()
            assert not any(n.startswith(("dan200/computercraft/", "org/squiddev/cobalt/", "com/jcraft/jzlib/")) for n in names)
            assert "data/thaumictinkerer/computercraft/turtle_upgrade/gas_remover.json" in names
        cc_fixtures = cc_test_sources(cc_source, add_on["source_sha256"])
    check_server(staged, project, expected, with_tinkerer, cc_fixtures)
    print(f"Thaumcraft preview: {base['gametests']['passed']} source GameTests; "
          f"{base['original_jar_checks']['passed']} original-JAR checks; "
          f"{len(base.get('converted_locales', []))} restored locale files; "
          f"asset audit: {base['legacy_assets']['retained']} retained, {base['legacy_assets']['adapted']} mapped adaptations, "
          f"{base['legacy_assets']['unmapped']} unverified of {base['legacy_assets']['original_count']} original paths.")
    if add_on:
        print(f"Thaumic Tinkerer: {add_on['gametests']['passed']} NeoForge source GameTests; "
              f"asset audit: {add_on['legacy_assets']['retained']} retained, {add_on['legacy_assets']['adapted']} mapped adaptations, "
              f"{add_on['legacy_assets']['unmapped']} unverified of {add_on['legacy_assets']['original_count']} original paths. "
              "The staged Osmotic Enchanter uses crafted obsidian tiles through the staged base mod's API.")
    if client:
        check_client(staged, project.with_name("client"), expected, with_tinkerer, bool(cc_fixtures))
    if cc_source is not None:
        # Remove only CC from separate test instances; do not rebuild or mutate the staged port JARs.
        without_cc = {name: checksum for name, checksum in expected.items() if name != cc_file.name}
        check_server(staged, project.with_name("server-no-cc"), without_cc, True, {})
        if client:
            check_client(staged, project.with_name("client-no-cc"), without_cc, True, False)
        print("The same built Thaumcraft and Tinkerer JARs also passed with optional CC: Tweaked absent.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("staged", type=Path)
    parser.add_argument("--with-tinkerer", action="store_true")
    parser.add_argument("--client", action="store_true", help="also launch a graphical client (requires a display)")
    parser.add_argument("--computercraft-source", type=Path, help="exact source ZIP produced by tinkerer_computercraft")
    args = parser.parse_args()
    if args.computercraft_source and not args.with_tinkerer:
        parser.error("--computercraft-source requires --with-tinkerer")
    with tempfile.TemporaryDirectory(prefix="mcmodup-thaumcraft-staged-test-") as directory:
        check(args.staged, Path(directory) / "project", args.with_tinkerer, args.client, args.computercraft_source)
