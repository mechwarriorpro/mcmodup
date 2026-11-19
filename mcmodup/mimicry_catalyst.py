"""Prepare a checksum-bound native preview of five measured Mimicry 1.3.9 recipes."""

import json
from pathlib import Path
import shutil
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from . import NEOFORGE_VERSION
from .audit import ModMetadata
from .inputs import InputError
from .port import TEMPLATES, _build_gradle, _metadata, empty_test_structure, write_workspace
from .runtime import file_digest


LEGACY_SHA256 = "4a3d6e72be0ae0f8929c11890dcf96b49750db48908c5bd5b5957d2d9542eada"
MOD_ID = "mimicry_catalyst"
VERSION = "1.3.9-preview.1"
ARCHIVE = f"{MOD_ID}-{VERSION}.jar"
PACKAGE = "mcmodup.mimicrycatalyst"
LEGACY_TEXTURE = "assets/minecraft/textures/items/Mimichite.png"
TEXTURE = f"assets/{MOD_ID}/textures/item/mimichite.png"
# Exact default Forge observations from LegacyMimicryCatalyst. This table is
# deliberately not a rule that turns any smelting or ore-tag match into a recipe.
RECIPES = {
    "iron_ore": ("iron_ingot", 2),
    "gold_ore": ("gold_ingot", 2),
    "sand": ("glass", 2),
    "iron_ingot": ("iron_ingot", 2),
    "lapis_lazuli": ("lapis_lazuli", 3),
}


def _json(value: dict) -> str:
    return json.dumps(value, indent=2) + "\n"


def checks(namespace: str) -> str:
    return (TEMPLATES / "mimicry_catalyst/CatalystChecks.java").read_text(encoding="utf-8").replace("@TEST_NAMESPACE@", namespace)


def _project(workspace: Path) -> None:
    resources = "src/main/resources"
    assets = f"{resources}/assets/{MOD_ID}"
    java = f"src/main/java/{PACKAGE.replace('.', '/')}"
    meta = _metadata(ModMetadata("", authors="sparroha (original Mimicry)",
                                 description="Five measured Mimichite crafting recipes from Mimicry 1.3.9; not the full mod."),
                     MOD_ID, "Mimicry Catalyst (Preview)", VERSION, "Original Mimicry license applies (local conversion)")
    write_workspace(workspace, {
        "settings.gradle": f"rootProject.name = '{MOD_ID}'\n",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "build.gradle": _build_gradle(MOD_ID, PACKAGE, VERSION) + f"""
tasks.named('jar') {{
    archiveFileName = '{ARCHIVE}'
    exclude '{PACKAGE.replace('.', '/')}/CatalystChecks.class', 'data/{MOD_ID}/structure/empty.nbt'
}}
neoForge.runs.create('gameTestServer') {{
    type = 'gameTestServer'
    gameDirectory = project.file('run')
    systemProperty 'neoforge.enabledGameTestNamespaces', '{MOD_ID}'
    systemProperty 'mcmodup.gameReport', layout.buildDirectory.file('gametest.xml').get().asFile.absolutePath
}}
""",
        f"{resources}/META-INF/neoforge.mods.toml": meta.replace(f"[{NEOFORGE_VERSION},21.2)", f"[{NEOFORGE_VERSION}]"),
        f"{resources}/data/{MOD_ID}/structure/empty.nbt": empty_test_structure(),
        f"{assets}/lang/en_us.json": _json({f"item.{MOD_ID}.mimichite": "Mimichite"}),
        f"{assets}/models/item/mimichite.json": _json({"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD_ID}:item/mimichite"}}),
        f"{java}/CatalystMod.java": (TEMPLATES / "mimicry_catalyst/CatalystMod.java").read_bytes(),
        f"{java}/CatalystChecks.java": checks(MOD_ID),
        **{f"{resources}/data/{MOD_ID}/recipe/{name}.json": _json({
            "type": "minecraft:crafting_shapeless", "category": "misc",
            "ingredients": [{"item": f"{MOD_ID}:mimichite"}, {"item": f"minecraft:{name}"}],
            "result": {"id": f"minecraft:{result}", "count": count},
        }) for name, (result, count) in RECIPES.items()},
    })


def prepare(output: Path) -> Path:
    output = output.expanduser()
    if output.exists() or output.is_symlink():
        raise InputError("Choose a new directory for the Mimicry catalyst recipe")
    output.mkdir(parents=True, exist_ok=False)
    try:
        source = output / "mimicry-catalyst-src.zip"
        with tempfile.TemporaryDirectory(prefix="mcmodup-mimicry-catalyst-") as temp:
            workspace = Path(temp) / "project"
            _project(workspace)
            with ZipFile(source, "x") as archive:
                for path in sorted(workspace.rglob("*")):
                    if path.is_file():
                        member = ZipInfo(path.relative_to(workspace).as_posix())
                        member.create_system = 3
                        member.external_attr = (0o100755 if member.filename == "gradlew" else 0o100644) << 16
                        archive.writestr(member, path.read_bytes(), compress_type=ZIP_DEFLATED)
        recipe = {"legacy_sha256": LEGACY_SHA256, "source_zip": source.name, "source_sha256": file_digest(source),
                  "ids": {MOD_ID: VERSION}, "legacy_ids": {"mimicry": MOD_ID}, "neoforge": f"[{NEOFORGE_VERSION}]",
                  "artifact": f"build/libs/{ARCHIVE}", "tasks": ["jar", "runGameTestServer"],
                  "game_report": "build/gametest.xml", "minimum_tests": 2,
                  "features": {"Five original catalyst outputs": "measuredmimichiterecipes",
                               "Shapeless inputs, negative controls and no crafting remainders": "requiresmimichiteandoneingredient"},
                  "status": "preview", "standalone_preview": True, "limitations": [
                      "Five measured default crafting recipes, using explicit inputs. No dynamic Ore Dictionary, furnace recipe scanning, other mod ingredients or configurable recipes.",
                      "Mimichite is creative-only. No ore, world generation, orbs, storage blocks, Upheaver or other items.",
                      "Keeps one original icon; no other assets, client visual verification, old saves or legacy add-on support."],
                  "import_assets": {LEGACY_TEXTURE: f"src/main/resources/{TEXTURE}"},
                  "asset_map": {LEGACY_TEXTURE: {"path": TEXTURE}}}
        manifest = output / "mimicry-catalyst.json"
        manifest.write_text(_json({"schema_version": 1, "ports": [recipe]}), encoding="utf-8")
        return manifest.resolve()
    except BaseException:
        shutil.rmtree(output)
        raise
