"""Exercise the decomposer on each checksum-pinned original, in separate Forge worlds."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import traceback
from zipfile import ZipFile

from legacy_recipe_fixture import java8, legacy_command, prepare, run_server
from mixed_legacy_pack_smoke import note
from mcmodup.machines import release394_synthesis_trace, release394_trace
from mcmodup.runtime import file_digest


VOLTZ = "2eda607746a22edbf45612243adce37a876251e36d46c692a48344fe98eec84e"
FINAL = "Minechem-1.6.4-5.0.5.FINAL.jar"
FINAL_HASH = "2443c7f91351f0e6c2323636be28a6fe8697704f20edf378dfe1a9d7a3070bea"
RELEASE_394 = "Minechem-1.7.10-5.0.5.394.jar"
RELEASE_394_HASH = "ea802167b760d43828a0648de3727a58d5c1f7737613123ae49f2743188cdae7"
COFH = "CoFHLib-[1.7.10]1.0.1-151.jar"


def compile_probe(root: Path, server: Path, forge: Path, javac: str) -> None:
    probe = root / "probe"
    probe.mkdir()
    fixtures = Path(__file__).with_name("fixtures")
    subprocess.run([javac, "-proc:none", "-encoding", "UTF-8", "-classpath", str(forge), "-d", str(probe),
                    *(str(fixtures / name) for name in ("LegacyMinechemDecomposer.java", "LegacyMinechemMaterials.java",
                                                        "LegacyMinechemSynthesis394.java"))], check=True, timeout=60)
    with ZipFile(server / "mods/mcmodup-minechemoracle.jar", "x") as jar:
        for compiled in probe.rglob("*.class"):
            jar.write(compiled, compiled.relative_to(probe).as_posix())


def original(root: Path, version: str) -> None:
    source, server = root / "original", root / "server"
    options = [f"-Dmcmodup.minechem.version={version}"]
    if version == "1.6.4":
        name, checksum = FINAL, FINAL_HASH
        assert file_digest(source / "mods" / name) == checksum
        archive = root / "voltz.zip"
        assert file_digest(archive) == VOLTZ
        with ZipFile(archive) as pack:
            files = ["Voltz.jar", "minecraft_server.1.6.4.jar", "config/forge.cfg", "config/UniversalElectricity.cfg",
                     "mods/universal-electricity-3.1.0.74-core.jar"]
            files += [item for item in pack.namelist() if item.startswith("libraries/") and item.endswith(".jar")]
            for file in files:
                assert not set(file.split("/")) & {"", ".", ".."}, file
                path = server / file
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(pack.read(file))
        shutil.copyfile(source / "mods" / name, server / "mods" / name)
        jdk = Path(os.environ["JAVA_HOME"]) / "bin"
        forge = server / "Voltz.jar"
        compile_probe(root, server, forge, str(jdk / "javac"))
        command = [str(jdk / "java"), "-Xmx1G", "-Djava.awt.headless=true", *options, "-jar", forge.name, "nogui"]
    else:
        name, checksum = RELEASE_394, RELEASE_394_HASH
        assert file_digest(source / "mods" / name) == checksum
        # 1360 changes the fluid registry used by this mod and crashes before world load.
        assert file_digest(source / "bin/modpack.jar", "sha1") == "e992c65ece8fd396c6b36ffffa4965c1ebb96ffd"
        assert file_digest(root / COFH, "sha1") == "20617e7822e0217cd091867db681d940f682235b"
        forge = prepare(server, source, "1.7.10")
        shutil.copyfile(root / COFH, server / "mods" / COFH)
        compile_probe(root, server, forge, java8("javac"))
        command = legacy_command("1.7.10", options)
    try:
        evidence = run_server(server, command, legacy=True, success_marker="MCMODUP_MINECHEM_ORIGINAL_PASS",
                              failure_marker="MCMODUP_MINECHEM_ORIGINAL_FAIL")
    except AssertionError as exc:
        reports = sorted((server / "crash-reports").glob("*.txt"))
        if reports:
            head = reports[-1].read_text(errors="replace").split("A detailed walkthrough", 1)[0]
            raise AssertionError(f"{head[:2200]}\n{exc}") from None
        raise
    assert file_digest(server / "mods" / name) == checksum
    note(f"Original Minechem decomposer on Forge {version}", evidence)
    if version == "1.7.10":
        observations = (server / "minechem-materials.txt").read_text(encoding="utf-8").splitlines()
        expected = [f"{key}=" + ",".join(f"{part}x{amount}" for part, amount in products)
                    for key, products in release394_trace().items()]
        missing = sorted(set(expected) - set(observations))
        unexpected = sorted(set(observations) - set(expected))
        assert observations == expected, f".394 material trace changed: missing {missing[:8]}; unexpected {unexpected[:8]}"
        note("Pinned .394 material trace re-observed", f"{len(observations)} ordered outputs matched the pinned Forge trace.")
        synthesis = (server / "minechem-synthesis.txt").read_text(encoding="utf-8").splitlines()
        expected_synthesis = [key + "=" + f"{cost};{count};{'shaped' if shaped else 'shapeless'};" +
                              ",".join(f"{part[0]}x{part[1]}" if part else "_" for part in pattern)
                              for key, (cost, count, shaped, pattern) in release394_synthesis_trace().items()]
        assert synthesis == expected_synthesis, (".394 synthesis trace changed: missing " + str(sorted(set(expected_synthesis) - set(synthesis))[:5]) +
                                                 "; unexpected " + str(sorted(set(synthesis) - set(expected_synthesis))[:5]))
        note("Pinned .394 synthesis trace re-observed", f"All {len(synthesis)} ghost-and-extraction rows matched the original capture.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("version", choices=("1.6.4", "1.7.10"))
    args = parser.parse_args()
    try:
        original(args.root.resolve(), args.version)
    except Exception:
        if os.environ.get("GITHUB_ACTIONS") == "true":
            note(f"Original Minechem {args.version} check failed", traceback.format_exc()[-5500:], level="error")
        raise
