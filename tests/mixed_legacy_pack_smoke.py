"""Check one exact mixed original selection, with a separate unchanged native control."""

import argparse
from dataclasses import dataclass
import json
import os
from pathlib import Path
import shutil
import traceback
from zipfile import ZipFile

from mcmodup.audit import ModMetadata
from mcmodup.classfiles import class_entries
from mcmodup.icbm_plate import prepare as prepare_plate
from mcmodup.inputs import open_input
from mcmodup.native_pack import REPORT_NAME
from mcmodup.port import _build_gradle, _metadata, write_workspace
from mcmodup.runtime import file_digest
from native_pack_smoke import run_gradle
from neoforge_smoke import empty_test_structure
from pack_release_smoke import boot
from updater_sample import fingerprints, run


@dataclass(frozen=True)
class Original:
    minecraft: str
    name: str
    filename: str
    ids: tuple[str, ...]
    sha256: str

    @property
    def label(self) -> str:
        return f"mods/{self.minecraft}/{self.filename}"


ORIGINALS = (
    Original("1.6.4", "Minechem", "Minechem-1.6.4-5.0.5.FINAL.jar", ("minechem",),
             "2443c7f91351f0e6c2323636be28a6fe8697704f20edf378dfe1a9d7a3070bea"),
    Original("1.7.10", "Minechem", "Minechem-1.7.10-5.0.5.394.jar", ("minechem",),
             "ea802167b760d43828a0648de3727a58d5c1f7737613123ae49f2743188cdae7"),
    Original("1.6.4", "Voltz ICBM", "icbm-1.4.1.344.jar", ("icbm", "icbm|explosion", "icbm|sentry"),
             "34a0efec6a1d078d2d0f1b7681c1040d4d68cb096330f4db79bf569a00f75130"),
    Original("1.6.4", "Voltz Atomic Science", "atomicscience.jar", ("atomicscience",),
             "80cf5bd9d485377ecde3822154a5db47804fab3e8f78c388cfcf529239655938"),
    Original("1.7.10", "Thaumcraft 4", "Thaumcraft-1.7.10-4.2.3.5.jar", ("thaumcraft",),
             "3dba9786966974701578a658d1bb369bf35bdf5f363079f5ac9c4910a39113be"),
    Original("1.6.4", "GeoStrata", "GeoStrata 1.6 v23b.zip", ("geostrata",),
             "afd68aa6ca1222a0f53ee25323c18bd3c2473c346a15345b216c9ef1042a0414"),
    Original("1.6.4", "Mimicry", "Mimicry 1.6.4_965_1.3.9_final.zip", ("mimicry",),
             "4a3d6e72be0ae0f8929c11890dcf96b49750db48908c5bd5b5957d2d9542eada"),
    Original("1.6.4", "Random Things", "Random_Things_v._1.9__MC_1.6.4_.jar", ("randomthings",),
             "cc3b350aed5a41b48da68ebcea27cb156c4eed6347bf060bbdbb00b5109faa49"),
)
VOLTZ_FILES = (
    "mods/icbm-1.4.1.344.jar", "mods/atomicscience.jar",
    "mods/calclavia-core-development-1.2.0.6-main.jar", "mods/universal-electricity-3.1.0.74-core.jar",
    "mods/1.6.4/ForgeMultipart-universal-1.6.4-1.0.0.250.jar", "mods/1.6.4/CodeChickenLib-universal-1.6.4-1.0.0.61.jar",
    "mods/codechickencore 0.9.0.7.jar", "mods/notenoughitems 1.6.1.8.jar",
)
MULTIPART = "CBMultipart-1.21.1-3.5.0.155.jar"
CCL = "CodeChickenLib-1.21.1-4.6.1.526.jar"
PACK_MODS = {MULTIPART, CCL}
CHECK = "mcmodup_multipart_control"
PACKAGE = "mcmodup.multipartcheck"


def note(title: str, message: str, *, level: str = "notice") -> None:
    escaped = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::{level} title={title}::{escaped}", flush=True)


def summary(lines: list[str]) -> None:
    text = "\n".join(lines) + "\n"
    print(text, flush=True)
    if path := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(path).open("a", encoding="utf-8") as stream:
            stream.write(text)


def inspect_overlap(left: Path, right: Path) -> tuple[int, int]:
    with open_input(left) as first, open_input(right) as second:
        classes = class_entries(first.files).keys() & class_entries(second.files).keys()
        assets = first.files.keys() & second.files.keys()
        return sum(name.startswith("minechem/") for name in classes), sum(name.startswith("assets/minechem/") for name in assets)


def voltz_dependencies(report: dict, source: Path) -> None:
    ue = "mods/1.6.4/universal-electricity-3.1.0.74-core.jar"
    core = "mods/1.6.4/calclavia-core-development-1.2.0.6-main.jar"
    assert file_digest(source / ue) == "709d41f4d4345681170b01f5e15142b5843c5750765e2dff42bb9233b825b7b6"
    assert file_digest(source / core) == "0372d373993769264da00cef69c3814f89295856d5f14dd570d6a7aaac6c5585"
    with open_input(source / ue) as archive:
        ue_owners = class_entries(archive.files).keys()
    for artifact in ORIGINALS[2:4]:
        declarations = [row for row in report["legacy_requirements"]
                        if row["consumer"] == artifact.label and row["id"] == "CalclaviaCore"]
        assert any(row["status"] == "present" and row["provider"]["minecraft"] == "1.6.4" and
                   row["provider"]["archive"] == core for row in declarations), declarations
        linkage = report["legacy_class_providers"][artifact.label]
        resolved = [name for row in linkage["providers"] if row["archive"] == ue for name in row["classes"]]
        ambiguous = set(linkage["ambiguous_classes"]) & ue_owners
        # Other originals can bundle the same UE class names. A duplicate owner
        # is evidence of a reference, but is not evidence of the bound JVM class.
        assert resolved or ambiguous, linkage
        note(f"{artifact.name} legacy dependency evidence",
             f"CalclaviaCore declared and found in 1.6.4; {len(resolved)} references to uniquely owned Universal Electricity "
             f"classes and {len(ambiguous)} whose JAR owner is ambiguous in this selection. Not a runtime linkage check.")


def originals(root: Path) -> None:
    source, target = root / "legacy", root / "target"
    assert file_digest(root / "voltz.zip") == "2eda607746a22edbf45612243adce37a876251e36d46c692a48344fe98eec84e"
    # Exact member names only. Never extract the launcher, legacy runtime or configs.
    with ZipFile(root / "voltz.zip") as pack:
        for member in VOLTZ_FILES:
            (source / "mods/1.6.4" / Path(member).name).write_bytes(pack.read(member))
    assert {path.name for path in (target / "mods").iterdir()} == PACK_MODS
    before = fingerprints(source, target)
    for original in ORIGINALS:
        assert before.get(source / original.label) == original.sha256, f"Wrong original {original.label}"
    code, catalog = run("update-pack", source, "--target", target)
    assert code == 0 and catalog["neoforge"] == "21.1.267", catalog
    lookup = {row["id"]: row for row in catalog["rows"]}
    lines = ["## One requested selection: Minecraft 1.21.1 / NeoForge 21.1.267", "",
             "| Original | Minecraft | IDs found | SHA-256 | Updater catalog result |", "| --- | --- | --- | --- | --- |"]
    for artifact in ORIGINALS:
        row = lookup[artifact.label]
        assert row["minecraft"] == artifact.minecraft and set(row["mods"]) == set(artifact.ids), row
        checksum = before[source / artifact.label]
        ids = ", ".join(row["mods"]).replace("|", "\\|")
        lines.append(f"| {artifact.name} (`{artifact.filename}`) | {artifact.minecraft} | {ids} | `{checksum}` | {row['status']}: {row['reason']} |")
        note(f"{artifact.name} {artifact.minecraft}", f"{', '.join(row['mods'])}; SHA-256 {checksum}; {row['status']}: {row['reason']}")
    classes, assets = inspect_overlap(source / ORIGINALS[0].label, source / ORIGINALS[1].label)
    assert classes > 0 and assets > 0, (classes, assets)
    note("Minechem namespace overlap", f"{classes} shared minechem/ class names and {assets} identical assets/minechem/ paths across both originals.")

    labels = sorted(artifact.label for artifact in ORIGINALS)
    common = ["update-pack", source, "--target", target, "--include-native", "cb_multipart"]
    for label in labels:
        common.extend(("--mod", label))
    for preview in (False, True):
        out = root / ("complete-with-preview-opt-in" if preview else "complete")
        code, report = run(*common, "--output-pack", out, *(["--allow-recipe-preview"] if preview else []))
        assert report["selected"] == labels and report["include_native"] == ["cb_multipart"], report
        assert code == 1 and not report["ready"] and not out.exists(), report
        assert not report["parity_verified"] and not report["gameplay_verified"], report
        assert all(report["legacy_archive_sha256"][label] == before[source / label] for label in labels), report
        assert not any(row["action"] in {"build", "convert"} for row in report["files"]), report
        conflict, = [row for row in report["legacy_selection_conflicts"] if row["id"] == "minechem"]
        assert {row["archive"] for row in conflict["archives"]} == {artifact.label for artifact in ORIGINALS[:2]}, conflict
        assert any("illegal NeoForge mod ID" in reason and "icbm|explosion" in reason and "icbm|sentry" in reason
                   for reason in report["problems"]), report["problems"]
        (root / f"{out.name}.json").write_text(json.dumps(report, indent=2) + "\n")
        if not preview:
            for reason in report["problems"]:
                note("Combined plan blocked", reason)
            voltz_dependencies(report, source)
    out = root / "complete-with-executable-preview"
    manifest = prepare_plate(root / "plate-preview-recipe")
    code, report = run(*common, "--output-pack", out, "--source-ports", manifest, "--allow-preview-ports")
    assert code == 1 and report["selected"] == labels and not report["ready"] and not out.exists(), report
    assert not report["native_builds"] and not report["parity_verified"], report
    assert any("No source recipe for this exact legacy archive" in reason for reason in report["problems"]), report
    assert report["legacy_selection_conflicts"][0]["id"] == "minechem", report
    (root / f"{out.name}.json").write_text(json.dumps(report, indent=2) + "\n")
    note("Executable plate preview does not satisfy the mixed pack", "With the plate recipe enabled, the same eight originals still block as one selection. No partial pack was written.")
    assert fingerprints(source, target) == before, "Planning modified a source or target file"
    summary([*lines, "", "**Combined plan blocked; 0 of 8 originals produced a converted JAR and no pack was staged.** "
             "The same selection also blocks with the recipe and executable plate previews opted in (in separate runs).",
             f"The two original Minechem archives share `minechem`, {classes} `minechem/` class names, and {assets} `assets/minechem/` paths.",
             "The single Voltz ICBM archive still accounts for all three modules. The legacy archives were inspected, not executed.", ""])


def control(root: Path) -> None:
    # This is not an attempt to boot a partial version of the requested pack.
    source, target, out = root / "empty-source", root / "target", root / "native-control"
    (source / "mods").mkdir(parents=True)
    before = fingerprints(target)
    code, report = run("update-pack", source, "--target", target, "--include-native", "cb_multipart", "--output-pack", out)
    assert code == 0 and report["ready"] and report["staged"], report
    assert not report["selected"] and report["include_native"] == ["cb_multipart"] and not report["connector_required"], report
    assert {path.name for path in (out / "mods").glob("*.jar")} == PACK_MODS
    files = {Path(row["file"]).name: row for row in report["files"]}
    assert set(files[MULTIPART]["ids"]) == {"cb_multipart", "cb_microblock", "cb_multipart_minecraft"}, files
    assert files[MULTIPART]["action"] == "include-native" and files[CCL]["action"] == "dependency", files
    for name in PACK_MODS:
        assert file_digest(out / "mods" / name) == before[target / "mods" / name] == files[name]["sha256"]
    assert fingerprints(target) == before and not (out / "eula.txt").exists()
    boot(out, report["instance_runtime"]["launch"]["unix"])
    assert not json.loads((out / REPORT_NAME).read_text())["parity_verified"]
    summary(["## Separate native control", "",
             "Staged both native releases byte-for-byte and booted/shut down the stock dedicated server. "
             "No legacy archive, converted mod or replacement port was present.", ""])

    # Tests use a separate dev runtime so the stock boot above has no probe mod.
    workspace = root / "multipart-gametest"
    fixture = Path(__file__).with_name("fixtures") / "MultipartControlSmoke.java"
    write_workspace(workspace, {
        "settings.gradle": "pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }\n"
                           "rootProject.name = 'multipart-control-check'\n",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "src/main/resources/META-INF/neoforge.mods.toml": _metadata(ModMetadata(""), CHECK, CHECK, "1.0.0", "Unlicense"),
        f"src/main/resources/data/{CHECK}/structure/empty.nbt": empty_test_structure(),
        f"src/main/java/{PACKAGE.replace('.', '/')}/{fixture.name}": fixture.read_bytes(),
        "build.gradle": _build_gradle(CHECK, PACKAGE, "1.0.0") + f"""
dependencies {{ compileOnly fileTree(dir: 'run/mods', include: ['*.jar']) }}
neoForge.runs.create('gameTestServer') {{
    type = 'gameTestServer'
    gameDirectory = project.file('run')
    systemProperty 'neoforge.enabledGameTestNamespaces', '{CHECK}'
}}
""",
    })
    shutil.copytree(out / "mods", workspace / "run/mods")
    output = run_gradle(workspace, "runGameTestServer")
    assert "All 1 required tests passed" in output, output[-12000:]
    note("Native Multipart control GameTest passed", "Saw and stone crafted two covers; item components and both parts survived serialization. No legacy interaction was tested.")
    summary(["Native control GameTest: crafted stone covers with the native saw, placed two parts using the Multipart API, "
             "and round-tripped the item component and saved tile. This does not exercise converted blocks or items.", ""])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scratch", type=Path)
    parser.add_argument("check", choices=("originals", "control"))
    args = parser.parse_args()
    try:
        {"originals": originals, "control": control}[args.check](args.scratch.resolve())
    except Exception:
        if os.environ.get("GITHUB_ACTIONS") == "true":
            note(f"Mixed legacy pack check failed ({args.check})", traceback.format_exc()[-5000:], level="error")
        raise
