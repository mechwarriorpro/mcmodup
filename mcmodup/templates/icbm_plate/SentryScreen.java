package mcmodup_kernel.icbm_glass_plate;

import mcmodup.icbmplate.GlassPlateMod;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = GlassPlateMod.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SentryScreen extends AbstractContainerScreen<SentryMenu> {
    private SentryScreen(SentryMenu menu, Inventory player, Component title) {
        super(menu, player, title);
        imageHeight = 206;
        inventoryLabelY = 112;
    }

    @SubscribeEvent
    public static void screens(RegisterMenuScreensEvent event) { event.register(SentryPlatform.MENU.get(), SentryScreen::new); }

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partial) {
        super.render(gui, mouseX, mouseY, partial);
        renderTooltip(gui, mouseX, mouseY);
    }

    @Override protected void renderBg(GuiGraphics gui, float partial, int mouseX, int mouseY) {
        gui.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xffc6c6c6);
        for (var slot : menu.slots) {
            int x = leftPos + slot.x, y = topPos + slot.y;
            gui.fill(x - 1, y - 1, x + 17, y + 17, 0xffeeeeee);
            gui.fill(x - 1, y - 1, x + 16, y + 16, 0xff8b8b8b);
        }
    }
}
