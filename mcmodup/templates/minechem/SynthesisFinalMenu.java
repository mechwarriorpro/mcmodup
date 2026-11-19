package @PACKAGE@;

import net.minecraft.world.SimpleContainer;
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

final class SynthesisFinalMenu extends AbstractContainerMenu implements ChemistryRadiation.MenuRules {
    static final int OUTPUT = 0, GHOST = 1, STORAGE = 10, JOURNAL = 19, PLAYER = 20;
    private static final int COUNTS = 5, DATA_SIZE = COUNTS + SynthesizerFinal.GRID;
    private final SynthesizerFinal.Tile machine;
    private final ContainerData data;

    SynthesisFinalMenu(int id, Inventory player) {
        this(id, player, null, new SimpleContainer(STORAGE), new ItemStackHandler(SynthesizerFinal.GRID), new SynthesizerFinal.JournalSlot());
    }

    SynthesisFinalMenu(int id, Inventory player, SynthesizerFinal.Tile machine) {
        this(id, player, machine, machine.display, machine.storage, machine.journal);
    }

    private SynthesisFinalMenu(int id, Inventory player, SynthesizerFinal.Tile machine, SimpleContainer display,
                               ItemStackHandler storage, SynthesizerFinal.JournalSlot journal) {
        super(SynthesizerFinal.MENU.get(), id);
        this.machine = machine;
        this.data = machine == null ? new SimpleContainerData(DATA_SIZE) : new ContainerData() {
            @Override public int get(int index) {
                return switch (index) {
                    case 0 -> machine.energy() & 0xffff;
                    case 1 -> machine.energy() >>> 16;
                    case 2 -> machine.cost() & 0xffff;
                    case 3 -> machine.cost() >>> 16;
                    case 4 -> machine.status();
                    default -> machine.ghostCount(index - COUNTS);
                };
            }
            @Override public void set(int index, int value) {}
            @Override public int getCount() { return DATA_SIZE; }
        };
        addDataSlots(data);
        addDisplay(display, OUTPUT, 134, 18);
        for (int i = 0; i < SynthesizerFinal.GRID; ++i) addDisplay(display, GHOST + i, 62 + i % 3 * 18, 18 + i / 3 * 18);
        for (int i = 0; i < SynthesizerFinal.GRID; ++i) {
            addSlot(new SlotItemHandler(storage, i, 8 + i * 18, 84) {
                @Override public boolean mayPlace(ItemStack stack) { return SynthesizerFinal.chemical(stack); }
                @Override public void setChanged() { if (machine != null) machine.setChanged(); }
            });
        }
        addSlot(new SlotItemHandler(journal, 0, 26, 36));
        for (int i = 0; i < 27; ++i) addSlot(new Slot(player, i + 9, 8 + i % 9 * 18, 105 + i / 9 * 18));
        for (int i = 0; i < 9; ++i) addSlot(new Slot(player, i, 8 + i * 18, 163));
    }

    private void addDisplay(SimpleContainer display, int index, int x, int y) {
        addSlot(new Slot(display, index, x, y) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) { return false; }
        });
    }

    int energy() { return (data.get(1) << 16) | (data.get(0) & 0xffff); }
    int cost() { return (data.get(3) << 16) | (data.get(2) & 0xffff); }
    int status() { return data.get(4); }
    int ghostCount(int slot) { return data.get(COUNTS + slot); }

    @Override public boolean virtualRadiationSlot(int slot) { return slot < STORAGE; }
    @Override public boolean shieldedRadiationSlot(int slot) { return slot >= STORAGE && slot < JOURNAL; }
    @Override public float radiationReduction() { return 0.4F; }

    @Override public boolean stillValid(Player player) {
        return machine == null || (!machine.isRemoved() && machine.getLevel() == player.level() &&
                player.level().getBlockEntity(machine.getBlockPos()) == machine && player.distanceToSqr(Vec3.atCenterOf(machine.getBlockPos())) <= 64.0);
    }
    @Override public boolean canDragTo(Slot slot) { return slot.index >= STORAGE && super.canDragTo(slot); }
    @Override public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.index >= STORAGE && super.canTakeItemForPickAll(stack, slot);
    }

    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (!stillValid(player)) return;
        if (slot < 0 || slot >= STORAGE) {
            super.clicked(slot, button, type, player);
            return;
        }
        if (machine == null) return; // The server owns the virtual slots and payment.
        if (slot == OUTPUT && type == ClickType.QUICK_MOVE) {
            quickMoveStack(player, OUTPUT);
        } else if (type == ClickType.PICKUP && (button == 0 || button == 1)) {
            if (slot == OUTPUT) takeToCursor(button == 1);
            else machine.edit(slot - GHOST, getCarried(), button == 1);
        }
    }

    private void takeToCursor(boolean split) {
        var preview = getSlot(OUTPUT).getItem();
        if (preview.isEmpty()) return;
        var cursor = getCarried();
        int requested = preview.getCount();
        if (cursor.isEmpty()) {
            if (split) requested = (requested + 1) / 2;
        } else if (!ItemStack.isSameItemSameComponents(cursor, preview) || cursor.getCount() + requested > cursor.getMaxStackSize()) {
            return;
        }
        var taken = machine.takeUnits(requested, false);
        if (!taken.isEmpty()) setCarried(taken.copyWithCount(cursor.getCount() + taken.getCount()));
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (machine == null || !stillValid(player) || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        if (index == OUTPUT) {
            if (player.getInventory().getFreeSlot() < 0) return ItemStack.EMPTY;
            ItemStack first = ItemStack.EMPTY;
            for (;;) {
                var batch = machine.takeBatch(true);
                if (batch.isEmpty() || roomFor(batch) < batch.getCount()) return first;
                if (first.isEmpty()) first = batch.copy();
                // The original could pay for batches it could not insert. Each
                // native batch is committed only after confirming space for it.
                moveItemStackTo(machine.takeBatch(false), PLAYER, slots.size(), true);
            }
        }
        if (index < STORAGE) return ItemStack.EMPTY;
        var slot = getSlot(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        var current = slot.getItem();
        var before = current.copy();
        boolean moved = index < PLAYER ? moveItemStackTo(current, PLAYER, slots.size(), true) :
                ChemistryResearch.isJournal(current) ? moveItemStackTo(current, JOURNAL, JOURNAL + 1, false) :
                SynthesizerFinal.chemical(current) && moveItemStackTo(current, STORAGE, JOURNAL, false);
        if (!moved) return ItemStack.EMPTY;
        if (current.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return before;
    }

    private int roomFor(ItemStack output) {
        int room = 0;
        for (int i = PLAYER; i < slots.size(); ++i) {
            var slot = getSlot(i);
            var present = slot.getItem();
            if (present.isEmpty() || ItemStack.isSameItemSameComponents(present, output)) {
                room += Math.max(0, slot.getMaxStackSize(output) - present.getCount());
            }
        }
        return room;
    }
}
