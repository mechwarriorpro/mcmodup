"""Prepare the experimental native Thaumcraft + Thaumic Tinkerer source pair.

No JARs or third-party source archives are distributed here. Repack a locally
supplied, verified source ZIP with reviewed NeoForge build and client changes.
"""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
from zipfile import ZipFile, ZipInfo

from mcmodup.inputs import InputError
from mcmodup.source_ports import digest

TC_COMMIT = "299a9c01cc4904597381f96cc4bbb8cb9ff667de"
TC_SOURCE_HASH = "6b3fa7bc6fd73d217d6fbba7ce5819a9d3760cffb5fecac3cde953588de740e4"
TT_COMMIT = "c2e67665446ffada85ec9342384f578875cabb12"
TT_SOURCE_HASH = "6ef30454521bfc65a894d3425784a3ccdd9931701edd50f0c483ef9cc3322ded"
TT_ROOT = f"Thaumic-Tinkerer-1.21.1-{TT_COMMIT}"
TT_LEGACY_HASH = "6aee6009a0ae0fa7929258f34530b1dc01c1245bfccae400fd673419ebe812b3"
EXAMPLES = Path(__file__).resolve().parents[1] / "examples"
TC_RECIPE = EXAMPLES / "thaumcraft4-reload-preview.json"
BEDROCK_EFFECTS = EXAMPLES / "port-patches/thaumic-tinkerer/BedrockEffects.java"


def change(body: str, old: str, new: str) -> str:
    if body.count(old) != 1:
        raise InputError("Pinned Tinkerer Gradle input does not match the reviewed build wiring")
    return body.replace(old, new, 1)


def asset_mappings(upstream: ZipFile) -> dict:
    """Check the upstream migration table bundled in the pinned source archive."""
    provenance = json.loads(upstream.read(f"{TT_ROOT}/scripts/porting/tt_assets_map.json"))
    prefix = f"{TT_ROOT}/common/src/main/resources/"
    result = {}
    for old, new in provenance["mapping"].items():
        checksum = hashlib.sha256(upstream.read(prefix + new)).hexdigest()
        if checksum != provenance["files"][old]:
            raise InputError(f"Native Tinkerer asset disagrees with upstream migration table: {new}")
        result[old] = {"path": new}
    for old, row in provenance["duplicates"].items():
        if hashlib.sha256(upstream.read(prefix + row["new"])).hexdigest() != row["sha256"]:
            raise InputError(f"Native Tinkerer duplicate disagrees with upstream migration table: {old}")
        result[old] = {"path": row["new"]}
    for old, new in provenance["lang_files"].items():
        result[old] = {"path": new, "sha256": hashlib.sha256(upstream.read(prefix + new)).hexdigest()}
    return result


def prepare(archive: Path, output: Path) -> Path:
    if archive.stat().st_size > 8 * 1024 * 1024 or digest(archive) != TT_SOURCE_HASH:
        raise InputError("Need the exact Thaumic Tinkerer alpha.7 source ZIP")
    output.mkdir(parents=True, exist_ok=False)
    try:
        local = output / "tinkerer-neoforge-source.zip"
        root_script = f"{TT_ROOT}/build.gradle"
        loader_script = f"{TT_ROOT}/neoforge/build.gradle"
        client = f"{TT_ROOT}/neoforge/src/main/java/thaumic/tinkerer/neoforge/client/"
        entry = client + "ThaumicTinkererNeoForgeClient.java"
        dimension = f"{TT_ROOT}/common/src/main/resources/data/thaumictinkerer/dimension_type/bedrock.json"
        dimension_test = f"{TT_ROOT}/common/src/test/java/thaumic/tinkerer/kamiworld/KamiWorldResourcesTest.java"
        with ZipFile(archive) as upstream, ZipFile(local, "x") as scratch:
            assets = asset_mappings(upstream)
            neo = upstream.read(loader_script).decode("utf-8")
            neo = change(neo, ''' exclusiveContent {
  forRepository { mavenLocal() }
  filter { includeGroup 'thaumcraft' }
 }
''', '')
            neo = change(neo, ''' modImplementation "dev.architectury:architectury-neoforge:$rootProject.architectury_api_version"
 modImplementation "thaumcraft:Thaumcraft-neoforge:$rootProject.thaumcraft_version"
''', ''' modImplementation fileTree(dir: providers.gradleProperty('mcmodupDependencyDir').get(), include: ['*.jar'])
''')
            replacements = {root_script: '''// NeoForge-only build. No Fabric or mavenLocal dependency probe.
plugins { id 'dev.architectury.loom' version '1.11.458' apply false }
'''.encode(), loader_script: neo.encode(),
                entry: change(upstream.read(entry).decode(), "        NeoForgeClientHooks.register(bus);\n",
                    "        NeoForgeClientHooks.register(bus);\n        bus.addListener(BedrockEffects::register);\n").encode(),
                dimension: change(upstream.read(dimension).decode(), '"effects": "minecraft:overworld"',
                    '"effects": "thaumictinkerer:bedrock"').encode(),
                dimension_test: change(upstream.read(dimension_test).decode(),
                    '"minecraft:overworld", type.get("effects").getAsString(), "the sky of the beach biome and the overworld"',
                    '"thaumictinkerer:bedrock", type.get("effects").getAsString(), "the legacy Bedrock fog and cloud provider"').encode(),
            }
            for info in upstream.infolist():
                scratch.writestr(info, replacements.get(info.filename, upstream.read(info)))
            scratch.writestr(ZipInfo(client + "BedrockEffects.java"), BEDROCK_EFFECTS.read_bytes())
        manifest = json.loads(TC_RECIPE.read_text(encoding="utf-8"))
        [base] = manifest["ports"]
        # Do not pair the original alpha.24 release with the add-on's broken obsidian tile recipe.
        base.update(source_url=f"https://codeload.github.com/acak1221/Thaumcraft-1.21.1/zip/{TC_COMMIT}",
                    source_sha256=TC_SOURCE_HASH, source_root=f"Thaumcraft-1.21.1-{TC_COMMIT}", minimum_tests=543)
        base["features"]["Legacy obsidian tile ingredients use the native item"] = "legacyblockstacksmatchthestacksplayershold"
        base["limitations"].append("This pair uses a pinned post-release Reload commit with the obsidian tile fix, not the alpha.24 release tag.")
        manifest["ports"].append({
            "legacy_sha256": TT_LEGACY_HASH, "source_zip": local.name,
            "source_sha256": digest(local), "source_root": TT_ROOT,
            "ids": {"thaumictinkerer": "0.1.0-alpha.7"}, "neoforge": "[21.1.259,21.2)",
            "target_property": "neoforge_version",
            "dependencies": [
                {"id": "architectury", "range": "[13.0.11,)"},
                {"id": "thaumcraft", "range": "*"},
                *({"id": key, "range": "*", "type": "optional"} for key in ("emi", "jei", "jade")),
            ],
            "artifact": "neoforge/build/libs/ThaumicTinkerer-neoforge-0.1.0-alpha.7.jar",
            "tasks": [":neoforge:assemble", ":neoforge:runGameTest"],
            "game_report": "neoforge/build/tinkerer-gametest-results.xml", "minimum_tests": 371,
            "features": {
                "Add-on research": "paritygametests.researchmatchestheoriginal",
                "Add-on recipes": "paritygametests.recipesmatchtheoriginal",
                "Add-on aspect tags": "paritygametests.objecttagsmatchtheoriginal",
                "Osmotic Enchanter works with native wand": "enchantmentsgametests.theenchanterenchantsatoolfromawandbetweensixpillars",
            },
            "asset_map": assets,
            "require_asset_coverage": True,
            "status": "preview",
            "limitations": [
                "Matches GTNH Thaumic Tinkerer 2.13.0 only. Include the exact original Thaumcraft JAR and select the base mod to build this pair.",
                "This repack restores the original Bedrock base fog and cloud height on NeoForge. It does not reproduce the other old sky and lighting hooks.",
                "Known omissions include ComputerCraft, OpenComputers and IC2 integrations; old worlds, client parity and other add-ons are unverified.",
                "The add-on is CC BY-NC-SA 3.0. Thaumcraft code and assets have separate terms. Keep these outputs local.",
            ],
        })
        recipe = output / "thaumcraft-tinkerer-preview.json"
        recipe.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        return recipe
    except BaseException:
        shutil.rmtree(output, ignore_errors=True)
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source_archive", type=Path)
    parser.add_argument("output_directory", type=Path)
    args = parser.parse_args()
    print(prepare(args.source_archive, args.output_directory))
