"""Prepare a local thermometer preview bound to Voltz Atomic Science 1.2.0."""

import json
from pathlib import Path
import shutil
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from . import NEOFORGE_VERSION
from .audit import ModMetadata
from .inputs import InputError
from .port import TEMPLATES, _build_gradle, _metadata, empty_test_structure, write_workspace
from .source_ports import digest


LEGACY_SHA256 = "80cf5bd9d485377ecde3822154a5db47804fab3e8f78c388cfcf529239655938"
MOD_ID = "atomic_thermometer"
VERSION = "1.2.0-preview.1"
ARCHIVE = f"{MOD_ID}-{VERSION}.jar"
PACKAGE = "mcmodup_kernel.atomic_thermometer"
KERNEL = PACKAGE.replace(".", "/")
OWNER = "atomicscience/process/sensor/TileThermometer"
PLAYER = "net/minecraft/entity/player/EntityPlayer"
VECTOR = "universalelectricity/api/vector/Vector3"
TEXTURES = {
    f"assets/atomicscience/textures/{kind}s/thermometer.png": f"assets/{MOD_ID}/textures/{kind}/thermometer.png"
    for kind in ("block", "item")
}


def method_group() -> dict:
    access = f"{KERNEL}/Thermometer$Access"
    user, hit = f"{KERNEL}/Thermometer$User", f"{KERNEL}/Thermometer$Hit"
    recv = f"L{access};"
    use = f"(L{PLAYER};IL{VECTOR};)Z"
    return {
        "owner": OWNER, "output": f"{KERNEL}/ThermometerLogic", "types": {OWNER: access, PLAYER: user, VECTOR: hit},
        "methods": {f"{OWNER}#setThreshold(I)V": "setThreshold", f"{OWNER}#isOverThreshold()Z": "isOverThreshold",
                    f"{OWNER}#use{use}": "use", f"{OWNER}#configure{use}": "configure"},
        "members": {},
        "redirects": {
            "getfield": {f"{OWNER}#threshold:I": f"{access}#threshold({recv})I",
                         f"{OWNER}#detectedTemperature:F": f"{access}#sample({recv})F"},
            "putfield": {f"{OWNER}#threshold:I": f"{access}#storeThreshold({recv}I)V"},
            "invokevirtual": {f"{OWNER}#getThershold()I": f"{access}#threshold({recv})I",
                              f"{OWNER}#setThreshold(I)V": f"{access}#setThreshold({recv}I)V",
                              f"{OWNER}#markUpdate()V": f"{access}#refresh({recv})V",
                              f"{PLAYER}#func_70093_af()Z": f"{access}#crouching(L{user};)Z"},
        },
    }


def _json(data: dict) -> str:
    return json.dumps(data, indent=2) + "\n"


def checks(namespace: str) -> str:
    return (TEMPLATES / "atomic_thermometer/ThermometerChecks.java").read_text(encoding="utf-8").replace("@TEST_NAMESPACE@", namespace)


def _project(workspace: Path) -> None:
    templates = TEMPLATES / "atomic_thermometer"
    name = "Atomic Science Thermometer (Preview)"
    description = "One exact Voltz 1.2.0 sensor. Not an implementation of Atomic Science or the legacy ThermalGrid."
    prefix = f"{MOD_ID}:block/thermometer"
    java, resources = f"src/main/java/{KERNEL}", "src/main/resources"
    assets = f"{resources}/assets/{MOD_ID}"
    meta = _metadata(ModMetadata("", description=description), MOD_ID, name, VERSION, "Original Atomic Science license applies (local conversion)")
    write_workspace(workspace, {
        "settings.gradle": f"rootProject.name = '{MOD_ID}'\n",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "build.gradle": _build_gradle(MOD_ID, PACKAGE, VERSION) + f"""
tasks.named('compileJava') {{ classpath += files('src/main/resources') }}
tasks.named('jar') {{
    archiveFileName = '{ARCHIVE}'
    exclude '{KERNEL}/ThermometerChecks.class', 'data/{MOD_ID}/structure/empty.nbt'
}}
neoForge.runs.create('gameTestServer') {{
    type = 'gameTestServer'
    gameDirectory = project.file('run')
    systemProperty 'neoforge.enabledGameTestNamespaces', '{MOD_ID}'
    systemProperty 'mcmodup.gameReport', layout.buildDirectory.file('gametest.xml').get().asFile.absolutePath
}}
""",
        f"{resources}/META-INF/neoforge.mods.toml": meta.replace(f"[{NEOFORGE_VERSION},21.2)", f"[{NEOFORGE_VERSION}]"),
        f"{java}/Thermometer.java": (templates / "Thermometer.java").read_bytes(),
        f"{java}/ThermometerMod.java": (templates / "ThermometerMod.java").read_bytes(),
        f"{java}/ThermometerChecks.java": checks(MOD_ID),
        f"{resources}/data/{MOD_ID}/structure/empty.nbt": empty_test_structure(5),
        f"{assets}/lang/en_us.json": _json({f"block.{MOD_ID}.thermometer": "Thermometer", f"itemGroup.{MOD_ID}": name,
            f"info.{MOD_ID}.status": "Threshold: %s K · Reading: %s K", f"info.{MOD_ID}.tracked": "Tracking %s, %s, %s",
            f"info.{MOD_ID}.untracked": "Not tracking a remote position"}),
        f"{assets}/blockstates/thermometer.json": _json({"variants": {"": {"model": prefix}}}),
        f"{assets}/models/block/thermometer.json": _json({"parent": "minecraft:block/cube_all", "textures": {"all": prefix}}),
        f"{assets}/models/item/thermometer.json": _json({"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD_ID}:item/thermometer"}}),
    })


def prepare(output: Path) -> Path:
    output = output.expanduser()
    if output.exists() or output.is_symlink():
        raise InputError("Choose a new directory for the Atomic Science thermometer recipe")
    output.mkdir(parents=True, exist_ok=False)
    try:
        source = output / "atomic-thermometer-src.zip"
        with tempfile.TemporaryDirectory(prefix="mcmodup-atomic-thermometer-") as tmp:
            workspace = Path(tmp) / "project"
            _project(workspace)
            with ZipFile(source, "x") as archive:
                for path in sorted(workspace.rglob("*")):
                    if path.is_file():
                        member = ZipInfo(path.relative_to(workspace).as_posix())
                        member.create_system = 3
                        member.external_attr = (0o100755 if member.filename == "gradlew" else 0o100644) << 16
                        archive.writestr(member, path.read_bytes(), compress_type=ZIP_DEFLATED)
        recipe = {"legacy_sha256": LEGACY_SHA256, "source_zip": source.name, "source_sha256": digest(source),
                  "ids": {MOD_ID: VERSION}, "legacy_ids": {"atomicscience": MOD_ID},
                  "neoforge": f"[{NEOFORGE_VERSION}]", "artifact": f"build/libs/{ARCHIVE}", "tasks": ["jar", "runGameTestServer"],
                  "game_report": "build/gametest.xml", "minimum_tests": 2, "features": {
                      "Original threshold adjustment and wrapping rules": "thresholdandsettings",
                      "Ten-tick thermal samples, tracked coordinates and redstone": "trackedheatandsignal"},
                  "status": "preview", "limitations": [
                      "One thermometer only. No CalclaviaCore heat simulation, reactors, turbines or other Atomic Science features.",
                      "Uses native biome sampling and an optional NeoForge block capability for heat instead of the legacy grid.",
                      "Uses an adapted cube and two original textures. Creative-only: no old circuits, wrench items or saved drops.",
                      "No ComputerCraft/OpenComputers, Universal Electricity, old saves or legacy add-ons."],
                  "import_assets": {old: f"src/main/resources/{new}" for old, new in TEXTURES.items()},
                  "asset_map": {old: {"path": new} for old, new in TEXTURES.items()}, "method_imports": [method_group()]}
        manifest = output / "atomic-thermometer.json"
        manifest.write_text(_json({"schema_version": 1, "ports": [recipe]}), encoding="utf-8")
        return manifest.resolve()
    except BaseException:
        shutil.rmtree(output)
        raise
