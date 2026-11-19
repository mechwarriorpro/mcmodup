package @PACKAGE@;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

final class Synthesis394Rules {
    record Part(String id, int count) {
        Item item() { return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)); }
        ItemStack stack() {
            if (count == 0) return ItemStack.EMPTY;
            var stack = new ItemStack(item(), count);
            if (stack.is(Items.POTION)) stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.WATER));
            return stack;
        }
        boolean matches(ItemStack other) { return count == 0 ? other.isEmpty() : other.is(item()) && other.getCount() == count; }
    }

    record Rule(Part result, int cost, boolean shaped, List<Part> pattern, int declaredSlots) {
        boolean matches(List<ItemStack> ghosts) {
            if (shaped) {
                for (int slot = 0; slot < declaredSlots; ++slot) if (!pattern.get(slot).matches(ghosts.get(slot))) return false;
                return true;
            }
            // Counts describe ghost stacks, not aggregate amounts. Repeated
            // ingredients can occupy separate ghost stacks with the same item.
            int used = 0;
            for (Part part : pattern) {
                boolean found = false;
                for (int slot = 0; slot < ghosts.size(); ++slot) {
                    if ((used & (1 << slot)) != 0 || !part.matches(ghosts.get(slot))) continue;
                    used |= 1 << slot;
                    found = true;
                    break;
                }
                if (!found) return false;
            }
            return true;
        }
    }

    private static final Part EMPTY = new Part("minecraft:air", 0);
    static final List<Rule> ALL = List.of(@RULES@).stream().flatMap(List::stream).toList();

@CHUNKS@

    private Synthesis394Rules() {}
}
