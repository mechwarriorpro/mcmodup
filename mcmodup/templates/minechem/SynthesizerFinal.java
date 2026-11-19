package @PACKAGE@;

import com.mojang.serialization.MapCodec;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Exact FINAL layout and separate paths for individual withdrawal and full-batch crafting. */
final class SynthesizerFinal extends BaseEntityBlock {
    private static final MapCodec<SynthesizerFinal> CODEC = simpleCodec(SynthesizerFinal::new);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CatalogPort.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CatalogPort.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, CatalogPort.MOD_ID);
    static final int GRID = 9, CAPACITY = 1_022_220, RECEIVE = 200;
    private static final int MAX_TEMPLATE_COUNT = GRID * 64;

    private static final ModConfigSpec CONFIG;
    private static final ModConfigSpec.BooleanValue AUTOMATION;
    static {
        var builder = new ModConfigSpec.Builder();
        AUTOMATION = builder.comment("Allow FINAL synthesizers to withdraw from ready machines through item capabilities.")
            .define("allowAutomation", false);
        CONFIG = builder.build();
    }

    private static final DeferredBlock<SynthesizerFinal> BLOCK = BLOCKS.register("synthesizer",
        () -> new SynthesizerFinal(Properties.of().strength(3.5F).requiresCorrectToolForDrops()));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Tile>> ENTITY =
        ENTITIES.register("synthesizer", () -> BlockEntityType.Builder.of(Tile::new, BLOCK.get()).build(null));
    static final DeferredHolder<MenuType<?>, MenuType<SynthesisFinalMenu>> MENU =
        MENUS.register("synthesizer", () -> new MenuType<>(SynthesisFinalMenu::new, FeatureFlags.DEFAULT_FLAGS));

    SynthesizerFinal(Properties properties) { super(properties); }

    static void registerConfig(ModContainer mod) {
        mod.registerConfig(ModConfig.Type.SERVER, CONFIG, CatalogPort.MOD_ID + "-synthesis.toml");
    }

    static List<? extends DeferredItem<?>> register(IEventBus bus, DeferredRegister.Items items, DeferredItem<?> decomposer) {
        BLOCKS.register(bus);
        ENTITIES.register(bus);
        MENUS.register(bus);
        bus.addListener((RegisterCapabilitiesEvent event) -> {
            event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ENTITY.get(), (tile, side) -> tile.power);
            event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ENTITY.get(),
                (tile, side) -> tile.faces.get(side == null ? Direction.UP : side));
        });
        return List.of(items.registerSimpleBlockItem(BLOCK), decomposer,
            items.registerSimpleItem("atomic_manipulator", new Item.Properties()));
    }

    @Override protected MapCodec<SynthesizerFinal> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Tile(pos, state); }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, ENTITY.get(), Tile::pullPower);
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player.isShiftKeyDown()) return InteractionResult.PASS;
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof Tile tile) player.openMenu(tile);
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        if (!level.isClientSide() && !state.is(replacement.getBlock()) && level.getBlockEntity(pos) instanceof Tile tile) {
            for (int slot = 0; slot < GRID; ++slot) drop(level, pos, tile.storage.getStackInSlot(slot));
            drop(level, pos, tile.journal.getStackInSlot(0));
        }
        super.onRemove(state, level, pos, replacement, moved);
    }

    private static void drop(Level level, BlockPos pos, ItemStack stack) {
        if (!stack.isEmpty()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
    }

    static boolean chemical(ItemStack stack) {
        return !stack.isEmpty() && ChemistryRecipes.CHEMICALS.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    static class JournalSlot extends ItemStackHandler {
        JournalSlot() { super(1); }
        @Override public int getSlotLimit(int slot) { return 1; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return ChemistryResearch.isJournal(stack); }
        @Override public void setStackInSlot(int slot, ItemStack stack) {
            super.setStackInSlot(slot, isItemValid(slot, stack) ? stack.copyWithCount(1) : ItemStack.EMPTY);
        }
    }

    static final class Tile extends BlockEntity implements MenuProvider {
        final ItemStackHandler storage = new ItemStackHandler(GRID) {
            @Override protected void onContentsChanged(int slot) { setChanged(); }
        };
        final JournalSlot journal = new JournalSlot() {
            @Override protected void onContentsChanged(int slot) {
                if (loading) return;
                var book = getStackInSlot(slot);
                if (book.isEmpty()) {
                    clearTemplates();
                } else {
                    String active = ChemistryResearch.active(book);
                    for (var rule : SynthesisFinalRules.ALL) {
                        if (rule.nativeOutput() && rule.result().id().equals(active)) {
                            clearTemplates();
                            for (int i = 0; i < rule.pattern().size(); ++i) {
                                var part = rule.pattern().get(i);
                                if (part.count() > 0) pattern.set(i, new ItemStack(part.item(), part.count()));
                            }
                            break;
                        }
                    }
                }
                refresh();
            }
        };
        final SimpleContainer display = new SimpleContainer(GRID + 1);
        private final NonNullList<ItemStack> pattern = NonNullList.withSize(GRID, ItemStack.EMPTY);
        private final EnumMap<Direction, IItemHandler> faces = new EnumMap<>(Direction.class);
        private final Power power = new Power();
        private SynthesisFinalRules.Rule selected;
        private boolean loading;

        Tile(BlockPos pos, BlockState state) {
            super(ENTITY.get(), pos, state);
            for (var side : Direction.values()) faces.put(side, new Face(side));
        }

        @Override public Component getDisplayName() { return getBlockState().getBlock().getName(); }
        @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
            return new SynthesisFinalMenu(id, inventory, this);
        }

        void edit(int slot, ItemStack held, boolean single) {
            var shown = pattern.get(slot);
            if (held.isEmpty()) {
                pattern.set(slot, ItemStack.EMPTY);
            } else if (chemical(held)) {
                // The original adds to the existing ghost even when the cursor
                // holds a different chemical. Clear the cell to change its type.
                ItemStack source = shown.isEmpty() ? held : shown;
                int count = Math.min(64, shown.getCount() + (single ? 1 : Math.min(64, held.getCount())));
                pattern.set(slot, new ItemStack(source.getItem(), count));
            } else {
                return;
            }
            refresh();
        }

        private void clearTemplates() {
            for (int i = 0; i < GRID; ++i) pattern.set(i, ItemStack.EMPTY);
        }

        private void refresh() {
            selected = null;
            for (var rule : SynthesisFinalRules.ALL) {
                if (rule.matches(pattern)) {
                    selected = rule;
                    break;
                }
            }
            display.setItem(0, selected == null ? ItemStack.EMPTY : selected.preview());
            for (int i = 0; i < GRID; ++i) {
                var part = pattern.get(i);
                // Ghost counts may be 144. Send a legal icon plus its count as
                // menu data, never an oversized real ItemStack over the wire.
                display.setItem(i + 1, part.getCount() > part.getMaxStackSize() ? part.copyWithCount(1) : part.copy());
            }
            setChanged();
        }

        int energy() { return power.getEnergyStored(); }
        int cost() { return selected == null ? 0 : selected.cost(); }
        int ghostCount(int slot) { return pattern.get(slot).getCount(); }
        int status() { return selected == null ? 0 : !selected.nativeOutput() ? 3 : payable(1) > 0 ? 2 : 1; }

        private int payable(int limit) {
            if (limit <= 0 || selected == null || display.getItem(0).isEmpty()) return 0;
            int count = Math.min(limit, power.getEnergyStored() / cost());
            for (var part : selected.requirements()) {
                if (count == 0) break;
                int available = 0;
                var item = part.item();
                for (int i = 0; i < GRID; ++i) {
                    var candidate = storage.getStackInSlot(i);
                    if (candidate.is(item)) available += candidate.getCount();
                }
                count = Math.min(count, available / part.count());
            }
            return count;
        }

        private void pay(int batches) {
            for (var part : selected.requirements()) {
                int unpaid = batches * part.count();
                var item = part.item();
                for (int i = 0; i < GRID && unpaid > 0; ++i) {
                    if (storage.getStackInSlot(i).is(item)) unpaid -= storage.extractItem(i, unpaid, false).getCount();
                }
            }
            power.spend(cost() * batches);
        }

        ItemStack takeUnits(int amount, boolean simulate) {
            var preview = display.getItem(0);
            int count = payable(Math.min(preview.getCount(), amount));
            if (count == 0) return ItemStack.EMPTY;
            if (!simulate) pay(count);
            return preview.copyWithCount(count);
        }

        ItemStack takeBatch(boolean simulate) {
            if (payable(1) == 0) return ItemStack.EMPTY;
            if (!simulate) pay(1);
            return display.getItem(0).copy();
        }

        private final class Face implements IItemHandler {
            private final Direction side;
            Face(Direction side) { this.side = side; }
            private boolean ready() { return payable(1) > 0; }
            private boolean result() { return side != Direction.UP && ready(); }
            private boolean storageIndex(int slot) { return slot >= 0 && slot < GRID; }
            @Override public int getSlots() { return result() ? 1 : GRID; }
            @Override public ItemStack getStackInSlot(int slot) {
                if (result()) return slot == 0 ? display.getItem(0).copy() : ItemStack.EMPTY;
                return storageIndex(slot) ? storage.getStackInSlot(slot) : ItemStack.EMPTY;
            }
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                // Only real storage is writable; even the original's permissive
                // sided insert check must not write through the result template.
                return isItemValid(slot, stack) ? storage.insertItem(slot, stack, simulate) : stack;
            }
            @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
                if (amount <= 0 || !CONFIG.isLoaded() || !AUTOMATION.get() || !ready()) return ItemStack.EMPTY;
                if (side != Direction.UP) return slot == 0 ? takeUnits(amount, simulate) : ItemStack.EMPTY;
                return storageIndex(slot) ? storage.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
            }
            @Override public boolean isItemValid(int slot, ItemStack stack) {
                return !result() && storageIndex(slot) && !stack.isEmpty();
            }
            @Override public int getSlotLimit(int slot) { return slot >= 0 && slot < getSlots() ? 64 : 0; }
        }

        private static void pullPower(Level level, BlockPos pos, BlockState state, Tile tile) {
            for (var side : Direction.values()) {
                int acceptance = tile.power.receiveEnergy(RECEIVE, true);
                if (acceptance == 0) break;
                var neighbor = pos.relative(side);
                if (!level.hasChunkAt(neighbor)) continue;
                var source = level.getCapability(Capabilities.EnergyStorage.BLOCK, neighbor, side.getOpposite());
                if (source != null && source.canExtract()) tile.power.receiveEnergy(source.extractEnergy(acceptance, false), false);
            }
        }

        private final class Power extends EnergyStorage {
            Power() { super(CAPACITY, RECEIVE, 0); }
            @Override public int receiveEnergy(int amount, boolean simulate) {
                if (amount <= 0) return 0;
                int accepted = super.receiveEnergy(amount, simulate);
                if (!simulate && accepted > 0) setChanged();
                return accepted;
            }
            void spend(int amount) { energy -= amount; setChanged(); }
            void restore(int amount) { energy = Math.clamp(amount, 0, CAPACITY); }
        }

        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
            super.saveAdditional(tag, lookup);
            tag.putInt("Energy", power.getEnergyStored());
            tag.put("Storage", storage.serializeNBT(lookup));
            tag.put("Journal", journal.getStackInSlot(0).saveOptional(lookup));
            var ghosts = new CompoundTag();
            for (int i = 0; i < GRID; ++i) {
                var part = pattern.get(i);
                if (part.isEmpty()) continue;
                var entry = new CompoundTag();
                entry.putString("Id", BuiltInRegistries.ITEM.getKey(part.getItem()).toString());
                entry.putInt("Count", part.getCount());
                ghosts.put(Integer.toString(i), entry);
            }
            tag.put("Pattern", ghosts);
        }

        @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
            super.loadAdditional(tag, lookup);
            power.restore(tag.getInt("Energy"));
            var inventory = tag.getCompound("Storage");
            inventory.putInt("Size", GRID);
            storage.deserializeNBT(lookup, inventory);
            loading = true;
            try {
                journal.setStackInSlot(0, ItemStack.parseOptional(lookup, tag.getCompound("Journal")));
            } finally {
                loading = false;
            }
            clearTemplates();
            var ghosts = tag.getCompound("Pattern");
            for (int i = 0; i < GRID; ++i) {
                var entry = ghosts.getCompound(Integer.toString(i));
                int count = Math.clamp(entry.getInt("Count"), 0, MAX_TEMPLATE_COUNT);
                var id = ResourceLocation.tryParse(entry.getString("Id"));
                if (id != null && count > 0 && ChemistryRecipes.CHEMICALS.contains(id.toString())) {
                    pattern.set(i, new ItemStack(BuiltInRegistries.ITEM.get(id), count));
                }
            }
            refresh();
        }
    }
}
