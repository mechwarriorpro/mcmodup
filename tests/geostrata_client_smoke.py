"""Build the exact GeoStrata native preview and inspect its baked models on a staged client."""

import argparse
from pathlib import Path
import shutil
import subprocess

from mcmodup import NEOFORGE_VERSION
from mcmodup.audit import ModMetadata
from mcmodup.geostrata_rocks import FEATURES, MOD_ID, prepare
from mcmodup.port import _build_gradle, _metadata, write_workspace
from mcmodup.runtime import file_digest
from mcmodup.source_ports import BuildOptions, build, load_ports
from neoforge_smoke import FIXTURES


def check_client(root: Path, jar: Path, *, testbed: bool = False) -> str:
    namespace = "mcmodup_geo_client"
    evidence = root / "run/client-evidence.txt"
    expected = file_digest(jar)
    files = {
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "settings.gradle": f"rootProject.name = '{namespace}'\n",
        "build.gradle": _build_gradle(namespace, "mcmodup.test", "1.0.0") + f"""
neoForge.runs.named('client') {{
    gameDirectory = project.file('run')
    jvmArgument '-Xmx3G'
    programArguments.addAll '--width', '1024', '--height', '576'
    systemProperty 'mcmodup.geoJar', '{jar.name}'
    systemProperty 'mcmodup.testbedClient', '{str(testbed).lower()}'
    systemProperty 'mcmodup.geoEvidence', '{evidence.as_posix()}'
}}
""",
        "src/main/resources/META-INF/neoforge.mods.toml": _metadata(ModMetadata(""), namespace, "Connected model smoke test", "1.0.0", "Unlicense"),
        "src/main/java/mcmodup/geoclient/GeoConnectedClientSmoke.java": (FIXTURES / "GeoConnectedClientSmoke.java").read_text(encoding="utf-8"),
        f"run/mods/{jar.name}": jar.read_bytes(),
    }
    write_workspace(root, files)
    result = subprocess.run(["./gradlew", "--no-daemon", "--console=plain", "--max-workers=2", "runClient"],
                            cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=420)
    details = evidence.read_text(encoding="utf-8") if evidence.exists() else "No client evidence"
    if result.returncode or not details.startswith("Actual staged client:"):
        lines = result.stdout.splitlines()
        issues = ["\n".join(lines[i:i + 5]) for i, line in enumerate(lines) if "error:" in line]
        raise AssertionError(details + "\n" + "\n".join(issues[:5]) + "\n" + result.stdout[-4800:])
    assert file_digest(root / "run/mods" / jar.name) == expected
    assert (root / "run/saves/geo-connected-check/level.dat").is_file()
    return details.strip()


def smoke(root: Path, source: Path) -> str:
    expected = file_digest(source)
    options = BuildOptions(prepare(root / "recipe"), allow_preview=True, timeout=480)
    spec, = load_ports(options).values()
    java = shutil.which("java")
    assert java, "JDK 21 is required for the client harness"
    jar, built = build(spec, source, root / "source-build", [], NEOFORGE_VERSION, java, options.timeout)
    assert built["gametests"]["passed"] == len(FEATURES)
    evidence = check_client(root / "staged-client", jar)
    assert file_digest(source) == expected
    return f"{MOD_ID}: {len(FEATURES)} source GameTests. {evidence}"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    print(smoke(args.root.resolve(), args.source.resolve()))
