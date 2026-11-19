package mcmodup.geostratarocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** The connected variant uses the v23b face mask rules in a native baked model. */
public final class ConnectedRock extends RockBlock {
    public static final int ALL_EDGES = 0x3fe; // Overlay 0 is inventory-only; 1–9 are world masks.

    ConnectedRock(String family, Properties properties) { super(family, properties); }

    @Override public boolean skipRendering(BlockState state, BlockState neighbor, Direction direction) {
        return neighbor.is(this) || super.skipRendering(state, neighbor, direction);
    }

    /** Return the overlay sprites for one plane. Opposite faces have the same world-space neighbor mapping. */
    public static int edges(BlockGetter world, BlockPos center, BlockState state, Direction.Axis axis) {
        if (!(state.getBlock() instanceof ConnectedRock rock)) return 0;
        Direction two = axis == Direction.Axis.Z ? Direction.EAST : Direction.SOUTH;
        Direction four = axis == Direction.Axis.Y ? Direction.EAST : Direction.UP;
        var p2 = center.relative(two);
        var p8 = center.relative(two.getOpposite());
        boolean b2 = rock.sameFamily(world, p2), b8 = rock.sameFamily(world, p8);
        boolean b4 = rock.sameFamily(world, center.relative(four)), b6 = rock.sameFamily(world, center.relative(four.getOpposite()));
        int result = ALL_EDGES;
        if (b2) result &= ~(1 << 2);
        if (b4) result &= ~(1 << 4);
        if (b6) result &= ~(1 << 6);
        if (b8) result &= ~(1 << 8);
        if (b2 && b4 && rock.sameFamily(world, p2.relative(four))) result &= ~(1 << 1);
        if (b2 && b6 && rock.sameFamily(world, p2.relative(four.getOpposite()))) result &= ~(1 << 3);
        if (b8 && b4 && rock.sameFamily(world, p8.relative(four))) result &= ~(1 << 7);
        if (b8 && b6 && rock.sameFamily(world, p8.relative(four.getOpposite()))) result &= ~(1 << 9);
        return result;
    }
}

/** Keep the rock identity separate from each registered block ID. */
class RockBlock extends Block {
    private final String family;

    RockBlock(String family, Properties properties) {
        super(properties);
        this.family = family;
    }

    final boolean sameFamily(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof RockBlock rock && family.equals(rock.family);
    }
}
