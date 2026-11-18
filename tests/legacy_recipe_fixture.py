"""Prepare isolated, checksum-verified Forge servers for the recipe parity tests."""

from collections import deque
import os
from pathlib import Path
import re
import shutil
import subprocess
from threading import Thread
from zipfile import ZipFile

from mcmodup.runtime import file_digest

SERVER_SHA1 = {"1.5.2": "f9ae3f651319151ce99a0bfad6b34fa16eb6775f",
               "1.6.4": "050f93c1f3fe9e2052398f7bd6aca10c63d64a87",
               "1.7.10": "952438ac4e01b4d115c5fc38f891710c4941df29"}
FML_152 = {
    "argo-small-3.2.jar": "58912ea2858d168c50781f956fa5b59f0f7c6b51",
    "asm-all-4.1.jar": "054986e962b88d8660ae4566475658469595ef58",
    "bcprov-jdk15on-148.jar": "960dea7c9181ba0b17e8bab0c06a43f0a5f04e65",
    "deobfuscation_data_1.5.2.zip": "446e55cd986582c70fcf12cb27bc00114c5adfd9",
    "guava-14.0-rc3.jar": "931ae21fa8014c3ce686aaa621eae565fefb1a6a",
    "scala-library.jar": "458d046151ad179c85429ed7420ffb1eaf6ddf85",
}
FML_152_MIRROR = "https://codeload.github.com/djoveryde/fmllibs/zip/79fb06ef11781191a072baa10e5b29b79cc97d03"
MOJANG_LIBS = "https://libraries.minecraft.net/"
FORGE_MAVEN = "https://maven.minecraftforge.net/"
CENTRAL = "https://repo.maven.apache.org/maven2/"
SUCCESS = "MCMODUP_RECIPE_PASS"
FAILURE = "MCMODUP_RECIPE_FAIL"


def fetch(url: str, path: Path, maximum: int = 32_000_000) -> bool:
    path.parent.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(["curl", "--proto", "=https", "--proto-redir", "=https", "--fail", "--silent", "--show-error",
                             "--location", "--retry", "1", "--max-time", "35", "--max-filesize", str(maximum), "--output", str(path), url],
                            capture_output=True, text=True, timeout=80)
    return result.returncode == 0


def pinned(url: str, path: Path, sha1: str) -> None:
    assert fetch(url, path), f"Could not download {path.name} from {url}"
    assert file_digest(path, "sha1") == sha1, f"Unexpected SHA-1 for {path.name}"


def library(relative: str, root: Path) -> None:
    # Class-Path is read only from the pinned Forge universal. Still restrict it to
    # Maven artifact paths and only fetch the Forge, Mojang, or Central coordinates.
    assert re.fullmatch(r"libraries/(?:[A-Za-z0-9_+.-]+/)+[A-Za-z0-9_+.-]+[.]jar", relative) and not set(relative.split("/")) & {".", ".."}
    artifact = relative.removeprefix("libraries/")
    if artifact.startswith(("org/scala-lang/", "com/typesafe/")):
        hosts = (FORGE_MAVEN, CENTRAL)
    elif artifact.startswith(("lzma/", "net/minecraft/")):
        hosts = (MOJANG_LIBS, FORGE_MAVEN)
    else:
        hosts = (MOJANG_LIBS, CENTRAL, FORGE_MAVEN)
    path = root / relative
    checksum = path.with_suffix(".jar.sha1")
    urls = [host + artifact for host in hosts]
    # These early Forge manifests predate the Scala modules group on Central.
    if re.match(r"org/scala-lang/scala-(?:parser-combinators|xml|swing)_2[.]11/", artifact):
        urls.append(CENTRAL + artifact.replace("org/scala-lang/", "org/scala-lang/modules/", 1))
    for url in urls:
        if fetch(url + ".sha1", checksum, 1024):
            match = re.match(r"[0-9a-fA-F]{40}\b", checksum.read_text().strip())
            if match and fetch(url, path) and file_digest(path, "sha1") == match[0].lower():
                checksum.unlink()
                return
    raise AssertionError(f"Could not obtain verified Forge library {artifact}")


def prepare(root: Path, source: Path, version: str) -> Path:
    root.mkdir(parents=True)
    forge = root / "forge.jar"
    shutil.copyfile(source / "bin/modpack.jar", forge)
    with ZipFile(forge) as jar:
        manifest = jar.read("META-INF/MANIFEST.MF").decode().replace("\r\n", "\n").replace("\n ", "")
    paths = next((line[12:].split() for line in manifest.splitlines() if line.startswith("Class-Path: ")), [])
    server_name = "minecraft_server.jar" if version == "1.5.2" else f"minecraft_server.{version}.jar"
    sha1 = SERVER_SHA1[version]
    pinned(f"https://launcher.mojang.com/v1/objects/{sha1}/server.jar", root / server_name, sha1)
    if version == "1.5.2":
        archive = root / "legacy-libraries.zip"
        assert fetch(FML_152_MIRROR, archive), "Could not download the pinned FML 1.5.2 library mirror"
        (root / "lib").mkdir()
        with ZipFile(archive) as jar:
            for name, checksum in FML_152.items():
                [entry] = [item for item in jar.infolist() if item.filename.endswith("/" + name)]
                assert 0 < entry.file_size < 10_000_000
                path = root / "lib" / name
                path.write_bytes(jar.read(entry))
                assert file_digest(path, "sha1") == checksum, name
        archive.unlink()
    for path in paths:
        if path != server_name:
            library(path, root)
    (root / "mods").mkdir()
    [original] = list((source / "mods").iterdir())
    shutil.copyfile(original, root / "mods" / original.name)
    return forge


def run_server(root: Path, command: list[str], *, marker: bool = True, legacy: bool = False,
               success_marker: str = SUCCESS, failure_marker: str = FAILURE) -> str:
    # All test servers are scratch instances and listen only inside the runner.
    (root / "eula.txt").write_text("eula=true\n")
    (root / "server.properties").write_text("server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nview-distance=3\n"
                                            f"level-type={'FLAT' if legacy else 'minecraft:flat'}\n"
                                            "simulation-distance=3\ngenerate-structures=false\nspawn-npcs=false\n")
    recent = deque(maxlen=100)
    ready, verified, result_summary, failure_line = False, not marker, "", ""
    causes = []
    with subprocess.Popen(command, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.PIPE,
                          text=True, encoding="utf-8", errors="replace", bufsize=1) as process:
        def observe():
            nonlocal ready, verified, result_summary, failure_line
            for line in process.stdout:
                recent.append(line)
                if "Caused by:" in line and len(causes) < 8:
                    causes.append(line.strip())
                ready |= "Done (" in line and "For help" in line
                if success_marker in line:
                    verified, result_summary = True, line.partition(success_marker)[2].strip()
                if failure_marker in line:
                    verified, failure_line = False, line.partition(failure_marker)[2].strip()
                if (ready and verified) or failure_line:
                    try:
                        process.stdin.write("stop\n")
                        process.stdin.flush()
                    except BrokenPipeError:
                        pass
                    # Continue draining stdout during shutdown without sending more stop commands.
                    for later in process.stdout:
                        recent.append(later)
                        if "Caused by:" in later and len(causes) < 8:
                            causes.append(later.strip())
                    break

        reader = Thread(target=observe, daemon=True)
        reader.start()
        try:
            result = process.wait(timeout=120)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
            result = -1
        reader.join(timeout=5)
    assert result == 0 and ready and verified, (
        f"Server verification failed (ready={ready}, verified={verified}, exit={result}): {failure_line}\n"
        + "\n".join(causes) + "\n" + "".join(recent)[-1700:]
    )
    return result_summary


def java8(program: str) -> str:
    return str(Path(os.environ["JAVA_HOME_8_X64"]) / "bin" / program)


def legacy_command(version: str, options: list[str]) -> list[str]:
    # The 1.5.2 universal targets the old dedicated-server entry point. Running
    # the unmodified Forge ZIP first in the classpath applies its official patches.
    main = (["-cp", f"forge.jar{os.pathsep}minecraft_server.jar", "net.minecraft.server.MinecraftServer"]
            if version == "1.5.2" else ["-jar", "forge.jar"])
    return [java8("java"), "-Xmx1G", "-Djava.awt.headless=true", *options, *main, "nogui"]
