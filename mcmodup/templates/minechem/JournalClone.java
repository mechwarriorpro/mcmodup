package @PACKAGE@;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Copy the whole journal, not just the active recipe. Each release can clone only its own item. */
final class JournalClone extends CustomRecipe {
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
        DeferredRegister.create(Registries.RECIPE_SERIALIZER, CatalogPort.MOD_ID);
    private static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<JournalClone>> SERIALIZER =
        SERIALIZERS.register("journal_clone", () -> new SimpleCraftingRecipeSerializer<>(JournalClone::new));

    JournalClone(CraftingBookCategory category) { super(category); }

    static void register(IEventBus bus) { SERIALIZERS.register(bus); }

    private static boolean valid(CraftingInput grid) {
        // 1.21 supplies a trimmed grid. Preserve the input order relative to the occupied
        // cells, but don't consume unrelated items as the old unbounded matcher did.
        return grid.width() == 2 && grid.height() == 1 && ChemistryResearch.isJournal(grid.getItem(0))
            && grid.getItem(1).is(Items.BOOK);
    }

    @Override public boolean matches(CraftingInput grid, Level level) { return valid(grid); }

    @Override public ItemStack assemble(CraftingInput grid, HolderLookup.Provider registries) {
        return valid(grid) ? grid.getItem(0).copyWithCount(2) : ItemStack.EMPTY;
    }

    @Override public boolean canCraftInDimensions(int width, int height) { return width >= 2 && height >= 1; }
    @Override public RecipeSerializer<?> getSerializer() { return SERIALIZER.get(); }
}
