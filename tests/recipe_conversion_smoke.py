"""Original-only integration checks for the automatic Forge recipe converter."""

import argparse
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
from zipfile import BadZipFile, ZipFile

from mcmodup.audit import ModMetadata
from mcmodup.native_pack import inventory
from mcmodup.port import _metadata
from mcmodup.recipe_conversion import PROVENANCE, analyze
from mcmodup.runtime import file_digest
from legacy_recipe_fixture import java8, legacy_command, prepare, run_server
from pack_release_smoke import boot

CASES = {
    "1.5.2": ("RottenFlesh-to-Leather-v1.2.2.zip", "rftl"),
    "1.6.4": ("ZombieLeather.zip", "zombieleather"),
    "1.7.10": ("Craftable Saddles mc 1.7.10.jar", "csaddle"),
}


def notice(title: str, message: str) -> None:
    print(f"::notice title={title}::" + message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A"), flush=True)


def convert(root: Path, version: str, name: str, mod_id: str) -> tuple[Path, dict]:
    source, target, output = root / "legacy" / version, root / "target", root / "staged" / version
    label = f"mods/{name}"
    assert list((source / "mods").iterdir()) == [source / label] and not list((target / "mods").iterdir())
    before = {(base, path): file_digest(path) for base in (source, target) for path in base.rglob("*") if path.is_file()}
    result = subprocess.run([sys.executable, "-m", "mcmodup", "update-pack", str(source), "--target", str(target),
                             "--mod", label, "--allow-recipe-preview", "--output-pack", str(output), "--json"], capture_output=True, text=True, timeout=80)
    if result.returncode:
        try:
            problems = "; ".join(json.loads(result.stdout)["problems"])
        except (ValueError, KeyError):
            problems = result.stderr or result.stdout
        with ZipFile(source / label) as jar:
            meta = jar.read("mcmod.info").decode(errors="replace")[:1500] if "mcmod.info" in jar.namelist() else "no mcmod.info"
        try:
            direct = analyze(source / label, version).report()
        except ValueError as exc:
            direct = str(exc)
        evidence = inventory(source, target).catalog()["rows"][0]
        raise AssertionError(f"{problems[:800]}\nAudit IDs {evidence['mods']}, version {evidence['minecraft']}; "
                             f"direct analysis: {direct}\nLegacy metadata: {meta}")
    assert before == {(base, path): file_digest(path) for base in (source, target) for path in base.rglob("*") if path.is_file()}
    report = json.loads((output / "update-report.json").read_text())
    assert report["staged"] and report["ready"] and not report["native_builds"] and not report["connector_required"]
    assert report["mode"] == "automatic-recipe-preview" and not report["parity_verified"], report
    artifact, = report["files"]
    assert artifact["action"] == "convert" and artifact["from"] == f"convert/{label}" and set(artifact["ids"]) == {mod_id}
    assert artifact["conversion"]["preview"] and not artifact["conversion"]["parity_verified"]
    assert artifact["conversion"]["source_minecraft"] == version and artifact["conversion"]["source_sha256"] == before[source, source / label]
    output_file = output / artifact["file"]
    assert file_digest(output_file) == artifact["sha256"] != before[source, source / label]
    assert list((output / "mods").iterdir()) == [output_file] and not (output / "eula.txt").exists()
    with ZipFile(output_file) as jar:
        assert not any(path.endswith(".class") for path in jar.namelist()), "The converter must not copy any legacy code"
        assert json.loads(jar.read(PROVENANCE)) == artifact["conversion"]
        recipes = {name: json.loads(jar.read(name)) for name in jar.namelist() if name.startswith(f"data/{mod_id}/recipe/")}
        assert len(recipes) == sum(artifact["conversion"]["recipes"].values()) > 0

    notice(f"Original {version} converted", f"{name} -> {output_file.name}; generated {len(recipes)} recipe(s).")
    return output, report


FIXTURES = Path(__file__).with_name("fixtures")


def choices(value) -> tuple[str, ...]:
    return tuple(row["item"] for row in value) if isinstance(value, list) else (value["item"],)


def craft_cases(recipe_id: str, recipe: dict):
    # The release fixture contains shaped recipes. For every recipe, exercise
    # both orientations, all placements on 3x3 (and 2x2 when applicable), each
    # allowed ingredient variant, and missing, wrong, and extra ingredients.
    assert recipe["type"] == "minecraft:crafting_shaped", recipe["type"]
    pattern, key = recipe["pattern"], recipe["key"]
    width, height = len(pattern[0]), len(pattern)
    result = recipe["result"]
    seen = set()
    for size in (3, 2):
        for flip in (False, True):
            for y in range(size - height + 1):
                for x in range(size - width + 1):
                    slots = [('.',)] * (size * size)
                    for dy, row in enumerate(pattern):
                        for dx, char in enumerate(row[::-1] if flip else row):
                            if char in key:
                                slots[(y + dy) * size + x + dx] = choices(key[char])
                    valid = [options[0] for options in slots]
                    tests = [(True, valid)]
                    for i, options in enumerate(slots):
                        for alternative in options[1:]:
                            tests.append((True, [*valid[:i], alternative, *valid[i + 1:]]))
                        if valid[i] != '.':
                            tests.append((False, [*valid[:i], '.', *valid[i + 1:]]))
                            if valid[i].endswith('_wool') and 'minecraft:black_wool' not in options:
                                tests.append((False, [*valid[:i], 'minecraft:black_wool', *valid[i + 1:]]))
                        tests.append((False, [*valid[:i], 'minecraft:cobblestone', *valid[i + 1:]]))
                    for valid_case, grid in tests:
                        case = (valid_case, size, tuple(grid))
                        if case in seen:
                            continue
                        seen.add(case)
                        yield ["C", recipe_id, str(int(valid_case)), result["id"], str(result["count"]), str(size), str(size), ",".join(grid)]


def input_cases(root: Path, output: Path, report: dict, version: str, mod_id: str) -> Path:
    file, = report["files"]
    with ZipFile(output / file["file"]) as jar:
        recipes = {name: json.loads(jar.read(name)) for name in jar.namelist() if name.startswith(f"data/{mod_id}/recipe/")}
    rows = [["M", mod_id, str(len(recipes)), file["conversion"]["source_mod_id"]]]
    for path, recipe in recipes.items():
        recipe_id = f"{mod_id}:{Path(path).stem}"
        if version == "1.7.10":
            rows.extend(craft_cases(recipe_id, recipe))
        else:
            # Independent fixture expectations, not the converter's XP value.
            assert len(recipes) == 1 and recipe["type"] == "minecraft:smelting"
            raw, awarded = ("2.0", "1") if version == "1.5.2" else ("0.0", "0")
            rows.append(["S", recipe_id, "minecraft:rotten_flesh", "minecraft:leather", "1", "200", raw, awarded])
    path = root / f"cases-{version}.tsv"
    path.write_text("".join("\t".join(row) + "\n" for row in rows), encoding="utf-8")
    return path


def compile_probe(source: Path, scratch: Path, classpath: list[Path], *, legacy: bool) -> Path:
    classes = scratch / "classes"
    classes.mkdir(parents=True)
    artifact = scratch / "recipe-test-probe.jar"
    cmd = ([java8("javac"), "-source", "7", "-target", "7"] if legacy else ["javac", "--release", "21"])
    result = subprocess.run([*cmd, "-proc:none", "-encoding", "UTF-8", "-classpath", os.pathsep.join(map(str, classpath)),
                             "-d", str(classes), str(source)], capture_output=True, text=True, timeout=60)
    assert result.returncode == 0, result.stderr[-2600:]
    with ZipFile(artifact, "w") as jar:
        for path in classes.rglob("*.class"):
            jar.write(path, path.relative_to(classes).as_posix())
        if not legacy:
            jar.writestr("META-INF/neoforge.mods.toml", _metadata(ModMetadata(""), "mcmodup_recipecheck", "Recipe parity probe", "1.0.0", "Unlicense"))
    return artifact


def legacy_verify(root: Path, version: str, cases: Path) -> str:
    server = root / "legacy-server" / version
    forge = prepare(server, root / "legacy" / version, version)
    scratch = root / "probes" / version
    scratch.mkdir(parents=True)
    source = scratch / "LegacyRecipeSmoke.java"
    with ZipFile(forge) as jar:
        event = "@Mod.EventHandler" if "cpw/mods/fml/common/Mod$EventHandler.class" in jar.namelist() else None
    source.write_text((FIXTURES / source.name).read_text().replace("@PRE_INIT@", event or "@Mod.PreInit")
                      .replace("@SERVER_STARTED@", event or "@Mod.ServerStarted"), encoding="utf-8")
    artifact = compile_probe(source, scratch, [forge], legacy=True)
    shutil.copyfile(artifact, server / "mods" / artifact.name)
    return run_server(server, legacy_command(version, [f"-Dmcmodup.recipe.source={version}", f"-Dmcmodup.recipe.cases={cases}"]), legacy=True)


def modern_verify(root: Path, output: Path, report: dict, cases: Path) -> str:
    runtime = report["instance_runtime"]
    scratch = root / "probes" / "modern"
    artifact = scratch / "recipe-test-probe.jar"
    if not artifact.is_file():
        paths = [output / row["file"] for row in runtime["files"] if row["file"].endswith(".jar")]
        paths.sort(key=lambda path: (not path.name.endswith("-server.jar"), path.name))
        compile_probe(FIXTURES / "ModernRecipeSmoke.java", scratch, paths, legacy=False)
    installed = output / "mods" / artifact.name
    shutil.copyfile(artifact, installed)
    try:
        launch = shlex.split(runtime["launch"]["unix"])
        launch[1:1] = [f"-Dmcmodup.recipe.cases={cases}"]
        return run_server(output, launch)
    finally:
        installed.unlink()


def main(root: Path, only: str | None) -> None:
    failures = []
    expected_errors = (AssertionError, OSError, ValueError, subprocess.SubprocessError, BadZipFile)
    for version, (name, mod_id) in CASES.items():
        if only and only != version:
            continue
        try:
            output, report = convert(root, version, name, mod_id)
            boot(output, report["instance_runtime"]["launch"]["unix"])
            cases = input_cases(root, output, report, version, mod_id)
        except expected_errors as exc:
            failures.append(version)
            notice(f"Conversion {version} failed", str(exc)[:3300])
            continue
        for label, check in (("generated NeoForge", lambda: modern_verify(root, output, report, cases)),
                             ("original Forge", lambda: legacy_verify(root, version, cases))):
            try:
                notice(f"{version} {label} behavior checked", check())
            except expected_errors as exc:
                failures.append(f"{version} {label}")
                notice(f"{version} {label} behavior failed", f"{type(exc).__name__}: {exc}"[:3200])
    assert not failures, failures


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scratch", type=Path)
    parser.add_argument("--version", choices=CASES)
    args = parser.parse_args()
    main(args.scratch.resolve(), args.version)
