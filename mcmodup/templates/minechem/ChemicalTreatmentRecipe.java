package @PACKAGE@;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** One base stack plus one tube, in either order. Do not accept (and burn) extra ingredients. */
final class ChemicalTreatmentRecipe extends CustomRecipe {
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
        DeferredRegister.create(Registries.RECIPE_SERIALIZER, CatalogPort.MOD_ID);
    private static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<ChemicalTreatmentRecipe>> COATING =
        SERIALIZERS.register("weapon_coating", () -> new SimpleCraftingRecipeSerializer<>(category -> new ChemicalTreatmentRecipe(category, true)));
    private static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<ChemicalTreatmentRecipe>> SPIKING =
        SERIALIZERS.register("food_spiking", () -> new SimpleCraftingRecipeSerializer<>(category -> new ChemicalTreatmentRecipe(category, false)));
    private final boolean sword;

    private ChemicalTreatmentRecipe(CraftingBookCategory category, boolean sword) {
        super(category);
        this.sword = sword;
    }

    static void register(IEventBus bus) { SERIALIZERS.register(bus); }

    private record Ingredients(ItemStack base, ItemStack tube) {}

    private Ingredients ingredients(CraftingInput input) {
        var occupied = input.items().stream().filter(stack -> !stack.isEmpty()).toList();
        if (occupied.size() != 2) return null;
        var first = occupied.get(0);
        var second = occupied.get(1);
        if (ChemistryEffects.canTreat(first, second, sword)) return new Ingredients(first, second);
        return ChemistryEffects.canTreat(second, first, sword) ? new Ingredients(second, first) : null;
    }

    @Override public boolean matches(CraftingInput input, Level level) { return ingredients(input) != null; }
    @Override public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        var ingredients = ingredients(input);
        return ingredients == null ? ItemStack.EMPTY : ChemistryEffects.treat(ingredients.base(), ingredients.tube(), sword);
    }
    @Override public boolean canCraftInDimensions(int width, int height) { return width * height >= 2; }
    @Override public RecipeSerializer<?> getSerializer() { return sword ? COATING.get() : SPIKING.get(); }
}
