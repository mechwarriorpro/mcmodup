package @PACKAGE@;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

import static @PACKAGE@.ReactorMachines.*;

final class ReactorMenu extends AbstractContainerMenu implements ChemistryRadiation.MenuRules {
    final Mode mode;
    private final Station station;
    private final BlockPos access;
    private final net.minecraft.world.level.block.entity.BlockEntity accessTile;
    private final int machineSlots;
    private final ContainerData data;

    ReactorMenu(int id, Inventory player, Mode mode) { this(id, player, mode, null, BlockPos.ZERO); }
    ReactorMenu(int id, Inventory player, Mode mode, Station station, BlockPos access) {
        super(mode.menu(), id);
        this.mode = mode;
        this.station = station;
        this.access = access;
        this.accessTile = station == null ? null : station.getLevel().getBlockEntity(access);
        ItemStackHandler inventory = station == null ? new ItemStackHandler(mode.slots()) {
            @Override public boolean isItemValid(int slot, ItemStack stack) { return mode.accepts(slot, stack); }
            @Override public int getSlotLimit(int slot) { return mode == Mode.PROJECT || !RF && mode == Mode.FUSION && slot == 0 ? 1 : 64; }
        } : station.inventory;
        switch (mode) {
            case PROJECT -> addSlot(machineSlot(inventory, 0, 25, 47));
            case FISSION -> {
                addSlot(machineSlot(inventory, 0, 80, 16));
                if (!RF) addSlot(machineSlot(inventory, 1, 125, 33));
                addSlot(machineSlot(inventory, mode.output(), 80, 62));
            }
            case FUSION -> {
                if (!RF) addSlot(machineSlot(inventory, 0, 80, 18));
                addSlot(machineSlot(inventory, mode.left(), 22, 62));
                addSlot(machineSlot(inventory, mode.right(), 138, 62));
                addSlot(machineSlot(inventory, mode.output(), 80, 62));
            }
        }
        machineSlots = slots.size();
        int row = mode == Mode.FUSION ? 105 : 84;
        for (int i = 0; i < 27; i++) addSlot(new Slot(player, i + 9, 8 + (i % 9) * 18, row + (i / 9) * 18));
        for (int i = 0; i < 9; i++) addSlot(new Slot(player, i, 8 + i * 18, row + 58));
        int dataSize = mode == Mode.PROJECT ? 2 : 4;
        data = station == null ? new SimpleContainerData(dataSize) : new ContainerData() {
            @Override public int get(int i) {
                if (mode == Mode.PROJECT) return station.status(i);
                int value = station.status(i / 2);
                return (i & 1) == 0 ? value & 65535 : value >>> 16;
            }
            @Override public void set(int i, int value) {}
            @Override public int getCount() { return dataSize; }
        };
        addDataSlots(data);
    }

    private Slot machineSlot(ItemStackHandler inventory, int index, int x, int y) {
        return new SlotItemHandler(inventory, index, x, y) {
            @Override public void setChanged() { if (station != null) station.setChanged(); }
        };
    }

    int energy() { return (data.get(0) & 65535) | (data.get(1) << 16); }
    int capacity() { return (data.get(2) & 65535) | (data.get(3) << 16); }
    int activeSlice() { return data.get(0); }
    boolean formed() { return data.get(1) == 1; }
    ReactorBlueprints.Plan blueprint() { return ReactorMachines.blueprint(getSlot(0).getItem()); }

    @Override public float radiationReduction() {
        return switch (mode) { case FUSION -> 1F; case FISSION -> 0.4F; default -> 0F; };
    }

    @Override public boolean stillValid(Player player) {
        if (station == null) return true;
        var level = station.getLevel();
        return station.ready() && level == player.level() && level.hasChunkAt(access) && level.hasChunkAt(station.getBlockPos())
                && level.getBlockEntity(station.getBlockPos()) == station && level.getBlockEntity(access) == accessTile
                && player.distanceToSqr(Vec3.atCenterOf(access)) <= 64.0;
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size() || !stillValid(player)) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem(), original = stack.copy();
        if (index < machineSlots) {
            if (!moveItemStackTo(stack, machineSlots, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            // Work one slot at a time: do not merge an element into a matching output stack.
            int end = mode == Mode.PROJECT ? machineSlots : machineSlots - 1;
            boolean moved = false;
            for (int i = 0; i < end && !stack.isEmpty(); i++) {
                if (getSlot(i).mayPlace(stack)) moved |= moveItemStackTo(stack, i, i + 1, false);
            }
            if (!moved) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        slot.onTake(player, stack);
        return original;
    }
}
