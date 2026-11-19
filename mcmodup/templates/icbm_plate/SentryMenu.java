package mcmodup_kernel.icbm_glass_plate;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

final class SentryMenu extends AbstractContainerMenu {
    private final SentryPlatform.Tile tile;

    SentryMenu(int id, Inventory player) { this(id, player, null, new SimpleContainer(SentryPlatform.SLOTS)); }
    SentryMenu(int id, Inventory player, SentryPlatform.Tile tile) { this(id, player, tile, tile.items); }

    private SentryMenu(int id, Inventory player, SentryPlatform.Tile tile, Container slots) {
        super(SentryPlatform.MENU.get(), id);
        this.tile = tile;
        // The original terminal exposes four columns, each of five storage slots.
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 5; row++) addSlot(new Slot(slots, col * 5 + row, 53 + col * 18, 18 + row * 18));
        }
        for (int i = 0; i < 27; i++) addSlot(new Slot(player, i + 9, 8 + i % 9 * 18, 124 + i / 9 * 18));
        for (int i = 0; i < 9; i++) addSlot(new Slot(player, i, 8 + i * 18, 182));
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size() || !stillValid(player)) return ItemStack.EMPTY;
        var slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        var stack = slot.getItem();
        var copy = stack.copy();
        boolean moved = index < SentryPlatform.SLOTS ? moveItemStackTo(stack, SentryPlatform.SLOTS, slots.size(), true) :
            moveItemStackTo(stack, 0, SentryPlatform.SLOTS, false);
        if (!moved) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        slot.onTake(player, stack);
        return copy;
    }

    @Override public boolean stillValid(Player player) {
        if (tile == null) return true;
        var level = tile.getLevel();
        var pos = tile.getBlockPos();
        return level != null && level == player.level() && level.getBlockEntity(pos) == tile &&
            stillValid(ContainerLevelAccess.create(level, pos), player, tile.getBlockState().getBlock());
    }
}
