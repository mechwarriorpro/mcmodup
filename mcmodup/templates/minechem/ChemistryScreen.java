package @PACKAGE@;

import java.util.ArrayList;
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
public final class ChemistryScreen extends AbstractContainerScreen<ChemistryMenu> {
    private Button before, after, run, mode, emptyTank;

    ChemistryScreen(ChemistryMenu menu, Inventory player, Component title) {
        super(menu, player, title);
        imageHeight = 247;
        inventoryLabelY = 153;
    }

    @SubscribeEvent
    public static void register(RegisterMenuScreensEvent event) { event.register(ChemistryMachines.MENU.get(), ChemistryScreen::new); }

    @Override
    protected void init() {
        super.init();
        mode = addRenderableWidget(Button.builder(Component.empty(), button -> send(ChemistryMenu.TOGGLE_AUTO))
            .bounds(leftPos + 28, topPos + 78, 49, 20)
            .tooltip(Tooltip.create(Component.translatable("gui.@NAMESPACE@.auto_help"))).build());
        before = addRenderableWidget(Button.builder(Component.literal("<"), button -> send(hasShiftDown() ? ChemistryMenu.BACK_TEN : ChemistryMenu.PREVIOUS))
            .bounds(leftPos + 79, topPos + 78, 20, 20).build());
        after = addRenderableWidget(Button.builder(Component.literal(">"), button -> send(hasShiftDown() ? ChemistryMenu.FORWARD_TEN : ChemistryMenu.NEXT))
            .bounds(leftPos + 100, topPos + 78, 20, 20).build());
        run = addRenderableWidget(Button.builder(Component.translatable("gui.@NAMESPACE@.process"), button -> send(ChemistryMenu.RUN))
            .bounds(leftPos + 122, topPos + 78, 46, 20).build());
        emptyTank = addRenderableWidget(Button.builder(Component.translatable("gui.@NAMESPACE@.empty_tank"), button -> send(ChemistryMenu.EMPTY_TANK))
            .bounds(leftPos + 111, topPos + 78, 57, 20).build());
    }

    private void send(int button) {
        if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
        mode.setMessage(Component.translatable(menu.automatic() ? "gui.@NAMESPACE@.auto" : "gui.@NAMESPACE@.manual"));
        before.visible = after.visible = menu.synthesis();
        run.visible = mode.visible = !menu.bufferedDecomposer();
        emptyTank.visible = ChemistryMachines.Machine.PREPAID_DECOMPOSER;
        emptyTank.active = menu.fluidVolume() > 0;
        before.active = after.active = run.active = menu.remaining() == 0;
        super.render(gui, mouseX, mouseY, delta);
        renderTooltip(gui, mouseX, mouseY);
        if (!menu.bufferedDecomposer() && isHovering(8, 80, 16, 16, mouseX, mouseY)
                && !menu.getSlot(ChemistryMachines.Machine.CHARGE).hasItem()) {
            gui.renderTooltip(font, Component.translatable("gui.@NAMESPACE@.charge"), mouseX, mouseY);
        }
        if (menu.synthesis() && isHovering(152, 16, 16, 16, mouseX, mouseY)
                && !menu.getSlot(ChemistryMachines.Machine.JOURNAL).hasItem()) {
            gui.renderTooltip(font, Component.translatable("gui.@NAMESPACE@.load_journal"), mouseX, mouseY);
        }
        if (menu.synthesis() && isHovering(8, 21, 140, 10, mouseX, mouseY)) {
            var recipe = ChemistryRecipes.SYNTHESIZE.get(menu.selected());
            var requirements = new ArrayList<Component>();
            requirements.add(Component.translatable("gui.@NAMESPACE@.recipe_help"));
            for (var part : recipe.inputs()) {
                requirements.add(Component.translatable("gui.@NAMESPACE@.requirement", part.count(), part.item().getDescription()));
            }
            gui.renderComponentTooltip(font, requirements, mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics gui, float delta, int mouseX, int mouseY) {
        gui.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xffc6c6c6);
        for (var slot : menu.slots) {
            if (!slot.isActive()) continue;
            int x = leftPos + slot.x, y = topPos + slot.y;
            gui.fill(x - 1, y - 1, x + 17, y + 17, 0xffeeeeee);
            gui.fill(x - 1, y - 1, x + 16, y + 16, 0xff8b8b8b);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
        super.renderLabels(gui, mouseX, mouseY);
        String line;
        if (menu.synthesis()) {
            var recipe = ChemistryRecipes.SYNTHESIZE.get(menu.selected());
            line = recipe.outputs().getFirst().item().getDescription().getString();
        } else line = Component.translatable("gui.@NAMESPACE@.decompose").getString();
        gui.drawString(font, font.plainSubstrByWidth(line, menu.synthesis() ? 140 : 160), 8, 21, 0x404040, false);
        if (ChemistryMachines.Machine.PREPAID_DECOMPOSER) {
            gui.drawString(font, Component.translatable("gui.@NAMESPACE@.water_tank", Component.translatable("gui.@NAMESPACE@." + menu.fluidLabel()), menu.fluidVolume(),
                ChemistryMachines.Machine.WATER_CAPACITY), 8, 84, 0x404040, false);
        }
        gui.drawString(font, Component.translatable("gui.@NAMESPACE@.state_" + menu.status(), menu.energy()), 8, 102, 0x404040, false);
    }
}
