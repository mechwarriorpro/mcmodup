package mcmodup.minechemoracle;

import net.minecraft.item.Item;
import net.minecraftforge.common.ForgeDirection;
import universalelectricity.api.energy.IEnergyContainer;
import universalelectricity.api.energy.IEnergyInterface;

/** A scratch item implementing the pinned UE interfaces, not loaded by the native port. */
public final class FinalGoldReceiver extends Item implements IEnergyContainer, IEnergyInterface {
    private long stored, requested;
    private int calls;
    private String side = "-";
    private boolean simulated;

    public FinalGoldReceiver(int id) { super(id); }

    @Override public void setEnergy(ForgeDirection side, long value) { stored = value; }
    @Override public long getEnergy(ForgeDirection side) { return stored; }
    @Override public long getEnergyCapacity(ForgeDirection side) { return 137; }
    @Override public long onReceiveEnergy(ForgeDirection side, long amount, boolean simulate) {
        calls++;
        this.side = side.name();
        requested = amount;
        simulated = simulate;
        long taken = Math.min(amount, 137 - stored);
        if (!simulate) stored += taken;
        return taken;
    }
    @Override public long onExtractEnergy(ForgeDirection side, long amount, boolean simulate) { return 0; }
    @Override public boolean canConnect(ForgeDirection side, Object source) { return true; }

    public String observed() { return calls + "/" + side + "/" + requested + "/" + simulated + "/" + stored; }
}
