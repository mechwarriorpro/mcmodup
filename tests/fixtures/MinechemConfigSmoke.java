package mcmodup.testbedcheck;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Runs only in a separate world started with a real on-disk STARTUP config. */
@GameTestHolder("mcmodup_testbed_settings")
@PrefixGameTestTemplate(false)
public final class MinechemConfigSmoke {
    private static final String FINAL = "minechem_chemistry", NEWER = "minechem_chemistry_394";

    @GameTest(template = "empty")
    public static void finalUraniumCraftOff(GameTestHelper test) {
        var level = test.getLevel();
        var recipes = level.getRecipeManager();
        var finalOre = new ItemStack(BuiltInRegistries.ITEM.get(id(FINAL, "uranium_ore")));
        var newerOre = new ItemStack(BuiltInRegistries.ITEM.get(id(NEWER, "uranium_ore")));
        test.assertTrue(!finalOre.isEmpty() && !newerOre.isEmpty(), "The startup crafting switch changed the block/item registries");
        test.assertTrue(recipes.byKey(id(FINAL, "uranium_ore_conversion")).isEmpty(), "The FINAL conversion loaded with uraniumOreCraftable=false");
        for (var input : List.of(finalOre, newerOre, new ItemStack(Items.LIGHT))) {
            var grid = CraftingInput.of(1, 1, List.of(input));
            test.assertTrue(recipes.getRecipeFor(RecipeType.CRAFTING, grid, level).isEmpty(), "A disabled uranium conversion still matches " + input);
        }
        test.succeed();
    }

    private static ResourceLocation id(String namespace, String path) { return ResourceLocation.fromNamespaceAndPath(namespace, path); }
}
