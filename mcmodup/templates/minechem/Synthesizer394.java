package @PACKAGE@;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** .394 has a template grid and is paid on extraction; it does not run FINAL's timed jobs. */
final class Synthesizer394 extends BaseEntityBlock {
    private static final MapCodec<Synthesizer394> CODEC = simpleCodec(Synthesizer394::new);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CatalogPort.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CatalogPort.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, CatalogPort.MOD_ID);
    static final int STORAGE_SLOTS = 18, GHOST_SLOTS = 9, CAPACITY = 100_000;

    private static final DeferredBlock<Synthesizer394> BLOCK =
        BLOCKS.register("synthesizer", () -> new Synthesizer394(Properties.of().strength(3.5F).requiresCorrectToolForDrops()));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Tile>> ENTITY =
        ENTITIES.register("synthesizer", () -> BlockEntityType.Builder.of(Tile::new, BLOCK.get()).build(null));
    static final DeferredHolder<MenuType<?>, MenuType<Synthesis394Menu>> MENU =
        MENUS.register("synthesizer", () -> new MenuType<>(Synthesis394Menu::new, FeatureFlags.DEFAULT_FLAGS));

    Synthesizer394(Properties properties) { super(properties); }

    static DeferredItem<?> register(IEventBus bus, DeferredRegister.Items items) {
        BLOCKS.register(bus);
        ENTITIES.register(bus);
        MENUS.register(bus);
        bus.addListener((RegisterCapabilitiesEvent event) -> {
            event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ENTITY.get(), (tile, side) -> tile.power);
            event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ENTITY.get(), (tile, side) ->
                side == null || side == Direction.UP ? tile.input : tile.output);
        });
        return items.registerSimpleBlockItem(BLOCK);
    }

    @Override protected MapCodec<Synthesizer394> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Tile(pos, state); }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof Tile tile) player.openMenu(tile);
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    static class JournalSlot extends ItemStackHandler {
        JournalSlot() { super(1); }
        @Override public int getSlotLimit(int slot) { return 1; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return ChemistryResearch.isJournal(stack); }
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        if (!level.isClientSide() && !state.is(replacement.getBlock()) && level.getBlockEntity(pos) instanceof Tile tile) {
            for (int slot = 0; slot < STORAGE_SLOTS; ++slot) {
                var stack = tile.storage.getStackInSlot(slot);
                if (!stack.isEmpty()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
            }
            var book = tile.journal.extractItem(0, 1, false);
            if (!book.isEmpty()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), book);
        }
        super.onRemove(state, level, pos, replacement, moved);
    }

    static boolean chemical(ItemStack stack) {
        return !stack.isEmpty() && ChemistryRecipes.CHEMICALS.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    static final class Tile extends BlockEntity implements MenuProvider {
        final ItemStackHandler storage = new ItemStackHandler(STORAGE_SLOTS) {
            @Override public boolean isItemValid(int slot, ItemStack stack) { return chemical(stack); }
            @Override protected void onContentsChanged(int slot) { setChanged(); }
        };
        private boolean loading;
        final JournalSlot journal = new JournalSlot() {
            @Override public void setStackInSlot(int slot, ItemStack stack) {
                super.setStackInSlot(slot, isItemValid(slot, stack) ? stack.copyWithCount(1) : ItemStack.EMPTY);
            }
            @Override protected void onContentsChanged(int slot) {
                if (loading) return;
                var book = getStackInSlot(slot);
                if (book.isEmpty()) {
                    clear();
                } else {
                    String active = ChemistryResearch.active(book);
                    for (var rule : Synthesis394Rules.ALL) {
                        if (!rule.result().id().equals(active)) continue;
                        apply(rule);
                        break;
                    }
                    setChanged();
                }
            }
        };
        // The result template and ghost pattern never belong to extractable storage.
        final ItemStackHandler display = new ItemStackHandler(GHOST_SLOTS + 1);
        final Power power = new Power();
        private Synthesis394Rules.Rule selected;
        private int cursor = -1;
        private final IItemHandler output = new IItemHandler() {
            @Override public int getSlots() { return 1; }
            @Override public ItemStack getStackInSlot(int slot) { return slot == 0 && plan() != null ? preview() : ItemStack.EMPTY; }
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
            @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
                return slot == 0 ? extract(amount, simulate) : ItemStack.EMPTY;
            }
            @Override public int getSlotLimit(int slot) { return slot == 0 ? 64 : 0; }
            @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
        };
        private final IItemHandler input = new IItemHandler() {
            @Override public int getSlots() { return STORAGE_SLOTS; }
            @Override public ItemStack getStackInSlot(int slot) { return storage.getStackInSlot(slot); }
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                return storage.insertItem(slot, stack, simulate);
            }
            @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return ItemStack.EMPTY; }
            @Override public int getSlotLimit(int slot) { return storage.getSlotLimit(slot); }
            @Override public boolean isItemValid(int slot, ItemStack stack) { return storage.isItemValid(slot, stack); }
        };

        Tile(BlockPos pos, BlockState state) { super(ENTITY.get(), pos, state); }
        @Override public Component getDisplayName() { return Component.translatable("block.@NAMESPACE@.synthesizer"); }
        @Override public AbstractContainerMenu createMenu(int id, Inventory player, Player opener) {
            return new Synthesis394Menu(id, player, this);
        }

        void clear() {
            for (int i = 0; i < GHOST_SLOTS; ++i) display.setStackInSlot(i, ItemStack.EMPTY);
            refresh();
        }

        void advance(int distance) {
            var rows = Synthesis394Rules.ALL;
            int start = cursor == -1 && distance < 0 ? 0 : cursor;
            apply(rows.get(Math.floorMod(start + distance, rows.size())));
        }

        private void apply(Synthesis394Rules.Rule rule) {
            for (int i = 0; i < GHOST_SLOTS; ++i) display.setStackInSlot(i, rule.pattern().get(i).stack());
            refresh();
        }

        void edit(int slot, ItemStack held, boolean single) {
            if (slot < 0 || slot >= GHOST_SLOTS || (!held.isEmpty() && !chemical(held))) return;
            var before = display.getStackInSlot(slot);
            var updated = ItemStack.EMPTY;
            if (held.isEmpty()) {
                if (single && before.getCount() > 1) updated = before.copyWithCount(before.getCount() - 1);
            } else {
                int count = single ? (before.is(held.getItem()) ? before.getCount() + 1 : 1) : held.getCount();
                updated = new ItemStack(held.getItem(), Math.min(count, held.getMaxStackSize()));
            }
            display.setStackInSlot(slot, updated);
            refresh();
        }

        int cost() { return selected == null ? 0 : selected.cost(); }
        boolean ready() { return plan() != null; }
        private ItemStack preview() { return selected == null ? ItemStack.EMPTY : selected.result().stack(); }

        private void refresh() {
            var ghosts = NonNullList.withSize(GHOST_SLOTS, ItemStack.EMPTY);
            for (int i = 0; i < GHOST_SLOTS; ++i) ghosts.set(i, display.getStackInSlot(i));
            cursor = -1;
            selected = null;
            var rows = Synthesis394Rules.ALL;
            for (int i = 0; i < rows.size(); ++i) {
                if (!rows.get(i).matches(ghosts)) continue;
                cursor = i;
                selected = rows.get(i);
                break;
            }
            display.setStackInSlot(GHOST_SLOTS, preview());
            setChanged();
        }

        /** Plan against a snapshot of counts. A failed check never consumes a partial batch. */
        private int[] plan() {
            if (selected == null || power.getEnergyStored() < selected.cost()) return null;
            int[] taken = new int[STORAGE_SLOTS];
            for (var part : selected.pattern()) {
                int missing = part.count();
                if (missing == 0) continue;
                var item = part.item();
                for (int slot = 0; slot < STORAGE_SLOTS && missing > 0; ++slot) {
                    var stack = storage.getStackInSlot(slot);
                    if (!stack.is(item)) continue;
                    int take = Math.min(missing, stack.getCount() - taken[slot]);
                    taken[slot] += take;
                    missing -= take;
                }
                if (missing != 0) return null;
            }
            return taken;
        }

        ItemStack extract(int amount, boolean simulate) {
            if (selected == null || amount < selected.result().count()) return ItemStack.EMPTY;
            var take = plan();
            if (take == null) return ItemStack.EMPTY;
            if (!simulate) {
                for (int i = 0; i < STORAGE_SLOTS; ++i) if (take[i] > 0) storage.extractItem(i, take[i], false);
                power.spend(cost());
                setChanged();
            }
            return preview();
        }

        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
            super.saveAdditional(tag, lookup);
            tag.putInt("energy", power.getEnergyStored());
            tag.put("chemical_storage", saveStacks(storage, STORAGE_SLOTS, lookup));
            tag.put("templates", saveStacks(display, GHOST_SLOTS, lookup));
            tag.put("journal", journal.getStackInSlot(0).saveOptional(lookup));
        }

        @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
            super.loadAdditional(tag, lookup);
            power.restore(tag.getInt("energy"));
            loadStacks(storage, STORAGE_SLOTS, tag.getCompound("chemical_storage"), lookup);
            loadStacks(display, GHOST_SLOTS, tag.getCompound("templates"), lookup);
            loading = true;
            try {
                journal.setStackInSlot(0, ItemStack.parseOptional(lookup, tag.getCompound("journal")));
            } finally {
                loading = false;
            }
            refresh();
        }

        private static CompoundTag saveStacks(ItemStackHandler handler, int count, HolderLookup.Provider lookup) {
            var stacks = NonNullList.withSize(count, ItemStack.EMPTY);
            for (int i = 0; i < count; ++i) stacks.set(i, handler.getStackInSlot(i));
            return ContainerHelper.saveAllItems(new CompoundTag(), stacks, lookup);
        }

        private static void loadStacks(ItemStackHandler handler, int count, CompoundTag tag, HolderLookup.Provider lookup) {
            var stacks = NonNullList.withSize(count, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(tag, stacks, lookup);
            for (int i = 0; i < count; ++i) {
                var stack = stacks.get(i);
                handler.setStackInSlot(i, chemical(stack) ? stack.copyWithCount(Math.min(stack.getCount(), stack.getMaxStackSize())) : ItemStack.EMPTY);
            }
        }

        final class Power extends EnergyStorage {
            Power() { super(CAPACITY, 100, 0); }
            @Override public int receiveEnergy(int amount, boolean simulate) {
                int received = super.receiveEnergy(Math.max(0, amount), simulate);
                if (!simulate && received > 0) setChanged();
                return received;
            }
            void restore(int amount) { energy = Math.clamp(amount, 0, CAPACITY); }
            void spend(int amount) { energy -= amount; }
        }
    }
}
