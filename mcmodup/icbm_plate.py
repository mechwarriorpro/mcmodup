"""Prepare a local source recipe for the exact Voltz glass pressure plate preview."""

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


LEGACY_SHA256 = "34a0efec6a1d078d2d0f1b7681c1040d4d68cb096330f4db79bf569a00f75130"
MOD_ID = "icbm_glass_plate"
VERSION = "1.4.1.344-preview.2"
ARCHIVE = f"{MOD_ID}-{VERSION}.jar"
PACKAGE = "mcmodup.icbmplate"
KERNEL = f"mcmodup_kernel/{MOD_ID}"
OWNER = "icbm/contraption/block/BlockGlassPressurePlate"
WORLD = "net/minecraft/world/World"
TEXTURE = "assets/icbm/textures/blocks/glassPressurePlate.png"


def method_group() -> dict:
    plate, world = f"{KERNEL}/GlassPlate$Access", f"{KERNEL}/GlassPlate$WorldAccess"
    old_world, new_world = f"L{WORLD};", f"L{world};"
    return {"owner": OWNER, "output": f"{KERNEL}/PlateLogic", "types": {OWNER: plate, WORLD: world},
            "methods": {f"{OWNER}#func_72193_l({old_world}IIII)V": "update", f"{OWNER}#func_71859_p_({old_world})I": "delay"},
            "members": {
                f"{OWNER}#func_94351_d({old_world}III)I": f"{plate}#sense({new_world}III)I",
                f"{OWNER}#func_94355_d(I)I": f"{plate}#toMetadata(I)I",
                f"{OWNER}#func_94354_b_({old_world}III)V": f"{plate}#notifyNeighbors({new_world}III)V",
                f"{OWNER}#func_71859_p_({old_world})I": f"{plate}#checkDelay({new_world})I",
                f"{WORLD}#func_72921_c(IIIII)Z": f"{world}#setMetadata(IIIII)Z",
                f"{WORLD}#func_72909_d(IIIIII)V": f"{world}#markRenderRange(IIIIII)V",
                f"{WORLD}#func_72836_a(IIIII)V": f"{world}#schedule(IIIII)V",
            },
            "redirects": {"getfield": {f"{OWNER}#field_71990_ca:I": f"{plate}#registryId(L{plate};)I"}}}


def _json(data: dict) -> str:
    return json.dumps(data, indent=2) + "\n"


def checks(namespace: str) -> str:
    return (TEMPLATES / "icbm_plate/PlateChecks.java").read_text(encoding="utf-8").replace("@TEST_NAMESPACE@", namespace)


def _project(workspace: Path) -> None:
    item = "glass_pressure_plate"
    assets, java = f"src/main/resources/assets/{MOD_ID}", "src/main/java"
    prefix = f"{MOD_ID}:block/{item}"
    # Retain the same render geometry across powered states, using the original
    # texture and the native pressure-plate model parents.
    models = {f"{assets}/models/block/{name}.json": _json({"parent": f"minecraft:block/{parent}", "render_type": "minecraft:translucent",
                                                        "textures": {"texture": prefix}})
              for name, parent in ((item, "pressure_plate_up"), (item + "_down", "pressure_plate_down"))}
    title = "ICBM Glass Pressure Plate (Preview)"
    desc = "Local port of one block from Voltz ICBM 1.4.1.344. Not a replacement for ICBM or its add-ons."
    meta = _metadata(ModMetadata("", description=desc), MOD_ID, title, VERSION, "Original ICBM license applies (local conversion)")
    write_workspace(workspace, {
        "settings.gradle": f"rootProject.name = '{MOD_ID}'\n",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "build.gradle": _build_gradle(MOD_ID, PACKAGE, VERSION) + f"""
// The updater writes only the checked method-import class here. No legacy JAR is
// on the compiler or runtime classpath.
tasks.named('compileJava') {{ classpath += files('src/main/resources') }}
tasks.named('jar') {{
    archiveFileName = '{ARCHIVE}'
    exclude 'mcmodup/icbmplate/PlateChecks.class', 'data/{MOD_ID}/structure/empty.nbt'
}}
neoForge.runs.create('gameTestServer') {{
    type = 'gameTestServer'
    gameDirectory = project.file('run')
    systemProperty 'neoforge.enabledGameTestNamespaces', '{MOD_ID}'
    systemProperty 'mcmodup.gameReport', layout.buildDirectory.file('gametest.xml').get().asFile.absolutePath
}}
""",
        "src/main/resources/META-INF/neoforge.mods.toml": meta.replace(f"[{NEOFORGE_VERSION},21.2)", f"[{NEOFORGE_VERSION}]"),
        f"{java}/{KERNEL}/GlassPlate.java": (TEMPLATES / "icbm_plate/GlassPlate.java").read_bytes(),
        f"{java}/mcmodup/icbmplate/GlassPlateMod.java": (TEMPLATES / "icbm_plate/GlassPlateMod.java").read_bytes(),
        f"{java}/mcmodup/icbmplate/PlateChecks.java": checks(MOD_ID),
        f"src/main/resources/data/{MOD_ID}/structure/empty.nbt": empty_test_structure(4),
        f"src/main/resources/data/{MOD_ID}/recipe/{item}.json": _json({"type": "minecraft:crafting_shaped", "category": "redstone",
                "pattern": ["##"], "key": {"#": {"item": "minecraft:glass"}}, "result": {"id": f"{MOD_ID}:{item}", "count": 1}}),
        f"{assets}/lang/en_us.json": _json({f"block.{MOD_ID}.{item}": "Glass Pressure Plate"}),
        f"{assets}/blockstates/{item}.json": _json({"variants": {"powered=false": {"model": prefix},
                                                              "powered=true": {"model": prefix + "_down"}}}),
        f"{assets}/models/item/{item}.json": _json({"parent": prefix}),
        **models,
    })


def prepare(output: Path) -> Path:
    """Generate a reviewable, source-only archive and its fingerprinted recipe."""
    output = output.expanduser()
    if output.exists() or output.is_symlink():
        raise InputError("Choose a new directory for the ICBM glass pressure plate recipe")
    output.mkdir(parents=True, exist_ok=False)
    try:
        source = output / "icbm-plate-src.zip"
        with tempfile.TemporaryDirectory(prefix="mcmodup-icbm-plate-") as tmp:
            workspace = Path(tmp) / "project"
            _project(workspace)
            with ZipFile(source, "x") as archive:
                for path in sorted(workspace.rglob("*")):
                    if path.is_file():
                        member = ZipInfo(path.relative_to(workspace).as_posix())
                        member.create_system = 3  # normalized Unix file modes, even on Windows
                        member.external_attr = (0o100755 if member.filename == "gradlew" else 0o100644) << 16
                        archive.writestr(member, path.read_bytes(), compress_type=ZIP_DEFLATED)
        texture = f"assets/{MOD_ID}/textures/block/glass_pressure_plate.png"
        recipe = {"legacy_sha256": LEGACY_SHA256, "source_zip": source.name, "source_sha256": digest(source),
                  "ids": {MOD_ID: VERSION}, "legacy_ids": {"icbm": MOD_ID, "icbm|explosion": None, "icbm|sentry": None},
                  "neoforge": f"[{NEOFORGE_VERSION}]", "artifact": f"build/libs/{ARCHIVE}", "tasks": ["jar", "runGameTestServer"],
                  "game_report": "build/gametest.xml", "minimum_tests": 2, "features": {
                      "Glass plate recipe and break behavior": "craftinganddrop",
                      "Original pressure-plate redstone updates and release timing": "contactandrelease"},
                  "status": "preview", "limitations": [
                      "Only the glass pressure plate from ICBM Core is implemented. Explosion, Sentry, and the rest of Core are absent.",
                      "Separate registry IDs for fresh worlds. Old saves and add-ons expecting ICBM are not supported.",
                      "No graphical client comparison; modern material, collision and redstone APIs are used at the boundary."],
                  "import_assets": {TEXTURE: f"src/main/resources/{texture}"}, "asset_map": {TEXTURE: {"path": texture}},
                  "method_imports": [method_group()]}
        manifest = output / "icbm-plate.json"
        manifest.write_text(_json({"schema_version": 1, "ports": [recipe]}), encoding="utf-8")
        return manifest.resolve()
    except BaseException:
        shutil.rmtree(output)
        raise
