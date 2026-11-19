package @PACKAGE@;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import @PACKAGE@.ChemistryRecipes.Part;
import @PACKAGE@.ChemistryRecipes.Recipe;

/** Default-handler decomposition recipes for installed native item tag providers. */
final class OreDictionary394 {
    private record Rule(int priority, TagKey<Item> tag, TagKey<Item> qualifier, List<Part> output) {
        boolean matchesHost(ItemStack stack) { return qualifier == null || stack.is(qualifier); }
    }

    private static TagKey<Item> tag(String id) { return TagKey.create(Registries.ITEM, ResourceLocation.parse(id)); }
    private static final Map<TagKey<Item>, List<Rule>> RULES = List.of(
@RULES@
    ).stream().collect(Collectors.groupingBy(Rule::tag));

    static Recipe lookup(ItemStack input, ResourceLocation id) {
        // Exact registry rows take precedence. Do not process the other Minechem's items by tag.
        if (id.getNamespace().equals("minechem_chemistry") || id.getNamespace().equals(CatalogPort.MOD_ID)) return null;
        var match = input.getTags().flatMap(tag -> RULES.getOrDefault(tag, List.of()).stream())
            .filter(rule -> rule.matchesHost(input)).min(Comparator.comparingInt(Rule::priority)).orElse(null);
        if (match == null) return null;
        return new Recipe(id.toString(), 1000, match.output().stream().mapToInt(Part::count).sum(), 0,
            List.of(new Part(id.toString(), 1)), match.output());
    }
}
