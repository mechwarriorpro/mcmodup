package @PACKAGE@;

import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Burn ticks from the exact .394 furnace fuel handler, with no inferred bucket multiplier. */
final class ChemicalFuels {
    private static final Map<ResourceLocation, Integer> TIMES = Map.ofEntries(
@FURNACE_FUELS@
    );
    static int burnTime(ItemStack stack) { return TIMES.getOrDefault(BuiltInRegistries.ITEM.getKey(stack.getItem()), 0); }
    private ChemicalFuels() {}
}
