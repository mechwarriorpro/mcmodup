package mcmodup.icbmplate;

import mcmodup_kernel.icbm_glass_plate.GlassPlate;
import mcmodup_kernel.icbm_glass_plate.LaunchSystem;
import mcmodup_kernel.icbm_glass_plate.SentryPlatform;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(GlassPlateMod.ID)
public final class GlassPlateMod {
    public static final String ID = "icbm_glass_plate";

    public GlassPlateMod(IEventBus bus) {
        var blocks = DeferredRegister.createBlocks(ID);
        var items = DeferredRegister.createItems(ID);
        var plate = blocks.register("glass_pressure_plate", GlassPlate::new);
        var item = items.registerSimpleBlockItem(plate);
        BuildingBlocks.register(bus, blocks, items);
        blocks.register(bus);
        items.register(bus);
        SentryPlatform.register(bus);
        LaunchSystem.register(bus);
        bus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) event.accept(item);
        });
    }
}
