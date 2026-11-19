package @PACKAGE@;

import java.util.ArrayList;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import static @PACKAGE@.PolytoolRules.*;

@EventBusSubscriber(modid = CatalogPort.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PolytoolScreen extends AbstractContainerScreen<PolytoolMenu> {
    private static final ResourceLocation BACKGROUND = ResourceLocation.fromNamespaceAndPath("@NAMESPACE@", "textures/gui/polytool.png");
    private static final int INK = 0xff353535;

    PolytoolScreen(PolytoolMenu menu, Inventory player, Component title) {
        super(menu, player, title);
        imageHeight = 218;
    }

    @SubscribeEvent public static void register(RegisterMenuScreensEvent event) { event.register(PolytoolMenu.TYPE.get(), PolytoolScreen::new); }

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
        super.render(gui, mouseX, mouseY, delta);
        renderTooltip(gui, mouseX, mouseY);
        var input = menu.getSlot(PolytoolMenu.INPUT);
        if (isHovering(input.x, input.y, 16, 16, mouseX, mouseY)) {
            var tip = new ArrayList<Component>();
            var cursor = menu.getCarried();
            var element = element(cursor);
            if (cursor.isEmpty()) {
                tip.add(Component.translatable(TEXT + "help"));
                tip.add(Component.translatable(TEXT + "shift"));
            } else if (!isPayment(cursor)) {
                tip.add(Component.translatable(TEXT + "wrong_input"));
            } else {
                var next = candidate(menu.held(), cursor);
                int rank = state(menu.held()).rank(element.atomic());
                tip.add(next == null ? Component.translatable(TEXT + "reject") :
                        Component.translatable(TEXT + "rank_next", element.name(), rank, rank + 1));
                tip.add(Component.translatable(TEXT + element.help()));
            }
            gui.renderComponentTooltip(font, tip, mouseX, mouseY);
        } else if (!menu.held().isEmpty() && menu.getCarried().isEmpty() && isHovering(80, 42, 16, 16, mouseX, mouseY)) {
            gui.renderTooltip(font, menu.held(), mouseX, mouseY);
        }
    }

    @Override protected void renderBg(GuiGraphics gui, float delta, int mouseX, int mouseY) {
        gui.blit(BACKGROUND, leftPos, topPos, 0, 0, imageWidth, imageHeight);
    }

    @Override protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
        gui.drawString(font, title, 8, 5, INK, false);
        number(gui, "infuse", 8, 40);
        number(gui, "prompt", 8, 51);
        var tool = menu.held();
        if (!tool.isEmpty()) gui.renderItem(tool, 80, 42);
        var view = state(tool);
        var stats = view.stats();
        number(gui, "total", 8, 80, view.ranks().stream().mapToInt(Rank::rank).sum());
        number(gui, "sword", 8, 94, stats.sword());
        number(gui, "ore", 92, 94, stats.ore());
        number(gui, "stone", 8, 105, stats.stone());
        number(gui, "axe", 92, 105, stats.axe());
        number(gui, "shovel", 8, 116, stats.shovel());
        if (!RELEASE_394 && view.rank(LITHIUM) > 0) number(gui, "charge", 8, 126, view.charge());
    }

    private void number(GuiGraphics gui, String name, int x, int y, Object... values) {
        gui.drawString(font, Component.translatable(TEXT + name, values), x, y, INK, false);
    }
}
