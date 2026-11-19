package mcmodup.mimicrycatalyst;

import java.io.File;
import java.util.List;
import javax.xml.parsers.ParserConfigurationException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.JUnitLikeTestReporter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("@TEST_NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class CatalystChecks {
    private static final String ID = "mimicry_catalyst";

    static {
        if (System.getProperty("mcmodup.gameReport") != null) {
            try {
                GlobalTestReporter.replaceWith(new JUnitLikeTestReporter(new File(System.getProperty("mcmodup.gameReport"))));
            } catch (ParserConfigurationException error) {
                throw new IllegalStateException(error);
            }
        }
    }

    @GameTest(template = "empty")
    public static void measuredMimichiteRecipes(GameTestHelper test) {
        test.assertTrue(ModList.get().isLoaded(ID) && !ModList.get().isLoaded("mimicry") && !ModList.get().isLoaded("legacyconnector"),
                "Expected the native catalyst alone, not the legacy mod or Connector");
        checkRecipe(test, "iron_ore", Items.IRON_ORE, Items.IRON_INGOT, 2, false);
        checkRecipe(test, "gold_ore", Items.GOLD_ORE, Items.GOLD_INGOT, 2, false);
        checkRecipe(test, "sand", Items.SAND, Items.GLASS, 2, false);
        checkRecipe(test, "iron_ingot", Items.IRON_INGOT, Items.IRON_INGOT, 2, false);
        checkRecipe(test, "lapis_lazuli", Items.LAPIS_LAZULI, Items.LAPIS_LAZULI, 3, false);
        test.succeed();
    }

    @GameTest(template = "empty")
    public static void requiresMimichiteAndOneIngredient(GameTestHelper test) {
        checkRecipe(test, "iron_ore", Items.IRON_ORE, Items.IRON_INGOT, 2, true);
        checkNoRecipe(test, CraftingInput.of(1, 1, List.of(new ItemStack(Items.IRON_ORE))), "iron ore without a catalyst");
        checkNoRecipe(test, grid(catalyst(test), new ItemStack(Items.DIRT)), "dirt with a catalyst");
        checkNoRecipe(test, CraftingInput.of(2, 2, List.of(catalyst(test), new ItemStack(Items.LAPIS_LAZULI),
                new ItemStack(Items.DIAMOND), ItemStack.EMPTY)), "three ingredients in one grid");
        test.succeed();
    }

    private static ItemStack catalyst(GameTestHelper test) {
        var id = ResourceLocation.fromNamespaceAndPath(ID, "mimichite");
        test.assertTrue(BuiltInRegistries.ITEM.containsKey(id), "Missing registered Mimichite");
        return new ItemStack(BuiltInRegistries.ITEM.get(id));
    }

    private static CraftingInput grid(ItemStack first, ItemStack second) {
        return CraftingInput.of(2, 2, List.of(first, ItemStack.EMPTY, ItemStack.EMPTY, second));
    }

    private static void checkRecipe(GameTestHelper test, String name, Item ingredient, Item expected, int count, boolean reversed) {
        var cat = catalyst(test);
        var material = new ItemStack(ingredient);
        var input = reversed ? grid(material, cat) : grid(cat, material);
        var level = test.getLevel();
        var recipe = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level).orElseThrow();
        test.assertTrue(recipe.id().equals(ResourceLocation.fromNamespaceAndPath(ID, name)), "Wrong loaded recipe: " + recipe.id());
        var result = recipe.value().assemble(input, level.registryAccess());
        test.assertTrue(result.is(expected) && result.getCount() == count, "Wrong Mimichite output for " + name + ": " + result);
        test.assertTrue(recipe.value().getRemainingItems(input).stream().allMatch(ItemStack::isEmpty), "Unexpected returned catalyst or ingredient");
    }

    private static void checkNoRecipe(GameTestHelper test, CraftingInput input, String description) {
        var level = test.getLevel();
        test.assertTrue(level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level).isEmpty(), "Unexpected recipe for " + description);
    }
}
