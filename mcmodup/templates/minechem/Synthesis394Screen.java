package @PACKAGE@;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = CatalogPort.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class Synthesis394Screen extends AbstractContainerScreen<Synthesis394Menu> {
    Synthesis394Screen(Synthesis394Menu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageHeight = 244;
        inventoryLabelY = 150;
    }

    @SubscribeEvent
    public static void register(RegisterMenuScreensEvent event) { event.register(Synthesizer394.MENU.get(), Synthesis394Screen::new); }

    @Override protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.literal("<"), b -> send(hasShiftDown() ? Synthesis394Menu.BACK_TEN : Synthesis394Menu.PREVIOUS))
                .bounds(leftPos + 55, topPos + 81, 20, 20).tooltip(Tooltip.create(Component.translatable("gui.@NAMESPACE@.recipe_help"))).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> send(hasShiftDown() ? Synthesis394Menu.AHEAD_TEN : Synthesis394Menu.NEXT))
                .bounds(leftPos + 76, topPos + 81, 20, 20).tooltip(Tooltip.create(Component.translatable("gui.@NAMESPACE@.recipe_help"))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.@NAMESPACE@.clear_ghosts"), b -> send(Synthesis394Menu.CLEAR))
                .bounds(leftPos + 107, topPos + 81, 60, 20).build());
    }

    private void send(int button) {
        if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
    }

    @Override public void render(GuiGraphics gui, int x, int y, float partial) {
        super.render(gui, x, y, partial);
        renderTooltip(gui, x, y);
        if (!menu.getSlot(Synthesis394Menu.JOURNAL).hasItem() && isHovering(25, 43, 16, 16, x, y)) {
            gui.renderTooltip(font, Component.translatable("gui.@NAMESPACE@.load_journal"), x, y);
        }
        if (isHovering(55, 25, 52, 52, x, y) && (hoveredSlot == null || !hoveredSlot.hasItem())) {
            gui.renderTooltip(font, Component.translatable("gui.@NAMESPACE@.ghost_help"), x, y);
        }
    }

    @Override protected void renderBg(GuiGraphics gui, float partial, int mouseX, int mouseY) {
        gui.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xffc6c6c6);
        for (var slot : menu.slots) {
            int x = leftPos + slot.x, y = topPos + slot.y;
            int color = slot.index < Synthesis394Menu.OUTPUT ? 0xff7987a0 :
                    slot.index == Synthesis394Menu.OUTPUT && menu.ready() ? 0xff5dae6d : 0xff8b8b8b;
            gui.fill(x - 1, y - 1, x + 17, y + 17, 0xffe5e5e5);
            gui.fill(x - 1, y - 1, x + 16, y + 16, color);
        }
    }

    @Override protected void renderLabels(GuiGraphics gui, int x, int y) {
        super.renderLabels(gui, x, y);
        gui.drawString(font, Component.translatable("gui.@NAMESPACE@.synthesis_power", menu.energy()), 8, 15, 0x404040, false);
        if (menu.cost() != 0) gui.drawString(font, Component.translatable("gui.@NAMESPACE@.synthesis_cost", menu.cost()), 8, 101, 0x404040, false);
    }
}
