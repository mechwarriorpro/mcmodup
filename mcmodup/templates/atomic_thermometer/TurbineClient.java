package mcmodup_kernel.atomic_thermometer;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

@EventBusSubscriber(modid = ThermometerMod.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TurbineClient {
    private static final ResourceLocation STEAM = ResourceLocation.fromNamespaceAndPath(ThermometerMod.ID, "block/steam");

    @SubscribeEvent
    public static void steamTexture(RegisterClientExtensionsEvent event) {
        event.registerFluidType(new IClientFluidTypeExtensions() {
            @Override public ResourceLocation getStillTexture() { return STEAM; }
            @Override public ResourceLocation getFlowingTexture() { return STEAM; }
        }, Turbine.STEAM_TYPE.get());
    }
}
