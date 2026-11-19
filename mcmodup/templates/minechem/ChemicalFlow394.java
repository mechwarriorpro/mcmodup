package @PACKAGE@;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;

/** The measured .394 quanta and direction rules, with bounded flow that never displaces other blocks. */
final class ChemicalFlow394 extends BaseFlowingFluid {
    private static final Direction[] HORIZONTAL = {Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH};
    private static final int FULL = 8, NO_OUTLET = 1000, SEARCH_DISTANCE = 4;
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
        if (open(level, toward)) {
            flow(level, toward, FULL - 1);
            return;
        }
        if (amount <= 1 || (!state.isSource() && amountAt(level, toward) > 0)) return;
        int next = amountAt(level, feed) > 0 ? FULL - 1 : amount - 1;
        int[] costs = new int[HORIZONTAL.length];
        Arrays.fill(costs, NO_OUTLET);
        int best = NO_OUTLET;
        for (int i = 0; i < HORIZONTAL.length; i++) {
            var dir = HORIZONTAL[i];
            var step = pos.relative(dir);
            if (!canRoute(level, step)) continue;
            costs[i] = outlet(level, step, vertical, dir, 0);
            best = Math.min(best, costs[i]);
        }
        for (int i = 0; i < HORIZONTAL.length; i++) {
            if (costs[i] != best) continue;
            var step = pos.relative(HORIZONTAL[i]);
            if (open(level, step)) flow(level, step, next);
        }
    }

    private int outlet(ServerLevel level, BlockPos pos, Direction vertical, Direction incoming, int steps) {
        if (open(level, pos.relative(vertical))) return steps;
        if (steps >= SEARCH_DISTANCE) return NO_OUTLET;
        int best = NO_OUTLET;
        for (var dir : HORIZONTAL) {
            if (dir == incoming.getOpposite()) continue;
            var next = pos.relative(dir);
            if (canRoute(level, next)) best = Math.min(best, outlet(level, next, vertical, dir, steps + 1));
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
        return state.isAir() || (isSame(state.getFluidState().getType()) && !state.getFluidState().isSource());
    }
    private void flow(ServerLevel level, BlockPos pos, int amount) {
        level.setBlockAndUpdate(pos, getFlowing(amount, false).createLegacyBlock());
    }
    private static boolean open(ServerLevel level, BlockPos pos) { return loaded(level, pos) && level.isEmptyBlock(pos); }
    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.hasChunkAt(pos) && level.getWorldBorder().isWithinBounds(pos);
    }
}
