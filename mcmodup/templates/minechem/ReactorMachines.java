package @PACKAGE@;

import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.RangedWrapper;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import static @PACKAGE@.ReactorBlueprints.*;

/** Original projector geometry and the two historical reaction profiles on native blocks. */
final class ReactorMachines {
    static final boolean RF = @RELEASE394@;
    private static int packet() { return @PACKET@; }
    private static final List<ResourceLocation> ELEMENTS = List.of(@ELEMENTS@).stream().map(ReactorMachines::id).toList();
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CatalogPort.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> TILES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CatalogPort.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, CatalogPort.MOD_ID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, CatalogPort.MOD_ID);
    private static final DeferredHolder<SoundEvent, SoundEvent> HUM = SOUNDS.register("projector", () -> SoundEvent.createVariableRangeEvent(id("projector")));
    private static final DeferredBlock<MachineBlock> PROJECTOR = BLOCKS.register("blueprint_projector", () -> new MachineBlock(metal().lightLevel(state -> 10)));
    private static final DeferredBlock<MachineBlock> CASING = BLOCKS.register("reactor_casing", () -> new MachineBlock(metal()));
    private static final DeferredBlock<MachineBlock> COIL = BLOCKS.register("reactor_coil", () -> new MachineBlock(metal()));
    private static final DeferredBlock<MachineBlock> FUSE = BLOCKS.register("fusion_core", () -> new MachineBlock(metal().noLootTable()));
    private static final DeferredBlock<MachineBlock> SPLIT = BLOCKS.register("fission_core", () -> new MachineBlock(metal().noLootTable()));
    private static final DeferredBlock<MachineBlock> GHOST_CASING = BLOCKS.register("ghost_casing", ReactorMachines::ghost);
    private static final DeferredBlock<MachineBlock> GHOST_COIL = BLOCKS.register("ghost_coil", ReactorMachines::ghost);
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Projector>> PROJECTOR_TILE =
            TILES.register("blueprint_projector", () -> BlockEntityType.Builder.of(Projector::new, PROJECTOR.get()).build(null));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Core>> CORE_TILE =
            TILES.register("reactor_core", () -> BlockEntityType.Builder.of(Core::new, FUSE.get(), SPLIT.get()).build(null));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Wall>> WALL_TILE =
            TILES.register("reactor_wall", () -> BlockEntityType.Builder.of(Wall::new, CASING.get(), COIL.get()).build(null));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Ghost>> GHOST_TILE =
            TILES.register("reactor_ghost", () -> BlockEntityType.Builder.of(Ghost::new, GHOST_CASING.get(), GHOST_COIL.get()).build(null));
    static final DeferredHolder<MenuType<?>, MenuType<ReactorMenu>> PROJECTION_MENU = menu("blueprint_projector", Mode.PROJECT);
    static final DeferredHolder<MenuType<?>, MenuType<ReactorMenu>> FUSION_MENU = menu("fusion_core", Mode.FUSION);
    static final DeferredHolder<MenuType<?>, MenuType<ReactorMenu>> FISSION_MENU = menu("fission_core", Mode.FISSION);
    private static DeferredItem<Item> fusionBlueprint, fissionBlueprint, fusionStar;

    private ReactorMachines() {}

    static List<? extends DeferredItem<?>> register(IEventBus bus, DeferredRegister.Items items) {
        BLOCKS.register(bus);
        TILES.register(bus);
        MENUS.register(bus);
        SOUNDS.register(bus);
        bus.addListener(ReactorMachines::capabilities);
        fusionBlueprint = items.registerSimpleItem("fusion_blueprint");
        fissionBlueprint = items.registerSimpleItem("fission_blueprint");
        var entries = new ArrayList<DeferredItem<?>>();
        entries.addAll(List.of(items.registerSimpleBlockItem(PROJECTOR), items.registerSimpleBlockItem(CASING),
                items.registerSimpleBlockItem(COIL), fusionBlueprint, fissionBlueprint, items.registerSimpleItem("concave_lens"),
                items.registerSimpleItem("convex_lens"), items.registerSimpleItem("microscope_lens"), items.registerSimpleItem("projector_lens")));
        if (!RF) {
            fusionStar = items.registerSimpleItem("fusion_star", new Item.Properties().durability(2_000));
            entries.add(fusionStar);
        }
        return entries;
    }

    private static void capabilities(RegisterCapabilitiesEvent event) {
        if (RF) {
            event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, CORE_TILE.get(), (core, face) -> core.power);
            event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, WALL_TILE.get(), (wall, face) -> wall.power);
        } else {
            // FINAL exposes the fission inventory on the core, not through the walls.
            event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CORE_TILE.get(), (core, face) -> {
                if (core.mode != Mode.FISSION) return null;
                int slot = face == Direction.DOWN ? 2 : face == Direction.UP || face == null ? 0 : 1;
                return new RangedWrapper(core.inventory, slot, slot + 1);
            });
        }
    }

    private static DeferredHolder<MenuType<?>, MenuType<ReactorMenu>> menu(String name, Mode mode) {
        return MENUS.register(name, () -> new MenuType<>((window, player) -> new ReactorMenu(window, player, mode), FeatureFlags.DEFAULT_FLAGS));
    }

    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath(CatalogPort.MOD_ID, name); }
    private static MachineBlock.Properties metal() { return MachineBlock.Properties.of().strength(3.5F).requiresCorrectToolForDrops().pushReaction(PushReaction.BLOCK); }
    private static MachineBlock ghost() { return new MachineBlock(MachineBlock.Properties.of().noCollission().noOcclusion().replaceable().noLootTable().pushReaction(PushReaction.DESTROY)); }
    private static Block material(char cell) { return cell == 'A' ? CASING.get() : COIL.get(); }
    private static boolean available(Level level, BlockPos pos) { return !level.isOutsideBuildHeight(pos) && level.hasChunkAt(pos); }
    static Plan blueprint(ItemStack stack) {
        return stack.is(fusionBlueprint.get()) ? FUSION : stack.is(fissionBlueprint.get()) ? FISSION : null;
    }
    private static int number(ItemStack stack) { return stack.isEmpty() ? 0 : ELEMENTS.indexOf(BuiltInRegistries.ITEM.getKey(stack.getItem())) + 1; }
    private static ItemStack element(int number, int count) { return new ItemStack(BuiltInRegistries.ITEM.get(ELEMENTS.get(number - 1)), count); }

    enum Mode {
        PROJECT, FUSION, FISSION;
        int slots() { return this == PROJECT ? 1 : this == FUSION ? 4 : 3; }
        int left() { return RF ? 0 : 1; }
        int right() { return left() + 1; }
        int output() { return this == FUSION ? right() + 1 : 2; }
        Plan plan() { return this == FUSION ? ReactorBlueprints.FUSION : ReactorBlueprints.FISSION; }
        boolean accepts(int slot, ItemStack stack) {
            if (stack.isEmpty()) return false;
            if (this == PROJECT) return slot == 0 && blueprint(stack) != null;
            if (this == FUSION) {
                if (!RF && slot == 0) return stack.is(Items.NETHER_STAR) || stack.is(fusionStar.get());
                return (slot == left() || slot == right()) && number(stack) != 0;
            }
            if (this == FISSION && slot == 1) return !RF && number(stack) != 0;
            return slot != output() && number(stack) != 0;
        }
        MenuType<ReactorMenu> menu() { return switch (this) {
            case PROJECT -> PROJECTION_MENU.get(); case FUSION -> FUSION_MENU.get(); case FISSION -> FISSION_MENU.get();
        }; }
    }

    private static final class MachineBlock extends BaseEntityBlock {
        private static final MapCodec<MachineBlock> CODEC = simpleCodec(MachineBlock::new);
        MachineBlock(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        }
        @Override protected MapCodec<MachineBlock> codec() { return CODEC; }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(BlockStateProperties.HORIZONTAL_FACING); }
        @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, context.getHorizontalDirection());
        }
        @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(BlockStateProperties.HORIZONTAL_FACING, rotation.rotate(facing(state))); }
        @Override protected BlockState mirror(BlockState state, Mirror mirror) { return rotate(state, mirror.getRotation(facing(state))); }
        @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            if (state.is(PROJECTOR.get())) return new Projector(pos, state);
            if (state.is(FUSE.get()) || state.is(SPLIT.get())) return new Core(pos, state);
            if (state.is(CASING.get()) || state.is(COIL.get())) return new Wall(pos, state);
            return new Ghost(pos, state);
        }
        @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
            if (level.isClientSide) return null;
            if (state.is(PROJECTOR.get())) return createTickerHelper(type, PROJECTOR_TILE.get(), (l, p, s, tile) -> tile.tick());
            if (state.is(FUSE.get()) || state.is(SPLIT.get())) return createTickerHelper(type, CORE_TILE.get(), (l, p, s, tile) -> tile.tick());
            if (state.is(CASING.get()) || state.is(COIL.get())) return RF ? createTickerHelper(type, WALL_TILE.get(), (l, p, s, tile) -> tile.tick()) : null;
            return createTickerHelper(type, GHOST_TILE.get(), (l, p, s, tile) -> tile.tick());
        }
        @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (state.is(GHOST_CASING.get()) || state.is(GHOST_COIL.get())) return InteractionResult.PASS;
            if (!level.isClientSide) {
                if (level.getBlockEntity(pos) instanceof Station station) {
                    if (station.ready()) player.openMenu(station);
                    else player.displayClientMessage(Component.translatable("gui." + CatalogPort.MOD_ID + ".reactor_incomplete"), true);
                } else if (level.getBlockEntity(pos) instanceof Wall wall) {
                    var target = wall.core();
                    if (target == null) return InteractionResult.PASS;
                    player.openMenu(new MenuProvider() {
                        @Override public Component getDisplayName() { return target.getDisplayName(); }
                        @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player p) {
                            return new ReactorMenu(id, inventory, target.mode, target, pos);
                        }
                    });
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
            if (!level.isClientSide && !state.is(replacement.getBlock()) && level.getBlockEntity(pos) instanceof Station station) {
                if (station instanceof Projector projector) projector.clearAll();
                for (int i = 0; i < station.inventory.getSlots(); i++) {
                    var stack = station.inventory.getStackInSlot(i);
                    if (!stack.isEmpty()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
                }
            }
            super.onRemove(state, level, pos, replacement, moved);
        }
    }

    private static Direction facing(BlockState state) { return state.getValue(BlockStateProperties.HORIZONTAL_FACING); }

    abstract static class Station extends BlockEntity implements MenuProvider {
        final Mode mode;
        final ItemStackHandler inventory;
        Station(BlockEntityType<?> type, BlockPos pos, BlockState state, Mode mode) {
            super(type, pos, state);
            this.mode = mode;
            inventory = new ItemStackHandler(mode.slots()) {
                @Override public boolean isItemValid(int slot, ItemStack stack) { return mode.accepts(slot, stack); }
                @Override public int getSlotLimit(int slot) { return mode == Mode.PROJECT || !RF && mode == Mode.FUSION && slot == 0 ? 1 : 64; }
                @Override protected void onContentsChanged(int slot) { setChanged(); }
            };
        }
        abstract boolean ready();
        int status(int index) { return 0; }
        @Override public Component getDisplayName() { return getBlockState().getBlock().getName(); }
        @Override public AbstractContainerMenu createMenu(int window, Inventory player, Player user) {
            return new ReactorMenu(window, player, mode, this, worldPosition);
        }
        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
            super.saveAdditional(tag, provider);
            tag.put("Inventory", inventory.serializeNBT(provider));
        }
        @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
            super.loadAdditional(tag, provider);
            if (tag.contains("Inventory")) inventory.deserializeNBT(provider, tag.getCompound("Inventory"));
        }
    }

    private static final class Projector extends Station {
        private Plan last;
        private Direction lastFacing;
        private int slice = -1;
        private boolean formed;
        Projector(BlockPos pos, BlockState state) { super(PROJECTOR_TILE.get(), pos, state, Mode.PROJECT); }
        Plan plan() { return blueprint(inventory.getStackInSlot(0)); }
        @Override boolean ready() { return true; }
        @Override int status(int index) { return index == 0 ? slice : formed ? 1 : 0; }
        void tick() {
            Plan plan = plan();
            Direction dir = facing(getBlockState());
            if (plan != last || dir != lastFacing) {
                clear(last, lastFacing);
                last = plan;
                lastFacing = dir;
            }
            if (plan == null) { slice = -1; formed = false; return; }
            // Take no action in a partial footprint; chunk probes never force loading.
            if (!available(level, plan.position(worldPosition, dir, 0, plan.height() - 1, 0)) ||
                !available(level, plan.position(worldPosition, dir, 0, 0, plan.depth() - 1)) ||
                !available(level, plan.position(worldPosition, dir, plan.width() - 1, 0, 0)) ||
                !available(level, plan.position(worldPosition, dir, plan.width() - 1, 0, plan.depth() - 1))) {
                slice = -1; formed = false; return;
            }
            var core = plan.position(worldPosition, dir, plan.cx(), plan.cy(), plan.cz());
            Block coreBlock = plan == FUSION ? FUSE.get() : SPLIT.get();
            // A built core persists when the blueprint is removed. It is not replaced on reload.
            if (level.getBlockState(core).is(coreBlock)) { formed = true; slice = -1; return; }
            formed = false;
            slice = -1;
            boolean nextSlice = true;
            for (int x = 0; x < plan.width(); x++) {
                boolean complete = true;
                for (int y = 0; y < plan.height(); y++) for (int z = 0; z < plan.depth(); z++) {
                    char cell = plan.cell(x, y, z);
                    if (cell == '.' || plan.manager(x, y, z)) continue;
                    BlockPos pos = plan.position(worldPosition, dir, x, y, z);
                    if (!nextSlice) { clearGhost(pos); continue; }
                    var state = level.getBlockState(pos);
                    if (cell == '0' ? state.isAir() : state.is(material(cell))) continue;
                    complete = false;
                    if (cell != '0' && state.isAir() && level.setBlock(pos,
                            (cell == 'A' ? GHOST_CASING.get() : GHOST_COIL.get()).defaultBlockState(), Block.UPDATE_ALL)
                            && level.getBlockEntity(pos) instanceof Ghost ghost) ghost.bind(worldPosition, dir, plan);
                }
                if (nextSlice && !complete) { slice = x; nextSlice = false; }
            }
            if (nextSlice && level.getBlockState(core).isAir()) {
                formed = level.setBlock(core, coreBlock.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, dir), Block.UPDATE_ALL);
            }
            if (!formed && level.getGameTime() % 20 == 0) level.playSound(null, worldPosition, HUM.get(), SoundSource.BLOCKS, 0.2F, 1.0F);
        }
        void clearAll() {
            clear(last, lastFacing);
            clear(plan(), facing(getBlockState()));
        }
        private void clear(Plan plan, Direction dir) {
            if (plan == null) return;
            for (int x = 0; x < plan.width(); x++) for (int y = 0; y < plan.height(); y++) for (int z = 0; z < plan.depth(); z++) {
                char cell = plan.cell(x, y, z);
                if (cell == 'A' || cell == 'C') clearGhost(plan.position(worldPosition, dir, x, y, z));
            }
        }
        private void clearGhost(BlockPos pos) {
            if (available(level, pos) && level.getBlockEntity(pos) instanceof Ghost ghost && worldPosition.equals(ghost.owner)) {
                level.removeBlock(pos, false);
            }
        }
    }

    private static final class Ghost extends BlockEntity {
        private BlockPos owner;
        private Direction facing;
        private Plan plan;
        Ghost(BlockPos pos, BlockState state) { super(GHOST_TILE.get(), pos, state); }
        void bind(BlockPos projector, Direction dir, Plan blueprint) { owner = projector; facing = dir; plan = blueprint; setChanged(); }
        void tick() {
            if (level.getGameTime() % 20 != 0) return;
            if (owner == null || available(level, owner) && !(level.getBlockEntity(owner) instanceof Projector p
                    && p.plan() == plan && facing(p.getBlockState()) == facing)) level.removeBlock(worldPosition, false);
        }
        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
            super.saveAdditional(tag, provider);
            if (owner != null) {
                tag.putLong("Owner", owner.asLong());
                tag.putString("Facing", facing.getName());
                tag.putBoolean("Fusion", plan == FUSION);
            }
        }
        @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
            super.loadAdditional(tag, provider);
            facing = Direction.byName(tag.getString("Facing"));
            owner = tag.contains("Owner") && facing != null ? BlockPos.of(tag.getLong("Owner")) : null;
            plan = tag.getBoolean("Fusion") ? FUSION : FISSION;
        }
    }

    private static final class Wall extends BlockEntity {
        private BlockPos manager;
        private final Charge power = new Charge(this, packet());
        Wall(BlockPos pos, BlockState state) { super(WALL_TILE.get(), pos, state); }
        void bind(BlockPos target) {
            if (!java.util.Objects.equals(manager, target)) { manager = target; setChanged(); }
        }
        Core core() {
            return manager != null && available(level, manager) && level.getBlockEntity(manager) instanceof Core found && found.ready() && found.includes(this) ? found : null;
        }
        void tick() {
            if (power.getEnergyStored() == 0) return;
            Core target = core();
            if (target != null) {
                int forwarded = target.power.receiveEnergy(power.getEnergyStored(), false);
                // The original proxy uses the same payment gate as machines. Free mode keeps its packet.
                if (@WALL_SPENDS@) power.spend(forwarded);
            }
        }
        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
            super.saveAdditional(tag, provider);
            if (manager != null) tag.putLong("Manager", manager.asLong());
            tag.putInt("Energy", power.getEnergyStored());
        }
        @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
            super.loadAdditional(tag, provider);
            manager = tag.contains("Manager") ? BlockPos.of(tag.getLong("Manager")) : null;
            power.restore(tag.getInt("Energy"));
        }
    }

    private static final class Charge extends EnergyStorage {
        private final BlockEntity tile;
        Charge(BlockEntity tile, int capacity) { super(capacity, packet(), 0); this.tile = tile; }
        @Override public int receiveEnergy(int maxReceive, boolean simulate) {
            int accepted = super.receiveEnergy(Math.max(maxReceive, 0), simulate);
            if (accepted != 0 && !simulate) tile.setChanged();
            return accepted;
        }
        void spend(int amount) { if (amount > 0) { energy -= amount; tile.setChanged(); } }
        void restore(int amount) { energy = Math.clamp(amount, 0, capacity); }
    }

    private static final class Core extends Station {
        private final Charge power;
        private long lastCheck = Long.MIN_VALUE;
        private boolean complete;
        Core(BlockPos pos, BlockState state) {
            super(CORE_TILE.get(), pos, state, state.is(FUSE.get()) ? Mode.FUSION : Mode.FISSION);
            power = new Charge(this, @CORE_CAPACITY@);
        }
        @Override boolean ready() { return complete; }
        @Override int status(int index) { return index == 0 ? power.getEnergyStored() : power.getMaxEnergyStored(); }
        void tick() {
            long now = level.getGameTime();
            if (lastCheck == Long.MIN_VALUE || now < lastCheck || now - lastCheck >= 40) {
                lastCheck = now;
                checkStructure();
            }
            if (!complete) return;
            if (mode == Mode.FUSION) fuse();
            else if (RF || now % 50 == 0) split();
        }
        private boolean includes(Wall wall) {
            char cell = mode.plan().at(worldPosition, facing(getBlockState()), wall.getBlockPos());
            return (cell == 'A' || cell == 'C') && wall.getBlockState().is(material(cell));
        }
        private void checkStructure() {
            Plan plan = mode.plan();
            Direction dir = facing(getBlockState());
            boolean valid = true;
            var walls = new ArrayList<Wall>();
            for (int x = 0; x < plan.width(); x++) for (int y = 0; y < plan.height(); y++) for (int z = 0; z < plan.depth(); z++) {
                char cell = plan.cell(x, y, z);
                if (cell == '.' || plan.manager(x, y, z)) continue;
                var pos = plan.around(worldPosition, dir, x, y, z);
                if (!available(level, pos)) { valid = false; continue; }
                var state = level.getBlockState(pos);
                if (cell == '0' ? !state.isAir() : !state.is(material(cell))) { valid = false; continue; }
                if (cell != '0' && level.getBlockEntity(pos) instanceof Wall wall) walls.add(wall);
            }
            complete = valid;
            for (Wall wall : walls) if (valid || worldPosition.equals(wall.manager)) wall.bind(valid ? worldPosition : null);
        }
        private void fuse() {
            var left = inventory.getStackInSlot(mode.left());
            var right = inventory.getStackInSlot(mode.right());
            var fuel = inventory.getStackInSlot(0);
            int a = number(left), b = number(right), product = a + b;
            if (a == 0 || b == 0 || product > (RF ? ELEMENTS.size() : 113)) return;
            ItemStack result = element(product, 1);
            if (RF) {
                int cost = @FUSION_GATE@;
                if (power.getEnergyStored() <= cost || !fits(result)) return;
                power.spend(@FUSION_PAYMENT@);
            } else {
                if (!(fuel.is(Items.NETHER_STAR) || fuel.is(fusionStar.get()))) return;
                // The original uses/wears the fuel even when a different result blocks the output.
                if (fuel.is(Items.NETHER_STAR)) inventory.setStackInSlot(0, new ItemStack(fusionStar.get()));
                else if (fuel.getDamageValue() < fuel.getMaxDamage()) { fuel.setDamageValue(fuel.getDamageValue() + 1); setChanged(); }
                if (!fits(result)) return;
            }
            produce(result);
            inventory.extractItem(mode.left(), 1, false);
            inventory.extractItem(mode.right(), 1, false);
        }
        private void split() {
            int input = number(inventory.getStackInSlot(0));
            if (input == 0) return;
            int product = input / 2;
            int cost = @FISSION_COST@;
            if (RF) {
                if (product == 0 || power.getEnergyStored() < cost) return;
            } else if (number(inventory.getStackInSlot(1)) != 92) return;
            ItemStack result = product > (RF ? 0 : 1) ? element(product, 2) : ItemStack.EMPTY;
            if (result.isEmpty() ? !inventory.getStackInSlot(2).isEmpty() : !fits(result)) return;
            if (RF) power.spend(cost);
            else inventory.extractItem(1, 1, false);
            inventory.extractItem(0, 1, false);
            if (!result.isEmpty()) produce(result);
        }
        private boolean fits(ItemStack result) {
            var output = inventory.getStackInSlot(mode.output());
            return output.isEmpty() || ItemStack.isSameItemSameComponents(output, result) && output.getCount() + result.getCount() <= output.getMaxStackSize();
        }
        private void produce(ItemStack stack) {
            var output = inventory.getStackInSlot(mode.output());
            inventory.setStackInSlot(mode.output(), output.isEmpty() ? stack : output.copyWithCount(output.getCount() + stack.getCount()));
        }
        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
            super.saveAdditional(tag, provider);
            if (RF) tag.putInt("Energy", power.getEnergyStored());
        }
        @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
            super.loadAdditional(tag, provider);
            if (RF) power.restore(tag.getInt("Energy"));
            complete = false;
            lastCheck = Long.MIN_VALUE;
        }
    }
}
