package mcmodup_kernel.atomic_thermometer;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.BlockCapability;

public final class Thermometer extends BaseEntityBlock {
    public static final BlockCapability<TemperatureSource, Void> TEMPERATURE = BlockCapability.createVoid(
        ResourceLocation.fromNamespaceAndPath(ThermometerMod.ID, "temperature"), TemperatureSource.class);
    public interface TemperatureSource { float sample(); }
    private static final String TRACKED = "atomic_thermometer:tracked";
    private static final String THRESHOLD = "threshold";
    private static final MapCodec<Thermometer> CODEC = simpleCodec(Thermometer::new);

    Thermometer(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.POWERED, false));
    }

    @Override protected MapCodec<Thermometer> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.POWERED);
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Sensor(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, ThermometerMod.SENSOR.get(), Sensor::tick);
    }
    @Override protected boolean isSignalSource(BlockState state) { return true; }
    @Override protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(BlockStateProperties.POWERED) ? 15 : 0;
    }
    @Override protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return getSignal(state, level, pos, direction);
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof Sensor sensor) {
            sensor.adjust(player.isShiftKeyDown(), hit.getDirection().get3DDataValue(), hit.getLocation(), false);
            player.displayClientMessage(Component.translatable("info." + ThermometerMod.ID + ".status", sensor.threshold, sensor.temperature), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof Sensor sensor) sensor.track(target(stack));
    }

    static void target(ItemStack stack, BlockPos pos) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            if (pos == null) tag.remove(TRACKED);
            else tag.putLong(TRACKED, pos.asLong());
        });
    }

    static BlockPos target(ItemStack stack) {
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.contains(TRACKED, Tag.TAG_LONG) ? BlockPos.of(tag.getLong(TRACKED)) : null;
    }

    public static final class Sensor extends BlockEntity {
        private final Access access = new Access(this);
        private int threshold = 1000;
        private float temperature = 295F;
        private int ticks;
        private BlockPos tracked;

        Sensor(BlockPos pos, BlockState state) { super(ThermometerMod.SENSOR.get(), pos, state); }

        void adjust(boolean crouching, int side, Vec3 cursor, boolean fine) {
            if (fine) ThermometerLogic.configure(access, new User(crouching), side, new Hit(cursor));
            else ThermometerLogic.use(access, new User(crouching), side, new Hit(cursor));
        }

        void track(BlockPos pos) {
            tracked = pos == null ? null : pos.immutable();
            setChanged();
        }

        static void tick(Level level, BlockPos pos, BlockState state, Sensor sensor) {
            if (++sensor.ticks < 10) return;
            sensor.ticks = 0;
            var samplePosition = sensor.tracked == null ? pos : sensor.tracked;
            // A saved tracking coordinate must not generate or load chunks.
            if (level.isOutsideBuildHeight(samplePosition) || !level.hasChunkAt(samplePosition)) return;
            var provider = level.getCapability(TEMPERATURE, samplePosition, null);
            float sample = provider == null ? Float.NaN : provider.sample();
            if (!Float.isFinite(sample)) {
                sample = level instanceof ServerLevel server ? HeatGrid.sample(server, samplePosition) : HeatGrid.ambient(level, samplePosition);
            }
            sensor.temperature = sample;
            sensor.refreshSignal();
        }

        private void refreshSignal() {
            if (level == null || level.isClientSide()) return;
            var state = getBlockState();
            boolean power = ThermometerLogic.isOverThreshold(access);
            if (state.getValue(BlockStateProperties.POWERED) != power) {
                level.setBlock(getBlockPos(), state.setValue(BlockStateProperties.POWERED, power), Block.UPDATE_ALL);
            }
        }

        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            tag.putInt(THRESHOLD, threshold);
            if (tracked != null) tag.putLong(TRACKED, tracked.asLong());
        }

        @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            int saved = tag.getInt(THRESHOLD);
            threshold = saved >= 1 && saved <= 5000 ? saved : 1000;
            tracked = tag.contains(TRACKED, Tag.TAG_LONG) ? BlockPos.of(tag.getLong(TRACKED)) : null;
        }
    }

    // Public, stack-shaped adapter boundary for the four imported methods.
    public record User(boolean crouching) {}
    public record Hit(Vec3 position) {}
    public static final class Access {
        private final Sensor sensor;
        private Access(Sensor sensor) { this.sensor = sensor; }
        public static int threshold(Access receiver) { return receiver.sensor.threshold; }
        public static float sample(Access receiver) { return receiver.sensor.temperature; }
        public static void storeThreshold(Access receiver, int value) { receiver.sensor.threshold = value; }
        public static void setThreshold(Access receiver, int value) { ThermometerLogic.setThreshold(receiver, value); }
        public static void refresh(Access receiver) {
            receiver.sensor.setChanged();
            receiver.sensor.refreshSignal();
        }
        public static boolean crouching(User user) { return user.crouching(); }
    }
}
