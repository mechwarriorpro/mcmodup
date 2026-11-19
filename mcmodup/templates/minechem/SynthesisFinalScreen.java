package @PACKAGE@;

import java.util.List;
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
public final class SynthesisFinalScreen extends AbstractContainerScreen<SynthesisFinalMenu> {
    private static final ResourceLocation BACKGROUND = ResourceLocation.fromNamespaceAndPath("@NAMESPACE@", "textures/gui/final_synthesis.png");
    private static final String GUI = "gui.@NAMESPACE@.";

    SynthesisFinalScreen(SynthesisFinalMenu menu, Inventory player, Component title) {
        super(menu, player, title);
        imageHeight = 187;
    }

    @SubscribeEvent
    public static void register(RegisterMenuScreensEvent event) { event.register(SynthesizerFinal.MENU.get(), SynthesisFinalScreen::new); }

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
        super.render(gui, mouseX, mouseY, delta);
        renderTooltip(gui, mouseX, mouseY);
        var journal = menu.getSlot(SynthesisFinalMenu.JOURNAL);
        if (!journal.hasItem() && isHovering(journal.x, journal.y, 16, 16, mouseX, mouseY)) {
            gui.renderTooltip(font, Component.translatable(GUI + "load_journal"), mouseX, mouseY);
        }
        if (isHovering(7, 18, 50, 17, mouseX, mouseY)) {
            gui.renderComponentTooltip(font, List.of(Component.translatable(GUI + "final_template_help"),
                Component.translatable(GUI + "final_take_help")), mouseX, mouseY);
        }
        if (isHovering(120, 40, 47, 27, mouseX, mouseY)) {
            gui.renderComponentTooltip(font, List.of(Component.translatable(GUI + "final_charge", menu.energy(), SynthesizerFinal.CAPACITY),
                Component.translatable(GUI + "synthesis_cost", menu.cost())), mouseX, mouseY);
        }
    }

    @Override protected void renderBg(GuiGraphics gui, float delta, int mouseX, int mouseY) {
        gui.blit(BACKGROUND, leftPos, topPos, 0, 0, imageWidth, imageHeight);
        int bar = 42 * menu.energy() / SynthesizerFinal.CAPACITY;
        gui.fill(leftPos + 121, topPos + 43, leftPos + 165, topPos + 49, 0xff333333);
        gui.fill(leftPos + 122, topPos + 44, leftPos + 122 + bar, topPos + 48, 0xff42b85d);
    }

    @Override protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
        gui.drawCenteredString(font, title, imageWidth / 2, 5, 0xffffffff);
        gui.drawString(font, "?", 30, 22, 0xff404040, false);
        String status = switch (menu.status()) {
            case 1 -> "final_unready";
            case 2 -> "final_ready";
            case 3 -> "final_unsupported";
            default -> "no_recipe";
        };
        gui.drawString(font, font.plainSubstrByWidth(Component.translatable(GUI + status).getString(), 47), 120, 55, 0xff383838, false);
        for (int i = 0; i < SynthesizerFinal.GRID; ++i) {
            var slot = menu.getSlot(SynthesisFinalMenu.GHOST + i);
            var icon = slot.getItem();
            int count = menu.ghostCount(i);
            if (!icon.isEmpty() && count > icon.getMaxStackSize()) gui.renderItemDecorations(font, icon, slot.x, slot.y, Integer.toString(count));
        }
    }
}
