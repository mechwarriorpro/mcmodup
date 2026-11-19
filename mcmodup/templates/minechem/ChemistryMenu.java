package @PACKAGE@;

import net.minecraft.network.chat.Component;
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

import static @PACKAGE@.ChemistryMachines.Machine.*;

final class ChemistryMenu extends AbstractContainerMenu@RADIATION_MENU@ {
    static final int RUN = 0, PREVIOUS = 1, NEXT = 2, BACK_TEN = 3, FORWARD_TEN = 4, TOGGLE_AUTO = 5, EMPTY_TANK = 6;
    private final ChemistryMachines.Machine machine;
    private final ContainerData values;

    ChemistryMenu(int id, Inventory player) {
        this(id, player, null, new ItemStackHandler(SLOTS), new SimpleContainerData(PREPAID_DECOMPOSER ? 8 : 6));
    }

    ChemistryMenu(int id, Inventory player, ChemistryMachines.Machine machine) {
        this(id, player, machine, machine.inventory, machine.data());
    }

    private ChemistryMenu(int id, Inventory player, ChemistryMachines.Machine machine, ItemStackHandler inventory, ContainerData values) {
        super(ChemistryMachines.MENU.get(), id);
        this.machine = machine;
        this.values = values;
        addDataSlots(values);
        for (int i = 0; i < SLOTS; i++) {
            final int slot = i;
            if (i == CHARGE) addSlot(new SlotItemHandler(inventory, i, 8, 80) {
                @Override public boolean isActive() { return !bufferedDecomposer(); }
                @Override public boolean mayPlace(ItemStack item) { return isActive() && super.mayPlace(item); }
            });
            else if (i == JOURNAL) addSlot(new SlotItemHandler(inventory, i, 152, 16) {
                @Override public boolean isActive() { return synthesis(); }
                @Override public boolean mayPlace(ItemStack item) {
                    return isActive() && remaining() == 0 && ChemistryResearch.isJournal(item) && super.mayPlace(item);
                }
            });
            else if (i < FIRST_OUTPUT) addSlot(new SlotItemHandler(inventory, i, 8 + (i % 9) * 18, 37 + (i / 9) * 18) {
                @Override public boolean isActive() { return slot == 0 || synthesis(); }
            });
            else {
                int n = i - FIRST_OUTPUT;
                addSlot(new SlotItemHandler(inventory, i, 8 + (n % 9) * 18, 115 + (n / 9) * 18) {
                    @Override public boolean isActive() { return !bufferedDecomposer() || n < BUFFERED_OUTPUTS; }
                    @Override public boolean mayPlace(ItemStack item) { return false; }
                });
            }
        }
        for (int i = 0; i < 27; i++) addSlot(new Slot(player, i + 9, 8 + (i % 9) * 18, 165 + (i / 9) * 18));
        for (int i = 0; i < 9; i++) addSlot(new Slot(player, i, 8 + i * 18, 223));
    }

    boolean synthesis() { return values.get(0) != 0; }
    boolean bufferedDecomposer() { return ORIGINAL_DECOMPOSER && !synthesis(); }
    int selected() { return values.get(1); }
    int energy() { return values.get(2); }
    int status() { return values.get(3); }
    int remaining() { return values.get(4); }
    boolean automatic() { return values.get(5) != 0; }
    int fluidVolume() { return PREPAID_DECOMPOSER ? values.get(6) : 0; }
    Component fluidLabel() { return @FLUID_LABEL@; }
@MACHINE_SHIELD@

    @Override
    public boolean clickMenuButton(Player player, int button) {
        if (machine == null || !stillValid(player)) return false;
        return switch (button) {
            case RUN -> machine.request();
            case PREVIOUS -> machine.advance(-1);
            case NEXT -> machine.advance(1);
            case BACK_TEN -> machine.advance(-10);
            case FORWARD_TEN -> machine.advance(10);
            case TOGGLE_AUTO -> machine.toggleAutomatic();
            case EMPTY_TANK -> machine.emptyTank();
            default -> false;
        };
    }

    @Override
    public boolean stillValid(Player player) {
        return machine == null || (machine.getLevel() != null && machine.getLevel() == player.level()
            && machine.getLevel().getBlockEntity(machine.getBlockPos()) == machine
            && player.distanceToSqr(Vec3.atCenterOf(machine.getBlockPos())) <= 64.0);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.isActive() || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack previous = stack.copy();
        if (index < SLOTS) {
            if (!moveItemStackTo(stack, SLOTS, slots.size(), true)) return ItemStack.EMPTY;
        } else if (ChemistryResearch.isJournal(stack)) {
            if (!moveItemStackTo(stack, JOURNAL, JOURNAL + 1, false)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, 0, FIRST_OUTPUT, false)) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        slot.onTake(player, stack);
        return previous;
    }
}
