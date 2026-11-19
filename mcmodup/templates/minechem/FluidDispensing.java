package @PACKAGE@;

import com.mojang.logging.LogUtils;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.core.dispenser.OptionalDispenseItemBehavior;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/** .394 container exchanges. Non-local empty bucket uses delegate to the existing behavior. */
final class FluidDispensing {
    static void register(IEventBus bus) { bus.addListener(FluidDispensing::setup); }
    private static void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            var tubes = new Tubes();
            var buckets = new Buckets();
            registerBucketPickup();
            DispenserBlock.registerBehavior(ChemistryFluids.emptyTube(), tubes);
            for (var fluid : ChemistryFluids.CHEMICALS) {
                if (!fluid.placeable()) continue;
                DispenserBlock.registerBehavior(fluid.bucket(), buckets);
                DispenserBlock.registerBehavior(fluid.tube(), tubes);
            }
        });
    }

    private static void registerBucketPickup() {
        // 1.21.1's registry exposes registration but not the previous behavior.
        // Vanilla's pickup reconstructs the result from the Item, dropping its decay clock.
        // Wrap just this item and keep the complete previous path for other targets.
        try {
            var registry = DispenserBlock.class.getDeclaredField("DISPENSER_REGISTRY");
            if (registry.trySetAccessible() && registry.get(null) instanceof Map<?, ?> behaviors &&
                    behaviors.get(Items.BUCKET) instanceof DispenseItemBehavior previous) {
                DispenserBlock.registerBehavior(Items.BUCKET, (source, stack) -> {
                    var target = front(source);
                    if (accessible(source, target)) {
                        var fluid = source.level().getFluidState(target);
                        if (fluid.isSource() && ChemistryFluids.worldType(fluid.getType()) != null)
                            return PICKUP.dispense(source, stack);
                    }
                    return previous.dispense(source, stack);
                });
            } else {
                LogUtils.getLogger().warn("Minechem could not wrap the empty bucket dispenser; leaving its previous behavior intact");
            }
        } catch (ReflectiveOperationException e) {
            LogUtils.getLogger().warn("Minechem could not read the 1.21.1 dispenser registry", e);
        }
    }

    private static final DefaultDispenseItemBehavior PICKUP = new DefaultDispenseItemBehavior() {
        @Override protected ItemStack execute(BlockSource source, ItemStack stack) {
            var target = front(source);
            var level = source.level();
            var state = level.getBlockState(target);
            if (!(state.getBlock() instanceof BucketPickup pickup)) return stack;
            var full = pickup.pickupBlock(null, level, target, state);
            if (full.isEmpty()) return stack;
            level.gameEvent(null, GameEvent.FLUID_PICKUP, target);
            stack.shrink(1);
            return returnToDispenser(source, stack, full);
        }
    };

    private static BlockPos front(BlockSource source) {
        return source.pos().relative(source.state().getValue(DispenserBlock.FACING));
    }
    private static boolean accessible(BlockSource source, BlockPos target) {
        var level = source.level();
        return !level.isOutsideBuildHeight(target) && level.hasChunkAt(target) && level.getWorldBorder().isWithinBounds(target);
    }

    private static final class Buckets extends OptionalDispenseItemBehavior {
        @Override protected ItemStack execute(BlockSource source, ItemStack bucket) {
            setSuccess(false);
            var target = front(source);
            var level = source.level();
            var chemical = ChemistryFluids.bucketType(bucket);
            if (chemical == null || !accessible(source, target) || !level.isEmptyBlock(target) ||
                !level.setBlockAndUpdate(target, chemical.source().defaultFluidState().createLegacyBlock())) return bucket;
            ChemistryFluids.rememberSource(level, target, bucket.get(ChemistryRadiation.CLOCK.get()));
            bucket.shrink(1);
            setSuccess(true);
            return returnToDispenser(source, bucket, new ItemStack(Items.BUCKET));
        }
    }

    private static final class Tubes extends OptionalDispenseItemBehavior {
        @Override protected ItemStack execute(BlockSource source, ItemStack selected) {
            setSuccess(false);
            var target = front(source);
            var level = source.level();
            if (!accessible(source, target) || TubeWorldUse.available(source.blockEntity(), selected) < 8) return selected;
            var state = level.getBlockState(target);
            if (selected.is(ChemistryFluids.emptyTube())) {
                var fluid = state.getFluidState();
                var tube = ChemistryFluids.tubeForFluid(fluid.getType());
                if (!fluid.isSource() || tube == Items.AIR || !(state.getBlock() instanceof BucketPickup pickup)) return selected;
                var container = pickup.pickupBlock(null, level, target, state);
                if (container.isEmpty()) return selected;
                TubeWorldUse.payEight(source.blockEntity(), selected);
                var result = new ItemStack(tube, 8);
                var clock = container.get(ChemistryRadiation.CLOCK.get());
                if (clock != null) result.set(ChemistryRadiation.CLOCK.get(), clock);
                setSuccess(true);
                return returnToDispenser(source, selected, result);
            }
            var chemical = ChemistryFluids.tubeType(selected);
            if (chemical == null || !state.isAir() || !level.setBlockAndUpdate(target, chemical.source().defaultFluidState().createLegacyBlock()))
                return selected;
            var clock = TubeWorldUse.payEight(source.blockEntity(), selected);
            ChemistryFluids.rememberSource(level, target, clock);
            setSuccess(true);
            return returnToDispenser(source, selected, new ItemStack(ChemistryFluids.emptyTube(), 8));
        }
    }

    private static ItemStack returnToDispenser(BlockSource source, ItemStack current, ItemStack result) {
        // Returning the new stack is required if the selected slot became empty; the vanilla
        // dispenser writes this method's return value to that slot after the operation.
        if (current.isEmpty()) return result;
        Container inventory = source.blockEntity();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var other = inventory.getItem(i);
            if (!ItemStack.isSameItemSameComponents(other, result)) continue;
            int moved = Math.min(result.getCount(), Math.min(inventory.getMaxStackSize(), other.getMaxStackSize()) - other.getCount());
            if (moved > 0) {
                other.grow(moved);
                result.shrink(moved);
                inventory.setChanged();
                if (result.isEmpty()) return current;
            }
        }
        for (int i = 0; i < inventory.getContainerSize(); i++) if (inventory.getItem(i).isEmpty()) {
            inventory.setItem(i, result);
            return current;
        }
        var target = front(source);
        Containers.dropItemStack(source.level(), target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, result);
        return current;
    }

    private FluidDispensing() {}
}
