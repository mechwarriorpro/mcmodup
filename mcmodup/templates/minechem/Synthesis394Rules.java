package @PACKAGE@;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

final class Synthesis394Rules {
    record Part(String id, int count) {
        Item item() { return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)); }
        ItemStack stack() { return count == 0 ? ItemStack.EMPTY : new ItemStack(item(), count); }
        boolean matches(ItemStack other) { return count == 0 ? other.isEmpty() : other.is(item()) && other.getCount() == count; }
    }

    record Rule(Part result, int cost, boolean shaped, List<Part> pattern) {
        boolean matches(List<ItemStack> ghosts) {
            if (shaped) {
                for (int slot = 0; slot < pattern.size(); ++slot) if (!pattern.get(slot).matches(ghosts.get(slot))) return false;
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
    static final List<Rule> ALL = List.of(
@RULES@
    );

    private Synthesis394Rules() {}
}
