package mcmodup.test;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@Mod(CatalogSmoke.ID)
@GameTestHolder(CatalogSmoke.ID)
@PrefixGameTestTemplate(false)
public final class CatalogSmoke {
    public static final String ID = "mcmodup_smoke";

    private static ItemStack stack(GameTestHelper helper, String namespace, String name) {
        var id = ResourceLocation.fromNamespaceAndPath(namespace, name);
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(id), "Missing converted item " + id);
        return new ItemStack(BuiltInRegistries.ITEM.get(id));
    }

    private static void check(GameTestHelper helper, CraftingInput input, String namespace, String result) {
        var level = helper.getLevel();
        var recipes = level.getRecipeManager();
        var match = recipes.getRecipeFor(RecipeType.CRAFTING, input, level);
        helper.assertTrue(match.isPresent(), "Missing recipe for " + namespace + ":" + result);
        var output = match.orElseThrow().value().assemble(input, level.registryAccess());
        helper.assertTrue(ItemStack.isSameItem(output, stack(helper, namespace, result)) && output.getCount() == 1,
                "Unexpected result " + output + " for " + match.orElseThrow().id());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void moleculeFromElements(GameTestHelper helper) {
        String ns = "minechem_chemistry";
        var hydrogen = stack(helper, ns, "element_h");
        var oxygen = stack(helper, ns, "element_o");
        check(helper, CraftingInput.of(2, 2, List.of(hydrogen, oxygen, hydrogen.copy(), ItemStack.EMPTY)), ns, "molecule_water");
    }

    @GameTest(template = "empty")
    public static void aspectFromComponents(GameTestHelper helper) {
        String ns = "thaumcraft_aspects";
        check(helper, CraftingInput.of(2, 1, List.of(stack(helper, ns, "aspect_ignis"), stack(helper, ns, "aspect_aer"))), ns, "aspect_lux");
    }
}
