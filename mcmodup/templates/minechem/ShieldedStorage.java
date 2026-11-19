package @PACKAGE@;

import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

final class ShieldedStorage {
    static final boolean FINAL = !ChemistryRadiation.RELEASE_394;
    static final int CHEST_SLOTS = 9, CABINET_SLOTS = 27;
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CatalogPort.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CatalogPort.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, CatalogPort.MOD_ID);

    private static final net.neoforged.neoforge.registries.DeferredBlock<Chest> CHEST = BLOCKS.register("leaded_chest", () -> new Chest(properties()));
    private static final net.neoforged.neoforge.registries.DeferredBlock<Chest> CABINET = FINAL ? BLOCKS.register("chemical_storage", () -> new Chest(properties())) : null;
    private static final net.neoforged.neoforge.registries.DeferredBlock<Block> ORE = BLOCKS.registerSimpleBlock("uranium_ore",
        BlockBehaviour.Properties.of().strength(4F, 4F).requiresCorrectToolForDrops());
    private static final net.neoforged.neoforge.registries.DeferredHolder<BlockEntityType<?>, BlockEntityType<Contents>> CONTENTS = ENTITIES.register("shielded_storage",
        () -> BlockEntityType.Builder.of(Contents::new, FINAL ? new Block[] {CHEST.get(), CABINET.get()} : new Block[] {CHEST.get()}).build(null));
    static final net.neoforged.neoforge.registries.DeferredHolder<MenuType<?>, MenuType<StorageMenu>> CHEST_MENU = MENUS.register("leaded_chest",
        () -> new MenuType<>((id, player) -> new StorageMenu(id, player, null, CHEST_SLOTS), FeatureFlags.DEFAULT_FLAGS));
    static final net.neoforged.neoforge.registries.DeferredHolder<MenuType<?>, MenuType<StorageMenu>> CABINET_MENU = FINAL ? MENUS.register("chemical_storage",
        () -> new MenuType<>((id, player) -> new StorageMenu(id, player, null, CABINET_SLOTS), FeatureFlags.DEFAULT_FLAGS)) : null;

    private static BlockBehaviour.Properties properties() { return BlockBehaviour.Properties.of().strength(2.5F, 2.5F); }
    static List<DeferredItem<?>> register(IEventBus bus, DeferredRegister.Items items) {
        BLOCKS.register(bus);
        ENTITIES.register(bus);
        MENUS.register(bus);
        bus.addListener((net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) ->
            event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CONTENTS.get(), (tile, side) -> tile.storage));
        var entries = new ArrayList<DeferredItem<?>>();
        entries.add(items.registerSimpleBlockItem(CHEST));
        entries.add(items.registerSimpleBlockItem(ORE));
        if (FINAL) entries.add(items.registerSimpleBlockItem(CABINET));
        return entries;
    }

    static final class Chest extends BaseEntityBlock {
        private static final MapCodec<Chest> CODEC = simpleCodec(Chest::new);
        Chest(Properties properties) { super(properties); }
        @Override protected MapCodec<Chest> codec() { return CODEC; }
        @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Contents(pos, state); }
        @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (!level.isClientSide() && level.getBlockEntity(pos) instanceof Contents tile) player.openMenu(tile);
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
            if (!level.isClientSide() && !state.is(replacement.getBlock()) && level.getBlockEntity(pos) instanceof Contents tile) {
                tile.pauseClocks();
                for (int i = 0; i < tile.storage.getSlots(); i++) {
                    var stack = tile.storage.getStackInSlot(i);
                    if (!stack.isEmpty()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack.copy());
                }
            }
            super.onRemove(state, level, pos, replacement, moving);
        }
    }

    static final class Contents extends BlockEntity implements MenuProvider {
        final ItemStackHandler storage;
        Contents(BlockPos pos, BlockState state) {
            super(CONTENTS.get(), pos, state);
            int size = FINAL && state.is(CABINET.get()) ? CABINET_SLOTS : CHEST_SLOTS;
            storage = new ItemStackHandler(size) {
                @Override public int getSlotLimit(int index) { return 64; }
                @Override public void setStackInSlot(int index, ItemStack value) { super.setStackInSlot(index, value.copy()); }
                @Override public ItemStack insertItem(int index, ItemStack value, boolean simulate) {
                    return super.insertItem(index, simulate ? value : value.copy(), simulate);
                }
                @Override protected void onContentsChanged(int index) {
                    if (level != null && !level.isClientSide()) ChemistryRadiation.checkpoint(getStackInSlot(index), level);
                    setChanged();
                }
                @Override public ItemStack extractItem(int index, int amount, boolean simulate) {
                    var output = super.extractItem(index, amount, simulate).copy();
                    if (!output.isEmpty() && level != null && !level.isClientSide())
                        ChemistryRadiation.checkpoint(output, level);
                    return output;
                }
            };
        }
        void pauseClocks() {
            if (level == null || level.isClientSide()) return;
            for (int i = 0; i < storage.getSlots(); i++) {
                if (ChemistryRadiation.checkpoint(storage.getStackInSlot(i), level)) setChanged();
            }
        }
        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            tag.put("Items", storage.serializeNBT(registries));
        }
        @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            int size = storage.getSlots();
            var items = tag.getCompound("Items").copy();
            items.putInt("Size", size); // Saved data cannot turn a leaded chest into a larger inventory.
            storage.deserializeNBT(registries, items);
        }
        @Override public Component getDisplayName() { return getBlockState().getBlock().getName(); }
        @Override public AbstractContainerMenu createMenu(int id, Inventory player, Player user) {
            pauseClocks();
            return new StorageMenu(id, player, this, storage.getSlots());
        }
    }

    static final class StorageMenu extends AbstractContainerMenu implements ChemistryRadiation.MenuRules {
        final int capacity;
        private final Contents tile;
        StorageMenu(int id, Inventory player, Contents tile, int capacity) {
            super(capacity == CHEST_SLOTS ? CHEST_MENU.get() : CABINET_MENU.get(), id);
            this.capacity = capacity;
            this.tile = tile;
            var inventory = tile == null ? new ItemStackHandler(capacity) : tile.storage;
            for (int i = 0; i < capacity; i++) addSlot(new SlotItemHandler(inventory, i, 8 + i % 9 * 18, 18 + i / 9 * 18) {
                    @Override public void setChanged() { if (tile != null) tile.setChanged(); }
                });
            int row = capacity == CHEST_SLOTS ? 50 : 84;
            for (int i = 0; i < 27; i++) addSlot(new Slot(player, i + 9, 8 + i % 9 * 18, row + i / 9 * 18));
            for (int i = 0; i < 9; i++) addSlot(new Slot(player, i, 8 + i * 18, row + 58));
        }
        @Override public boolean shieldedRadiationSlot(int slot) {
            // FINAL scanned the first 27 slots, including 18 player slots in the nine-slot chest.
            return slot < (FINAL ? CABINET_SLOTS : capacity);
        }
        @Override public boolean stillValid(Player player) {
            return tile == null || (tile.getLevel() == player.level() && player.level().getBlockEntity(tile.getBlockPos()) == tile &&
                player.distanceToSqr(Vec3.atCenterOf(tile.getBlockPos())) <= 64);
        }
        @Override public void clicked(int slot, int mouse, ClickType type, Player player) {
            if (tile != null) tile.pauseClocks();
            super.clicked(slot, mouse, type, player);
        }
        @Override public ItemStack quickMoveStack(Player player, int index) {
            if (index < 0 || index >= slots.size() || !stillValid(player)) return ItemStack.EMPTY;
            if (tile != null) tile.pauseClocks();
            var slot = getSlot(index);
            if (!slot.hasItem()) return ItemStack.EMPTY;
            var stack = slot.getItem();
            var moved = stack.copy();
            if (!(index < capacity ? moveItemStackTo(stack, capacity, slots.size(), true) : moveItemStackTo(stack, 0, capacity, false))) return ItemStack.EMPTY;
            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();
            slot.onTake(player, stack);
            return moved;
        }
    }
    private ShieldedStorage() {}
}
