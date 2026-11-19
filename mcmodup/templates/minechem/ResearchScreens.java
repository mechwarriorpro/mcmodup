package @PACKAGE@;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import @PACKAGE@.ResearchMenus.JournalMenu;
import @PACKAGE@.ResearchMenus.MicroscopeMenu;

@EventBusSubscriber(modid = CatalogPort.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ResearchScreens {
    private static final String GUI = "gui.@NAMESPACE@.";

    @SubscribeEvent
    public static void register(RegisterMenuScreensEvent event) {
        event.register(ChemistryResearch.MICROSCOPE_MENU.get(), MicroscopeScreen::new);
        event.register(ChemistryResearch.JOURNAL_MENU.get(), JournalScreen::new);
    }

    private abstract static class RecipeScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {
        private boolean synthesis = true;
        private String inspected = "";
        private ChemistryRecipes.Recipe recipe;
        private List<ChemistryRecipes.Part> parts = List.of();
        private Button mode;

        RecipeScreen(T menu, Inventory inventory, Component title) { super(menu, inventory, title); }
        abstract String inspectedKey();
        final boolean hasInspection() { return !inspected.isEmpty(); }

        @Override
        protected void init() {
            super.init();
            mode = addRenderableWidget(Button.builder(Component.empty(), button -> synthesis = !synthesis)
                .bounds(leftPos + 96, topPos + 18, 72, 20).build());
        }

        @Override
        public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
            inspected = inspectedKey();
            recipe = synthesis ? ChemistryRecipes.BY_RESULT.get(inspected) : ChemistryRecipes.inspection(inspected);
            parts = recipe == null ? List.of() : (synthesis ? recipe.inputs() : recipe.outputs());
            mode.setMessage(Component.translatable(GUI + (synthesis ? "inspect_synthesis" : "inspect_decomposition")));
            super.render(gui, mouseX, mouseY, delta);
            renderTooltip(gui, mouseX, mouseY);
            for (int i = 0; i < parts.size(); i++) {
                if (isHovering(partX(i), partY(i), 16, 16, mouseX, mouseY)) {
                    var part = parts.get(i);
                    gui.renderTooltip(font, Component.translatable(GUI + "requirement", part.count(), part.item().getDescription()), mouseX, mouseY);
                    break;
                }
            }
        }

        @Override
        protected void renderBg(GuiGraphics gui, float delta, int mouseX, int mouseY) {
            gui.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xffc6c6c6);
            for (var slot : menu.slots) {
                int x = leftPos + slot.x, y = topPos + slot.y;
                gui.fill(x - 1, y - 1, x + 17, y + 17, 0xffeeeeee);
                gui.fill(x - 1, y - 1, x + 16, y + 16, 0xff8b8b8b);
            }
            // Only draw the recipe. These items never exist in a container slot or a server inventory.
            for (int i = 0; i < parts.size(); i++) {
                var part = parts.get(i);
                int x = leftPos + partX(i), y = topPos + partY(i);
                var icon = new ItemStack(part.item());
                gui.renderItem(icon, x, y);
                gui.renderItemDecorations(font, icon, x, y, part.count() == 1 ? null : Integer.toString(part.count()));
            }
        }

        @Override
        protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
            label(gui, title, 6);
            if (!inspected.isEmpty()) {
                label(gui, ResearchMenus.name(inspected), 43);
                label(gui, recipe == null ? Component.translatable(GUI + "no_recipe")
                    : Component.translatable(GUI + (synthesis ? "inspect_inputs" : ChemistryRecipes.variable(recipe) ? "inspect_example" : "inspect_outputs"), recipe.energy()), 55);
            }
        }

        final void label(GuiGraphics gui, Component text, int y) {
            gui.drawString(font, font.plainSubstrByWidth(text.getString(), imageWidth - 16), 8, y, 0x404040, false);
        }

        private static int partX(int i) { return 8 + (i % 9) * 18; }
        private static int partY(int i) { return 68 + (i / 9) * 18; }
    }

    private static final class MicroscopeScreen extends RecipeScreen<MicroscopeMenu> {
        MicroscopeScreen(MicroscopeMenu menu, Inventory inventory, Component title) {
            super(menu, inventory, title);
            imageHeight = 211;
        }

        @Override String inspectedKey() {
            var sample = menu.getSlot(ChemistryResearch.SAMPLE_SLOT).getItem();
            return sample.isEmpty() ? "" : ChemistryResearch.key(sample);
        }

        @Override
        public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
            super.render(gui, mouseX, mouseY, delta);
            for (int i = 0; i < ChemistryResearch.INSPECTION_SLOTS; i++) {
                var slot = menu.getSlot(i);
                if (!slot.hasItem() && isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
                    gui.renderTooltip(font, Component.translatable(GUI + (i == ChemistryResearch.SAMPLE_SLOT ? "inspect_sample" : "inspect_book")), mouseX, mouseY);
                }
            }
        }

        @Override
        protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
            super.renderLabels(gui, mouseX, mouseY);
            if (!hasInspection()) label(gui, Component.translatable(GUI + "inspect_sample"), 43);
            label(gui, playerInventoryTitle, 117);
        }
    }

    private static final class JournalScreen extends RecipeScreen<JournalMenu> {
        private Button previous, next;

        JournalScreen(JournalMenu menu, Inventory inventory, Component title) {
            super(menu, inventory, title);
            imageHeight = 112;
        }

        @Override String inspectedKey() { return ChemistryResearch.active(menu.book()); }

        @Override
        protected void init() {
            super.init();
            previous = addRenderableWidget(Button.builder(Component.literal("<"), button -> send(JournalMenu.PREVIOUS))
                .bounds(leftPos + 32, topPos + 18, 20, 20).tooltip(Tooltip.create(Component.translatable(GUI + "journal_previous"))).build());
            next = addRenderableWidget(Button.builder(Component.literal(">"), button -> send(JournalMenu.NEXT))
                .bounds(leftPos + 54, topPos + 18, 20, 20).tooltip(Tooltip.create(Component.translatable(GUI + "journal_next"))).build());
        }

        private void send(int action) {
            if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, action);
        }

        @Override
        public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
            previous.active = next.active = !ChemistryResearch.discoveries(menu.book()).isEmpty();
            super.render(gui, mouseX, mouseY, delta);
        }

        @Override
        protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
            super.renderLabels(gui, mouseX, mouseY);
            if (!hasInspection()) label(gui, Component.translatable(GUI + (next.active ? "journal_choose" : "journal_empty")), 43);
        }
    }
}
