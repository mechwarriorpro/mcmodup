package mcmodup.mimicrycatalyst;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(CatalystMod.ID)
public final class CatalystMod {
    public static final String ID = "mimicry_catalyst";
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredItem<Item> MIMICHITE = ITEMS.register("mimichite", () -> new Item(new Item.Properties()));

    public CatalystMod(IEventBus bus) {
        ITEMS.register(bus);
        bus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) event.accept(MIMICHITE);
        });
    }
}
