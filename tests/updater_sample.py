"""Measure standalone update-pack on three sampled release JARs (no legacy code is run)."""

import argparse
from contextlib import contextmanager
from dataclasses import dataclass
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from mcmodup.runtime import file_digest
from pack_release_smoke import boot


@dataclass(frozen=True)
class Sample:
    minecraft: str
    project: str
    mod_id: str
    original: str
    native: str | None = None


# Independent draws from the first 20 CurseForge search results (total downloads,
# exact old release, Mods); Random(20261118).randint(1, 20) gives 7, 11, 3.
SAMPLES = (
    Sample("1.7.10", "Xaero's Minimap", "xaerominimap", "Xaeros_Minimap_21.10.44_Forge_1.7.10.jar",
           "xaerominimap-neoforge-1.21.1-26.6.0.jar"),
    Sample("1.6.4", "BdLib", "bdlib", "bdlib-mc164-1.2.3.48.jar"),
    Sample("1.5.2", "The Twilight Forest", "twilightforest", "Twilight Forest - MC 1.5.2 - 1.18.2.jar",
           "twilightforest-1.21.1-4.8.3345-universal.jar"),
)


def run(*args: str | Path) -> tuple[int, dict]:
    result = subprocess.run([sys.executable, "-m", "mcmodup", *args, "--json"], capture_output=True,
                            text=True, timeout=120)
    if result.returncode not in (0, 1) or not result.stdout:
        raise AssertionError(f"{' '.join(map(str, args))} [{result.returncode}]: {result.stderr}")
    return result.returncode, json.loads(result.stdout)


def fingerprints(*roots: Path) -> dict[Path, str]:
    return {path: file_digest(path) for root in roots for path in root.rglob("*") if path.is_file()}


@contextmanager
def supply(target: Path, native: Path):
    library = target / "lib"
    library.mkdir(exist_ok=False)
    try:
        shutil.copyfile(native, library / native.name)
        yield
    finally:
        shutil.rmtree(library)


def experiment(source: Path, target: Path, label: str, artifact: Path, out: Path, sample: Sample) -> str:
    before = fingerprints(source, target)
    code, report = run("update-pack", source, "--target", target, "--mod", label, "--output-pack", out)
    assert fingerprints(source, target) == before, "The updater changed an input"
    assert report["selected"] == [label], report
    assert report["legacy_archive_sha256"][label] == before[artifact], report
    if code == 1:
        assert not report["ready"] and not out.exists() and report["problems"], report
        return f"Blocked (exit 1, no JAR): {' '.join(report['problems'])}"

    assert report["ready"] and report["staged"] and not report["connector_required"], report
    [selection] = [row for row in report["files"] if sample.mod_id in row["ids"]]
    staged = out / selection["file"]
    assert file_digest(staged) == selection["sha256"] != before[artifact], report
    native_source = target / "lib" / sample.native if sample.native else None
    provided = native_source is not None and native_source.is_file()
    if provided:
        assert selection["sha256"] == before[native_source], "The staged JAR is not the official native control"
    boot(out, report["instance_runtime"]["launch"]["unix"])
    return f"{'Staged existing release' if provided else 'Produced JAR'} {selection['ids'][sample.mod_id]}; dedicated server booted"


def check(root: Path) -> list[tuple[Sample, str, str, str]]:
    target = root / "target"
    results, inputs = [], []
    assert not (target / "lib").exists() and not list((target / "mods").glob("*.jar")), "Test needs a clean NeoForge target"
    # Finish *all* original-only experiments before placing any native controls in the target.
    for sample in SAMPLES:
        source = root / "legacy" / sample.minecraft
        label = f"mods/{sample.original}"
        artifact = source / label
        code, scan = run("scan-pack", source, "--mod", label)
        [seen] = scan["archives"]
        assert code == 0 and seen["source_version"] == scan["legacy_profile"] == sample.minecraft, (
            seen["version_evidence"], scan["local_runtime"], scan["warnings"])
        assert seen["classes_indexed"] and sample.mod_id in {row["mod_id"].casefold() for row in seen["mods"]}, seen["mods"]
        assert scan["local_runtime"]["forge_files"], scan["local_runtime"]
        fingerprint = file_digest(artifact)
        print(f"{sample.minecraft} {label}: {seen['classes_indexed']} classes, SHA-256 {fingerprint}", flush=True)
        bare = experiment(source, target, label, artifact, root / f"output-{sample.minecraft}-original-only", sample)
        print(f"::notice title={sample.minecraft} original-only::{bare}", flush=True)
        inputs.append((sample, source, label, artifact, fingerprint, bare))

    for sample, source, label, artifact, fingerprint, bare in inputs:
        control = "Not run: no official 1.21.1 NeoForge release supplied"
        if sample.native:
            with supply(target, root / "controls" / sample.native):
                control = experiment(source, target, label, artifact, root / f"output-{sample.minecraft}-with-native", sample)
                print(f"::notice title={sample.minecraft} with native control::{control}", flush=True)
            assert control.startswith("Staged existing release"), control
        results.append((sample, fingerprint, bare, control))
    return results


def summarize(results: list[tuple[Sample, str, str, str]]) -> str:
    ran_automatic = sum(bare.startswith("Produced JAR") for _, _, bare, _ in results)
    lines = ["## Unassisted legacy update sample", "",
             f"**{ran_automatic}/{len(results)} original-only inputs produced a JAR and booted.** "
             "Staging already-ported releases is reported separately; it does not count as conversion.", "",
             "| Input | Original SHA-256 | Original JAR + clean NeoForge only | With official native JAR supplied |",
             "| --- | --- | --- | --- |"]
    lines.extend(f"| {sample.minecraft} {sample.project} (`{sample.original}`) | `{sha}` | {bare} | {control} |"
                 for sample, sha, bare, control in results)
    lines.extend(["", "Each boot check uses the copied dedicated server runtime. It does not test a graphical client, gameplay parity, or saved-world migration.", ""])
    return "\n".join(lines)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scratch", type=Path)
    summary = summarize(check(parser.parse_args().scratch.resolve()))
    print(summary)
    if path := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(path).open("a", encoding="utf-8") as stream:
            stream.write(summary)
