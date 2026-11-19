package @PACKAGE@;

import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import @PACKAGE@.ChemistryRecipes.Part;
import @PACKAGE@.ChemistryRecipes.Recipe;

/** Measured default-handler decomposition recipes for installed providers of selected ore tags. */
final class OreDictionary394 {
    private record Rule(TagKey<Item> tag, TagKey<Item> qualifier, List<Part> output) {
        boolean matches(ItemStack stack) { return stack.is(tag) && (qualifier == null || stack.is(qualifier)); }
    }

    private static TagKey<Item> tag(String id) { return TagKey.create(Registries.ITEM, ResourceLocation.parse(id)); }
    private static final List<Rule> RULES = List.of(
@RULES@
    );

    static Recipe lookup(ItemStack input, ResourceLocation id) {
        // Exact registry rows take precedence. Do not process the other Minechem's items by tag.
        if (id.getNamespace().equals("minechem_chemistry") || id.getNamespace().equals(CatalogPort.MOD_ID)) return null;
        for (var rule : RULES) if (rule.matches(input)) return new Recipe(id.toString(), 1000,
            rule.output().stream().mapToInt(Part::count).sum(), 0, List.of(new Part(id.toString(), 1)), rule.output());
        return null;
    }
}
