package mcmodup.geostratarocks;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(GeoRocks.ID)
public final class GeoRocks {
    public static final String ID = "geostrata_rocks";
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);
    private static final List<DeferredItem<?>> ROCKS = new ArrayList<>();

    public GeoRocks(IEventBus bus) {
@REGISTRATIONS@
        GeoCrystals.register(bus, BLOCKS, ITEMS, ROCKS);
        TABS.register("rocks", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup." + ID + ".rocks"))
            .icon(() -> ROCKS.getFirst().get().getDefaultInstance())
            .displayItems((parameters, output) -> ROCKS.forEach(item -> output.accept(item.get())))
            .build());
        BLOCKS.register(bus);
        ITEMS.register(bus);
        TABS.register(bus);
    }

    private static void register(String name, String family, float hardness, float resistance, boolean connected) {
        var block = BLOCKS.register(name, () -> {
            var properties = BlockBehaviour.Properties.of().strength(hardness, resistance).requiresCorrectToolForDrops();
            return connected ? new ConnectedRock(family, properties) : new RockBlock(family, properties);
        });
        ROCKS.add(ITEMS.registerSimpleBlockItem(block));
    }
}
