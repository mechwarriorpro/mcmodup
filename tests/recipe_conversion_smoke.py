"""Original-only integration checks for the automatic Forge recipe converter."""

import argparse
import json
from pathlib import Path
import subprocess
import sys
from zipfile import ZipFile

from mcmodup.recipe_conversion import PROVENANCE
from mcmodup.runtime import file_digest
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
                             "--mod", label, "--output-pack", str(output), "--json"], capture_output=True, text=True, timeout=80)
    assert result.returncode == 0, result.stdout + result.stderr
    assert before == {(base, path): file_digest(path) for base in (source, target) for path in base.rglob("*") if path.is_file()}
    report = json.loads((output / "update-report.json").read_text())
    assert report["staged"] and report["ready"] and not report["native_builds"] and not report["connector_required"]
    assert report["mode"] == "automatic-recipe-conversion", report
    artifact, = report["files"]
    assert artifact["action"] == "convert" and artifact["from"] == f"convert/{label}" and set(artifact["ids"]) == {mod_id}
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


def main(root: Path) -> None:
    for version, (name, mod_id) in CASES.items():
        staged, report = convert(root, version, name, mod_id)
        boot(staged, report["instance_runtime"]["launch"]["unix"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scratch", type=Path)
    main(parser.parse_args().scratch.resolve())
