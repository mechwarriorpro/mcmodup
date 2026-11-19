package mcmodup_kernel.atomic_thermometer;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/** Voltz in-game steam collection, separate from arbitrary liquid transport. */
public final class SteamFunnel extends BaseEntityBlock {
    private static final MapCodec<SteamFunnel> CODEC = simpleCodec(SteamFunnel::new);
    private static final int CAPACITY = 16_000, REACH = 2;
    static final float BOILING_POINT = 373F;
    // Only tiles that advertise this capability participate in heated-water collection.
    public static final BlockCapability<IFluidHandler, Direction> BOIL_RECEIVER =
        BlockCapability.createSided(ResourceLocation.fromNamespaceAndPath(ThermometerMod.ID, "boil_receiver"), IFluidHandler.class);

    SteamFunnel(Properties properties) { super(properties); }

    static void register(IEventBus bus) {
        bus.addListener((RegisterCapabilitiesEvent event) -> {
            event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ThermometerMod.COLLECTOR.get(), (tile, side) ->
                side == Direction.DOWN ? tile.input : side == Direction.UP ? tile.output : null);
            event.registerBlockEntity(BOIL_RECEIVER, ThermometerMod.COLLECTOR.get(), (tile, side) ->
                side == Direction.DOWN ? tile.input : null);
        });
    }

    /** Called within the server-level heat grid update for an active water block. */
    static void boil(ServerLevel level, BlockPos water, float temperature, boolean source) {
        int batch = (int) (1000F * (temperature / BOILING_POINT));
        for (int height = 1; height <= REACH; height++) {
            var target = water.above(height);
            if (level.isOutsideBuildHeight(target) || !level.hasChunkAt(target)) continue;
            var receiver = level.getCapability(BOIL_RECEIVER, target, Direction.DOWN);
            if (receiver != null) {
                // Separate offers at each height (500 mB at one block and 1,000 at two, at 373 K).
                int offered = (int) (batch * (height / (float) REACH));
                receiver.fill(Turbine.steam(offered), FluidAction.EXECUTE);
            }
        }
        if (source) level.setBlockAndUpdate(water, Blocks.AIR.defaultBlockState());
    }

    @Override protected MapCodec<SteamFunnel> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Collector(pos, state); }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, ThermometerMod.COLLECTOR.get(), Collector::tick);
    }

    private static boolean gas(FluidStack stack) { return !stack.isEmpty() && stack.getFluidType().isLighterThanAir(); }

    static final class Collector extends BlockEntity {
        private final FluidTank tank = new FluidTank(CAPACITY, SteamFunnel::gas) {
            @Override protected void onContentsChanged() { setChanged(); }
        };
        private final IFluidHandler input = new Port(true), output = new Port(false);

        Collector(BlockPos pos, BlockState state) { super(ThermometerMod.COLLECTOR.get(), pos, state); }

        static void tick(Level level, BlockPos pos, BlockState state, Collector collector) {
            if (collector.tank.isEmpty()) return;
            var above = pos.above();
            if (level.isOutsideBuildHeight(above) || !level.hasChunkAt(above)) return;
            var handler = level.getCapability(Capabilities.FluidHandler.BLOCK, above, Direction.DOWN);
            if (handler == null) return;
            var offered = collector.tank.getFluid();
            int accepted = Math.clamp(handler.fill(offered.copy(), FluidAction.EXECUTE), 0, offered.getAmount());
            if (accepted > 0) collector.tank.drain(accepted, FluidAction.EXECUTE);
        }

        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            tank.writeToNBT(registries, tag);
        }

        @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            // Unlike the legacy tile's reversed read/write calls, retain valid gas across saves.
            var saved = FluidStack.parseOptional(registries, tag.getCompound("Fluid"));
            tank.setFluid(gas(saved) ? saved.copyWithAmount(Math.min(saved.getAmount(), CAPACITY)) : FluidStack.EMPTY);
        }

        private final class Port implements IFluidHandler {
            private final boolean fills;
            Port(boolean fills) { this.fills = fills; }
            @Override public int getTanks() { return 1; }
            @Override public FluidStack getFluidInTank(int slot) { return slot == 0 ? tank.getFluid().copy() : FluidStack.EMPTY; }
            @Override public int getTankCapacity(int slot) { return slot == 0 ? CAPACITY : 0; }
            @Override public boolean isFluidValid(int slot, FluidStack resource) { return slot == 0 && fills && gas(resource); }
            @Override public int fill(FluidStack resource, FluidAction action) { return fills ? tank.fill(resource, action) : 0; }
            @Override public FluidStack drain(FluidStack resource, FluidAction action) {
                return fills ? FluidStack.EMPTY : tank.drain(resource, action);
            }
            @Override public FluidStack drain(int amount, FluidAction action) {
                return fills || amount <= 0 ? FluidStack.EMPTY : tank.drain(amount, action);
            }
        }
    }
}
