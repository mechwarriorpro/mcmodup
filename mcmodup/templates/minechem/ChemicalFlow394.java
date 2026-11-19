package @PACKAGE@;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;

/** The measured .394 quanta, direction and displacement rules in loaded native chunks. */
final class ChemicalFlow394 extends BaseFlowingFluid {
    private static final Direction[] HORIZONTAL = {Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH};
    private static final int FULL = 8, NO_OUTLET = 1000, SEARCH_DISTANCE = 4;
    private static final TagKey<Block> FRAGILE = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath(CatalogPort.MOD_ID, "chemical_fluid_displaceable"));
    private final int density;
    private final boolean source;

    ChemicalFlow394(Properties properties, int density, boolean source) {
        super(properties);
        this.density = density;
        this.source = source;
        registerDefaultState(getStateDefinition().any().setValue(LEVEL, FULL - 1));
    }
    @Override protected void createFluidStateDefinition(StateDefinition.Builder<Fluid, FluidState> builder) {
        super.createFluidStateDefinition(builder);
        builder.add(LEVEL);
    }
    @Override public boolean isSource(FluidState state) { return source; }
    @Override public int getAmount(FluidState state) { return source ? FULL : state.getValue(LEVEL); }
    @Override protected boolean canBeReplacedWith(FluidState state, BlockGetter level, BlockPos pos, Fluid fluid, Direction direction) {
        return false;
    }

    @Override public void tick(Level world, BlockPos pos, FluidState state) {
        // Original one-quanta solids did not run the classic fluid update at all.
        if (density == 0 || !(world instanceof ServerLevel level) || !loaded(level, pos)) return;
        var vertical = density < 0 ? Direction.UP : Direction.DOWN;
        var feed = pos.relative(vertical.getOpposite());
        int amount = state.getAmount();
        if (!state.isSource()) {
            int next = fedVertically(level, feed) ? FULL - 1 : strongestNeighbor(level, pos) - 1;
            if (next <= 0) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                return; // An expired flow must not create a new unsupported column.
            }
            if (next != amount) {
                amount = next;
                var updated = getFlowing(amount, false);
                if (!level.setBlockAndUpdate(pos, updated.createLegacyBlock())) return;
                level.scheduleTick(pos, updated.getType(), getTickDelay(level));
            }
        }
        var toward = pos.relative(vertical);
        if (canDisplace(level, toward)) {
            flow(level, toward, FULL - 1);
            return;
        }
        if (amount <= 1 || (!state.isSource() && (amountAt(level, toward) > 0 || canRoute(level, toward)))) return;
        int next = amountAt(level, feed) > 0 ? FULL - 1 : amount - 1;
        int[] costs = new int[HORIZONTAL.length];
        Arrays.fill(costs, NO_OUTLET);
        int best = NO_OUTLET;
        for (int i = 0; i < HORIZONTAL.length; i++) {
            var dir = HORIZONTAL[i];
            var step = pos.relative(dir);
            if (!canRoute(level, step) || (amountAt(level, step) == FULL)) continue;
            costs[i] = outlet(level, step, vertical, dir, 0);
            best = Math.min(best, costs[i]);
        }
        for (int i = 0; i < HORIZONTAL.length; i++) {
            if (costs[i] != best) continue;
            // In Forge, blocked candidates tie at 1000. The final write still checks displacement,
            // which can replace vanilla liquid even though route planning won't go through it.
            flow(level, pos.relative(HORIZONTAL[i]), next);
        }
    }

    private int outlet(ServerLevel level, BlockPos pos, Direction vertical, Direction incoming, int steps) {
        if (canRoute(level, pos.relative(vertical))) return steps;
        if (steps >= SEARCH_DISTANCE) return NO_OUTLET;
        int best = NO_OUTLET;
        for (var dir : HORIZONTAL) {
            if (dir == incoming.getOpposite()) continue;
            var next = pos.relative(dir);
            if (canRoute(level, next) && amountAt(level, next) != FULL)
                best = Math.min(best, outlet(level, next, vertical, dir, steps + 1));
        }
        return best;
    }
    private boolean fedVertically(ServerLevel level, BlockPos feed) {
        return amountAt(level, feed) > 0 || strongestNeighbor(level, feed) > 0;
    }
    private int strongestNeighbor(ServerLevel level, BlockPos pos) {
        int best = 0;
        for (var dir : HORIZONTAL) best = Math.max(best, amountAt(level, pos.relative(dir)));
        return best;
    }
    private int amountAt(ServerLevel level, BlockPos pos) {
        if (!loaded(level, pos)) return 0;
        var state = level.getFluidState(pos);
        return isSame(state.getType()) ? state.getAmount() : 0;
    }
    private boolean canRoute(ServerLevel level, BlockPos pos) {
        if (!loaded(level, pos)) return false;
        var state = level.getBlockState(pos);
        if (isSame(state.getFluidState().getType())) return true;
        return !vanillaLiquid(state) && displaceable(state);
    }

    private static boolean vanillaLiquid(BlockState state) { return state.is(Blocks.WATER) || state.is(Blocks.LAVA); }

    private boolean displaceable(BlockState state) {
        if (state.isAir() || vanillaLiquid(state)) return true;
        var target = state.getFluidState().getType();
        if (target instanceof ChemicalFlow394 chemical) return !isSame(target) && density > chemical.density;
        // Never destroy a container/attachment or a waterlogged block. Other mod fluids
        // need an explicit compatibility rule instead of guessing their density scale.
        return state.getFluidState().isEmpty() && state.is(FRAGILE) && !state.hasBlockEntity();
    }

    private boolean canDisplace(ServerLevel level, BlockPos pos) { return loaded(level, pos) && displaceable(level.getBlockState(pos)); }

    private void flow(ServerLevel level, BlockPos pos, int amount) {
        if (!canDisplace(level, pos)) return;
        var previous = level.getBlockState(pos);
        if (!level.setBlockAndUpdate(pos, getFlowing(amount, false).createLegacyBlock()) || !previous.is(FRAGILE)) return;
        if (previous.is(Blocks.SNOW)) {
            // Legacy Forge drops the removed layer even without a shovel. NeoForge's vanilla loot table doesn't.
            if (level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS))
                Block.popResource(level, pos, new ItemStack(Items.SNOWBALL, previous.getValue(SnowLayerBlock.LAYERS)));
        } else {
            Block.dropResources(previous, level, pos, null);
        }
    }
    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.hasChunkAt(pos) && level.getWorldBorder().isWithinBounds(pos);
    }
}
