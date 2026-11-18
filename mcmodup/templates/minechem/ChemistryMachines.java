package @PACKAGE@;

import com.mojang.serialization.MapCodec;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
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

public final class ChemistryMachines {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CatalogPort.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CatalogPort.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, CatalogPort.MOD_ID);
    static final DeferredBlock<MachineBlock> SYNTHESIZER = Machine.PREPAID_DECOMPOSER ? null :
        BLOCKS.register("synthesizer", () -> new MachineBlock(BlockBehaviour.Properties.of().strength(3.5F).requiresCorrectToolForDrops()));
    static final DeferredBlock<MachineBlock> DECOMPOSER =
        BLOCKS.register("decomposer", () -> new MachineBlock(BlockBehaviour.Properties.of().strength(3.5F).requiresCorrectToolForDrops()));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Machine>> MACHINE =
        ENTITIES.register("chemical_machine", () -> BlockEntityType.Builder.of(Machine::new,
            SYNTHESIZER == null ? new MachineBlock[]{DECOMPOSER.get()} : new MachineBlock[]{SYNTHESIZER.get(), DECOMPOSER.get()}).build(null));
    static final DeferredHolder<MenuType<?>, MenuType<ChemistryMenu>> MENU =
        MENUS.register("chemical_machine", () -> new MenuType<>(ChemistryMenu::new, FeatureFlags.DEFAULT_FLAGS));

    private ChemistryMachines() {}

    static List<? extends DeferredItem<?>> register(IEventBus bus, DeferredRegister.Items items) {
        BLOCKS.register(bus);
        ENTITIES.register(bus);
        MENUS.register(bus);
        bus.addListener(ChemistryMachines::registerCapabilities);
        var decomposer = items.registerSimpleBlockItem(DECOMPOSER);
        return SYNTHESIZER == null ? List.of(decomposer) : List.of(items.registerSimpleBlockItem(SYNTHESIZER), decomposer);
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, MACHINE.get(), (machine, side) -> machine.power);
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, MACHINE.get(), (machine, side) -> {
            if (machine.bufferedDecomposer()) return side == null || side == Direction.UP ? machine.inputs : machine.outputs;
            return side == Direction.DOWN ? machine.outputs : machine.inputs;
        });
    }

    public static final class MachineBlock extends BaseEntityBlock {
        private static final MapCodec<MachineBlock> CODEC = simpleCodec(MachineBlock::new);

        MachineBlock(Properties properties) { super(properties); }

        @Override protected MapCodec<MachineBlock> codec() { return CODEC; }
        @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Machine(pos, state); }

        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
            return level.isClientSide() ? null : createTickerHelper(type, MACHINE.get(), Machine::tick);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (!level.isClientSide() && level.getBlockEntity(pos) instanceof Machine machine) player.openMenu(machine);
            return InteractionResult.sidedSuccess(level.isClientSide());
        }

        @Override
        protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
            if (!level.isClientSide() && !state.is(replacement.getBlock()) && level.getBlockEntity(pos) instanceof Machine machine) {
                for (int slot = 0; slot < Machine.SLOTS; slot++) {
                    ItemStack stack = machine.inventory.getStackInSlot(slot);
                    if (!stack.isEmpty()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
                }
            }
            super.onRemove(state, level, pos, replacement, moved);
        }
    }

    static final class Machine extends BlockEntity implements MenuProvider {
        static final int CHARGE = 18, FIRST_OUTPUT = 19, JOURNAL = 37, SLOTS = JOURNAL + 1;
        static final int CAPACITY = 10_000, DUST_FE = 500;
        static final boolean ORIGINAL_DECOMPOSER = @BUFFERED_DECOMPOSER@;
        static final boolean PREPAID_DECOMPOSER = @PREPAID_DECOMPOSER@;
        static final int BUFFERED_OUTPUTS = PREPAID_DECOMPOSER ? 18 : 9;
        static final int IDLE = 0, WORKING = 1, NEED_INPUT = 2, NEED_POWER = 3, OUTPUT_FULL = 4, DONE = 5;

        private int selected;
        private int remaining;
        private int status;
        private boolean automatic;
        private String job = "";
        private final Deque<ItemStack> decompositionBuffer = new ArrayDeque<>();

        final ItemStackHandler inventory = new ItemStackHandler(SLOTS) {
            @Override protected void onContentsChanged(int slot) {
                if (slot == JOURNAL && synthesis() && remaining == 0 && ChemistryResearch.isJournal(getStackInSlot(JOURNAL))) {
                    String recipe = ChemistryResearch.active(getStackInSlot(JOURNAL));
                    if (ChemistryRecipes.BY_RESULT.containsKey(recipe)) {
                        selected = ChemistryRecipes.indexOf(recipe);
                        status = IDLE;
                    }
                }
                setChanged();
            }
            @Override public int getSlotLimit(int slot) { return slot == JOURNAL ? 1 : super.getSlotLimit(slot); }
            @Override public boolean isItemValid(int slot, ItemStack stack) {
                if (stack.isEmpty()) return false;
                if (slot == JOURNAL) return synthesis() && remaining == 0 && ChemistryResearch.isJournal(stack);
                if (slot >= FIRST_OUTPUT) return false;
                if (slot == CHARGE) return !bufferedDecomposer() && stack.is(Items.REDSTONE);
                String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                return synthesis() ? ChemistryRecipes.CHEMICALS.contains(key) : slot == 0 && ChemistryRecipes.DECOMPOSE.containsKey(key);
            }
        };
        final RangedWrapper inputs;
        final RangedWrapper outputs;
        private final Power power;

        private final class Power extends EnergyStorage {
            Power(int maxReceive) { super(CAPACITY, maxReceive, 0); }
            @Override public int receiveEnergy(int amount, boolean simulate) {
                if (amount <= 0) return 0;
                int received = super.receiveEnergy(amount, simulate);
                if (!simulate && received > 0) setChanged();
                return received;
            }
            void restore(int amount) { this.energy = Math.clamp(amount, 0, CAPACITY); }
        }

        Machine(BlockPos pos, BlockState state) {
            super(MACHINE.get(), pos, state);
            boolean buffered = bufferedDecomposer();
            inputs = new RangedWrapper(inventory, 0, buffered ? 1 : FIRST_OUTPUT);
            outputs = new RangedWrapper(inventory, FIRST_OUTPUT, buffered ? FIRST_OUTPUT + BUFFERED_OUTPUTS : JOURNAL);
            power = new Power(PREPAID_DECOMPOSER ? 100 : buffered ? 20 : 200);
        }
        boolean synthesis() { return SYNTHESIZER != null && getBlockState().is(SYNTHESIZER.get()); }
        boolean bufferedDecomposer() { return ORIGINAL_DECOMPOSER && !synthesis(); }

        private ChemistryRecipes.Recipe currentDecomposition() {
            return ChemistryRecipes.DECOMPOSE.get(BuiltInRegistries.ITEM.getKey(inventory.getStackInSlot(0).getItem()).toString());
        }

        @Override public Component getDisplayName() { return getBlockState().getBlock().getName(); }
        @Override public AbstractContainerMenu createMenu(int id, Inventory player, Player user) { return new ChemistryMenu(id, player, this); }

        ContainerData data() {
            return new ContainerData() {
                @Override public int get(int index) {
                    return switch (index) {
                        case 0 -> synthesis() ? 1 : 0;
                        case 1 -> selected;
                        case 2 -> power.getEnergyStored();
                        case 3 -> status;
                        case 4 -> bufferedDecomposer() ? decompositionBuffer.stream().mapToInt(ItemStack::getCount).sum() : remaining;
                        case 5 -> (bufferedDecomposer() || automatic) ? 1 : 0;
                        default -> 0;
                    };
                }
                @Override public void set(int index, int value) {} // Server is authoritative.
                @Override public int getCount() { return 6; }
            };
        }

        boolean advance(int by) {
            if (!synthesis() || remaining > 0) return false;
            selected = Math.floorMod(selected + by, ChemistryRecipes.SYNTHESIZE.size());
            status = IDLE;
            setChanged();
            return true;
        }

        boolean toggleAutomatic() {
            if (bufferedDecomposer()) return false;
            automatic = !automatic;
            setChanged();
            return true;
        }

        boolean request() {
            if (bufferedDecomposer() || remaining > 0) return false;
            ChemistryRecipes.Recipe recipe = synthesis() ? ChemistryRecipes.SYNTHESIZE.get(selected) : currentDecomposition();
            if (recipe == null) {
                status = NEED_INPUT;
                return true;
            }
            Plan plan = plan(recipe);
            status = plan.status();
            if (status == WORKING) {
                job = recipe.id();
                remaining = recipe.ticks();
                setChanged();
            }
            return true;
        }

        private record Plan(int status, ItemStack[] stacks, int energy) {}

        /** Simulate every decrement and output insertion before touching any real slot or FE. */
        private Plan plan(ChemistryRecipes.Recipe recipe) {
            // The journal only selects a recipe; it never participates in processing.
            ItemStack[] stacks = new ItemStack[JOURNAL];
            for (int i = 0; i < stacks.length; i++) stacks[i] = inventory.getStackInSlot(i).copy();
            for (var part : recipe.inputs()) {
                int count = part.count();
                for (int i = 0; count > 0 && i < (synthesis() ? CHARGE : 1); i++) {
                    if (!stacks[i].is(part.item())) continue;
                    int take = Math.min(count, stacks[i].getCount());
                    stacks[i].shrink(take);
                    count -= take;
                }
                if (count > 0) return new Plan(NEED_INPUT, null, 0);
            }
            for (var part : recipe.outputs()) {
                int count = part.count();
                ItemStack output = new ItemStack(part.item());
                for (int i = FIRST_OUTPUT; count > 0 && i < stacks.length; i++) {
                    ItemStack current = stacks[i];
                    if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, output)) continue;
                    int put = Math.min(count, output.getMaxStackSize() - current.getCount());
                    if (put <= 0) continue;
                    stacks[i] = output.copyWithCount(current.getCount() + put);
                    count -= put;
                }
                if (count > 0) return new Plan(OUTPUT_FULL, null, 0);
            }
            int energy = power.getEnergyStored();
            if (energy < recipe.energy()) {
                ItemStack dust = stacks[CHARGE];
                int needed = Math.ceilDiv(recipe.energy() - energy, DUST_FE);
                if (!dust.is(Items.REDSTONE) || dust.getCount() < needed) return new Plan(NEED_POWER, null, 0);
                dust.shrink(needed);
                energy = Math.min(CAPACITY, energy + needed * DUST_FE);
            }
            return new Plan(WORKING, stacks, energy - recipe.energy());
        }

        private void tickBufferedDecomposer() {
            int energy = power.getEnergyStored();
            if (decompositionBuffer.isEmpty()) {
                var recipe = currentDecomposition();
                if (recipe == null || energy < (PREPAID_DECOMPOSER ? recipe.energy() : 1)) {
                    if (status != IDLE) { status = IDLE; setChanged(); }
                    return;
                }
                for (var part : recipe.outputs()) {
                    ItemStack product = new ItemStack(part.item());
                    for (int count = part.count(); count > 0;) {
                        int batch = Math.min(count, product.getMaxStackSize());
                        decompositionBuffer.addLast(product.copyWithCount(batch));
                        count -= batch;
                    }
                }
                inventory.extractItem(0, 1, false);
                if (PREPAID_DECOMPOSER) {
                    power.restore(energy - recipe.energy());
                    @COMPACT_PREPAID@;
                }
                status = WORKING;
                setChanged();
                return; // Intake and the first output occur on different ticks.
            }
            int end = FIRST_OUTPUT + BUFFERED_OUTPUTS;
            if (!PREPAID_DECOMPOSER && status == OUTPUT_FULL) {
                // FINAL only clears a jam with power and an empty slot, even if
                // an occupied matching stack could otherwise accept an item.
                if (energy <= 0 || !(@FINAL_CAN_UNJAM@)) return;
            }
            ItemStack next = decompositionBuffer.getFirst();
            int destination = -1;
            for (int slot = FIRST_OUTPUT; slot < end; slot++) {
                ItemStack stored = inventory.getStackInSlot(slot);
                if (stored.isEmpty()) {
                    if (destination < 0) destination = slot;
                    if (!PREPAID_DECOMPOSER) break; // FINAL uses the first usable slot.
                } else if (ItemStack.isSameItemSameComponents(stored, next) && stored.getCount() < stored.getMaxStackSize()) {
                    destination = slot;
                    break; // .394 merges into a matching stack before using an empty slot.
                }
            }
            if (destination < 0) {
                if (status != OUTPUT_FULL) { status = OUTPUT_FULL; setChanged(); }
                return;
            }
            int count = inventory.getStackInSlot(destination).getCount();
            inventory.setStackInSlot(destination, next.copyWithCount(count + 1));
            next.shrink(1);
            if (next.isEmpty()) decompositionBuffer.removeFirst();
            // .394 already paid at intake; FINAL spends up to one stored unit
            // but keeps draining a running buffer even with zero stored power.
            if (!PREPAID_DECOMPOSER) power.restore(Math.max(0, energy - 1));
            status = WORKING;
            setChanged();
        }

        private boolean hasEmptyFinalOutput() {
            for (int slot = FIRST_OUTPUT; slot < FIRST_OUTPUT + BUFFERED_OUTPUTS; slot++) {
                if (inventory.getStackInSlot(slot).isEmpty()) return true;
            }
            return false;
        }

        // .394 compacts stacks already in the output inventory at intake,
        // before emitting the first tube from the new buffer on a later tick.
        private void compactPrepaidOutputs() {
            for (int from = FIRST_OUTPUT + BUFFERED_OUTPUTS - 1; from > FIRST_OUTPUT; from--) {
                ItemStack moving = inventory.getStackInSlot(from);
                if (moving.isEmpty()) continue;
                for (int to = FIRST_OUTPUT; to < from; to++) {
                    ItemStack target = inventory.getStackInSlot(to);
                    if (target.isEmpty()) {
                        inventory.setStackInSlot(to, moving.copy());
                        inventory.setStackInSlot(from, ItemStack.EMPTY);
                        break;
                    }
                    if (ItemStack.isSameItemSameComponents(target, moving)) {
                        int transfer = Math.min(moving.getCount(), target.getMaxStackSize() - target.getCount());
                        if (transfer > 0) {
                            inventory.setStackInSlot(to, target.copyWithCount(target.getCount() + transfer));
                            inventory.setStackInSlot(from, moving.copyWithCount(moving.getCount() - transfer));
                        }
                        break;
                    }
                }
            }
        }

        static void tick(Level level, BlockPos pos, BlockState state, Machine machine) {
            if (machine.bufferedDecomposer()) { machine.tickBufferedDecomposer(); return; }
            if (machine.remaining <= 0) {
                // Periodically retry with current hopper contents and FE. Stagger across positions.
                if (machine.automatic && (level.getGameTime() + pos.hashCode()) % 5 == 0) machine.request();
                return;
            }
            machine.remaining--;
            if (machine.remaining == 0) machine.finish();
            machine.setChanged();
        }

        private void finish() {
            var recipe = (synthesis() ? ChemistryRecipes.BY_RESULT : ChemistryRecipes.DECOMPOSE).get(job);
            job = "";
            if (recipe == null) { status = IDLE; return; }
            Plan plan = plan(recipe);
            status = plan.status();
            if (status != WORKING) return; // Inventory changed during the run: no items or energy were consumed.
            for (int i = 0; i < plan.stacks().length; i++) inventory.setStackInSlot(i, plan.stacks()[i]);
            power.restore(plan.energy());
            status = DONE;
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            tag.put("Inventory", inventory.serializeNBT(registries));
            tag.putInt("Energy", power.getEnergyStored());
            if (bufferedDecomposer()) {
                ListTag queue = new ListTag();
                for (ItemStack stack : decompositionBuffer) queue.add(stack.save(registries));
                tag.put("DecompositionBuffer", queue);
                tag.putBoolean("DecomposerJammed", status == OUTPUT_FULL);
                return;
            }
            tag.putString("Selected", ChemistryRecipes.SYNTHESIZE.get(selected).id());
            tag.putString("Job", job);
            tag.putInt("Remaining", remaining);
            tag.putBoolean("Automatic", automatic);
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            CompoundTag stored = tag.getCompound("Inventory");
            stored.putInt("Size", SLOTS);
            inventory.deserializeNBT(registries, stored);
            power.restore(tag.getInt("Energy"));
            if (bufferedDecomposer()) {
                decompositionBuffer.clear();
                for (Tag entry : tag.getList("DecompositionBuffer", Tag.TAG_COMPOUND)) {
                    ItemStack stack = ItemStack.parseOptional(registries, (CompoundTag) entry);
                    if (!stack.isEmpty()) decompositionBuffer.addLast(stack);
                }
                status = decompositionBuffer.isEmpty() ? IDLE : tag.getBoolean("DecomposerJammed") ? OUTPUT_FULL : WORKING;
                return;
            }
            automatic = tag.getBoolean("Automatic");
            selected = ChemistryRecipes.indexOf(tag.getString("Selected"));
            job = tag.getString("Job");
            var recipe = (synthesis() ? ChemistryRecipes.BY_RESULT : ChemistryRecipes.DECOMPOSE).get(job);
            remaining = recipe == null ? 0 : Math.clamp(tag.getInt("Remaining"), 0, recipe.ticks());
            status = remaining == 0 ? IDLE : WORKING;
        }
    }
}
