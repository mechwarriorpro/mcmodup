"""Boot the exact Minechem and ICBM previews together with the native controls."""

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import traceback
from zipfile import ZipFile

from mcmodup.icbm_plate import ARCHIVE as PLATE, MOD_ID as PLATE_ID, prepare as prepare_plate
from mcmodup.method_ports import PROVENANCE
from mcmodup.minechem_pair import FINAL, RELEASE_394, VERSIONS
from mcmodup.native_pack import REPORT_NAME
from mcmodup.runtime import file_digest
from mixed_legacy_pack_smoke import ORIGINALS, PACK_MODS, fingerprints, note, summary
from neoforge_smoke import check_recipes_in_game
from pack_release_smoke import boot
from updater_sample import run


def check(root: Path) -> None:
    source, target, out = root / "preview-sources", root / "target", root / "linked-previews"
    full_source = root / "legacy"
    labels = [entry.label for entry in ORIGINALS[:2]]
    plate_original = next(entry for entry in ORIGINALS if "icbm" in entry.ids)
    selected = sorted([*labels, plate_original.label])
    all_before = fingerprints(full_source, target)
    for label in selected:
        path = source / label
        path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(full_source / label, path)
    # FINAL bundles some UE and Modstats classes also supplied by Voltz. With
    # those libraries absent, the static graph would see only FINAL as a binary
    # owner and report ICBM as its add-on. Keep the actual Voltz inputs visible
    # as legacy evidence; neither is a selected mod or stages into the target.
    libraries = source / "lib"
    libraries.mkdir()
    for filename in ("universal-electricity-3.1.0.74-core.jar", "calclavia-core-development-1.2.0.6-main.jar"):
        shutil.copyfile(full_source / "mods/1.6.4" / filename, libraries / filename)
    before = fingerprints(source)
    recipe = root / "minechem-recipes"
    proc = subprocess.run([sys.executable, "-m", "mcmodup", "prepare-minechem-pair", *(str(source / label) for label in labels),
                           "-o", str(recipe)], text=True, capture_output=True, timeout=50)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    manifest = recipe / "minechem-pair.json"
    plate_manifest = prepare_plate(root / "linked-plate-recipe")
    manifests = ["--source-ports", str(manifest), "--source-ports", str(plate_manifest)]
    command = ["update-pack", str(source), "--target", str(target), *manifests, "--include-native", "cb_multipart"]
    for label in selected:
        command.extend(("--mod", label))
    code, unapproved = run(*command, "--output-pack", out)
    assert code == 1 and not out.exists() and any("--allow-preview-ports" in issue for issue in unapproved["problems"]), unapproved["problems"]
    allowed = ["--allow-preview-ports", "--output-pack", str(out)]
    proc = subprocess.run([sys.executable, "-m", "mcmodup", *command, *allowed, "--build-timeout", "420", "--json"],
                          text=True, capture_output=True, timeout=1050)
    if proc.returncode:
        problems = json.loads(proc.stdout)["problems"] if not proc.stderr and proc.stdout.startswith("{") else []
        raise AssertionError("Combined staging failed: " + ("\n".join(problems) or proc.stderr or proc.stdout)[:2200])
    report = json.loads((out / REPORT_NAME).read_text())
    assert report["staged"] and report["ready"] and not report["parity_verified"] and not report["connector_required"], report["problems"]
    assert report["selected"] == selected and report["build_order"] == selected, (selected, report["selected"], report["build_order"])
    assert not report["auto_addons"], report["auto_addons"]
    linkage = report["legacy_class_providers"][plate_original.label]
    assert "universalelectricity/api/vector/Vector3" in linkage["ambiguous_classes"], linkage
    [split] = report["legacy_identity_splits"]
    assert split["id"] == "minechem" and not report["legacy_selection_conflicts"], split
    assert [row["archive"] for row in split["archives"]] == labels, split
    controls = [out / "mods" / name for name in sorted(PACK_MODS)]
    assert all(file_digest(path) == all_before[target / "mods" / path.name] for path in controls)
    artifacts = [out / "mods" / f"{profile.namespace}-{VERSIONS[profile.namespace]}.jar" for profile in (FINAL, RELEASE_394)]
    plate = out / "mods" / PLATE
    assert set((out / "mods").iterdir()) == {*artifacts, *controls, plate}
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
    evidence = report["native_builds"][plate.name]
    assert evidence["status"] == "preview" and evidence["gametests"]["passed"] == 2, evidence
    assert evidence["original_sha256"] == plate_original.sha256 == before[source / plate_original.label], evidence
    assert evidence["jar_sha256"] == file_digest(plate)
    assert evidence["legacy_ids"] == {"icbm": PLATE_ID, "icbm|explosion": None, "icbm|sentry": None}, evidence
    boot(out, report["instance_runtime"]["launch"]["unix"])
    check_recipes_in_game(root / "preview-staged-smoke", [artifacts[0]], minechem_394=artifacts[1],
                          multipart_control=controls, icbm_plate=plate)
    assert fingerprints(full_source, target) == all_before and fingerprints(source) == before
    # Opting in must not silently stage previews if the rest of the full selection is blocked.
    delta = root / "mixed-with-linked-previews-must-not-stage"
    full = ["update-pack", str(full_source), "--target", str(target), *manifests,
            "--allow-preview-ports", "--include-native", "cb_multipart", "--output-pack", str(delta)]
    for entry in ORIGINALS:
        full.extend(("--mod", entry.label))
    code, report = run(*full)
    assert code == 1 and not report["ready"] and not delta.exists() and not report["native_builds"], report["problems"]
    assert not report["legacy_selection_conflicts"] and report["legacy_identity_splits"], report["legacy_selection_conflicts"]
    note("Combined native previews booted", "Both exact Minechem previews and the ICBM plate reached dedicated server ready with unchanged Multipart and CCL. Six source and 13 staged-JAR tests passed. All eight still block without writing a pack.")
    summary(["## Combined native previews", "",
             "The two Minechem previews and the Voltz ICBM glass pressure plate built in one updater invocation and staged in a new NeoForge "
             "instance with unchanged CB Multipart and CodeChickenLib. Six source GameTests, stock dedicated boot, and 13 staged-JAR GameTests "
             "passed. The tests include the native Multipart lookup of the converted plate's redstone signal. These are isolated previews, "
             "not full ports. The all-eight selection with the same recipes staged nothing.", ""])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    try:
        check(parser.parse_args().root.resolve())
    except Exception:
        note("Combined native preview check failed", traceback.format_exc()[-7000:], level="error")
        raise
