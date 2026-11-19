package @PACKAGE@;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

final class PolytoolMenu extends AbstractContainerMenu {
    static final int INPUT = 0;
    private static final int HOTBAR = 1, MAIN = 10, END = 37;
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, CatalogPort.MOD_ID);
    static final DeferredHolder<MenuType<?>, MenuType<PolytoolMenu>> TYPE =
        MENUS.register("polytool", () -> new MenuType<>(PolytoolMenu::new, FeatureFlags.DEFAULT_FLAGS));
    private final Inventory inventory;
    private final ItemStack bound;
    private final int selected;

    static void register(IEventBus bus) { MENUS.register(bus); }
    PolytoolMenu(int id, Inventory inventory) { this(id, inventory, null); }
    PolytoolMenu(int id, Inventory inventory, ItemStack held) {
        super(TYPE.get(), id);
        this.inventory = inventory;
        this.bound = held;
        this.selected = inventory.selected;
        // The infusion target is an action, not somewhere to bank an unpaid partial stack.
        addSlot(new Slot(new SimpleContainer(1), 0, 8, 17) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) { return false; }
        });
        for (int i = 0; i < 9; i++) addSlot(new Slot(inventory, i, 8 + i * 18, 194) {
            @Override public boolean mayPlace(ItemStack stack) { return getContainerSlot() != selected; }
            @Override public boolean mayPickup(Player player) { return getContainerSlot() != selected; }
        });
        for (int i = 0; i < 27; i++) addSlot(new Slot(inventory, i + 9, 8 + i % 9 * 18, 136 + i / 9 * 18));
    }

    ItemStack held() { return getSlot(HOTBAR + selected).getItem(); }

    @Override public boolean stillValid(Player player) {
        return bound == null || (player == inventory.player && player.isAlive() && inventory.selected == selected &&
            player.getMainHandItem() == bound && !bound.isEmpty() && bound.getItem() instanceof PolytoolItem);
    }

    @Override public void clicked(int index, int button, ClickType type, Player player) {
        if (!stillValid(player) || index == HOTBAR + selected || (type == ClickType.SWAP && button == selected)) return;
        if (index != INPUT) {
            super.clicked(index, button, type, player);
        } else if (bound != null && type == ClickType.PICKUP && button == 0 && infuse(getCarried())) {
            setCarried(ItemStack.EMPTY);
        }
    }

    @Override public boolean canDragTo(Slot slot) { return slot.index != INPUT && slot.index != HOTBAR + selected; }
    @Override public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) { return canDragTo(slot); }

    private boolean infuse(ItemStack tubes) {
        var next = PolytoolRules.candidate(bound, tubes);
        if (next == null) return false;
        PolytoolRules.write(bound, next);
        inventory.setChanged();
        return true;
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (!PolytoolRules.RELEASE_394 || bound == null || !stillValid(player) || index < HOTBAR || index >= END || index == HOTBAR + selected)
            return ItemStack.EMPTY;
        var slot = getSlot(index);
        if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        var tubes = slot.getItem();
        var before = tubes.copy();
        if (PolytoolRules.isPayment(tubes)) {
            if (!infuse(tubes)) return ItemStack.EMPTY;
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            boolean moved = index < MAIN ? moveItemStackTo(tubes, MAIN, END, true) : moveItemStackTo(tubes, HOTBAR, MAIN, true);
            if (!moved) return ItemStack.EMPTY;
            if (tubes.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
            else slot.setChanged();
        }
        return before;
    }
}
