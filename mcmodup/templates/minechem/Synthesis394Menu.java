package @PACKAGE@;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

import static @PACKAGE@.Synthesizer394.*;

final class Synthesis394Menu extends AbstractContainerMenu {
    static final int OUTPUT = GHOST_SLOTS, STORAGE = OUTPUT + 1, JOURNAL = STORAGE + STORAGE_SLOTS, PLAYER = JOURNAL + 1;
    static final int PREVIOUS = 0, NEXT = 1, BACK_TEN = 2, AHEAD_TEN = 3, CLEAR = 4;
    private final Synthesizer394.Tile machine;
    private final ContainerData state;

    Synthesis394Menu(int id, Inventory player) {
        this(id, player, null, new ItemStackHandler(GHOST_SLOTS + 1), new ItemStackHandler(STORAGE_SLOTS), new JournalSlot());
    }

    Synthesis394Menu(int id, Inventory player, Synthesizer394.Tile machine) {
        this(id, player, machine, machine.display, machine.storage, machine.journal);
    }

    private Synthesis394Menu(int id, Inventory player, Synthesizer394.Tile machine, ItemStackHandler display, ItemStackHandler storage, JournalSlot journal) {
        super(Synthesizer394.MENU.get(), id);
        this.machine = machine;
        state = machine == null ? new SimpleContainerData(5) : new ContainerData() {
            @Override public int get(int index) {
                return switch (index) {
                    case 0 -> machine.power.getEnergyStored() & 0xffff;
                    case 1 -> machine.power.getEnergyStored() >>> 16;
                    case 2 -> machine.cost() & 0xffff;
                    case 3 -> machine.cost() >>> 16;
                    case 4 -> machine.ready() ? 1 : 0;
                    default -> 0;
                };
            }
            @Override public void set(int index, int value) {}
            @Override public int getCount() { return 5; }
        };
        addDataSlots(state);
        for (int i = 0; i <= OUTPUT; ++i) {
            boolean result = i == OUTPUT;
            addSlot(new SlotItemHandler(display, i, result ? 143 : 55 + (i % 3) * 18, result ? 43 : 25 + (i / 3) * 18) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean mayPickup(Player player) { return false; }
            });
        }
        for (int i = 0; i < STORAGE_SLOTS; ++i) addSlot(new SlotItemHandler(storage, i, 8 + (i % 9) * 18, 111 + (i / 9) * 18) {
            @Override public boolean mayPlace(ItemStack stack) { return chemical(stack) && super.mayPlace(stack); }
            @Override public void setChanged() { if (machine != null) machine.setChanged(); }
        });
        addSlot(new SlotItemHandler(journal, 0, 25, 43));
        for (int i = 0; i < 27; ++i) addSlot(new Slot(player, i + 9, 8 + (i % 9) * 18, 162 + (i / 9) * 18));
        for (int i = 0; i < 9; ++i) addSlot(new Slot(player, i, 8 + i * 18, 220));
    }

    int energy() { return (state.get(1) << 16) | (state.get(0) & 0xffff); }
    int cost() { return (state.get(3) << 16) | (state.get(2) & 0xffff); }
    boolean ready() { return state.get(4) != 0; }

    @Override public boolean stillValid(Player player) {
        return machine == null || (machine.getLevel() == player.level() && player.level().getBlockEntity(machine.getBlockPos()) == machine &&
                player.distanceToSqr(Vec3.atCenterOf(machine.getBlockPos())) <= 64.0);
    }

    @Override public boolean clickMenuButton(Player player, int button) {
        if (machine == null || !stillValid(player)) return false;
        switch (button) {
            case NEXT -> machine.advance(1);
            case PREVIOUS -> machine.advance(-1);
            case BACK_TEN -> machine.advance(-10);
            case AHEAD_TEN -> machine.advance(10);
            case CLEAR -> machine.clear();
            default -> { return false; }
        }
        return true;
    }

    @Override public boolean canDragTo(Slot slot) { return slot.index >= STORAGE && super.canDragTo(slot); }
    @Override public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.index >= STORAGE && super.canTakeItemForPickAll(stack, slot);
    }

    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (slot < 0 || slot >= STORAGE) {
            super.clicked(slot, button, type, player);
            return;
        }
        if (machine == null || !stillValid(player)) return;
        if (slot < OUTPUT && type == ClickType.PICKUP && (button == 0 || button == 1)) {
            machine.edit(slot, getCarried(), button == 1);
        } else if (slot == OUTPUT && type == ClickType.PICKUP && (button == 0 || button == 1)) {
            var result = machine.extract(64, true);
            var held = getCarried();
            if (result.isEmpty() || (!held.isEmpty() && (!ItemStack.isSameItemSameComponents(held, result) ||
                    held.getCount() + result.getCount() > held.getMaxStackSize()))) return;
            var made = machine.extract(result.getCount(), false);
            if (!made.isEmpty()) setCarried(made.copyWithCount(made.getCount() + held.getCount()));
        } else if (slot == OUTPUT && type == ClickType.QUICK_MOVE) {
            quickMoveStack(player, OUTPUT);
        }
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (machine == null || !stillValid(player) || index < OUTPUT || index >= slots.size()) return ItemStack.EMPTY;
        if (index == OUTPUT) {
            var result = machine.extract(64, true);
            if (result.isEmpty() || room(result) < result.getCount()) return ItemStack.EMPTY;
            // Commit one batch at a time, after confirming destination space.
            var made = machine.extract(result.getCount(), false);
            if (made.isEmpty()) return ItemStack.EMPTY;
            moveItemStackTo(made, PLAYER, slots.size(), true);
            return result;
        }
        var slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        var stack = slot.getItem();
        var copy = stack.copy();
        boolean moved = index < PLAYER ? moveItemStackTo(stack, PLAYER, slots.size(), true) :
                ChemistryResearch.isJournal(stack) ? moveItemStackTo(stack, JOURNAL, PLAYER, false) :
                    moveItemStackTo(stack, STORAGE, JOURNAL, false);
        if (!moved) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        slot.onTake(player, stack);
        return copy;
    }

    private int room(ItemStack result) {
        int room = 0;
        for (int i = PLAYER; i < slots.size(); ++i) {
            var slot = slots.get(i);
            var existing = slot.getItem();
            if (slot.mayPlace(result) && (existing.isEmpty() || ItemStack.isSameItemSameComponents(existing, result)))
                room += Math.max(0, slot.getMaxStackSize(result) - existing.getCount());
        }
        return room;
    }
}
