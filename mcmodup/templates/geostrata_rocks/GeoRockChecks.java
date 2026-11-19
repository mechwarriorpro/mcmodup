package @PACKAGE@;

import java.io.File;
import java.util.ArrayList;
import javax.xml.parsers.ParserConfigurationException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.JUnitLikeTestReporter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("@NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class GeoRockChecks {
    private static final String ID = "geostrata_rocks";
    private static final String[] SHAPES = {"", "_cobblestone", "_brick", "_round", "_fitted", "_tile", "_engraved", "_inscribed"};
    private record Rock(String name, float hardness, float resistance, String tier) {}
    private static final Rock[] ROCKS = {
@ROCKS@
    };

    static {
        String report = System.getProperty("mcmodup.gameReport");
        if (report != null) {
            try {
                GlobalTestReporter.replaceWith(new JUnitLikeTestReporter(new File(report)));
            } catch (ParserConfigurationException error) {
                throw new IllegalStateException(error);
            }
        }
    }

    @GameTest(template = "rock_room")
    public static void measuredRockFamilies(GameTestHelper test) {
        var mods = ModList.get();
        test.assertTrue(mods.isLoaded(ID) && !mods.isLoaded("geostrata") && !mods.isLoaded("dragonapi") && !mods.isLoaded("legacyconnector"),
                "Expected only the bounded native GeoStrata rock preview");
        var level = test.getLevel();
        var pos = test.absolutePos(BlockPos.ZERO);
        var iron = new ItemStack(Items.IRON_PICKAXE);
        var stone = new ItemStack(Items.STONE_PICKAXE);
        var wood = new ItemStack(Items.WOODEN_PICKAXE);
        for (Rock rock : ROCKS) for (String shape : SHAPES) {
            String name = rock.name() + shape;
            var key = id(name);
            test.assertTrue(BuiltInRegistries.BLOCK.containsKey(key), "Unregistered " + key);
            var block = BuiltInRegistries.BLOCK.get(key);
            test.setBlock(BlockPos.ZERO, block);
            var state = level.getBlockState(pos);
            test.assertTrue(state.is(block) && state.is(BlockTags.MINEABLE_WITH_PICKAXE), "Wrong rock state for " + key);
            test.assertTrue(state.getDestroySpeed(level, pos) == rock.hardness() && block.getExplosionResistance() == rock.resistance(),
                    "Wrong block properties for " + name);
            test.assertTrue(iron.isCorrectToolForDrops(state) && stone.isCorrectToolForDrops(state) == !rock.tier().equals("iron") &&
                    wood.isCorrectToolForDrops(state) == rock.tier().equals("wood"), "Wrong pickaxe tier for " + name);
            var drops = Block.getDrops(state, level, pos, null, null, iron);
            var drop = item(shape.isEmpty() ? rock.name() + "_cobblestone" : name);
            test.assertTrue(drops.size() == 1 && drops.getFirst().is(drop.getItem()) && drops.getFirst().getCount() == 1,
                    "Wrong drop for " + name + ": " + drops);
        }
        test.assertTrue(!BuiltInRegistries.BLOCK.containsKey(id("granite_connected")), "Do not replace connected rendering with a normal cube");
        test.succeed();
    }

    @GameTest(template = "rock_room")
    public static void measuredRockRecipes(GameTestHelper test) {
        var level = test.getLevel();
        for (Rock rock : ROCKS) {
            String name = rock.name();
            craft(test, name, "brick", 4, "SS", "SS");
            craft(test, name, "round", 4, "BB", "BB");
            craft(test, name, "fitted", 2, "BB");
            craft(test, name, "tile", 4, " S ", "S S", " S ");
            craft(test, name, "inscribed", 3, "B", "S", "B");
            craft(test, name, "engraved", 2, "SB", "BS");
            craft(test, name, "engraved", 2, "BS", "SB");
            var input = new SingleRecipeInput(item(name + "_cobblestone"));
            var smelt = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, input, level).orElseThrow();
            var result = smelt.value().assemble(input, level.registryAccess());
            test.assertTrue(smelt.id().equals(id(name + "_from_smelting")) && result.is(item(name).getItem()) && result.getCount() == 1,
                    "Wrong smelt output for " + name);
        }
        // The recipes require four blocks of the same rock, not a tag mixing rock families.
        var mixed = CraftingInput.of(2, 2, java.util.List.of(item("basalt"), item("granite"), item("granite"), item("granite")));
        test.assertTrue(level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, mixed, level).isEmpty(), "Mixed rock recipe unexpectedly matched");
        test.succeed();
    }

    @GameTest(template = "vein_room")
    public static void orePreservingVeins(GameTestHelper test) {
        var level = test.getLevel();
        var registries = level.registryAccess();
        var configured = registries.registryOrThrow(Registries.CONFIGURED_FEATURE);
        var placed = registries.registryOrThrow(Registries.PLACED_FEATURE);
        var biomes = registries.registryOrThrow(Registries.BIOME);
        var plains = biomes.get(Biomes.PLAINS.location());
        var desert = biomes.get(Biomes.DESERT.location());
        var snow = biomes.get(Biomes.SNOWY_PLAINS.location());
        var nether = biomes.get(Biomes.NETHER_WASTES.location());
        var end = biomes.get(Biomes.THE_END.location());
        for (Rock rock : ROCKS) {
            var key = id(rock.name() + "_vein");
            var vein = placed.get(key);
            test.assertTrue(vein != null && configured.get(key) != null, "Missing registered feature " + key);
            boolean hornfel = rock.name().equals("hornfel");
            boolean sediment = rock.name().equals("slate") || rock.name().equals("shale");
            test.assertTrue(hasVein(plains, vein) == !hornfel && hasVein(desert, vein) == !(hornfel || sediment) &&
                    hasVein(snow, vein) && !hasVein(nether, vein) && !hasVein(end, vein), "Wrong biome placement hooks for " + key);
        }
        var granite = configured.get(id("granite_vein"));
        var center = test.absolutePos(new BlockPos(9, 8, 9));
        var from = center.offset(-8, -6, -8);
        var to = center.offset(8, 5, 8);
        var generator = level.getChunkSource().getGenerator();
        for (var pos : BlockPos.betweenClosed(from, to)) level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        test.assertTrue(granite.place(level, generator, RandomSource.create(237), center), "No stone replaced by the staged granite feature");
        var replacement = BuiltInRegistries.BLOCK.get(id("granite"));
        var replaced = new ArrayList<BlockPos>();
        for (var pos : BlockPos.betweenClosed(from, to)) {
            if (level.getBlockState(pos).is(replacement)) {
                replaced.add(pos.immutable());
                level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            } else {
                test.assertTrue(level.getBlockState(pos).is(Blocks.STONE), "Unexpected generated state at " + pos);
            }
        }
        test.assertTrue(replaced.size() >= 4, "Need four known targets for the ore preservation comparison");
        Block[] exclusions = {Blocks.DIAMOND_ORE, Blocks.DEEPSLATE, Blocks.ANDESITE};
        for (int marker = 0; marker < exclusions.length; ++marker) {
            level.setBlock(replaced.get(marker), exclusions[marker].defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        test.assertTrue(granite.place(level, generator, RandomSource.create(237), center), "Excluded targets prevented valid placements");
        for (int marker = 0; marker < exclusions.length; ++marker) {
            test.assertTrue(level.getBlockState(replaced.get(marker)).is(exclusions[marker]), "Feature replaced " + exclusions[marker]);
        }
        test.assertTrue(level.getBlockState(replaced.get(3)).is(replacement), "Same seed did not replace the remaining plain stone");
        test.succeed();
    }

    private static boolean hasVein(Biome biome, PlacedFeature feature) {
        var steps = biome.getGenerationSettings().features();
        int step = GenerationStep.Decoration.UNDERGROUND_DECORATION.ordinal();
        return steps.size() > step && steps.get(step).stream().anyMatch(holder -> holder.value() == feature);
    }

    private static void craft(GameTestHelper test, String rock, String shape, int count, String... pattern) {
        var stacks = new ArrayList<ItemStack>();
        for (String row : pattern) for (char slot : row.toCharArray()) {
            stacks.add(slot == ' ' ? ItemStack.EMPTY : item(rock + (slot == 'B' ? "_brick" : "")));
        }
        var level = test.getLevel();
        var input = CraftingInput.of(pattern[0].length(), pattern.length, stacks);
        var recipe = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level).orElseThrow();
        String expected = rock + "_" + shape;
        var output = recipe.value().assemble(input, level.registryAccess());
        test.assertTrue(recipe.id().equals(id(expected)) && output.is(item(expected).getItem()) && output.getCount() == count,
                "Wrong original crafting output for " + expected + ": " + output);
    }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(ID, path); }
    private static ItemStack item(String path) { return new ItemStack(BuiltInRegistries.ITEM.get(id(path))); }
}
