package @PACKAGE@;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import static @PACKAGE@.PolytoolRules.*;

@EventBusSubscriber(modid = CatalogPort.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PolytoolScreen extends AbstractContainerScreen<PolytoolMenu> {
    private static final ResourceLocation BACKGROUND = ResourceLocation.fromNamespaceAndPath("@NAMESPACE@", "textures/gui/polytool.png");
    private static final int INK = 0xff353535;
    private static final int CENTER_X = @ORBIT_X@, CENTER_Y = @ORBIT_Y@;
    private static final int INNER = @ORBIT_INNER@, OUTER = @ORBIT_OUTER@, MAX_ORBIT_SPRITES = 128;
    private static final double RADIANS_PER_FRAME = @ORBIT_STEP@;
    private final RandomSource random = RandomSource.create();
    private List<Rank> lastRanks = List.of();
    private List<Satellite> satellites = List.of();
    private long orbitFrame;
    private Button helpButton;
    private boolean helpOpen;

    private record Satellite(int element, ItemStack icon, int radius, double phase) {
        double angle(long frame) { return phase + frame * RADIANS_PER_FRAME; }
        int x(long frame) { return (int)(CENTER_X + Math.sin(angle(frame)) * radius); }
        int y(long frame) { return (int)(CENTER_Y + Math.cos(angle(frame)) * radius); }
    }

    PolytoolScreen(PolytoolMenu menu, Inventory player, Component title) {
        super(menu, player, title);
        imageHeight = 218;
    }

    @SubscribeEvent public static void register(RegisterMenuScreensEvent event) { event.register(PolytoolMenu.TYPE.get(), PolytoolScreen::new); }

    @Override protected void init() {
        super.init();
        if (RELEASE_394) helpButton = addRenderableWidget(Button.builder(Component.literal("?"), button -> helpOpen = !helpOpen)
            .bounds(leftPos + imageWidth - 21, topPos + 5, 16, 16)
            .tooltip(Tooltip.create(Component.translatable(TEXT + "help_tab"))).build());
    }

    private void refreshOrbit(List<Rank> ranks) {
        if (ranks.equals(lastRanks)) return;
        Map<Integer, List<Satellite>> byElement = satellites.stream().collect(Collectors.groupingBy(Satellite::element));
        var updated = new ArrayList<Satellite>();
        for (var rank : ranks) {
            var existing = byElement.getOrDefault(rank.element(), List.of());
            var icon = new ItemStack(BuiltInRegistries.ITEM.get(element(rank.element()).tube()));
            for (int count = 0; count < rank.rank() && updated.size() < MAX_ORBIT_SPRITES; count++) {
                updated.add(count < existing.size() ? existing.get(count) :
                    new Satellite(rank.element(), icon, random.nextBoolean() ? INNER : OUTER, random.nextDouble() * Math.PI * 2));
            }
        }
        lastRanks = ranks;
        satellites = List.copyOf(updated);
    }

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
        } else if (!menu.held().isEmpty() && menu.getCarried().isEmpty() && isHovering(CENTER_X, CENTER_Y, 16, 16, mouseX, mouseY)) {
            gui.renderTooltip(font, menu.held(), mouseX, mouseY);
        }
    }

    @Override protected void renderBg(GuiGraphics gui, float delta, int mouseX, int mouseY) {
        gui.blit(BACKGROUND, leftPos, topPos, 0, 0, imageWidth, imageHeight);
    }

    @Override protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
        var tool = menu.held();
        var view = state(tool);
        refreshOrbit(view.ranks());
        long frame = ++orbitFrame;
        for (var satellite : satellites) gui.renderItem(satellite.icon(), satellite.x(frame), satellite.y(frame));
        if (!tool.isEmpty()) gui.renderItem(tool, CENTER_X, CENTER_Y);
        gui.drawString(font, title, 8, 5, INK, false);
        number(gui, "infuse", 30, 18);
        number(gui, "prompt", 30, 28);
        var stats = view.stats();
        number(gui, "sword", 10, 80, stats.sword());
        number(gui, "ore", 10, 90, stats.ore());
        number(gui, "stone", 10, 100, stats.stone());
        number(gui, "axe", 10, 110, stats.axe());
        number(gui, "shovel", 10, 120, stats.shovel());
        number(gui, "total", 91, 100, view.ranks().stream().mapToInt(Rank::rank).sum());
        if (!RELEASE_394 && view.rank(LITHIUM) > 0) number(gui, "charge", 73, 120, view.charge());
        if (helpOpen) {
            gui.fill(23, 23, imageWidth - 5, 77, 0xffdedede);
            gui.drawWordWrap(font, Component.translatable(TEXT + "help_page"), 28, 27, imageWidth - 42, INK);
        }
    }

    private void number(GuiGraphics gui, String name, int x, int y, Object... values) {
        gui.drawString(font, Component.translatable(TEXT + name, values), x, y, INK, false);
    }
}
