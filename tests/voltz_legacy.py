"""Launch checksum-pinned Voltz originals with a small Java 7 Forge test mod."""

import os
from pathlib import Path
import subprocess
from zipfile import ZipFile

from legacy_recipe_fixture import run_server
from mcmodup.runtime import file_digest


VOLTZ_SHA256 = "2eda607746a22edbf45612243adce37a876251e36d46c692a48344fe98eec84e"
FORGE_SHA256 = "72a0eb2d239a878ba0ef5c0d2a8e78ef162580e3ab285eb79fb995981d1c06c8"
SERVER_SHA256 = "f132f35de7202137ac86ab5b9829d191caeb286b79111cfc2b09df5789379c9b"
PROVIDERS = ("mods/calclavia-core-development-1.2.0.6-main.jar", "mods/universal-electricity-3.1.0.74-core.jar",
             "mods/codechickencore 0.9.0.7.jar", "mods/notenoughitems 1.6.1.8.jar",
             "mods/1.6.4/CodeChickenLib-universal-1.6.4-1.0.0.61.jar", "mods/1.6.4/ForgeMultipart-universal-1.6.4-1.0.0.250.jar")
FIXTURES = Path(__file__).with_name("fixtures")


def unpack(pack: ZipFile, member: str, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(pack.read(member))


def probe(root: Path, name: str, originals: dict[str, str], configs: tuple[str, ...], marker: str) -> str:
    source = root / "voltz.zip"
    assert file_digest(source) == VOLTZ_SHA256
    server = root / (name + "-server")
    server.mkdir()
    exact = {"Voltz.jar": FORGE_SHA256, "minecraft_server.1.6.4.jar": SERVER_SHA256, **originals}
    with ZipFile(source) as pack:
        for member in (*exact, *PROVIDERS):
            unpack(pack, member, server / member)
        for member in pack.namelist():
            if member.startswith("libraries/") and member.endswith(".jar"):
                assert not set(member.split("/")) & {"", ".", ".."}, member
                unpack(pack, member, server / member)
        config = pack.read("config/modstats.cfg").replace(b"B:AllowUpdates=true", b"B:AllowUpdates=false")
        assert b"B:AllowUpdates=false" in config
        for config_name in (*configs, "Calclavia", "UniversalElectricity", "CodeChickenCore", "multipart", "forge"):
            path = f"config/{config_name}.cfg"
            unpack(pack, path, server / path)
        (server / "config/modstats.cfg").write_bytes(config)
    for path, sha in exact.items():
        assert file_digest(server / path) == sha, f"Unexpected original {path}"
    classes = root / (name + "-classes")
    classes.mkdir()
    jdk = Path(os.environ["JAVA_HOME"]) / "bin"
    version = subprocess.run([jdk / "java", "-version"], text=True, capture_output=True, check=True, timeout=15)
    assert 'version "1.7.' in (version.stderr + version.stdout), "Use JDK 7 for the original Voltz runtime"
    built = subprocess.run([jdk / "javac", "-proc:none", "-encoding", "UTF-8", "-classpath", str(server / "Voltz.jar"),
                            "-d", str(classes), str(FIXTURES / (name + ".java"))], capture_output=True, text=True, timeout=60)
    assert built.returncode == 0, built.stderr[-3500:]
    with ZipFile(server / "mods/mcmodup-voltz-check.jar", "x") as jar:
        for path in classes.rglob("*.class"):
            jar.write(path, path.relative_to(classes).as_posix())
    # Java 8 invalidates an iterator over LaunchWrapper's active tweakers in the
    # original FML. Use Java 7 and the bundled launcher, without patching the JARs.
    return run_server(server, [str(jdk / "java"), "-Xmx1G", "-Djava.awt.headless=true", "-jar", "Voltz.jar", "nogui"],
                      legacy=True, success_marker=marker + "_PASS", failure_marker=marker + "_FAIL")
