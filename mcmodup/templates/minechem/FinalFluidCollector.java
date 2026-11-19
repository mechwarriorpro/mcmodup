package @PACKAGE@;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/** FINAL stores non-water chemicals separately and never exposes them as recipes or drainable fluid. */
final class FinalFluidCollector implements IFluidHandler {
    private final FluidTank water;
    private final List<FluidStack> other = new ArrayList<>();
    FinalFluidCollector(FluidTank water) { this.water = water; }
    @Override public int getTanks() { return 1 + other.size(); }
    @Override public int getTankCapacity(int slot) { return slot >= 0 && slot < getTanks() ? water.getCapacity() : 0; }
    @Override public FluidStack getFluidInTank(int slot) {
        if (slot == 0) return water.getFluid().copy();
        return slot > 0 && slot < getTanks() ? other.get(slot - 1).copy() : FluidStack.EMPTY;
    }
    @Override public boolean isFluidValid(int slot, FluidStack resource) {
        return slot == 0 ? resource.is(Fluids.WATER) : slot > 0 && slot < getTanks()
                && other.get(slot - 1).is(resource.getFluid());
    }
    @Override public int fill(FluidStack resource, FluidAction action) {
        if (resource.isEmpty()) return 0;
        if (resource.is(Fluids.WATER)) return water.fill(resource, action);
        if (!ChemistryFluids.localFluid(resource.getFluid())) return 0;
        for (var stored : other) if (stored.is(resource.getFluid())) {
            int filled = Math.min(water.getCapacity() - stored.getAmount(), resource.getAmount());
            if (action.execute()) stored.grow(filled);
            return filled;
        }
        int filled = Math.min(water.getCapacity(), resource.getAmount());
        if (action.execute()) other.add(resource.copyWithAmount(filled));
        return filled;
    }
    @Override public FluidStack drain(FluidStack resource, FluidAction action) { return FluidStack.EMPTY; }
    @Override public FluidStack drain(int amount, FluidAction action) { return FluidStack.EMPTY; }
    void reset() { other.clear(); }
}
