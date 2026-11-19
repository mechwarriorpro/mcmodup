package @PACKAGE@;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = CatalogPort.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ShieldedStorageScreen extends AbstractContainerScreen<ShieldedStorage.StorageMenu> {
    private static final ResourceLocation LEAD = ResourceLocation.fromNamespaceAndPath(CatalogPort.MOD_ID, "textures/gui/leaded_chest.png");
    ShieldedStorageScreen(ShieldedStorage.StorageMenu menu, Inventory player, Component title) {
        super(menu, player, title);
        imageHeight = menu.capacity == ShieldedStorage.CHEST_SLOTS ? 132 : 166;
        inventoryLabelY = imageHeight - 94;
    }
    @SubscribeEvent public static void register(RegisterMenuScreensEvent event) {
        event.register(ShieldedStorage.CHEST_MENU.get(), ShieldedStorageScreen::new);
        if (ShieldedStorage.FINAL) event.register(ShieldedStorage.CABINET_MENU.get(), ShieldedStorageScreen::new);
    }
    @Override protected void renderBg(GuiGraphics graphics, float delta, int mx, int my) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xffc6c6c6);
        if (menu.capacity == ShieldedStorage.CHEST_SLOTS) {
            graphics.blit(LEAD, leftPos, topPos, 0, 0, imageWidth, imageHeight);
            return;
        }
        for (var slot : menu.slots) {
            int x = leftPos + slot.x, y = topPos + slot.y;
            graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xffeeeeee);
            graphics.fill(x - 1, y - 1, x + 16, y + 16, 0xff8b8b8b);
        }
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
