package @PACKAGE@;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

@EventBusSubscriber(modid = CatalogPort.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class FluidPresentation {
    private static final ResourceLocation STILL = ResourceLocation.withDefaultNamespace("block/water_still");
    private static final ResourceLocation FLOW = ResourceLocation.withDefaultNamespace("block/water_flow");

    @SubscribeEvent
    public static void extensions(RegisterClientExtensionsEvent event) {
        for (var fluid : ChemistryFluids.CHEMICALS) event.registerFluidType(new IClientFluidTypeExtensions() {
            @Override public ResourceLocation getStillTexture() { return STILL; }
            @Override public ResourceLocation getFlowingTexture() { return FLOW; }
            @Override public int getTintColor() { return 0xFF000000 | fluid.tint(); }
        }, fluid.source().getFluidType());
    }
    @SubscribeEvent
    public static void buckets(RegisterColorHandlersEvent.Item event) {
        for (var fluid : ChemistryFluids.CHEMICALS) if (fluid.placeable())
            event.register((stack, layer) -> layer == 0 ? -1 : 0xFF000000 | fluid.tint(), fluid.bucket());
    }
}
