package mcmodup.minechemoracle;

import cofh.api.energy.IEnergyContainerItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** A scratch item implementing the pinned CoFHLib API; the callback must pass the held stack. */
public final class Release394GoldReceiver extends Item implements IEnergyContainerItem {
    public ItemStack expected;
    private int calls, requested, stored;
    private boolean sameStack, simulated;

    public Release394GoldReceiver() { super(); }

    @Override public int getEnergyStored(ItemStack stack) { return stored; }
    @Override public int getMaxEnergyStored(ItemStack stack) { return 137; }
    @Override public int receiveEnergy(ItemStack stack, int amount, boolean simulate) {
        calls++;
        requested = amount;
        simulated = simulate;
        sameStack = stack == expected;
        int taken = Math.min(amount, 137 - stored);
        if (!simulate) stored += taken;
        return taken;
    }
    @Override public int extractEnergy(ItemStack stack, int amount, boolean simulate) { return 0; }
    public String observed() { return calls + "/" + sameStack + "/" + requested + "/" + simulated + "/" + stored; }
}
