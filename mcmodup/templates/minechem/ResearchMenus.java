package @PACKAGE@;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.SlotItemHandler;

import static @PACKAGE@.ChemistryResearch.*;

final class ResearchMenus {
    private ResearchMenus() {}

    static Component name(String id) { return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)).getDescription(); }

    static final class MicroscopeMenu extends AbstractContainerMenu {
        private final Microscope microscope;

        MicroscopeMenu(int id, Inventory player) { this(id, player, null); }

        MicroscopeMenu(int id, Inventory player, Microscope microscope) {
            super(MICROSCOPE_MENU.get(), id);
            this.microscope = microscope;
            var inventory = microscope == null ? new InspectionSlots() : microscope.inventory;
            addSlot(new SlotItemHandler(inventory, SAMPLE_SLOT, 8, 20));
            addSlot(new SlotItemHandler(inventory, BOOK_SLOT, 32, 20));
            for (int i = 0; i < 27; i++) addSlot(new Slot(player, i + 9, 8 + (i % 9) * 18, 129 + (i / 9) * 18));
            for (int i = 0; i < 9; i++) addSlot(new Slot(player, i, 8 + i * 18, 187));
        }

        @Override
        public boolean stillValid(Player player) {
            if (microscope == null) return true;
            var pos = microscope.getBlockPos();
            return microscope.getLevel() == player.level() && player.level().getBlockEntity(pos) == microscope
                && player.distanceToSqr(Vec3.atCenterOf(pos)) <= 64.0;
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
            Slot slot = slots.get(index);
            if (!slot.hasItem()) return ItemStack.EMPTY;
            ItemStack stack = slot.getItem();
            ItemStack previous = stack.copy();
            if (index < INSPECTION_SLOTS) {
                if (!moveItemStackTo(stack, INSPECTION_SLOTS, slots.size(), true)) return ItemStack.EMPTY;
            } else {
                int destination = isJournal(stack) ? BOOK_SLOT : SAMPLE_SLOT;
                if (!moveItemStackTo(stack, destination, destination + 1, false)) return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();
            slot.onTake(player, stack);
            return previous;
        }
    }

    static final class JournalMenu extends AbstractContainerMenu {
        static final int PREVIOUS = 0, NEXT = 1;
        private final InteractionHand hand;
        private final ItemStack viewed;

        JournalMenu(int id, Inventory player) { this(id, player, null, ItemStack.EMPTY); }

        JournalMenu(int id, Inventory player, InteractionHand hand, ItemStack viewed) {
            super(JOURNAL_MENU.get(), id);
            this.hand = hand;
            this.viewed = viewed;
            // Sync the held book through a read-only slot, including updated selection and title/owner data.
            addSlot(new Slot(new SimpleContainer(viewed), 0, 8, 20));
        }

        ItemStack book() { return getSlot(0).getItem(); }

        @Override
        public boolean stillValid(Player player) {
            return hand == null || (player.getItemInHand(hand) == viewed && isJournal(viewed));
        }

        @Override
        public boolean clickMenuButton(Player player, int button) {
            if (hand == null || !stillValid(player)) return false;
            return switch (button) {
                case PREVIOUS -> cycle(viewed, -1);
                case NEXT -> cycle(viewed, 1);
                default -> false;
            };
        }

        @Override public void clicked(int slot, int button, ClickType operation, Player player) {}
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    }
}
