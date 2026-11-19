package mcmodup_kernel.atomic_thermometer;

import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
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
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Native adaptation of TileAccelerator and its electromagnet beam path. */
public final class Accelerator extends BaseEntityBlock {
    private static final MapCodec<Accelerator> CODEC = simpleCodec(Accelerator::new);
    private static final String ID = ThermometerMod.ID;
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister<BlockEntityType<?>> TILES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    public static final int CAPACITY = 9_600_000, STEP_COST = 240_000, CELL_MG = 125;
    private static final int COOLDOWN = 64, MAX_TRACK = 160;
    private static final float MAX_SPEED = 0.9F, ACCELERATION = 0.0006F;
    static final net.neoforged.neoforge.registries.DeferredBlock<Accelerator> BLOCK = BLOCKS.register("particle_accelerator", () -> new Accelerator(Properties.of().strength(3.5F)));
    static final net.neoforged.neoforge.registries.DeferredBlock<Block> MAGNET = BLOCKS.registerSimpleBlock("electromagnet", Properties.of().strength(3.5F));
    static final net.neoforged.neoforge.registries.DeferredBlock<Block> GLASS = BLOCKS.registerSimpleBlock("electromagnet_glass", Properties.of().strength(1.5F).noOcclusion());
    static final net.neoforged.neoforge.registries.DeferredBlock<CreativeBuilder> BUILDER = BLOCKS.register("creative_builder", () -> new CreativeBuilder(Properties.of().strength(3.5F)));
    private static final net.neoforged.neoforge.registries.DeferredItem<Item> EMPTY_CELL = ITEMS.registerSimpleItem("empty_cell");
    private static final net.neoforged.neoforge.registries.DeferredItem<Item> ANTIMATTER = ITEMS.registerSimpleItem("antimatter_cell");
    private static final net.neoforged.neoforge.registries.DeferredHolder<BlockEntityType<?>, BlockEntityType<Beam>> BEAM = TILES.register("particle_accelerator",
        () -> BlockEntityType.Builder.of(Beam::new, BLOCK.get()).build(null));

    static void register(IEventBus bus) {
        var accelerator = ITEMS.registerSimpleBlockItem(BLOCK);
        var magnet = ITEMS.registerSimpleBlockItem(MAGNET);
        var glass = ITEMS.registerSimpleBlockItem(GLASS);
        var builder = ITEMS.registerSimpleBlockItem(BUILDER);
        ITEMS.register(bus);
        BLOCKS.register(bus);
        TILES.register(bus);
        bus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() != CreativeModeTabs.FUNCTIONAL_BLOCKS) return;
            for (var entry : List.of(accelerator, magnet, glass, builder, EMPTY_CELL, ANTIMATTER)) event.accept(entry.get());
        });
        bus.addListener((RegisterCapabilitiesEvent event) -> {
            event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, BEAM.get(), (tile, side) -> tile.power);
            event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, BEAM.get(), (tile, side) -> tile.slots);
        });
    }

    Accelerator(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
    }

    @Override protected MapCodec<Accelerator> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, context.getHorizontalDirection());
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.HORIZONTAL_FACING);
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Beam(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, BEAM.get(), Beam::tick);
    }

    @Override protected ItemInteractionResult useItemOn(ItemStack held, BlockState state, Level level, BlockPos pos,
                                                       Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof Beam beam) {
            if (player.isCreative() && held.is(Items.LIGHTNING_ROD)) {
                beam.creativeFeed = !beam.creativeFeed;
                player.displayClientMessage(Component.literal("Creative beam feed " + (beam.creativeFeed ? "on" : "off")), true);
            } else {
                int slot = held.is(EMPTY_CELL.get()) ? 1 : 0;
                var left = beam.slots.insertItem(slot, held, false);
                if (!player.isCreative()) player.setItemInHand(hand, left);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof Beam beam) {
            if (player.isShiftKeyDown()) {
                int slot = !beam.slots.getStackInSlot(2).isEmpty() ? 2 : !beam.slots.getStackInSlot(1).isEmpty() ? 1 : 0;
                var stack = beam.slots.getStackInSlot(slot).copy();
                beam.slots.setStackInSlot(slot, ItemStack.EMPTY);
                if (!stack.isEmpty() && !player.addItem(stack)) player.drop(stack, false);
            } else {
                player.displayClientMessage(Component.literal("Beam: " + beam.status + " · " + beam.antimatter + " mg · " + beam.power.getEnergyStored() + " FE"), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        if (!state.is(replacement.getBlock()) && !level.isClientSide() && level.getBlockEntity(pos) instanceof Beam beam) {
            for (int i = 0; i < 4; i++) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), beam.slots.getStackInSlot(i));
        }
        super.onRemove(state, level, pos, replacement, moved);
    }

    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.hasChunkAt(pos) && level.getWorldBorder().isWithinBounds(pos);
    }
    private static boolean magnet(ServerLevel level, BlockPos pos) {
        if (!loaded(level, pos)) return false;
        var state = level.getBlockState(pos);
        return state.is(MAGNET.get()) || state.is(GLASS.get());
    }
    private static boolean guide(ServerLevel level, BlockPos pos) {
        return loaded(level, pos) && level.getBlockState(pos).isAir() && magnet(level, pos.above()) && magnet(level, pos.below());
    }
    private static List<BlockPos> trace(ServerLevel level, BlockPos start) {
        if (!guide(level, start)) return List.of();
        var visited = new HashSet<BlockPos>();
        var path = new ArrayList<BlockPos>();
        BlockPos current = start, from = null;
        while (path.size() < MAX_TRACK && visited.add(current)) {
            path.add(current);
            var next = new ArrayList<BlockPos>(2);
            for (var side : Direction.Plane.HORIZONTAL) {
                var candidate = current.relative(side);
                if (guide(level, candidate) && !candidate.equals(from)) next.add(candidate);
            }
            if (next.size() != (from == null ? 2 : 1)) return List.of();
            var chosen = next.getFirst();
            if (chosen.equals(start)) return path.size() >= 8 ? List.copyOf(path) : List.of();
            from = current;
            current = chosen;
        }
        return List.of();
    }

    public static final class Beam extends BlockEntity {
        private final Charge power = new Charge();
        private final ItemStackHandler slots = new ItemStackHandler(4) {
            @Override public boolean isItemValid(int slot, ItemStack stack) {
                return slot == 0 && !stack.is(EMPTY_CELL.get()) || slot == 1 && stack.is(EMPTY_CELL.get());
            }
            @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
                return slot >= 2 && amount > 0 ? super.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
            }
            @Override protected void onContentsChanged(int slot) { setChanged(); }
        };
        private int antimatter, delay;
        private float speed, distance;
        private List<BlockPos> path = List.of();
        private String status = "idle";
        private boolean creativeFeed;

        Beam(BlockPos pos, BlockState state) { super(BEAM.get(), pos, state); }

        public static void tick(Level level, BlockPos pos, BlockState state, Beam tile) {
            if (level instanceof ServerLevel server) tile.advance(server, pos, state);
        }

        private void advance(ServerLevel level, BlockPos pos, BlockState state) {
            var output = slots.getStackInSlot(2);
            var cell = slots.getStackInSlot(1);
            if (antimatter >= CELL_MG && cell.is(EMPTY_CELL.get()) && (output.isEmpty() || output.is(ANTIMATTER.get()) && output.getCount() < output.getMaxStackSize())) {
                slots.setStackInSlot(1, cell.copyWithCount(cell.getCount() - 1));
                slots.setStackInSlot(2, new ItemStack(ANTIMATTER.get(), output.getCount() + 1));
                antimatter -= CELL_MG;
                setChanged();
            }
            if (delay < COOLDOWN) delay++;
            if (!level.hasNeighborSignal(pos)) { stop("needs redstone"); return; }
            if (!creativeFeed && power.getEnergyStored() < STEP_COST) { stop("needs FE"); return; }
            if (path.isEmpty()) {
                if (delay < COOLDOWN || slots.getStackInSlot(0).isEmpty()) { status = "needs material"; return; }
                path = trace(level, pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING)));
                if (path.isEmpty()) { status = "open or obstructed magnet loop"; return; }
                var input = slots.getStackInSlot(0);
                slots.setStackInSlot(0, input.copyWithCount(input.getCount() - 1));
                delay = 0;
            }
            int index = ((int) distance) % path.size();
            BlockPos from = path.get(index), to = path.get((index + 1) % path.size());
            if (!guide(level, from) || !guide(level, to)) { stop("beam lost confinement"); return; }
            if (!creativeFeed) power.spend();
            speed += ACCELERATION * (creativeFeed ? 10F : 1F);
            distance += speed;
            status = "circulating";
            if ((level.getGameTime() & 1) == 0) {
                double fraction = distance % 1.0;
                level.sendParticles(ParticleTypes.END_ROD, from.getX() + .5 + (to.getX() - from.getX()) * fraction,
                    from.getY() + .5, from.getZ() + .5 + (to.getZ() - from.getZ()) * fraction, 1, 0, 0, 0, 0);
            }
            if (speed < MAX_SPEED) return;
            antimatter = Math.min(16_000, antimatter + 5 + level.getRandom().nextInt(5));
            stop("collected antimatter");
            setChanged();
        }

        private void stop(String reason) { path = List.of(); speed = 0F; distance = 0F; status = reason; }

        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
            super.saveAdditional(tag, lookup);
            tag.putInt("energy", power.getEnergyStored());
            tag.putInt("antimatter_mg", antimatter);
            tag.put("inventory", slots.serializeNBT(lookup));
        }
        @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
            super.loadAdditional(tag, lookup);
            power.restore(tag.getInt("energy"));
            antimatter = Math.clamp(tag.getInt("antimatter_mg"), 0, 16_000);
            if (tag.contains("inventory")) slots.deserializeNBT(lookup, tag.getCompound("inventory"));
            creativeFeed = false;
            stop("idle");
        }

        private final class Charge extends EnergyStorage {
            Charge() { super(CAPACITY, STEP_COST, 0); }
            @Override public int receiveEnergy(int amount, boolean simulate) {
                int received = super.receiveEnergy(Math.max(0, amount), simulate);
                if (!simulate && received > 0) setChanged();
                return received;
            }
            void restore(int saved) { energy = Math.clamp(saved, 0, CAPACITY); }
            void spend() { energy -= STEP_COST; setChanged(); }
        }
    }

    public static final class CreativeBuilder extends Block {
        private static final MapCodec<CreativeBuilder> CODEC = simpleCodec(CreativeBuilder::new);
        CreativeBuilder(Properties properties) { super(properties); }
        @Override protected MapCodec<CreativeBuilder> codec() { return CODEC; }
        @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (!player.isCreative()) return InteractionResult.PASS;
            if (level instanceof ServerLevel server) {
                boolean built = build(server, pos.above(2), player.isShiftKeyDown() ? 7 : 5);
                player.displayClientMessage(Component.literal(built ? "Accelerator ring built" : "Need a loaded, empty area above the builder"), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }

        /** Original nested ring with a native injector in its outer wall. */
        public static boolean build(ServerLevel level, BlockPos origin, int size) {
            if (!level.getServer().isSameThread() || size != 5 && size != 7) return false;
            var plan = new java.util.LinkedHashMap<BlockPos, BlockState>();
            for (int radius = size; radius >= size - 2; radius--) {
                for (int x = -radius; x < radius; x++) for (int z = -radius; z < radius; z++) {
                    if (x != -radius && x != radius - 1 && z != -radius && z != radius - 1) continue;
                    for (int y = -1; y <= 1; y++) {
                        var pos = origin.offset(x, y, z);
                        if (!loaded(level, pos) || !level.getBlockState(pos).isAir()) return false;
                        if (radius == size - 1 && y == 0) continue;
                        var block = radius == size - 1 ? GLASS.get() : MAGNET.get();
                        plan.put(pos, block.defaultBlockState());
                    }
                }
            }
            plan.put(origin.west(size), BLOCK.get().defaultBlockState());
            for (var entry : plan.entrySet()) level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_CLIENTS);
            return true;
        }
    }
}
