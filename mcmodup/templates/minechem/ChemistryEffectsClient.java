package @PACKAGE@;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

@EventBusSubscriber(modid = CatalogPort.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ChemistryEffectsClient {
    @SubscribeEvent
    public static void register(RegisterGuiLayersEvent event) {
        event.registerBelowAll(ResourceLocation.fromNamespaceAndPath(CatalogPort.MOD_ID, "delirium"), (graphics, delta) -> {
            var player = Minecraft.getInstance().player;
            var effect = player == null ? null : player.getEffect(ChemistryEffects.DELIRIUM);
            if (effect != null) {
                int alpha = (220 * (5 * effect.getAmplifier() + 5) - 150) & 255;
                graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha << 24 | 0x101020);
            }
        });
    }
}
