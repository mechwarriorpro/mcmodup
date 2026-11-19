package @PACKAGE@;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** .394: one empty bucket + eight identical vials, or one bucket back to eight vials. */
final class ChemicalBucketRecipe extends CustomRecipe {
    private static final DeferredRegister<RecipeSerializer<?>> REGISTRY = DeferredRegister.create(Registries.RECIPE_SERIALIZER, CatalogPort.MOD_ID);
    private static final java.util.function.Supplier<RecipeSerializer<ChemicalBucketRecipe>> FILL = REGISTRY.register("chemical_bucket_fill",
        () -> new SimpleCraftingRecipeSerializer<>(category -> new ChemicalBucketRecipe(category, false)));
    private static final java.util.function.Supplier<RecipeSerializer<ChemicalBucketRecipe>> EMPTY = REGISTRY.register("chemical_bucket_empty",
        () -> new SimpleCraftingRecipeSerializer<>(category -> new ChemicalBucketRecipe(category, true)));
    private final boolean empty;

    static void register(IEventBus bus) { REGISTRY.register(bus); }
    ChemicalBucketRecipe(CraftingBookCategory category, boolean empty) { super(category); this.empty = empty; }
    @Override public boolean canCraftInDimensions(int width, int height) { return empty || width >= 3 && height >= 3; }
    @Override public boolean matches(CraftingInput grid, Level level) { return !result(grid).isEmpty(); }
    @Override public ItemStack assemble(CraftingInput grid, HolderLookup.Provider registries) { return result(grid); }
    @Override public RecipeSerializer<?> getSerializer() { return empty ? EMPTY.get() : FILL.get(); }

    private ItemStack result(CraftingInput grid) {
        if (empty) {
            if (grid.ingredientCount() != 1) return ItemStack.EMPTY;
            for (var input : grid.items()) {
                if (input.isEmpty()) continue;
                Item tube = ChemistryFluids.tubeForBucket(input.getItem());
                if (tube == Items.AIR) return ItemStack.EMPTY;
                var result = new ItemStack(tube, 8);
                copyClock(input.get(ChemistryRadiation.CLOCK.get()), result);
                return result;
            }
            return ItemStack.EMPTY;
        }
        if (grid.ingredientCount() != 9) return ItemStack.EMPTY;
        boolean foundBucket = false;
        Item chemical = null;
        ChemistryRadiation.Clock oldest = null;
        for (var stack : grid.items()) {
            if (stack.isEmpty()) continue;
            if (stack.is(Items.BUCKET)) {
                if (foundBucket) return ItemStack.EMPTY;
                foundBucket = true;
                continue;
            }
            if (chemical != null && !stack.is(chemical)) return ItemStack.EMPTY;
            chemical = stack.getItem();
            var clock = stack.get(ChemistryRadiation.CLOCK.get());
            if (clock != null && (oldest == null || clock.tick() - clock.age() < oldest.tick() - oldest.age())) oldest = clock;
        }
        if (!foundBucket || chemical == null) return ItemStack.EMPTY;
        Item bucket = ChemistryFluids.bucketForTube(chemical);
        if (bucket == Items.AIR) return ItemStack.EMPTY;
        var result = new ItemStack(bucket);
        copyClock(oldest, result);
        return result;
    }
    private static void copyClock(ChemistryRadiation.Clock clock, ItemStack target) {
        if (clock != null) target.set(ChemistryRadiation.CLOCK.get(), clock);
    }
}
