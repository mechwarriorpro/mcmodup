"""Stage real NeoForge fixture JARs, then exercise the add-on without Connector."""

import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

from mcmodup.audit import ModMetadata
from mcmodup.native_pack import inventory, plan, stage
from mcmodup.port import _build_gradle, _metadata, write_workspace
from neoforge_smoke import empty_test_structure
from test_native_pack import legacy, setup_platform

ADDON = "mcmodup_update_addon"
LIB = "mcmodup_update_lib"
PACKAGE = "mcmodup.packcheck"
JAVA = PACKAGE.replace(".", "/")
ADDON_JAVA, LIB_JAVA, HOST_JAVA = (f"{JAVA}/{name}" for name in ("addon", "lib", "host"))


def run_gradle(workspace: Path, *tasks: str) -> str:
    result = subprocess.run(["./gradlew", "--no-daemon", "--console=plain", "--max-workers=2", *tasks], cwd=workspace,
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=480)
    if result.returncode:
        raise AssertionError(result.stdout[-12000:])
    return result.stdout


def bare_mod(name: str, mod_id: str, subpackage: str) -> str:
    return f"""package {PACKAGE}.{subpackage};
import net.neoforged.fml.common.Mod;
@Mod("{mod_id}")
public final class {name} {{}}
"""


def fixture_metadata(mod_id: str, deps: tuple[str, ...] = ()) -> str:
    template = _metadata(ModMetadata(""), mod_id, mod_id, "1.0.0", "Unlicense")
    for dep in deps:
        template += f'''\n[[dependencies.{mod_id}]]
modId="{dep}"
type="required"
versionRange="[1,2)"
ordering="AFTER"
side="BOTH"
'''
    return template


def build(root: Path, ruby: Path) -> None:
    project = root / "build-fixtures"
    addon = (Path(__file__).parent / "fixtures" / "NativeUpdateSmoke.java").read_bytes()
    write_workspace(project, {
        "settings.gradle": "rootProject.name = 'update-fixtures'\n",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "src/main/resources/META-INF/neoforge.mods.toml": fixture_metadata(ADDON, ("retro_ruby", LIB)),
        f"src/main/resources/data/{ADDON}/structure/empty.nbt": empty_test_structure(),
        f"src/main/java/{ADDON_JAVA}/NativeUpdateSmoke.java": addon,
        f"src/main/java/{LIB_JAVA}/LocalLibrary.java": bare_mod("LocalLibrary", LIB, "lib"),
        "library-meta/META-INF/neoforge.mods.toml": fixture_metadata(LIB),
        "build.gradle": _build_gradle(ADDON, PACKAGE, "1.0.0") + f"""
tasks.named('jar') {{
    archiveFileName = 'update-addon.jar'
    exclude '{LIB_JAVA}/LocalLibrary.class'
}}
tasks.register('libraryJar', Jar) {{
    dependsOn tasks.named('classes')
    archiveFileName = 'update-library.jar'
    destinationDirectory = layout.buildDirectory.dir('libs')
    from(sourceSets.main.output.classesDirs) {{ include '{LIB_JAVA}/LocalLibrary.class' }}
    from('library-meta')
}}
""",
    })
    run_gradle(project, "jar", "libraryJar")
    original, target = root / "original-pack", root / "target-pack"
    # Only the old pack and discovered platform inventory have synthetic headers. The staged mods
    # and the runtime below come from actual NeoForge builds.
    setup_platform(original, "1.7.10")
    setup_platform(target)
    legacy(original, "mods/RetroRuby.jar", "retro_ruby")
    legacy(original, "mods/AddOn.jar", ADDON, requirements=("required-after:retro_ruby",))
    (original / "lib").mkdir()
    (target / "lib").mkdir()
    shutil.copyfile(ruby, original / "lib/retro-ruby.jar")
    shutil.copyfile(project / "build/libs/update-addon.jar", original / "lib/update-addon.jar")
    shutil.copyfile(project / "build/libs/update-library.jar", target / "lib/update-library.jar")
    update = plan(inventory(original, target), ["mods/RetroRuby.jar"])
    assert update.report["ready"], update.problems
    assert set(update.addons) == {"mods/AddOn.jar"}
    assert [row["file"] for row in update.report["files"] if row["action"] == "dependency"] == ["mods/update-library.jar"]
    staged = stage(update, root / "new-pack-mods")
    runtime = root / "server"
    anchor = "mcmodup_update_host"
    write_workspace(runtime, {
        "settings.gradle": "rootProject.name = 'native-pack-runtime'\n",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "src/main/resources/META-INF/neoforge.mods.toml": fixture_metadata(anchor),
        f"src/main/java/{HOST_JAVA}/ServerAnchor.java": bare_mod("ServerAnchor", anchor, "host"),
        "build.gradle": _build_gradle(anchor, PACKAGE, "1.0.0") + f"""
neoForge.runs.create('gameTestServer') {{
    type = 'gameTestServer'
    gameDirectory = project.file('run')
    systemProperty 'neoforge.enabledGameTestNamespaces', '{ADDON}'
}}
""",
    })
    shutil.copytree(staged / "mods", runtime / "run/mods")
    output = run_gradle(runtime, "runGameTestServer")
    if "All 1 required tests passed" not in output:
        raise AssertionError(output[-12000:])
    print("Staged native base, add-on and automatic local library passed GameTest without Legacy Connector.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ruby", type=Path, help="built NeoForge Retro Ruby example JAR")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="mcmodup-native-pack-test-") as scratch:
        build(Path(scratch), args.ruby)
