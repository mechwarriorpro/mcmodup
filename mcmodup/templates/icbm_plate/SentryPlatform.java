package mcmodup_kernel.icbm_glass_plate;

import com.mojang.serialization.MapCodec;
import mcmodup.icbmplate.GlassPlateMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Inventory and passive power forwarding only. No registered turret types or projectiles. */
public final class SentryPlatform extends BaseEntityBlock {
    private static final String ID = GlassPlateMod.ID, NAME = "sentry_platform";
    private static final MapCodec<SentryPlatform> CODEC = simpleCodec(SentryPlatform::new);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, ID);
    private static final DeferredBlock<SentryPlatform> PLATFORM = BLOCKS.register(NAME,
        () -> new SentryPlatform(Properties.of().strength(100F, @RESISTANCE@F).requiresCorrectToolForDrops()));
    private static final DeferredItem<BlockItem> ITEM = ITEMS.registerSimpleBlockItem(PLATFORM);
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Tile>> ENTITY = ENTITIES.register(NAME,
        () -> BlockEntityType.Builder.of(Tile::new, PLATFORM.get()).build(null));
    static final DeferredHolder<MenuType<?>, MenuType<SentryMenu>> MENU = MENUS.register(NAME,
        () -> new MenuType<>(SentryMenu::new, FeatureFlags.DEFAULT_FLAGS));
    public static final BlockCapability<IEnergyStorage, Direction> RECEIVER = BlockCapability.createSided(
        ResourceLocation.fromNamespaceAndPath(ID, "sentry_receiver"), IEnergyStorage.class);
    static final int SLOTS = 20;

    private SentryPlatform(Properties props) { super(props); }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        MENUS.register(bus);
        bus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) event.accept(ITEM);
        });
        bus.addListener((RegisterCapabilitiesEvent event) -> {
            event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ENTITY.get(), (tile, side) -> tile.sided);
            event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ENTITY.get(), (tile, side) -> tile.power);
        });
    }

    @Override protected MapCodec<SentryPlatform> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Tile(pos, state); }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof Tile tile) player.openMenu(tile);
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        if (!state.is(replacement.getBlock()) && !level.isClientSide() && level.getBlockEntity(pos) instanceof Tile tile) {
            for (int index = 0; index < SLOTS; ++index) {
                ItemStack stack = tile.items.extractItem(index, Integer.MAX_VALUE, false);
                if (!stack.isEmpty()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
            }
        }
        super.onRemove(state, level, pos, replacement, moved);
    }

    static final class Tile extends BlockEntity implements MenuProvider {
        private boolean forwarding;
        final ItemStackHandler items = new ItemStackHandler(SLOTS) {
            @Override protected void onContentsChanged(int slot) { setChanged(); }
        };
        // The original permits manual placement, but the inherited canStore hook rejects automated input from every side.
        private final IItemHandler sided = new IItemHandler() {
            @Override public int getSlots() { return SLOTS; }
            @Override public ItemStack getStackInSlot(int slot) { return items.getStackInSlot(slot).copy(); }
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
            @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return items.extractItem(slot, amount, simulate); }
            @Override public int getSlotLimit(int slot) { return items.getSlotLimit(slot); }
            @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
        };
        private final IEnergyStorage power = new IEnergyStorage() {
            @Override public int receiveEnergy(int max, boolean simulate) {
                if (max <= 0 || !canReceive() || !(getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return 0;
                forwarding = true;
                try {
                    return SentryBridge.forward(level, getBlockPos(), max, simulate);
                } finally {
                    forwarding = false;
                }
            }
            @Override public int extractEnergy(int max, boolean simulate) { return SentryBridge.extract(max, simulate); }
            @Override public int getEnergyStored() { return 0; }
            @Override public int getMaxEnergyStored() { return 0; }
            @Override public boolean canExtract() { return false; }
            @Override public boolean canReceive() { return !isRemoved() && !forwarding && getLevel() instanceof ServerLevel; }
        };

        Tile(BlockPos pos, BlockState state) { super(ENTITY.get(), pos, state); }
        @Override public AbstractContainerMenu createMenu(int id, Inventory player, Player opener) { return new SentryMenu(id, player, this); }
        @Override public Component getDisplayName() { return Component.translatable("block." + ID + "." + NAME); }

        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            var stacks = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
            for (int i = 0; i < SLOTS; ++i) stacks.set(i, items.getStackInSlot(i));
            ContainerHelper.saveAllItems(tag, stacks, registries);
        }

        @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            var stacks = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(tag, stacks, registries);
            for (int i = 0; i < SLOTS; ++i) {
                var stack = stacks.get(i);
                items.setStackInSlot(i, stack.copyWithCount(Math.min(stack.getCount(), stack.getMaxStackSize())));
            }
        }
    }
}
