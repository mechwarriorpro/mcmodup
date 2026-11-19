package mcmodup_kernel.atomic_thermometer;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Test-only FE supplier, with no survival recipe. Use an ordinary FE generator for survival. */
public final class CreativeSupply extends BaseEntityBlock {
    private static final MapCodec<CreativeSupply> CODEC = simpleCodec(CreativeSupply::new);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ThermometerMod.ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ThermometerMod.ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ThermometerMod.ID);
    private static final net.neoforged.neoforge.registries.DeferredBlock<CreativeSupply> BLOCK = BLOCKS.register("creative_fe_supply",
        () -> new CreativeSupply(Properties.of().strength(-1.0F, 3_600_000F).noLootTable().lightLevel(state -> 10)));
    private static final net.neoforged.neoforge.registries.DeferredHolder<BlockEntityType<?>, BlockEntityType<Supply>> TILE = ENTITIES.register("creative_fe_supply",
        () -> BlockEntityType.Builder.of(Supply::new, BLOCK.get()).build(null));

    static void register(IEventBus bus) {
        var item = ITEMS.registerSimpleBlockItem(BLOCK);
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        bus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) event.accept(item.get());
        });
    }

    private CreativeSupply(Properties properties) { super(properties); }
    @Override protected MapCodec<CreativeSupply> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Supply(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, TILE.get(), CreativeSupply::tick);
    }

    private static void tick(Level level, BlockPos pos, BlockState state, Supply supply) {
        if (!(level instanceof ServerLevel server)) return;
        for (var direction : Direction.values()) {
            var neighbor = pos.relative(direction);
            if (level.isOutsideBuildHeight(neighbor) || !level.hasChunkAt(neighbor)) continue;
            var sink = server.getCapability(Capabilities.EnergyStorage.BLOCK, neighbor, direction.getOpposite());
            if (sink != null && sink.canReceive()) sink.receiveEnergy(20_000_000, false);
        }
    }

    private static final class Supply extends BlockEntity {
        Supply(BlockPos pos, BlockState state) { super(TILE.get(), pos, state); }
    }
}
