package mcmodup.thaumcraftcheck;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import thaumcraft.api.ThaumcraftApi;
import thaumcraft.api.crafting.InfusionRecipe;
import thaumic.tinkerer.common.core.handler.ConfigHandler;
import thaumic.tinkerer.common.dim.EnumOreFrequency;
import thaumic.tinkerer.common.dim.OreClusterGenerator;

@GameTestHolder(ThaumcraftPackSmoke.ID)
@PrefixGameTestTemplate(false)
public final class TinkererPackSmoke {
    @GameTest(template = "empty")
    public static void tinkererRegistersAnInfusionUsingCraftedTiles(GameTestHelper helper) {
        helper.assertTrue(ModList.get().isLoaded("thaumictinkerer"), "The staged native add-on was not loaded");
        var enchanter = ResourceLocation.parse("thaumictinkerer:enchanter");
        var tile = ResourceLocation.parse("thaumcraft:obsidian_tile");
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(enchanter) && BuiltInRegistries.ITEM.containsKey(tile),
            "The staged JARs must register their recipe items");
        var recipe = ThaumcraftApi.getInfusionRecipe(new ItemStack(BuiltInRegistries.ITEM.get(enchanter)));
        helper.assertTrue(recipe != null && recipe.getRecipeInput().is(Items.ENCHANTING_TABLE),
            "The add-on did not register its osmotic enchanter through the base API");
        var crafted = new ItemStack(BuiltInRegistries.ITEM.get(tile));
        var ingredients = Arrays.stream(recipe.getComponents()).filter(ingredient -> ingredient.is(crafted.getItem())).toList();
        helper.assertTrue(ingredients.size() == 5 && ingredients.stream().allMatch(stack ->
            InfusionRecipe.areItemStacksEqual(crafted, stack, true)),
            "The actual staged add-on must accept crafted obsidian tiles without a mismatched block-state component");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void bedrockTerrainUsesTheLegacyPopulationSeed(GameTestHelper helper) {
        var key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("thaumictinkerer:bedrock"));
        var level = helper.getLevel().getServer().getLevel(key);
        if (level == null) throw new IllegalStateException("Missing the staged Bedrock dimension");
        checkLegacyChunk(helper, level, -4001, 3201);
        checkLegacyChunk(helper, level, 4001, -3201);
        helper.succeed();
    }

    private static void checkLegacyChunk(GameTestHelper helper, ServerLevel level, int firstX, int chunkZ) {
        // A cluster can land wholly in a neighbour. Pick a nonempty witness without depending on a fixed server seed.
        for (int chunkX = firstX; chunkX < firstX + 64; chunkX += 2) {
            var expected = oldPopulation(level.getSeed(), chunkX, chunkZ);
            if (expected.size() < 32) continue;
            // All source chunks that could contribute must pass their features step before checking the result.
            for (int z = chunkZ - 1; z <= chunkZ; z++) {
                for (int x = chunkX - 1; x <= chunkX; x++) level.getChunk(x, z);
            }
            var chunk = level.getChunk(chunkX, chunkZ);
            var pos = new BlockPos.MutableBlockPos();
            for (int y = 0; y < 256; y++) {
                for (int z = chunkZ * 16; z < chunkZ * 16 + 16; z++) {
                    for (int x = chunkX * 16; x < chunkX * 16 + 16; x++) {
                        pos.set(x, y, z);
                        var states = expected.get(pos);
                        var actual = chunk.getBlockState(pos);
                        if (states == null ? !actual.is(Blocks.BEDROCK) : !states.contains(actual)) {
                            helper.fail("Wrong Bedrock block at " + pos + " for world seed " + level.getSeed()
                                    + ": got " + actual + ", expected " + (states == null ? "bedrock" : states));
                            return;
                        }
                    }
                }
            }
            return;
        }
        helper.fail("No nonempty legacy ore witness for world seed " + level.getSeed());
    }

    private static Map<BlockPos, Set<BlockState>> oldPopulation(long seed, int targetX, int targetZ) {
        // Oracle: ChunkProviderBedrock.populate at GTNH 28f4def uses the world seed and java.util.Random.
        // The existing port supplies the vein geometry and current ore tags; this checks the exact random stream
        // and placement from the seed, not the order of legacy Forge OreDictionary providers.
        var original = new Random(seed);
        long xMultiplier = original.nextLong() / 2 * 2 + 1;
        long zMultiplier = original.nextLong() / 2 * 2 + 1;
        Map<BlockPos, Set<BlockState>> result = new HashMap<>();
        for (int z = targetZ - 1; z <= targetZ; z++) {
            for (int x = targetX - 1; x <= targetX; x++) {
                var random = new LegacyRandomSource((x * xMultiplier + z * zMultiplier) ^ seed);
                random.nextInt(16);
                random.nextInt(128);
                random.nextInt(16);
                Map<BlockPos, BlockState> placed = new HashMap<>();
                OreClusterGenerator.generate(random, x, z, ConfigHandler.bedrockOreDensity,
                    rand -> EnumOreFrequency.getRandomOre(rand, ConfigHandler.bedrockOreBlacklist),
                    new OreClusterGenerator.ClusterWorld<BlockState>() {
                        @Override public boolean isReplaceable(int bx, int by, int bz) {
                            return (bx >> 4) == targetX && (bz >> 4) == targetZ;
                        }

                        @Override public void place(int bx, int by, int bz, BlockState state) {
                            placed.putIfAbsent(new BlockPos(bx, by, bz), state);
                        }
                    });
                // Two origins can overlap; the first populated chunk wins in both versions. Don't assert a scheduler order.
                placed.forEach((pos, state) -> result.computeIfAbsent(pos, p -> new HashSet<>()).add(state));
            }
        }
        return result;
    }
}
