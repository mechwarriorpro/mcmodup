package @PACKAGE@;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** .394 survival exchanges: eight matching vials per source, never partial world transfers. */
final class TubeWorldUse {
    static final class TubeItem extends Item {
        private final boolean empty;
        TubeItem(Properties properties, boolean empty) { super(properties); this.empty = empty; }
        @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            var hit = getPlayerPOVHitResult(level, player, empty ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE);
            return empty ? collect(level, player, hand, hit) : pour(level, player, hand, hit);
        }
    }

    static InteractionResultHolder<ItemStack> pour(Level level, Player player, InteractionHand hand, BlockHitResult hit) {
        var held = player.getItemInHand(hand);
        var chemical = ChemistryFluids.tubeType(held);
        if (chemical == null || hit.getType() != HitResult.Type.BLOCK) return InteractionResultHolder.pass(held);
        var target = hit.getBlockPos().relative(hit.getDirection());
        // Do not destroy an existing fluid or replace a machine/plant just to pour a tube.
        if (!allowed(level, player, hit, target, held) || !level.getBlockState(target).isAir()) return InteractionResultHolder.pass(held);
        if (!player.isCreative() && available(player, held) < 8) return InteractionResultHolder.pass(held);
        if (level.isClientSide()) return InteractionResultHolder.success(held);
        var state = chemical.source().defaultFluidState().createLegacyBlock();
        if (!level.setBlockAndUpdate(target, state)) return InteractionResultHolder.fail(held);
        if (!player.isCreative()) {
            var clock = payEight(player, held);
            ChemistryFluids.rememberSource(level, target, clock);
            return give(player, hand, held, new ItemStack(ChemistryFluids.emptyTube(), 8));
        }
        ChemistryFluids.rememberSource(level, target, held.get(ChemistryRadiation.CLOCK.get()));
        return InteractionResultHolder.consume(held);
    }

    static InteractionResultHolder<ItemStack> collect(Level level, Player player, InteractionHand hand, BlockHitResult hit) {
        var held = player.getItemInHand(hand);
        var target = hit.getBlockPos();
        var state = level.getBlockState(target);
        var fluid = state.getFluidState();
        var tube = ChemistryFluids.tubeForFluid(fluid.getType());
        if (hit.getType() != HitResult.Type.BLOCK || !fluid.isSource() || tube == net.minecraft.world.item.Items.AIR ||
                !(state.getBlock() instanceof BucketPickup source) || !allowed(level, player, hit, target, held)) return InteractionResultHolder.pass(held);
        if (!player.isCreative() && available(player, held) < 8) return InteractionResultHolder.fail(held);
        if (level.isClientSide()) return InteractionResultHolder.success(held);
        var recovered = source.pickupBlock(player, level, target, state);
        if (recovered.isEmpty()) return InteractionResultHolder.fail(held);
        if (player.isCreative()) return InteractionResultHolder.consume(held);
        payEight(player, held);
        var filled = new ItemStack(tube, 8);
        var clock = recovered.get(ChemistryRadiation.CLOCK.get());
        if (clock != null) filled.set(ChemistryRadiation.CLOCK.get(), clock);
        return give(player, hand, held, filled);
    }

    private static boolean allowed(Level level, Player player, BlockHitResult hit, BlockPos pos, ItemStack held) {
        return level.getWorldBorder().isWithinBounds(pos) && level.mayInteract(player, hit.getBlockPos()) && level.mayInteract(player, pos)
            && player.mayUseItemAt(pos, hit.getDirection(), held);
    }
    private static int available(Player player, ItemStack held) {
        int count = held.getCount();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize() && count < 8; i++) {
            var stack = inventory.getItem(i);
            if (stack != held && stack.is(held.getItem())) count += stack.getCount();
        }
        return count;
    }
    private static ChemistryRadiation.Clock payEight(Player player, ItemStack held) {
        var type = held.getItem();
        var oldest = held.get(ChemistryRadiation.CLOCK.get());
        int needed = 8 - Math.min(8, held.getCount());
        held.shrink(8 - needed);
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize() && needed > 0; i++) {
            var stack = inventory.getItem(i);
            if (stack == held || !stack.is(type)) continue;
            var age = stack.get(ChemistryRadiation.CLOCK.get());
            if (ChemistryRadiation.olderThan(age, oldest)) oldest = age;
            int paid = Math.min(stack.getCount(), needed);
            stack.shrink(paid);
            needed -= paid;
            if (stack.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
        }
        inventory.setChanged();
        return oldest;
    }
    private static InteractionResultHolder<ItemStack> give(Player player, InteractionHand hand, ItemStack previous, ItemStack reward) {
        player.level().playSound(null, player.blockPosition(), SoundEvents.BUCKET_FILL, SoundSource.PLAYERS, 1F, 1F);
        if (previous.isEmpty()) return InteractionResultHolder.consume(reward);
        if (!player.getInventory().add(reward)) player.drop(reward, false);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }
    private TubeWorldUse() {}
}
