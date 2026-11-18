package mcmodup_kernel.@NAMESPACE@;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/** Release-local ABI for the linked output-slot methods. Empty 1.21 stacks appear as null. */
public final class DecomposerAccess {
    private final Cell[] slots;
    private static final int OUTPUTS = @OUTPUTS@;

    private DecomposerAccess(IItemHandler outputs) {
        if (outputs.getSlots() != OUTPUTS) throw new IllegalArgumentException("Wrong Minechem output inventory");
        slots = new Cell[OUTPUTS + 1]; // slot zero was the input; neither kernel touches it
        for (int i = 1; i <= OUTPUTS; i++) {
            var stack = outputs.getStackInSlot(i - 1);
            if (!stack.isEmpty()) slots[i] = new Cell(stack.copy());
        }
    }

    @ENTRY@

    public static int[] outputSlots() {
        return java.util.stream.IntStream.rangeClosed(1, OUTPUTS).toArray();
    }

    public Cell getSlot(int slot) {
        if (slot < 1) throw new IllegalArgumentException("Input slot is not an output");
        return slots[slot];
    }

    public void setSlot(int slot, Cell cell) {
        if (slot < 1) throw new IllegalArgumentException("Input slot is not an output");
        if (cell != null && cell.stack.getCount() > 64) cell.stack.setCount(64);
        slots[slot] = cell;
    }

    public static Cell[] inventory(DecomposerAccess view) {
        return view.slots;
    }

    public static boolean sameKind(Cell a, Cell b) {
        // The catalog uses distinct native item IDs for legacy item metadata.
        // This is native component equality, not a legacy NBT/wildcard-ID bridge.
        return ItemStack.isSameItemSameComponents(a.stack, b.stack);
    }

    public static final class Cell {
        private final ItemStack stack;

        private Cell(ItemStack stack) { this.stack = stack; }

        public static int count(Cell cell) { return cell.stack.getCount(); }
        public static void setCount(Cell cell, int count) { cell.stack.setCount(count); }
        public int limit() { return stack.getMaxStackSize(); }
    }
}
