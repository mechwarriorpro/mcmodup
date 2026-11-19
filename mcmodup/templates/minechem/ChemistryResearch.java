package @PACKAGE@;

import com.mojang.serialization.MapCodec;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import @PACKAGE@.ResearchMenus.JournalMenu;
import @PACKAGE@.ResearchMenus.MicroscopeMenu;

/** Native adaptation of Minechem's microscope and journal for the recovered recipe table. */
final class ChemistryResearch {
    static final int SAMPLE_SLOT = 0, BOOK_SLOT = 1, INSPECTION_SLOTS = 2;
    private static final String JOURNAL_DATA = "ChemistJournal";
    private static final String DISCOVERIES = "Discoveries", ACTIVE = "Active", OWNER = "Owner";
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CatalogPort.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CatalogPort.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, CatalogPort.MOD_ID);
    private static final DeferredBlock<MicroscopeBlock> MICROSCOPE = BLOCKS.register("microscope", () ->
        new MicroscopeBlock(BlockBehaviour.Properties.of().strength(3.5F).requiresCorrectToolForDrops().lightLevel(state -> 7)));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Microscope>> ENTITY = ENTITIES.register("microscope", () ->
        BlockEntityType.Builder.of(Microscope::new, MICROSCOPE.get()).build(null));
    static final DeferredHolder<MenuType<?>, MenuType<MicroscopeMenu>> MICROSCOPE_MENU = MENUS.register("microscope", () ->
        new MenuType<>(MicroscopeMenu::new, FeatureFlags.DEFAULT_FLAGS));
    static final DeferredHolder<MenuType<?>, MenuType<JournalMenu>> JOURNAL_MENU = MENUS.register("chemist_journal", () ->
        new MenuType<>(JournalMenu::new, FeatureFlags.DEFAULT_FLAGS));
    private static DeferredItem<JournalItem> journal;

    private ChemistryResearch() {}

    static List<? extends DeferredItem<?>> register(IEventBus bus, DeferredRegister.Items items) {
        journal = items.register("chemist_journal", JournalItem::new);
        BLOCKS.register(bus);
        ENTITIES.register(bus);
        MENUS.register(bus);
@REGISTER_CLONING@
        return List.of(items.registerSimpleBlockItem(MICROSCOPE), journal);
    }

    static boolean isJournal(ItemStack stack) { return journal != null && stack.is(journal.get()); }

    static String key(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return @RESEARCH_KEY@;
    }

    private static CompoundTag read(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound(JOURNAL_DATA);
    }

    private static void update(ItemStack stack, Consumer<CompoundTag> change) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            var journalTag = tag.getCompound(JOURNAL_DATA);
            change.accept(journalTag);
            tag.put(JOURNAL_DATA, journalTag);
        });
    }

    private static boolean supported(String id) {
        return ChemistryRecipes.BY_RESULT.containsKey(id) || ChemistryRecipes.inspection(id) != null;
    }

    private static List<String> discoveries(CompoundTag tag) {
        var unique = new LinkedHashSet<String>();
        for (Tag value : tag.getList(DISCOVERIES, Tag.TAG_STRING)) {
            var id = value.getAsString();
            if (supported(id)) unique.add(id);
        }
        return List.copyOf(unique);
    }

    static List<String> discoveries(ItemStack stack) { return discoveries(read(stack)); }

    static String active(ItemStack stack) {
        var tag = read(stack);
        String id = tag.getString(ACTIVE);
        return discoveries(tag).contains(id) ? id : "";
    }

    static boolean cycle(ItemStack stack, int step) {
        var tag = read(stack);
        var known = discoveries(tag);
        if (known.isEmpty()) return false;
        int index = known.indexOf(tag.getString(ACTIVE));
        int next = index < 0 ? (step < 0 ? known.size() - 1 : 0) : Math.floorMod(index + step, known.size());
        update(stack, edited -> edited.putString(ACTIVE, known.get(next)));
        return true;
    }

    private static void discover(ItemStack sample, ItemStack book) {
        if (sample.isEmpty() || !isJournal(book)) return;
        String id = key(sample);
        if (!supported(id)) return;
        var known = discoveries(book);
        if (known.contains(id)) return;
        update(book, tag -> {
            var list = new ListTag();
            known.forEach(recipe -> list.add(StringTag.valueOf(recipe)));
            list.add(StringTag.valueOf(id));
            tag.put(DISCOVERIES, list);
        });
    }

    static final class JournalItem extends Item {
        JournalItem() { super(new Item.Properties().stacksTo(@JOURNAL_LIMIT@)); }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            var book = player.getItemInHand(hand);
            if (!level.isClientSide()) {
                update(book, tag -> tag.putString(OWNER, player.getGameProfile().getName()));
                player.openMenu(new SimpleMenuProvider((id, inventory, actor) -> new JournalMenu(id, inventory, hand, book), book.getHoverName()));
            }
            return InteractionResultHolder.sidedSuccess(book, level.isClientSide());
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
            var tag = read(stack);
            var known = discoveries(tag);
            lines.add(Component.translatable("gui.@NAMESPACE@.journal_count", known.size()));
            String id = tag.getString(ACTIVE);
            if (known.contains(id)) lines.add(Component.translatable("gui.@NAMESPACE@.journal_active", ResearchMenus.name(id)));
            if (tag.contains(OWNER, Tag.TAG_STRING)) lines.add(Component.translatable("gui.@NAMESPACE@.journal_owner", tag.getString(OWNER)));
        }
    }

    private static final class MicroscopeBlock extends BaseEntityBlock {
        private static final MapCodec<MicroscopeBlock> CODEC = simpleCodec(MicroscopeBlock::new);
        MicroscopeBlock(Properties properties) { super(properties); }
        @Override protected MapCodec<MicroscopeBlock> codec() { return CODEC; }
        @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Microscope(pos, state); }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (player.isShiftKeyDown()) return InteractionResult.PASS;
            if (!level.isClientSide() && level.getBlockEntity(pos) instanceof Microscope microscope) player.openMenu(microscope);
            return InteractionResult.sidedSuccess(level.isClientSide());
        }

        @Override
        protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
            if (!level.isClientSide() && !state.is(replacement.getBlock()) && level.getBlockEntity(pos) instanceof Microscope microscope) {
                for (int slot = 0; slot < INSPECTION_SLOTS; slot++) {
                    var stack = microscope.inventory.getStackInSlot(slot);
                    if (!stack.isEmpty()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
                }
            }
            super.onRemove(state, level, pos, replacement, moved);
        }
    }

    static class InspectionSlots extends ItemStackHandler {
        InspectionSlots() { super(INSPECTION_SLOTS); }
        @Override public int getSlotLimit(int slot) { return 1; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return slot == BOOK_SLOT ? isJournal(stack) : !isJournal(stack); }
    }

    static final class Microscope extends BlockEntity implements MenuProvider {
        final ItemStackHandler inventory = new InspectionSlots() {
            @Override protected void onContentsChanged(int slot) {
                if (getLevel() != null && !getLevel().isClientSide()) {
                    discover(getStackInSlot(SAMPLE_SLOT), getStackInSlot(BOOK_SLOT));
                    setChanged();
                }
            }
        };
        Microscope(BlockPos pos, BlockState state) { super(ENTITY.get(), pos, state); }
        @Override public Component getDisplayName() { return getBlockState().getBlock().getName(); }
        @Override public AbstractContainerMenu createMenu(int id, Inventory player, Player user) { return new MicroscopeMenu(id, player, this); }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            tag.put("Inspection", inventory.serializeNBT(registries));
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            var stored = tag.getCompound("Inspection");
            stored.putInt("Size", INSPECTION_SLOTS);
            inventory.deserializeNBT(registries, stored);
        }
    }
}
