"""Exercise the checksum-pinned originals in separate Forge worlds."""

import argparse
import base64
import zlib
import os
from pathlib import Path
import shutil
import subprocess
import traceback
from zipfile import ZipFile

from legacy_recipe_fixture import java8, legacy_command, prepare, run_server
from mixed_legacy_pack_smoke import note
from mcmodup.minechem_final_synthesis import TRACE
from mcmodup.minechem_materials import observations
from mcmodup.minechem_matter import AUTOMATION_TRACE, trace_name as matter_trace_name
from mcmodup.minechem_polytool import combat_trace_name, layout_trace_name, mining_trace_name, trace_name
from minechem_polytool_layout import layout
from mcmodup.machines import SYNTHESIS_394_COLLISION, release394_synthesis_trace, release394_trace
from mcmodup.runtime import file_digest


VOLTZ = "2eda607746a22edbf45612243adce37a876251e36d46c692a48344fe98eec84e"
FINAL = "Minechem-1.6.4-5.0.5.FINAL.jar"
FINAL_HASH = "2443c7f91351f0e6c2323636be28a6fe8697704f20edf378dfe1a9d7a3070bea"
RELEASE_394 = "Minechem-1.7.10-5.0.5.394.jar"
RELEASE_394_HASH = "ea802167b760d43828a0648de3727a58d5c1f7737613123ae49f2743188cdae7"
COFH = "CoFHLib-[1.7.10]1.0.1-151.jar"
UE = "universal-electricity-3.1.0.74-core.jar"


def compile_probe(root: Path, server: Path, forge: Path, javac: str, *, final: bool) -> None:
    probe = root / "probe"
    probe.mkdir()
    fixtures = Path(__file__).with_name("fixtures")
    result = subprocess.run([javac, "-proc:none", "-encoding", "UTF-8", "-classpath", str(forge), "-d", str(probe),
                             *(str(fixtures / name) for name in ("LegacyMinechemDecomposer.java", "LegacyMinechemMaterials.java",
                                                                 "LegacyMinechemSynthesis394.java", "LegacyMinechemJournal394.java",
                                                                 "LegacyMinechemReactors.java", "LegacyMinechemSynthesisFinal.java",
                                                                 "LegacyMinechemChemistry.java", "LegacyMinechemPolytool.java",
                                                                 "LegacyMinechemMatter.java", "LegacyMinechemPlayerDecay.java", "LegacyMinechemMining.java",
                                                                 "LegacyMinechemCombat.java",
                                                                 "LegacyMinechemFluidAutomation.java", "LegacyMinechemOre.java",
                                                                 "LegacyMinechemOreInterop.java"))],
                            capture_output=True, text=True, timeout=60)
    assert result.returncode == 0, result.stderr
    # Only the receiver needs a typed Item. javac sees shells for the obfuscated Minecraft
    # classes, checks the exact API JAR, and the runtime links to the real Forge classes.
    signature_dir = root / "signatures/net/minecraft/item"
    signature_dir.mkdir(parents=True)
    item = signature_dir / "Item.java"
    stack = signature_dir / "ItemStack.java"
    item.write_text("package net.minecraft.item; public class Item { public Item(" + ("int unused" if final else "") + ") {} }\n", encoding="utf-8")
    stack.write_text("package net.minecraft.item; public class ItemStack {}\n", encoding="utf-8")
    typed = fixtures / "minechem_gold" / ("FinalGoldReceiver.java" if final else "Release394GoldReceiver.java")
    api = server / "mods" / (UE if final else COFH)
    result = subprocess.run([javac, "-proc:none", "-encoding", "UTF-8", "-classpath", os.pathsep.join(map(str, (forge, api))),
                             "-d", str(probe), str(item), str(stack), str(typed)], capture_output=True, text=True, timeout=60)
    assert result.returncode == 0, result.stderr
    with ZipFile(server / "mods/mcmodup-minechemoracle.jar", "x") as jar:
        for compiled in (probe / "mcmodup").rglob("*.class"):
            jar.write(compiled, compiled.relative_to(probe).as_posix())


def original(root: Path, version: str, matter_only: bool = False, ore_disable: bool = False) -> None:
    source, server = root / "original", root / "server"
    matter = matter_only or version == "1.6.4"
    options = [f"-Dmcmodup.minechem.version={version}", f"-Dmcmodup.minechem.matter={str(matter).lower()}"]
    if ore_disable:
        assert version == "1.6.4" and not matter_only
        options.append("-Dmcmodup.minechem.ore_disable=true")
    jdk = Path(os.environ["JAVA_HOME"]) / "bin"
    if version == "1.6.4":
        name, checksum = FINAL, FINAL_HASH
        assert file_digest(source / "mods" / name) == checksum
        archive = root / "voltz.zip"
        assert file_digest(archive) == VOLTZ
        with ZipFile(archive) as pack:
            files = ["Voltz.jar", "minecraft_server.1.6.4.jar", "config/forge.cfg", "config/UniversalElectricity.cfg",
                     f"mods/{UE}"]
            files += [item for item in pack.namelist() if item.startswith("libraries/") and item.endswith(".jar")]
            for file in files:
                assert not set(file.split("/")) & {"", ".", ".."}, file
                path = server / file
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(pack.read(file))
        shutil.copyfile(source / "mods" / name, server / "mods" / name)
        if ore_disable:
            (server / "config/minechem.cfg").write_text("general {\n    B:UraniumOreCraftable=false\n}\n", encoding="utf-8")
        forge = server / "Voltz.jar"
        compile_probe(root, server, forge, str(jdk / "javac"), final=True)
        command = [str(jdk / "java"), "-Xmx1G", "-Djava.awt.headless=true", *options, "-jar", forge.name, "nogui"]
    else:
        name, checksum = RELEASE_394, RELEASE_394_HASH
        assert file_digest(source / "mods" / name) == checksum
        # 1360 changes the fluid registry used by this mod and crashes before world load.
        assert file_digest(source / "bin/modpack.jar", "sha1") == "e992c65ece8fd396c6b36ffffa4965c1ebb96ffd"
        assert file_digest(root / COFH, "sha1") == "20617e7822e0217cd091867db681d940f682235b"
        forge = prepare(server, source, "1.7.10")
        shutil.copyfile(root / COFH, server / "mods" / COFH)
        # .394's molecule decay links to sun.misc.Hashing, removed in Java 8.
        # Retain Java 8 for the existing integration trace: its Forge recipe order differs from Java 7.
        compile_probe(root, server, forge, str(jdk / "javac") if matter else java8("javac"), final=False)
        command = legacy_command("1.7.10", options)
        if matter:
            command[0] = str(jdk / "java")
    if not ore_disable and not matter_only:
        geometry = layout(jdk / "javap", server / "mods" / name)
        assert geometry == observations(layout_trace_name(version == "1.7.10")), geometry
        note(f"Pinned Minechem {version} Polytool client layout", str(geometry))
    try:
        evidence = run_server(server, command, legacy=True, success_marker="MCMODUP_MINECHEM_ORIGINAL_PASS",
                              failure_marker="MCMODUP_MINECHEM_ORIGINAL_FAIL",
                              level_seed=176394211 if matter else None)
    except AssertionError as exc:
        reports = sorted((server / "crash-reports").glob("*.txt"))
        if reports:
            head = reports[-1].read_text(errors="replace").split("A detailed walkthrough", 1)[0]
            raise AssertionError(f"{head[:2200]}\n{exc}") from None
        raise
    assert file_digest(server / "mods" / name) == checksum
    note(f"Original Minechem decomposer on Forge {version}", evidence)
    trace = "final-ore-disabled" if ore_disable else ("release394-ore-interop" if version == "1.7.10" else "final-ore-interop")
    path = server / "minechem-ore-interop.txt"
    text = path.read_text(encoding="utf-8")
    expected = observations(f"{trace}.txt")
    observed = dict(line.split("=", 1) for line in text.splitlines())
    if os.environ.get("MCMODUP_CAPTURE_CHEMISTRY") == "1" or observed != expected:
        note(f"Original Minechem {version} {trace}", text)
    assert observed == expected, f"{trace} differed from the pinned crafting and ore tag observations"
    note(f"Pinned Minechem {version} {trace}", f"{len(expected)} original ore interop rows re-observed")
    if ore_disable:
        return
    for kind in (("fluids", "radiation", "player-decay", "uranium-worldgen") if matter else ()):
        path = server / f"minechem-{kind}.txt"
        actual = dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines())
        expected = observations(matter_trace_name(version == "1.7.10", kind))
        if version == "1.6.4" and kind == "fluids":
            # FINAL's molecule enum assigns random colors to uncolored molecules at startup.
            # Keep a recorded tint for the native preview; identity, density, and container behavior must still match.
            def without_molecule_tints(rows):
                return {key: ("|".join(value.split("|")[:4] + value.split("|")[5:])
                              if key.startswith("m") and key[1:].isdigit() and key != "m1" else value)
                        for key, value in rows.items()}
            expected, actual = map(without_molecule_tints, (expected, actual))
        observed_variants = {}
        if version == "1.7.10" and kind == "fluids":
            # Both declarations have the legacy name `mt`. The decomposer keys bucket
            # inputs by hash, and either recipe can win at boot. Keep the measured
            # element recipe for the native bucket and check the two observed outcomes.
            observed_variants["be109"] = {"e26x48,m88x8"}
        mismatch = [key for key in sorted(expected.keys() | actual.keys())
                    if expected.get(key) != actual.get(key) and actual.get(key) not in observed_variants.get(key, ())]
        if os.environ.get("MCMODUP_CAPTURE_CHEMISTRY") == "1" or mismatch:
            capture(path, f"Minechem {version} matter {kind}")
        assert not mismatch, f"{version} {kind} differs at {[(key, expected.get(key), actual.get(key)) for key in mismatch[:12]]}"
        note(f"Pinned Minechem {version} {kind}", f"{len(expected)} original rows re-observed")
    for kind, trace in (("mining", mining_trace_name), ("combat", combat_trace_name)):
        path = server / f"minechem-polytool-{kind}.txt"
        text = path.read_text(encoding="utf-8")
        actual = dict(line.split("=", 1) for line in text.splitlines())
        expected = observations(trace(version == "1.7.10"))
        mismatched = [(key, expected.get(key), actual.get(key)) for key in sorted(expected.keys() | actual.keys())
                      if expected.get(key) != actual.get(key)]
        if os.environ.get("MCMODUP_CAPTURE_CHEMISTRY") == "1" or mismatched:
            note(f"Original Minechem {version} Polytool {kind}", text)
        assert not mismatched, f"{version} Polytool {kind} changed: {mismatched[:12]}"
        note(f"Pinned Minechem {version} Polytool {kind}", f"{len(expected)} original rows re-observed")
    if matter_only:
        path = server / "minechem-fluid-automation.txt"
        text = path.read_text(encoding="utf-8")
        actual = dict(line.split("=", 1) for line in text.splitlines())
        expected = observations(AUTOMATION_TRACE)
        mismatched = [(key, expected.get(key), actual.get(key)) for key in sorted(expected.keys() | actual.keys())
                      if expected.get(key) != actual.get(key)]
        if os.environ.get("MCMODUP_CAPTURE_CHEMISTRY") == "1" or mismatched:
            note("Original .394 fluid automation", text)
        assert not mismatched, f".394 fluid automation changed: {mismatched}"
        note("Pinned .394 fluid automation", f"{len(expected)} furnace, dispenser, reaction, flow and source controls re-observed")
        return
    verify_chemistry(server, version)
    polytool = (server / "minechem-polytool.txt").read_text(encoding="utf-8").splitlines()
    expected_tool = [f"{key}={value}" for key, value in observations(trace_name(version == "1.7.10")).items()]
    assert polytool == expected_tool, ("Polytool trace changed: " + str(sorted(set(polytool).symmetric_difference(expected_tool))[:10]))
    note(f"Minechem {version} Polytool", "\n".join(polytool))
    shape = (server / "minechem-blueprints.txt").read_text(encoding="utf-8")
    expected_shape = Path(__file__).resolve().parents[1] / "mcmodup/templates/minechem/reactor-blueprints.txt"
    assert shape.splitlines() == expected_shape.read_text(encoding="utf-8").splitlines(), "Blueprint geometry in the original binary changed"
    if version == "1.6.4":
        pinned = [row for row in TRACE.read_text(encoding="utf-8").splitlines() if row and not row.startswith("#")]
        observed = (server / "minechem-final-synthesis.txt").read_text(encoding="utf-8").splitlines()
        assert len(observed) == len(pinned), f"Wrong number of FINAL synthesis rules: {len(observed)}"
        for index, (actual, expected) in enumerate(zip(observed, pinned)):
            assert actual == expected, f"FINAL synthesis row {index}: {actual!r} != {expected!r}"
    if version == "1.7.10":
        material_rows = (server / "minechem-materials.txt").read_text(encoding="utf-8").splitlines()
        expected = [f"{key}=" + ",".join(f"{part}x{amount}" for part, amount in products)
                    for key, products in release394_trace().items()]
        missing = sorted(set(expected) - set(material_rows))
        unexpected = sorted(set(material_rows) - set(expected))
        assert material_rows == expected, f".394 material trace changed: missing {missing[:8]}; unexpected {unexpected[:8]}"
        note("Pinned .394 material trace re-observed", f"{len(material_rows)} ordered outputs matched the pinned Forge trace.")
        synthesis = (server / "minechem-synthesis.txt").read_text(encoding="utf-8").splitlines()
        expected_synthesis = {key: f"{cost};{count};{'shaped' if shaped else 'shapeless'};" +
                              ",".join(f"{part[0]}x{part[1]}" if part else "_" for part in pattern)
                              for key, (cost, count, shaped, pattern) in release394_synthesis_trace().items()}
        first, alternate = SYNTHESIS_394_COLLISION
        collision = expected_synthesis.pop(first)
        alternatives = [[f"{key}={value}" for key, value in sorted((expected_synthesis | {output: collision}).items())]
                        for output in SYNTHESIS_394_COLLISION]
        assert synthesis in alternatives, (".394 synthesis trace changed: missing " + str(sorted(set(alternatives[0]) - set(synthesis))[:5]) +
                                            "; unexpected " + str(sorted(set(synthesis) - set(alternatives[0]))[:5]))
        selected = first if synthesis == alternatives[0] else alternate
        note("Pinned .394 synthesis trace re-observed", f"{len(expected_synthesis)} stable ghost-and-extraction rows matched; "
             f"the ambiguous pattern selected {selected}. The port lets players explicitly select either output ({first}/{alternate}).")


def capture(path: Path, label: str) -> None:
    data = base64.b64encode(zlib.compress(path.read_bytes(), level=9)).decode("ascii")
    for offset in range(0, len(data), 3000):
        note(f"{label} {offset // 3000}", data[offset:offset + 3000])


def normalize_material(value: str) -> str:
    # Map/Hashtable iteration order isn't part of .394's product identity.
    if value.startswith("R"):
        base, *refs = value[1:].split("|")
        return "R" + normalize_material(base) + "|" + "|".join(sorted(refs))
    if value.startswith(("S", "C")):
        roll, _, values = value.partition("!")
        return roll + "!" + "|".join(normalize_material(part) for part in values.split("|"))
    return ",".join(sorted(value.split(",")))


def verify_chemistry(server: Path, version: str) -> None:
    final = version == "1.6.4"
    profile = "final" if final else "release394"
    table = {"all-decomposition": "materials", "pharmacology": "effects", "foods": "foods"}
    if not final:
        table["all-synthesis"] = "synthesis-registry"
    for trace, target in table.items():
        path = server / f"minechem-{trace}.txt"
        if os.environ.get("MCMODUP_CAPTURE_CHEMISTRY") == "1":
            capture(path, f"Minechem {version} {trace}")
        actual = dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines())
        if not final and trace == "foods":
            expected = {key: "yes" for key in observations("release394-effects.txt")}
        else:
            expected = observations(f"{profile}-{target}.txt")
        if trace == "all-decomposition":
            if final:
                # The unported fractional fallback may return extra sample results.
                actual = {key: value for key, value in actual.items() if key in expected}
            else:
                # Optional, unported Minechem bucket outputs depend on boot-time registration order.
                actual.pop("citem.minechemBucket:0", None)
                expected.pop("citem.minechemBucket:0", None)
            actual = {key: normalize_material(value) for key, value in actual.items()}
            expected = {key: normalize_material(value) for key, value in expected.items()}
            # Forge sometimes resolves the torch's coal/charcoal ingredient to
            # the other equivalent vanilla crafting recipe during boot. Use
            # the recorded charcoal variant in the native table.
            if not final and actual.get("v50:0") == "R-|v263:0@0.25|v280:0@0.25":
                assert expected["v50:0"] == "R-|v263:1@0.25|v280:0@0.25"
                actual["v50:0"] = expected["v50:0"]
        mismatch = [key for key in sorted(expected.keys() | actual.keys()) if expected.get(key) != actual.get(key)]
        assert not mismatch, f"{version} {trace} differs at {[(key, expected.get(key), actual.get(key)) for key in mismatch[:12]]}"
        note(f"Pinned Minechem {version} {target}", f"{len(expected)} original rows re-observed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("version", choices=("1.6.4", "1.7.10"))
    parser.add_argument("--matter-only", action="store_true")
    parser.add_argument("--ore-disable", action="store_true", help="Boot FINAL with the uranium crafting config off")
    args = parser.parse_args()
    try:
        original(args.root.resolve(), args.version, args.matter_only, args.ore_disable)
    except Exception:
        if os.environ.get("GITHUB_ACTIONS") == "true":
            note(f"Original Minechem {args.version} check failed", traceback.format_exc()[-5500:], level="error")
        raise
