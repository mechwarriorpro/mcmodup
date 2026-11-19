package mcmodup_kernel.icbm_glass_plate;

import mcmodup.icbmplate.GlassPlateMod;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = GlassPlateMod.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class LaunchClient {
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(LaunchSystem.MISSILE.get(), context -> new ThrownItemRenderer<>(context, 1.1F, false));
    }
}
