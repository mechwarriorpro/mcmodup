"""Boot the pack the updater stages from two exact Minechem inputs and the native controls."""

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import traceback
from zipfile import ZipFile

from mcmodup.method_ports import PROVENANCE
from mcmodup.minechem_pair import FINAL, RELEASE_394, VERSIONS
from mcmodup.native_pack import REPORT_NAME
from mcmodup.runtime import file_digest
from mixed_legacy_pack_smoke import ORIGINALS, PACK_MODS, fingerprints, note, summary
from neoforge_smoke import check_recipes_in_game
from pack_release_smoke import boot
from updater_sample import run


def check(root: Path) -> None:
    source, target, out = root / "minechem-source", root / "target", root / "minechem-output-pack"
    full_source = root / "legacy"
    labels = [entry.label for entry in ORIGINALS[:2]]
    all_before = fingerprints(full_source, target)
    for label in labels:
        path = source / label
        path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(full_source / label, path)
    before = fingerprints(source)
    recipe = root / "minechem-recipes"
    proc = subprocess.run([sys.executable, "-m", "mcmodup", "prepare-minechem-pair", *(str(source / label) for label in labels),
                           "-o", str(recipe)], text=True, capture_output=True, timeout=50)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    manifest = recipe / "minechem-pair.json"
    command = ["update-pack", str(source), "--target", str(target), "--source-ports", str(manifest), "--include-native", "cb_multipart"]
    for label in labels:
        command.extend(("--mod", label))
    code, unapproved = run(*command, "--output-pack", out)
    assert code == 1 and not out.exists() and any("--allow-preview-ports" in issue for issue in unapproved["problems"]), unapproved
    allowed = ["--allow-preview-ports", "--output-pack", str(out)]
    proc = subprocess.run([sys.executable, "-m", "mcmodup", *command, *allowed, "--build-timeout", "420", "--json"],
                          text=True, capture_output=True, timeout=900)
    assert proc.returncode == 0, proc.stderr or proc.stdout
    report = json.loads((out / REPORT_NAME).read_text())
    assert report["staged"] and report["ready"] and not report["parity_verified"] and not report["connector_required"], report
    assert report["selected"] == sorted(labels) and report["build_order"] == sorted(labels), report
    [split] = report["legacy_identity_splits"]
    assert split["id"] == "minechem" and not report["legacy_selection_conflicts"], split
    assert [row["archive"] for row in split["archives"]] == labels, split
    controls = [out / "mods" / name for name in sorted(PACK_MODS)]
    assert all(file_digest(path) == all_before[target / "mods" / path.name] for path in controls)
    artifacts = [out / "mods" / f"{profile.namespace}-{VERSIONS[profile.namespace]}.jar" for profile in (FINAL, RELEASE_394)]
    assert set((out / "mods").iterdir()) == {*artifacts, *controls}
    for split_row, path, label, method in zip(split["archives"], artifacts, labels, ("canUnjam()Z", "pushQueue()V")):
        evidence = report["native_builds"][path.name]
        assert evidence["status"] == "preview" and evidence["gametests"]["passed"] == 2, evidence
        assert evidence["original_sha256"] == split_row["sha256"] == before[source / label], split_row
        assert evidence["legacy_ids"] == {"minechem": split_row["native_id"]}, evidence
        assert evidence["jar_sha256"] == file_digest(path)
        [linked] = evidence["method_imports"]
        assert linked["methods"][0].endswith("#" + method) and linked["call_redirects"], linked
        with ZipFile(path) as jar:
            proof = json.loads(jar.read(PROVENANCE))
            assert proof["imports"] == [linked] and not proof["parity_verified"]
            assert not any(name.endswith((".jar", "DecomposerChecks.class")) or name.startswith("minechem/") for name in jar.namelist())
        note("Original method linked and tested", f"{path.name}: {method}; {len(linked['call_redirects'])} approved redirects; two source GameTests passed.")
    boot(out, report["instance_runtime"]["launch"]["unix"])
    check_recipes_in_game(root / "minechem-staged-smoke", [artifacts[0]], minechem_394=artifacts[1], multipart_control=controls)
    assert fingerprints(full_source, target) == all_before and fingerprints(source) == before
    # Opting in to the pair must not silently stage them if the rest of the full selection is blocked.
    delta = root / "mixed-with-minechem-previews-must-not-stage"
    full = ["update-pack", str(full_source), "--target", str(target), "--source-ports", str(manifest),
            "--allow-preview-ports", "--include-native", "cb_multipart", "--output-pack", str(delta)]
    for entry in ORIGINALS:
        full.extend(("--mod", entry.label))
    code, report = run(*full)
    assert code == 1 and not report["ready"] and not delta.exists() and not report["native_builds"], report
    assert not report["legacy_selection_conflicts"] and report["legacy_identity_splits"], report
    note("Staged Minechem pair booted", "Two exact-input previews and unchanged Multipart/CCL reached dedicated server ready and passed 10 staged-JAR tests. Selecting all eight still blocks without writing a pack.")
    summary(["## Updater-staged Minechem previews", "",
             "The two SHA-bound Minechem recipes built together, executed linked output-slot methods, and staged in a new NeoForge instance "
             "with unchanged CB Multipart and CodeChickenLib. Two source tests per release, stock dedicated boot, and ten staged-JAR GameTests "
             "passed. This covers selected behavior, not full parity. The all-eight selection with these recipes still staged nothing.", ""])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    try:
        check(parser.parse_args().root.resolve())
    except Exception:
        note("Staged Minechem pair check failed", traceback.format_exc()[-7000:], level="error")
        raise
