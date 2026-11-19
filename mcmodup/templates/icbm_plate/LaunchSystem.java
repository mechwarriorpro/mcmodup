package mcmodup_kernel.icbm_glass_plate;

import com.mojang.serialization.MapCodec;
import java.util.Comparator;
import java.util.List;
import mcmodup.icbmplate.GlassPlateMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.registries.DeferredRegister;

/** First-tier launcher and a missile-only Sentry receiver. All targeting and flight are server authoritative. */
public final class LaunchSystem extends BaseEntityBlock {
    private static final MapCodec<LaunchSystem> CODEC = simpleCodec(LaunchSystem::new);
    private static final String ID = GlassPlateMod.ID;
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister<BlockEntityType<?>> TILES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, ID);
    static final net.neoforged.neoforge.registries.DeferredBlock<LaunchSystem> BASE = BLOCKS.register("launcher_base", LaunchSystem::new);
    static final net.neoforged.neoforge.registries.DeferredBlock<LaunchSystem> SCREEN = BLOCKS.register("launcher_screen", LaunchSystem::new);
    static final net.neoforged.neoforge.registries.DeferredBlock<LaunchSystem> AA = BLOCKS.register("anti_air_turret", LaunchSystem::new);
    static final net.neoforged.neoforge.registries.DeferredBlock<Block> FRAME = BLOCKS.registerSimpleBlock("launcher_frame", Properties.of().strength(4F).noOcclusion());
    static final net.neoforged.neoforge.registries.DeferredItem<Item> TRAINING = ITEMS.register("training_missile", () -> new Item(new Item.Properties().stacksTo(1)));
    static final net.neoforged.neoforge.registries.DeferredItem<Item> CONDENSED = ITEMS.register("condensed_missile", () -> new Item(new Item.Properties().stacksTo(1)));
    static final net.neoforged.neoforge.registries.DeferredItem<Designator> DESIGNATOR = ITEMS.register("target_designator", Designator::new);
    static final net.neoforged.neoforge.registries.DeferredHolder<BlockEntityType<?>, BlockEntityType<LauncherTile>> TILE = TILES.register("launcher_machine",
        () -> BlockEntityType.Builder.of(LauncherTile::new, BASE.get(), SCREEN.get(), AA.get()).build(null));
    public static final net.neoforged.neoforge.registries.DeferredHolder<EntityType<?>, EntityType<Missile>> MISSILE = ENTITIES.register("ballistic_missile",
        () -> EntityType.Builder.<Missile>of(Missile::new, MobCategory.MISC).sized(.35F, .8F).clientTrackingRange(10).updateInterval(1).build(ID + ":ballistic_missile"));
    public static final int LAUNCH_FE = 500_000_000, AA_SHOT_FE = 12_000;
    private static final int AA_CAPACITY = 240_000, AA_RADIUS = 48;

    public static void register(IEventBus bus) {
        var base = ITEMS.registerSimpleBlockItem(BASE);
        var screen = ITEMS.registerSimpleBlockItem(SCREEN);
        var frame = ITEMS.registerSimpleBlockItem(FRAME);
        var turret = ITEMS.registerSimpleBlockItem(AA);
        BLOCKS.register(bus);
        ITEMS.register(bus);
        TILES.register(bus);
        ENTITIES.register(bus);
        bus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
                for (var item : List.of(base, screen, frame, turret, TRAINING, CONDENSED, DESIGNATOR)) event.accept(item.get());
            }
        });
        bus.addListener((RegisterCapabilitiesEvent event) -> {
            event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, TILE.get(), (tile, side) -> tile.isBase() ? null : tile.power);
            event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, TILE.get(), (tile, side) -> tile.isScreen() ? null : tile.slot);
            event.registerBlockEntity(SentryPlatform.RECEIVER, TILE.get(), (tile, side) -> tile.isAA() ? tile.power : null);
        });
    }

    private LaunchSystem() { this(Properties.of().strength(4F)); }
    private LaunchSystem(Properties properties) { super(properties); }
    @Override protected MapCodec<LaunchSystem> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new LauncherTile(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, TILE.get(), LauncherTile::tick);
    }

    @Override protected ItemInteractionResult useItemOn(ItemStack held, BlockState state, Level level, BlockPos pos, Player player,
                                                       InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof LauncherTile tile) {
            if (tile.isScreen() && held.is(DESIGNATOR.get())) {
                var tag = held.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                if (tag.contains("Target") && tag.getString("Dimension").equals(level.dimension().location().toString())) {
                    tile.setTarget(BlockPos.of(tag.getLong("Target")));
                    player.displayClientMessage(Component.literal("Target: " + tile.target.toShortString()), true);
                } else {
                    player.displayClientMessage(Component.literal("Mark a block in this dimension with the designator first"), true);
                }
            } else if (!tile.isBase() && player.isCreative() && held.is(Items.LIGHTNING_ROD)) {
                tile.power.restore(tile.power.getMaxEnergyStored());
                player.displayClientMessage(Component.literal("Creative test charge filled"), true);
            } else if (!tile.isScreen() && tile.slot.isItemValid(0, held)) {
                ItemStack left = tile.slot.insertItem(0, held, false);
                if (!player.isCreative()) player.setItemInHand(hand, left);
            } else {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof LauncherTile tile) {
            if (tile.isScreen()) {
                boolean launched = player.isShiftKeyDown() && tile.launch(server);
                player.displayClientMessage(Component.literal(launched ? "Missile launched" : tile.status(server)), true);
            } else if (player.isShiftKeyDown()) {
                var stack = tile.slot.extractItem(0, 64, false);
                if (!stack.isEmpty() && !player.addItem(stack)) player.drop(stack, false);
            } else {
                player.displayClientMessage(Component.literal(tile.isAA() ? "Sentry: " + tile.power.getEnergyStored() + " FE · " +
                    tile.slot.getStackInSlot(0).getCount() + " iron rounds" : tile.slot.getStackInSlot(0).isEmpty() ? "Launcher base empty" : "Missile loaded"), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        if (!state.is(replacement.getBlock()) && !level.isClientSide() && level.getBlockEntity(pos) instanceof LauncherTile tile) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), tile.slot.getStackInSlot(0));
        }
        super.onRemove(state, level, pos, replacement, moved);
    }

    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.hasChunkAt(pos) && level.getWorldBorder().isWithinBounds(pos);
    }

    private static final class Designator extends Item {
        Designator() { super(new Item.Properties().stacksTo(1)); }
        @Override public InteractionResult useOn(UseOnContext context) {
            if (context.getPlayer() == null) return InteractionResult.PASS;
            if (!context.getLevel().isClientSide()) {
                var pos = context.getClickedPos();
                CustomData.update(DataComponents.CUSTOM_DATA, context.getItemInHand(), tag -> {
                    tag.putLong("Target", pos.asLong());
                    tag.putString("Dimension", context.getLevel().dimension().location().toString());
                });
                context.getPlayer().displayClientMessage(Component.literal("Marked " + pos.toShortString()), true);
            }
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
        }
    }

    public static final class LauncherTile extends BlockEntity {
        private final Charge power;
        private final ItemStackHandler slot = new ItemStackHandler(1) {
            @Override public boolean isItemValid(int index, ItemStack stack) {
                return isBase() ? stack.is(TRAINING.get()) || stack.is(CONDENSED.get()) : isAA() && stack.is(Items.IRON_NUGGET);
            }
            @Override public int getSlotLimit(int index) { return isBase() ? 1 : 64; }
            @Override protected void onContentsChanged(int index) { setChanged(); }
        };
        private BlockPos target;
        private boolean lastSignal;
        private int cooldown;

        LauncherTile(BlockPos pos, BlockState state) {
            super(TILE.get(), pos, state);
            power = new Charge(state.is(SCREEN.get()) ? LAUNCH_FE : AA_CAPACITY);
        }
        private boolean isBase() { return getBlockState().is(BASE.get()); }
        private boolean isScreen() { return getBlockState().is(SCREEN.get()); }
        private boolean isAA() { return getBlockState().is(AA.get()); }
        public void setTarget(BlockPos pos) { if (isScreen()) { target = pos == null ? null : pos.immutable(); setChanged(); } }

        private LauncherTile base(ServerLevel level) {
            for (var side : Direction.Plane.HORIZONTAL) {
                var pos = getBlockPos().relative(side);
                if (loaded(level, pos) && level.getBlockEntity(pos) instanceof LauncherTile tile && tile.isBase()) return tile;
            }
            return null;
        }
        private static boolean inRange(BlockPos base, BlockPos target) {
            long x = (long) target.getX() - base.getX(), z = (long) target.getZ() - base.getZ();
            // Original tier zero uses a minimum of 10 and the default maximum setting (10000 / 10), exclusive.
            return x * x + z * z >= 100 && x * x + z * z < 1_000_000;
        }
        private boolean frame(ServerLevel level, BlockPos base) {
            for (var side : Direction.Plane.HORIZONTAL) {
                var pos = base.relative(side);
                if (loaded(level, pos) && level.getBlockState(pos).is(FRAME.get())) return true;
            }
            return false;
        }
        public String status(ServerLevel level) {
            if (!isScreen()) return "Not a launcher screen";
            var base = base(level);
            if (base == null || !frame(level, base.getBlockPos())) return "Needs an adjacent launcher base and support frame";
            if (base.slot.getStackInSlot(0).isEmpty()) return "No missile loaded";
            if (target == null || !inRange(base.getBlockPos(), target)) return "Set a target 10–999 blocks away";
            if (!loaded(level, target) || !loaded(level, base.getBlockPos().above()) || !level.getBlockState(base.getBlockPos().above()).isAir()) return "Blocked or unloaded flight path";
            if (power.getEnergyStored() < LAUNCH_FE) return "Charging: " + power.getEnergyStored() + " / " + LAUNCH_FE + " FE";
            return "Ready · sneak-use or send a redstone pulse";
        }
        public boolean launch(ServerLevel level) {
            if (getLevel() != level || !level.getServer().isSameThread() || !isScreen() || power.getEnergyStored() < LAUNCH_FE) return false;
            var base = base(level);
            if (base == null || target == null || !inRange(base.getBlockPos(), target) || !loaded(level, target) ||
                !loaded(level, base.getBlockPos().above()) || !frame(level, base.getBlockPos()) ||
                !level.getBlockState(base.getBlockPos().above()).isAir()) return false;
            var stack = base.slot.getStackInSlot(0);
            if (!base.slot.isItemValid(0, stack)) return false;
            var missile = new Missile(MISSILE.get(), level);
            missile.launch(base.getBlockPos().above(), target, stack.is(TRAINING.get()));
            if (!level.addFreshEntity(missile)) return false;
            base.slot.extractItem(0, 1, false);
            power.restore(0);
            return true;
        }

        public static void tick(Level level, BlockPos pos, BlockState state, LauncherTile tile) {
            if (!(level instanceof ServerLevel server)) return;
            if (tile.isScreen()) {
                boolean signal = level.hasNeighborSignal(pos);
                if (signal && !tile.lastSignal) tile.launch(server);
                if (signal != tile.lastSignal) { tile.lastSignal = signal; tile.setChanged(); }
            } else if (tile.isAA()) tile.intercept(server, pos);
        }

        private void intercept(ServerLevel level, BlockPos pos) {
            if (cooldown > 0) { cooldown--; return; }
            if (power.getEnergyStored() < AA_SHOT_FE || !slot.getStackInSlot(0).is(Items.IRON_NUGGET)) return;
            var center = Vec3.atCenterOf(pos).add(0, .65, 0);
            var target = level.getEntitiesOfClass(Missile.class, new AABB(pos).inflate(AA_RADIUS), missile ->
                missile.getTicksInAir() > 5 && missile.position().distanceToSqr(center) <= AA_RADIUS * AA_RADIUS
                && level.clip(new ClipContext(center, missile.position(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, missile)).getType() == HitResult.Type.MISS)
                .stream().min(Comparator.comparingDouble(missile -> missile.position().distanceToSqr(center))).orElse(null);
            if (target == null) return;
            slot.extractItem(0, 1, false);
            power.restore(power.getEnergyStored() - AA_SHOT_FE);
            cooldown = 5;
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, target.getX(), target.getY(), target.getZ(), 14, .2, .2, .2, .01);
            target.discard();
        }

        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
            super.saveAdditional(tag, lookup);
            tag.put("inventory", slot.serializeNBT(lookup));
            tag.putInt("energy", power.getEnergyStored());
            tag.putBoolean("signal", lastSignal);
            if (target != null) tag.putLong("target", target.asLong());
        }
        @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
            super.loadAdditional(tag, lookup);
            if (tag.contains("inventory")) slot.deserializeNBT(lookup, tag.getCompound("inventory"));
            power.restore(tag.getInt("energy"));
            lastSignal = tag.getBoolean("signal");
            target = tag.contains("target") ? BlockPos.of(tag.getLong("target")) : null;
        }

        private final class Charge extends EnergyStorage {
            Charge(int max) { super(max, max == LAUNCH_FE ? LAUNCH_FE / 50 : AA_CAPACITY, 0); }
            @Override public int receiveEnergy(int amount, boolean simulate) {
                int received = super.receiveEnergy(Math.max(amount, 0), simulate);
                if (received > 0 && !simulate) setChanged();
                return received;
            }
            void restore(int value) { energy = Math.clamp(value, 0, getMaxEnergyStored()); setChanged(); }
        }
    }

    public static final class Missile extends Entity implements ItemSupplier {
        private static final EntityDataAccessor<Boolean> INERT = SynchedEntityData.defineId(Missile.class, EntityDataSerializers.BOOLEAN);
        private BlockPos origin, target;
        private int air;
        public Missile(EntityType<? extends Missile> type, Level level) { super(type, level); }
        @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { builder.define(INERT, true); }
        @Override public ItemStack getItem() { return new ItemStack(entityData.get(INERT) ? TRAINING.get() : CONDENSED.get()); }
        public int getTicksInAir() { return air; }
        void launch(BlockPos start, BlockPos end, boolean practice) {
            origin = start;
            target = end;
            setPos(Vec3.atBottomCenterOf(start).add(0, .15, 0));
            entityData.set(INERT, practice);
        }
        @Override public void tick() {
            super.tick();
            if (!(level() instanceof ServerLevel level)) return;
            if (origin == null || target == null || !LauncherTile.inRange(origin, target)) { discard(); return; }
            Vec3 start = Vec3.atBottomCenterOf(origin).add(0, .15, 0), end = Vec3.atBottomCenterOf(target.above()).add(0, .15, 0);
            double horizontal = Math.hypot(end.x - start.x, end.z - start.z);
            double t = Math.min(1D, ++air / (30D + horizontal));
            Vec3 next = start.lerp(end, t).add(0, Math.sin(Math.PI * t) * Math.min(32D, 7D + horizontal / 4D), 0);
            if (!loaded(level, BlockPos.containing(next))) { discard(); return; }
            var hit = level.clip(new ClipContext(position(), next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            if (hit.getType() != HitResult.Type.MISS || t >= 1D) {
                Vec3 impact = hit.getType() == HitResult.Type.MISS ? next : hit.getLocation();
                if (!entityData.get(INERT)) level.explode(this, impact.x, impact.y, impact.z, 2.5F, Level.ExplosionInteraction.TNT);
                level.sendParticles(ParticleTypes.POOF, impact.x, impact.y, impact.z, 14, .25, .25, .25, .04);
                discard();
            } else {
                setPos(next);
                level.sendParticles(ParticleTypes.SMOKE, next.x, next.y, next.z, 1, 0, .04, 0, .01);
            }
        }
        @Override protected void readAdditionalSaveData(CompoundTag tag) {
            origin = tag.contains("origin") ? BlockPos.of(tag.getLong("origin")) : null;
            target = tag.contains("target") ? BlockPos.of(tag.getLong("target")) : null;
            air = Math.clamp(tag.getInt("air"), 0, 1030);
            entityData.set(INERT, !tag.contains("inert") || tag.getBoolean("inert"));
        }
        @Override protected void addAdditionalSaveData(CompoundTag tag) {
            if (origin != null) tag.putLong("origin", origin.asLong());
            if (target != null) tag.putLong("target", target.asLong());
            tag.putInt("air", air);
            tag.putBoolean("inert", entityData.get(INERT));
        }
    }
}
