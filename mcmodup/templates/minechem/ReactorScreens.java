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

import static @PACKAGE@.ReactorMachines.*;

@EventBusSubscriber(modid = CatalogPort.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ReactorScreens {
    private static final String GUI = "gui." + CatalogPort.MOD_ID + ".";
    @SubscribeEvent
    public static void register(RegisterMenuScreensEvent event) {
        event.register(PROJECTION_MENU.get(), Screen::new);
        event.register(FISSION_MENU.get(), Screen::new);
        event.register(FUSION_MENU.get(), Screen::new);
    }

    private static final class Screen extends AbstractContainerScreen<ReactorMenu> {
        private final ResourceLocation background;
        Screen(ReactorMenu menu, Inventory inventory, Component title) {
            super(menu, inventory, title);
            imageHeight = menu.mode == Mode.FUSION ? 187 : 166;
            inventoryLabelY = imageHeight - 94;
            background = ResourceLocation.fromNamespaceAndPath(CatalogPort.MOD_ID, "textures/gui/reactor_" + switch (menu.mode) {
                case PROJECT -> "projector.png"; case FISSION -> "fission.png"; case FUSION -> "fusion.png";
            });
        }
        @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
            super.render(gui, mouseX, mouseY, partialTick);
            renderTooltip(gui, mouseX, mouseY);
        }
        @Override protected void renderBg(GuiGraphics gui, float delta, int mouseX, int mouseY) {
            gui.blit(background, leftPos, topPos, 0, 0, imageWidth, imageHeight);
            if (menu.mode != Mode.PROJECT) {
                if (RF) {
                    int y = topPos + (menu.mode == Mode.FUSION ? 39 : 51);
                    gui.fill(leftPos + 8, y, leftPos + 168, y + 3, 0xff333333);
                    gui.fill(leftPos + 8, y, leftPos + 8 + 160 * menu.energy() / CAPACITY, y + 3, 0xffdf971f);
                }
                return;
            }
            var plan = menu.blueprint();
            int slice = menu.activeSlice();
            if (plan == null || slice < 0 || slice >= plan.width()) return;
            int scale = Math.min(12, 86 / plan.depth());
            for (int y = 0; y < plan.height(); y++) for (int z = 0; z < plan.depth(); z++) {
                char cell = plan.cell(slice, y, z);
                int color = plan.manager(slice, y, z) ? 0xffe8bd6b : switch (cell) {
                    case 'A' -> 0xff5297d0; case 'C' -> 0xff86bee8; case '0' -> 0xffdddddd; default -> 0;
                };
                int x = leftPos + 74 + z * scale, yy = topPos + 24 + (plan.height() - 1 - y) * scale;
                if (color != 0) gui.fill(x, yy, x + scale - 1, yy + scale - 1, color);
            }
        }
        @Override protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
            super.renderLabels(gui, mouseX, mouseY);
            if (menu.mode == Mode.PROJECT) {
                var plan = menu.blueprint();
                if (plan == null) return;
                Component text = menu.formed() ? Component.translatable(GUI + "reactor_formed") : menu.activeSlice() < 0 ?
                        Component.translatable(GUI + "reactor_waiting") : Component.translatable(GUI + "reactor_slice", menu.activeSlice() + 1, plan.width());
                gui.drawString(font, text, 74, 60, 0x404040, false);
            } else if (RF) {
                gui.drawString(font, Component.translatable(GUI + "reactor_energy", menu.energy(), CAPACITY), 8, menu.mode == Mode.FUSION ? 45 : 39, 0x404040, false);
            }
        }
    }
}
