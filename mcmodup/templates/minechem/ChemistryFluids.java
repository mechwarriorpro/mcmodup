package @PACKAGE@;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.fluids.capability.wrappers.FluidBucketWrapper;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

final class ChemistryFluids {
    static final int TUBE = 125, BUCKET = 8 * TUBE;
    private static final boolean RELEASE_394 = @RELEASE_394@;
    private static final DeferredRegister<FluidType> TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, CatalogPort.MOD_ID);
    private static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(Registries.FLUID, CatalogPort.MOD_ID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CatalogPort.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CatalogPort.MOD_ID);
    private static final ResourceLocation WATER_TUBE = id("molecule_water");
    private static final ResourceLocation EMPTY_TUBE = id("test_tube");

    record Chemical(String path, String chemical, int density, int viscosity, int tint, boolean placeable) {
        Item tube() { return BuiltInRegistries.ITEM.get(ResourceLocation.parse(chemical)); }
        Fluid source() { return BuiltInRegistries.FLUID.get(id(path)); }
        Item bucket() { return BuiltInRegistries.ITEM.get(id("bucket_" + path.substring(6))); }
        ResourceLocation typeId() { return id(path); }
    }
    static final List<Chemical> CHEMICALS = List.of(
@FLUID_ROWS@
    );
    private static final Map<Item, Chemical> TUBES = new HashMap<>(), BUCKETS = new HashMap<>();
    private static final Map<Fluid, Chemical> SOURCES = new HashMap<>();
    private static final Map<Fluid, Integer> DISPLAY = new HashMap<>();
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SourceContents>> RADIOACTIVE_SOURCE = RELEASE_394 ?
        ENTITIES.register("radioactive_fluid_source", () -> BlockEntityType.Builder.of(SourceContents::new, CHEMICALS.stream()
            .filter(spec -> spec.placeable() && ChemistryRadiation.tier(spec.chemical()) > 0)
            .map(spec -> BuiltInRegistries.BLOCK.get(id(spec.path()))).toArray(Block[]::new)).build(null)) : null;

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(CatalogPort.MOD_ID, path); }
    static Chemical tubeType(ItemStack stack) { return TUBES.get(stack.getItem()); }
    static Item emptyTube() { return BuiltInRegistries.ITEM.get(EMPTY_TUBE); }
    static void rememberSource(Level level, BlockPos position, ChemistryRadiation.Clock clock) {
        if (clock != null && level.getBlockEntity(position) instanceof SourceContents source) {
            source.clock = clock;
            source.setChanged();
        }
    }
    static boolean localFluid(Fluid fluid) { return SOURCES.containsKey(fluid); }
    static Chemical bucketType(ItemStack stack) { return BUCKETS.get(stack.getItem()); }
    static Item bucketForTube(Item tube) {
        if (tube == BuiltInRegistries.ITEM.get(WATER_TUBE)) return @WATER_BUCKET_CRAFT@ ? Items.WATER_BUCKET : Items.AIR;
        var spec = TUBES.get(tube);
        return spec != null && spec.placeable() ? spec.bucket() : Items.AIR;
    }
    static Item tubeForBucket(Item bucket) {
        if (bucket == Items.WATER_BUCKET) return BuiltInRegistries.ITEM.get(WATER_TUBE);
        var spec = BUCKETS.get(bucket);
        return spec == null ? Items.AIR : spec.tube();
    }
    static int displayId(Fluid fluid) {
        if (fluid == Fluids.WATER) return 1;
        if (fluid == Fluids.LAVA) return 2;
        return DISPLAY.getOrDefault(fluid, 0);
    }
    static Component displayName(int index) {
        return switch (index) {
            case 0 -> Component.translatable("gui.@NAMESPACE@.tank_empty");
            case 1 -> Fluids.WATER.getFluidType().getDescription();
            case 2 -> Fluids.LAVA.getFluidType().getDescription();
            default -> index < 3 || index - 3 >= CHEMICALS.size() ? Component.empty() : CHEMICALS.get(index - 3).tube().getDescription();
        };
    }

    static List<net.neoforged.neoforge.registries.DeferredItem<?>> register(IEventBus bus, DeferredRegister.Items items) {
        var buckets = new ArrayList<net.neoforged.neoforge.registries.DeferredItem<?>>();
@REGISTER_EMPTY_TUBE@
        for (var spec : CHEMICALS) {
            var type = TYPES.register(spec.path(), () -> new FluidType(FluidType.Properties.create()
                .density(spec.density()).viscosity(spec.viscosity()).canConvertToSource(false)
                .canDrown(spec.density() > 0).sound(SoundActions.BUCKET_FILL, SoundEvents.BUCKET_FILL)
                .sound(SoundActions.BUCKET_EMPTY, SoundEvents.BUCKET_EMPTY)) {
                @Override public ItemStack getBucket(FluidStack contents) {
                    var bucket = super.getBucket(contents);
                    var clock = contents.get(ChemistryRadiation.CLOCK.get());
                    if (!bucket.isEmpty() && clock != null) bucket.set(ChemistryRadiation.CLOCK.get(), clock);
                    return bucket;
                }
            });
            var properties = new BaseFlowingFluid.Properties(type,
                () -> spec.source(), () -> BuiltInRegistries.FLUID.get(id("flowing_" + spec.path())))
                .tickRate(Math.max(1, spec.viscosity() / 200)).levelDecreasePerBlock(spec.density() == 0 ? 8 : 1);
            // Both variants exist even for the registered, non-placeable FINAL fluids.
            var still = FLUIDS.register(spec.path(), () -> new BaseFlowingFluid.Source(properties));
            FLUIDS.register("flowing_" + spec.path(), () -> new BaseFlowingFluid.Flowing(properties));
            if (!spec.placeable()) continue;
            var block = BLOCKS.register(spec.path(), () -> ChemistryRadiation.tier(spec.chemical()) > 0
                ? new RadioactiveBlock((FlowingFluid) still.get(), spec) : new ChemicalBlock((FlowingFluid) still.get(), spec));
            var bucket = items.registerItem("bucket_" + spec.path().substring(6), p -> new ChemicalBucket(still.get(), p),
                new Item.Properties().stacksTo(1).craftRemainder(Items.BUCKET));
            properties.block(block).bucket(bucket);
            buckets.add(bucket);
        }
        TYPES.register(bus);
        FLUIDS.register(bus);
        BLOCKS.register(bus);
        ENTITIES.register(bus);
        bus.addListener(ChemistryFluids::capabilities);
        if (RELEASE_394) ChemicalBucketRecipe.register(bus);
        return List.copyOf(buckets);
    }

    private static void capabilities(RegisterCapabilitiesEvent event) {
        for (int index = 0; index < CHEMICALS.size(); index++) {
            var type = CHEMICALS.get(index);
            SOURCES.put(type.source(), type);
            DISPLAY.put(type.source(), index + 3);
            if (!type.placeable()) continue;
            TUBES.put(type.tube(), type);
            BUCKETS.put(type.bucket(), type);
            event.registerItem(Capabilities.FluidHandler.ITEM, (stack, context) -> new TubeContents(stack), type.tube());
            event.registerItem(Capabilities.FluidHandler.ITEM, (stack, context) -> new BucketContents(stack), type.bucket());
        }
        if (RELEASE_394) event.registerItem(Capabilities.FluidHandler.ITEM, (stack, context) -> new TubeContents(stack),
            BuiltInRegistries.ITEM.get(WATER_TUBE), BuiltInRegistries.ITEM.get(EMPTY_TUBE));
    }
    static Item tubeForFluid(Fluid fluid) {
        if (fluid == Fluids.WATER) return BuiltInRegistries.ITEM.get(WATER_TUBE);
        var type = SOURCES.get(fluid);
        return type != null && type.placeable() ? type.tube() : Items.AIR;
    }

    private static final class TubeContents implements IFluidHandlerItem {
        private ItemStack tube;
        TubeContents(ItemStack tube) { this.tube = tube; }
        @Override public ItemStack getContainer() { return tube; }
        @Override public int getTanks() { return 1; }
        @Override public int getTankCapacity(int tank) { return tank == 0 ? TUBE : 0; }
        @Override public boolean isFluidValid(int tank, FluidStack fluid) { return tank == 0 && tubeForFluid(fluid.getFluid()) != Items.AIR; }
        @Override public FluidStack getFluidInTank(int tank) {
            if (tank != 0) return FluidStack.EMPTY;
            var spec = TUBES.get(tube.getItem());
            var fluid = tube.is(BuiltInRegistries.ITEM.get(WATER_TUBE)) ? Fluids.WATER : spec == null ? Fluids.EMPTY : spec.source();
            if (fluid == Fluids.EMPTY) return FluidStack.EMPTY;
            var contents = new FluidStack(fluid, TUBE);
            var clock = tube.get(ChemistryRadiation.CLOCK.get());
            if (clock != null) contents.set(ChemistryRadiation.CLOCK.get(), clock);
            return contents;
        }
        @Override public int fill(FluidStack fluid, FluidAction action) {
            if (tube.getCount() != 1 || !tube.is(BuiltInRegistries.ITEM.get(EMPTY_TUBE)) || fluid.getAmount() < TUBE || !isFluidValid(0, fluid)) return 0;
            if (action.execute()) {
                tube = new ItemStack(tubeForFluid(fluid.getFluid()));
                var clock = fluid.get(ChemistryRadiation.CLOCK.get());
                if (clock != null) tube.set(ChemistryRadiation.CLOCK.get(), clock);
            }
            return TUBE;
        }
        @Override public FluidStack drain(FluidStack fluid, FluidAction action) {
            return FluidStack.isSameFluidSameComponents(fluid, getFluidInTank(0)) ? drain(fluid.getAmount(), action) : FluidStack.EMPTY;
        }
        @Override public FluidStack drain(int amount, FluidAction action) {
            if (amount < TUBE || tube.getCount() != 1) return FluidStack.EMPTY;
            var fluid = getFluidInTank(0);
            if (!fluid.isEmpty() && action.execute()) tube = new ItemStack(BuiltInRegistries.ITEM.get(EMPTY_TUBE));
            return fluid;
        }
    }

    private static final class BucketContents extends FluidBucketWrapper {
        BucketContents(ItemStack bucket) { super(bucket); }
        @Override public FluidStack getFluid() {
            var contents = super.getFluid();
            var clock = getContainer().get(ChemistryRadiation.CLOCK.get());
            if (!contents.isEmpty() && clock != null) contents.set(ChemistryRadiation.CLOCK.get(), clock);
            return contents;
        }
    }

    static int mergeClock(FluidTank tank, FluidStack offered, FluidAction action) {
        var current = tank.getFluid();
        if (!tank.isFluidValid(offered) || current.isEmpty() || offered.isEmpty() || !FluidStack.isSameFluid(current, offered)) return 0;
        var component = ChemistryRadiation.CLOCK.get();
        var a = current.copy();
        var b = offered.copy();
        var currentClock = a.remove(component);
        var offeredClock = b.remove(component);
        if (!FluidStack.isSameFluidSameComponents(a, b)) return 0;
        int accepted = Math.min(tank.getSpace(), offered.getAmount());
        if (accepted > 0 && action.execute()) {
            // Keep the older sample when two amounts of the same fluid coalesce.
            var merged = ChemistryRadiation.olderThan(offeredClock, currentClock) ? offeredClock : currentClock;
            var contents = current.copyWithAmount(current.getAmount() + accepted);
            if (merged != null) contents.set(component, merged);
            tank.setFluid(contents);
        }
        return accepted;
    }

    private static class ChemicalBlock extends LiquidBlock {
        private final Chemical type;
        ChemicalBlock(FlowingFluid fluid, Chemical type) {
            super(fluid, BlockBehaviour.Properties.ofFullCopy(Blocks.WATER));
            this.type = type;
        }
        @Override protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
            super.entityInside(state, level, pos, entity);
            if (level.isClientSide() || !ChemistryRadiation.fluidEffects() || !(entity instanceof LivingEntity target)) return;
            ChemistryEffects.apply(target, type.chemical());
            int tier = ChemistryRadiation.tier(type.chemical());
            if (tier > 0) target.addEffect(new MobEffectInstance(MobEffects.POISON, 10, tier - 1));
        }
    }

    private static final class ChemicalBucket extends BucketItem {
        ChemicalBucket(Fluid fluid, Properties properties) { super(fluid, properties); }
        @Override public void checkExtraContent(Player player, Level level, ItemStack stack, BlockPos pos) {
            if (!level.isClientSide()) rememberSource(level, pos, stack.get(ChemistryRadiation.CLOCK.get()));
        }
    }

    private static final class RadioactiveBlock extends ChemicalBlock implements EntityBlock {
        RadioactiveBlock(FlowingFluid fluid, Chemical type) { super(fluid, type); }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return state.getFluidState().isSource() ? new SourceContents(pos, state) : null;
        }
        @Override public ItemStack pickupBlock(Player player, LevelAccessor level, BlockPos pos, BlockState state) {
            var clock = level.getBlockEntity(pos) instanceof SourceContents source ? source.clock : null;
            var bucket = super.pickupBlock(player, level, pos, state);
            if (!bucket.isEmpty() && clock != null) bucket.set(ChemistryRadiation.CLOCK.get(), clock);
            return bucket;
        }
    }

    private static final class SourceContents extends BlockEntity {
        private ChemistryRadiation.Clock clock;
        SourceContents(BlockPos pos, BlockState state) { super(RADIOACTIVE_SOURCE.get(), pos, state); }
        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            if (clock != null) ChemistryRadiation.Clock.CODEC.encodeStart(NbtOps.INSTANCE, clock).result().ifPresent(value -> tag.put("Decay", value));
        }
        @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            clock = ChemistryRadiation.Clock.CODEC.parse(NbtOps.INSTANCE, tag.getCompound("Decay")).result().orElse(null);
        }
    }

    private ChemistryFluids() {}
}
