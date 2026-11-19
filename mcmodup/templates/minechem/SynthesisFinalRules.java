package @PACKAGE@;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import @PACKAGE@.ChemistryRecipes.Part;

final class SynthesisFinalRules {
    record Rule(Part result, int cost, boolean shaped, List<Part> pattern, boolean nativeOutput, List<Part> requirements) {
        Rule(Part result, int cost, boolean shaped, List<Part> pattern, boolean nativeOutput) {
            this(result, cost, shaped, pattern, nativeOutput, totals(pattern));
        }

        ItemStack preview() {
            if (!nativeOutput) return ItemStack.EMPTY;
            var output = new ItemStack(result.item(), result.count());
            if (output.is(Items.POTION)) output.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.WATER));
            return output;
        }

        boolean matches(List<ItemStack> ghosts) {
            if (shaped) {
                // FINAL checks only the declared positions, including internal blanks.
                for (int i = 0; i < pattern.size(); ++i) {
                    var part = pattern.get(i);
                    var ghost = ghosts.get(i);
                    if (part.count() == 0 ? !ghost.isEmpty() : !ghost.is(part.item()) || ghost.getCount() < part.count()) return false;
                }
                return true;
            }
            // Shapeless needs a one-to-one match, not just equal totals.
            int used = 0;
            int matched = 0;
            for (var ghost : ghosts) {
                if (ghost.isEmpty()) continue;
                int index = 0;
                for (; index < pattern.size(); ++index) {
                    var part = pattern.get(index);
                    if ((used & (1 << index)) == 0 && part.count() == ghost.getCount() && ghost.is(part.item())) break;
                }
                if (index == pattern.size()) return false;
                used |= 1 << index;
                matched++;
            }
            return matched == pattern.stream().filter(part -> part.count() > 0).count();
        }
    }

    private static List<Part> totals(List<Part> pattern) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var part : pattern) if (part.count() > 0) counts.merge(part.id(), part.count(), Integer::sum);
        var result = new ArrayList<Part>();
        counts.forEach((item, count) -> result.add(new Part(item, count)));
        return List.copyOf(result);
    }

    static boolean isWaterBottle(ItemStack stack) {
        return stack.is(Items.POTION) && new PotionContents(Potions.WATER).equals(stack.get(DataComponents.POTION_CONTENTS));
    }

    private static final Part EMPTY = new Part("minecraft:air", 0);
    static final List<Rule> ALL = List.of(
@RULES@
    );

    private SynthesisFinalRules() {}
}
