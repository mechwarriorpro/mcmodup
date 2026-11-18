package mcmodup.thaumcraftcheck;

import java.util.Arrays;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import thaumcraft.api.ThaumcraftApi;
import thaumcraft.api.crafting.InfusionRecipe;

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
}
