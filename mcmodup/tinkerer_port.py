"""Prepare the experimental native Thaumcraft + Thaumic Tinkerer source pair.

No JARs or third-party source archives are distributed here. Repack a locally
supplied, verified source ZIP with reviewed NeoForge build and client changes.
"""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
from typing import Callable
from zipfile import ZipFile, ZipInfo

from .inputs import InputError
from .source_ports import digest

TC_COMMIT = "299a9c01cc4904597381f96cc4bbb8cb9ff667de"
TC_SOURCE_HASH = "6b3fa7bc6fd73d217d6fbba7ce5819a9d3760cffb5fecac3cde953588de740e4"
TT_COMMIT = "c2e67665446ffada85ec9342384f578875cabb12"
TT_SOURCE_HASH = "6ef30454521bfc65a894d3425784a3ccdd9931701edd50f0c483ef9cc3322ded"
TT_ROOT = f"Thaumic-Tinkerer-1.21.1-{TT_COMMIT}"
TT_LEGACY_HASH = "6aee6009a0ae0fa7929258f34530b1dc01c1245bfccae400fd673419ebe812b3"
TINKERER_VERSION = "0.1.0-alpha.7-mcmodup.3"
ENDER_STORAGE_VERSION = "2.13.0.191"
CODE_CHICKEN_LIB_VERSION = "4.6.0.521"
# Modrinth release identities, filenames, sizes and hashes for the staged tests. The build uses
# the same Maven version IDs for its compile-only API when the pack doesn't contain the mods.
ENDER_STORAGE_INPUTS = {
    "enderstorage": ("BbrHg80P", "iDxzdaLT", f"EnderStorage-1.21.1-{ENDER_STORAGE_VERSION}.jar", 264089,
                     "4a65d6c2d098e4db61237ff83b68f7d03d5dcd16572c6c12d159c9c1f2f49c7abcc1792432b0bd88589549cec8a4705b292492fbe1913b624537c2e4fbc968f2"),
    "codechickenlib": ("2gq0ALnz", "tQEcfeph", f"CodeChickenLib-1.21.1-{CODE_CHICKEN_LIB_VERSION}.jar", 1562073,
                       "ad315047f7a441a6347b0cab336bd0dda340d2fd221836e168831f7a309e63ad3fe98407b71c9bb3cbbfd6a95670a38b9e4c23c2a327edca4b497664f07c4e9d"),
}
SOURCE_TEMPLATES = Path(__file__).with_name("templates") / "source-ports"
TC_RECIPE = SOURCE_TEMPLATES / "thaumcraft4-reload-preview.json"


def change(body: str, old: str, new: str) -> str:
    if body.count(old) != 1:
        raise InputError(f"Expected exactly one patch anchor in the pinned source: {old.strip().splitlines()[0]!r}")
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


def fire_with_native_flammability(text: str) -> bytes:
    """Restore the positional/side-sensitive Forge callback for other mods' blocks."""
    text = change(text, "import net.minecraft.core.Direction;", "import net.minecraft.core.Direction;\nimport net.minecraft.core.registries.BuiltInRegistries;")
    text = change(text, "return flammabilityOf(level.getBlockState(pos));", """return getBlockFlammability(level, pos, Direction.UP);
    }

    public int getBlockFlammability(BlockGetter level, BlockPos pos, Direction face) {
        BlockState state = level.getBlockState(pos);
        // Keep the original vanilla burn chances and the imbued fire's own target/result overrides.
        if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft")) {
            return flammabilityOf(state);
        }
        if (isTransmutationTarget(state)) return 100;
        if (isTransmutationResult(state)) return 0;
        return state.getFlammability(level, pos, face);
    }

    @Override
    public boolean canCatchFire(BlockGetter level, BlockPos pos, Direction face) {
        return getBlockFlammability(level, pos, face) > 0;""")
    # Legacy BlockFireBase.tryCatchFire passes the direction of the spread, not its opposite.
    text = change(text, "this.tryCatchFire(world, pos.relative(dir), 300 + strMod, rand, blockMeta);",
                  "this.tryCatchFire(world, pos.relative(dir), 300 + strMod, rand, blockMeta, dir);")
    return change(text, """private void tryCatchFire(Level world, BlockPos pos, int strength, RandomSource rand, int meta) {
        if (!world.isLoaded(pos)) return;
        final int blockFlammability = getBlockFlammability(world, pos);""", """private void tryCatchFire(Level world, BlockPos pos, int strength, RandomSource rand, int meta, Direction spread) {
        if (!world.isLoaded(pos)) return;
        final int blockFlammability = getBlockFlammability(world, pos, spread);""").encode()


def ender_storage_overlays(upstream: ZipFile, neo: str) -> tuple[str, dict[str, bytes]]:
    focus_path = f"{TT_ROOT}/common/src/main/java/thaumic/tinkerer/common/item/foci/ItemFocusEnderChest.java"
    focus = upstream.read(focus_path).decode()
    focus = change(focus, "import net.minecraft.network.chat.Component;", """import dev.architectury.platform.Platform;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;""")
    focus = change(focus, "import net.minecraft.world.item.Items;", "import net.minecraft.world.item.Items;\nimport net.minecraft.world.item.TooltipFlag;")
    focus = change(focus, "import thaumic.tinkerer.common.research.TTResearchItem;",
                   "import thaumic.tinkerer.common.research.TTResearchItem;\nimport thaumic.tinkerer.compat.enderstorage.EnderStorageCompat;")
    focus = change(focus, """ * <p>The window is the vanilla one ({@code ChestMenu} of three rows over the player's ender chest). The EnderStorage
 * branch of 1.7.10 (a different mod's chests) is not ported: that mod does not exist for 1.21.1.</p>""",
                   """ * <p>With Ender Storage installed, sneak-use binds its chest or unbinds on obsidian. An unlinked
 * focus, or a world without that optional mod, opens the vanilla Ender Chest.</p>""")
    focus = change(focus, "    @Override\n    public ItemStack onFocusRightClick", """    public static boolean enderStorageLoaded() {
        return Platform.isModLoaded("enderstorage");
    }

    @Override
    public ItemStack onFocusRightClick""")
    focus = change(focus, "        if (WandHelper.consumeAllVis(wandstack, player, VIS_USAGE, true, false)) {", """        if (enderStorageLoaded() && (level.isClientSide || !(player instanceof ServerPlayer server)
                || EnderStorageCompat.tryCast(wandstack, server, VIS_USAGE))) return wandstack;
        if (WandHelper.consumeAllVis(wandstack, player, VIS_USAGE, true, false)) {""")
    focus = change(focus, '        return "TTEC" + super.getSortingHelper(focusstack);', """        return enderStorageLoaded() ? EnderStorageCompat.sortingKey(focusstack)
                : "TTEC" + super.getSortingHelper(focusstack);""")
    focus = change(focus, "    @Override\n    public AspectList getVisCost", """    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flags) {
        super.appendHoverText(stack, context, tooltip, flags);
        if (enderStorageLoaded()) EnderStorageCompat.addTooltip(stack, tooltip);
    }

    @Override
    public AspectList getVisCost""")
    focus = change(focus, 'new ResearchPage("0"),', 'new ResearchPage(enderStorageLoaded() ? "ES" : "0"),')
    oracle = f"{TT_ROOT}/common/src/gameTest/java/thaumic/tinkerer/testing/ParityRuntimeChecks.java"
    parity = change(upstream.read(oracle).decode(), 'case "mod":\n                return false;',
                    'case "mod":\n                return c.expr().equals("LoadedMods.enderStorageLoaded")\n'
                    '                        && thaumic.tinkerer.common.item.foci.ItemFocusEnderChest.enderStorageLoaded();')
    metadata = f"{TT_ROOT}/neoforge/src/main/resources/META-INF/neoforge.mods.toml"
    toml = upstream.read(metadata) + f'''
[[dependencies.thaumictinkerer]]
modId="enderstorage"
type="optional"
versionRange="[{ENDER_STORAGE_VERSION}]"
ordering="AFTER"
side="BOTH"
'''.encode()
    es, ccl = (":".join(ENDER_STORAGE_INPUTS[name][:2]) for name in ("enderstorage", "codechickenlib"))
    neo = change(neo, "dependencies {\n minecraft", """// Reuse exactly the selected pack libraries when present; absent integrations are compile-only.
def buildInputs = fileTree(dir: providers.gradleProperty('mcmodupDependencyDir').get(), include: ['*.jar'])
def provides = { name -> buildInputs.files.any { jar ->
 new java.util.zip.ZipFile(jar).withCloseable { it.getEntry(name) != null }
} }

dependencies {
 minecraft""")
    neo = change(neo, " modImplementation fileTree(dir: providers.gradleProperty('mcmodupDependencyDir').get(), include: ['*.jar'])",
                 f""" modImplementation buildInputs
 if (!provides('codechicken/enderstorage/api/Frequency.class')) modCompileOnly 'maven.modrinth:{es}'
 if (!provides('codechicken/lib/colour/EnumColour.class')) modCompileOnly 'maven.modrinth:{ccl}'""")
    bridge = f"{TT_ROOT}/compat/src/main/java/thaumic/tinkerer/compat/enderstorage/EnderStorageCompat.java"
    deviations = f"{TT_ROOT}/docs/deviations/Foci.md"
    doc = change(upstream.read(deviations).decode(), """The EnderStorage branch of 1.7.10 (`EnderStorageFunctions`, the research page
`ES`) is not ported: that mod does not exist for 1.21.1; the lang key `FOCUS_ENDER_CHEST.ES` stays unused.""",
                 f"""The optional Ender Storage {ENDER_STORAGE_VERSION} branch restores the `ES` page and linking. Sneak-use
an Ender Storage chest to link its colours and owner UUID, or obsidian to reset to vanilla storage, without spending vis.
The focus stores a new native stack tag; this does not migrate legacy wands or Ender Storage data.""")
    doc = change(doc, "the EnderStorage page and branch, the Botania and Blood Magic", "the Botania and Blood Magic")
    return neo, {focus_path: focus.encode(), oracle: parity.encode(), metadata: toml, deviations: doc.encode(),
                 bridge: (SOURCE_TEMPLATES / "EnderStorageCompat.java").read_bytes()}


def prepare(archive: Path, output: Path) -> Path:
    if archive.stat().st_size > 8 * 1024 * 1024 or digest(archive) != TT_SOURCE_HASH:
        raise InputError("Need the exact Thaumic Tinkerer alpha.7 source ZIP")
    return prepare_pair(archive, output)


def prepare_pair(archive: Path, output: Path, *, version: str = TINKERER_VERSION,
                 configure: Callable[[Path, dict, dict], None] | None = None) -> Path:
    """Wire an already-verified add-on source (or its reviewed CC backport) to the pair recipe."""
    output.mkdir(parents=True, exist_ok=False)
    try:
        local = output / "tinkerer-neoforge-source.zip"
        root_script = f"{TT_ROOT}/build.gradle"
        loader_script = f"{TT_ROOT}/neoforge/build.gradle"
        client = f"{TT_ROOT}/neoforge/src/main/java/thaumic/tinkerer/neoforge/client/"
        entry = client + "ThaumicTinkererNeoForgeClient.java"
        dimension = f"{TT_ROOT}/common/src/main/resources/data/thaumictinkerer/dimension_type/bedrock.json"
        dimension_test = f"{TT_ROOT}/common/src/test/java/thaumic/tinkerer/kamiworld/KamiWorldResourcesTest.java"
        worldgen = f"{TT_ROOT}/common/src/main/java/thaumic/tinkerer/common/dim/"
        feature = worldgen + "BedrockOreClustersFeature.java"
        imbued_fire = f"{TT_ROOT}/common/src/main/java/thaumic/tinkerer/common/block/fire/BlockFireBase.java"
        properties = f"{TT_ROOT}/gradle.properties"
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
            neo, ender_storage_files = ender_storage_overlays(upstream, neo)
            replacements = {root_script: '''// NeoForge-only build. No Fabric or mavenLocal dependency probe.
plugins { id 'dev.architectury.loom' version '1.11.458' apply false }
'''.encode(), loader_script: neo.encode(),
                entry: change(upstream.read(entry).decode(), "        NeoForgeClientHooks.register(bus);\n",
                    "        NeoForgeClientHooks.register(bus);\n        bus.addListener(BedrockEffects::register);\n").encode(),
                dimension: change(upstream.read(dimension).decode(), '"effects": "minecraft:overworld"',
                    '"effects": "thaumictinkerer:bedrock"').encode(),
                dimension_test: change(upstream.read(dimension_test).decode(),
                    '"minecraft:overworld", type.get("effects").getAsString(), "the sky of the beach biome and the overworld"',
                    '"thaumictinkerer:bedrock", type.get("effects").getAsString(), "the legacy Bedrock effects provider"').encode(),
                feature: change(upstream.read(feature).decode(),
                    "OreClusterGenerator.generate(context.random(), chunkX, chunkZ, density,",
                    "var populationRandom = LegacyBedrockSeed.population(level.getSeed(), chunkX, chunkZ);\n"
                    "        OreClusterGenerator.generate(populationRandom, chunkX, chunkZ, density,").encode(),
                imbued_fire: fire_with_native_flammability(upstream.read(imbued_fire).decode()),
                properties: change(upstream.read(properties).decode(), "mod_version=0.1.0-alpha.7\n", f"mod_version={version}\n").encode(),
                **ender_storage_files,
            }
            for info in upstream.infolist():
                scratch.writestr(info, replacements.pop(info.filename) if info.filename in replacements else upstream.read(info))
            for path, data in sorted(replacements.items()):
                scratch.writestr(ZipInfo(path), data)
            for path, name in ((client, "BedrockEffects.java"), (worldgen, "LegacyBedrockSeed.java")):
                scratch.writestr(ZipInfo(path + name), (SOURCE_TEMPLATES / name).read_bytes())
        manifest = json.loads(TC_RECIPE.read_text(encoding="utf-8"))
        [base] = manifest["ports"]
        # Do not pair the original alpha.24 release with the add-on's broken obsidian tile recipe.
        base.update(source_url=f"https://codeload.github.com/acak1221/Thaumcraft-1.21.1/zip/{TC_COMMIT}",
                    source_sha256=TC_SOURCE_HASH, source_root=f"Thaumcraft-1.21.1-{TC_COMMIT}", minimum_tests=543)
        base["features"]["Legacy obsidian tile ingredients use the native item"] = "legacyblockstacksmatchthestacksplayershold"
        base["limitations"].append("This pair uses a pinned post-release Reload commit with the obsidian tile fix, not the alpha.24 release tag.")
        add_on = {
            "legacy_sha256": TT_LEGACY_HASH, "source_zip": local.name,
            "source_sha256": digest(local), "source_root": TT_ROOT,
            "ids": {"thaumictinkerer": version}, "neoforge": "[21.1.259,21.2)",
            "target_property": "neoforge_version",
            "dependencies": [
                {"id": "architectury", "range": "[13.0.11,)"},
                {"id": "thaumcraft", "range": "*"},
                {"id": "enderstorage", "range": f"[{ENDER_STORAGE_VERSION}]", "type": "optional"},
                *({"id": key, "range": "*", "type": "optional"} for key in ("emi", "jei", "jade")),
            ],
            "artifact": f"neoforge/build/libs/ThaumicTinkerer-neoforge-{version}.jar",
            "tasks": [":neoforge:assemble", ":neoforge:runGameTest"],
            "game_report": "neoforge/build/tinkerer-gametest-results.xml", "minimum_tests": 371,
            "features": {
                "Add-on research": "paritygametests.researchmatchestheoriginal",
                "Add-on recipes": "paritygametests.recipesmatchtheoriginal",
                "Add-on aspect tags": "paritygametests.objecttagsmatchtheoriginal",
                "Osmotic Enchanter works with native wand": "enchantmentsgametests.theenchanterenchantsatoolfromawandbetweensixpillars",
                "Bedrock terrain and ore clusters": "kamiworldgametests.bedrockdimensionissolidbedrockwithoreveins",
                "Bedrock density and blacklist": "kamiworldgametests.bedrockoreconfigdecidesdensityandblacklist",
                "Imbued fire vanilla flammability and transmutation overrides": "simpleblocksgametests.thefiresknowtheflammabilityoftheoriginal",
            },
            "asset_map": assets,
            "require_asset_coverage": True,
            "status": "preview",
            "limitations": [
                "Matches GTNH Thaumic Tinkerer 2.13.0 only. Include the exact original Thaumcraft JAR and select the base mod to build this pair.",
                "Bedrock uses the legacy ore-population seed and full-bright lightmap input, base fog and cloud height. "
                "Modern tag order, overlapping cluster winners and the final client renderer can differ.",
                "Known omissions include ComputerCraft, OpenComputers and IC2 integrations; old worlds, client parity and other add-ons are unverified.",
                f"Optional Ender Storage {ENDER_STORAGE_VERSION} restores Ender Rift links for new worlds. "
                "Install it and compatible CodeChicken Lib in the target pack to use it; no legacy links or inventories are migrated.",
                "The add-on is CC BY-NC-SA 3.0. Thaumcraft code and assets have separate terms. Keep these outputs local.",
            ],
        }
        if configure is not None:
            configure(output, base, add_on)
        manifest["ports"].append(add_on)
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
