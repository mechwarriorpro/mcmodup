package mcmodup.icbmplate;

import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The three legacy concrete metadata values are separate blocks on the modern registry. */
final class BuildingBlocks {
    private BuildingBlocks() {}

    static void register(IEventBus bus, DeferredRegister.Blocks blocks, DeferredRegister.Items items) {
        var concrete = blocks.registerSimpleBlock("concrete", concrete(30F));
        var compact = blocks.registerSimpleBlock("compact_concrete", concrete(38F));
        var reinforced = blocks.registerSimpleBlock("reinforced_concrete", concrete(48F));
        var glass = blocks.register("reinforced_glass", () -> new ReinforcedGlass(BlockBehaviour.Properties.of()
            .strength(0.6F, 28.8F).sound(SoundType.GLASS).noOcclusion()));
        var entries = List.of(items.registerSimpleBlockItem(concrete), items.registerSimpleBlockItem(compact),
            items.registerSimpleBlockItem(reinforced), items.registerSimpleBlockItem(glass));
        bus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) entries.forEach(entry -> event.accept(entry.get()));
        });
    }

    private static BlockBehaviour.Properties concrete(float resistance) {
        return BlockBehaviour.Properties.of().strength(3.8F, resistance).sound(SoundType.METAL).requiresCorrectToolForDrops();
    }

    private static final class ReinforcedGlass extends Block {
        ReinforcedGlass(Properties properties) { super(properties); }
        @Override public boolean skipRendering(BlockState state, BlockState neighbor, Direction direction) {
            return neighbor.is(this) || super.skipRendering(state, neighbor, direction);
        }
    }
}
