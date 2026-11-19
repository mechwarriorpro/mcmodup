package mcmodup_kernel.atomic_thermometer;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** A single-cell turbine with the measured Voltz steam consumption path and native FE. */
public final class Turbine extends BaseEntityBlock {
    private static final MapCodec<Turbine> CODEC = simpleCodec(Turbine::new);
    private static final TagKey<Fluid> STEAM_TAG = TagKey.create(Registries.FLUID, ResourceLocation.fromNamespaceAndPath("c", "steam"));
    private static final int STEAM_CAPACITY = 100_000, OUTPUT_CAPACITY = 100_000_000;
    private static final int ENERGY_PER_MB = 2647, SINGLE_ROTOR_LIMIT = 5_000_000 / ENERGY_PER_MB;
    private static final float SINGLE_ROTOR_TORQUE = 2_500_000F;
    private static final DeferredRegister<FluidType> TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, ThermometerMod.ID);
    private static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(Registries.FLUID, ThermometerMod.ID);
    static final DeferredHolder<FluidType, FluidType> STEAM_TYPE = TYPES.register("steam",
        () -> new FluidType(FluidType.Properties.create().descriptionId("fluid.atomic_thermometer.steam").density(-1000).temperature(373)));
    private static final DeferredHolder<Fluid, BaseFlowingFluid.Source> STEAM = FLUIDS.register("steam", () -> new BaseFlowingFluid.Source(steamProperties()));
    private static final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> FLOWING_STEAM = FLUIDS.register("flowing_steam", () -> new BaseFlowingFluid.Flowing(steamProperties()));

    Turbine(Properties properties) { super(properties); }

    static void register(IEventBus bus) {
        TYPES.register(bus);
        FLUIDS.register(bus);
        bus.addListener((RegisterCapabilitiesEvent event) -> {
            event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ThermometerMod.ROTOR.get(), (tile, side) ->
                side == Direction.DOWN ? tile.steam : null);
            event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ThermometerMod.ROTOR.get(), (tile, side) ->
                side == Direction.UP ? tile.output : null);
        });
    }

    private static BaseFlowingFluid.Properties steamProperties() {
        // Gas in transport pipes/tanks only. Do not register a bucket or liquid block.
        return new BaseFlowingFluid.Properties(STEAM_TYPE, STEAM, FLOWING_STEAM);
    }

    @Override protected MapCodec<Turbine> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Rotor(pos, state); }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, ThermometerMod.ROTOR.get(), Rotor::tick);
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof Rotor rotor) {
            player.displayClientMessage(Component.translatable("info.atomic_thermometer.turbine", rotor.steam.getFluidAmount(),
                rotor.output.getEnergyStored(), Float.toString(rotor.angularVelocity())), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    public static final class Rotor extends BlockEntity {
        private final SteamTank steam = new SteamTank();
        private final Output output = new Output();
        private float angularVelocity;

        Rotor(BlockPos pos, BlockState state) { super(ThermometerMod.ROTOR.get(), pos, state); }

        static void tick(Level level, BlockPos pos, BlockState state, Rotor rotor) {
            int available = rotor.steam.getFluidAmount();
            if (available == 0) {
                rotor.angularVelocity = 0F;
                return;
            }
            int used = (int) Math.ceil(Math.min(available * 0.1D, SINGLE_ROTOR_LIMIT));
            int produced = used * ENERGY_PER_MB;
            rotor.angularVelocity = produced / SINGLE_ROTOR_TORQUE;
            rotor.steam.consume(used);
            rotor.output.add(produced);
            // As in the original standalone tile, only the upper neighbor is offered output.
            BlockPos above = pos.above();
            if (level.isOutsideBuildHeight(above) || !level.hasChunkAt(above)) return;
            var consumer = level.getCapability(Capabilities.EnergyStorage.BLOCK, above, Direction.DOWN);
            if (consumer != null && consumer.canReceive()) {
                int offered = rotor.output.getEnergyStored();
                int accepted = Math.clamp(consumer.receiveEnergy(offered, false), 0, offered);
                if (accepted > 0) rotor.output.extractEnergy(accepted, false);
            }
        }

        public float angularVelocity() { return angularVelocity; }

        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            tag.putInt("steam", steam.getFluidAmount());
            tag.putInt("energy", output.getEnergyStored());
        }

        @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            angularVelocity = 0F;
            int saved = Math.clamp(tag.getInt("steam"), 0, STEAM_CAPACITY);
            steam.setFluid(saved == 0 ? FluidStack.EMPTY : new FluidStack(STEAM.get(), saved));
            output.restore(tag.getInt("energy"));
        }

        private final class SteamTank extends FluidTank {
            SteamTank() { super(STEAM_CAPACITY, stack -> !stack.isEmpty() && (stack.is(STEAM.get()) || stack.is(STEAM_TAG))); }
            @Override public int fill(FluidStack stack, FluidAction action) {
                if (!isFluidValid(stack)) return 0;
                return super.fill(new FluidStack(STEAM.get(), stack.getAmount()), action);
            }
            @Override public FluidStack getFluidInTank(int tank) { return tank == 0 ? getFluid().copy() : FluidStack.EMPTY; }
            @Override public int getTankCapacity(int tank) { return tank == 0 ? super.getTankCapacity(tank) : 0; }
            @Override public boolean isFluidValid(int tank, FluidStack stack) { return tank == 0 && isFluidValid(stack); }
            @Override public FluidStack drain(FluidStack stack, FluidAction action) { return FluidStack.EMPTY; }
            @Override public FluidStack drain(int limit, FluidAction action) { return FluidStack.EMPTY; }
            void consume(int amount) { super.drain(amount, FluidAction.EXECUTE); }
            @Override protected void onContentsChanged() { setChanged(); }
        }

        private final class Output extends EnergyStorage {
            Output() { super(OUTPUT_CAPACITY, 0, OUTPUT_CAPACITY); }
            void restore(int saved) { energy = Math.clamp(saved, 0, OUTPUT_CAPACITY); }
            void add(int amount) { restore(energy + amount); setChanged(); }
            @Override public int extractEnergy(int amount, boolean simulate) {
                int spent = super.extractEnergy(Math.max(0, amount), simulate);
                if (spent > 0 && !simulate) setChanged();
                return spent;
            }
        }
    }
}
