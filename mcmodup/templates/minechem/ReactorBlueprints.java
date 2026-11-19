package @PACKAGE@;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Cells in the original blueprint's Y, X, Z order. The manager replaces one cell. */
final class ReactorBlueprints {
    static final Plan FUSION = new Plan(@FUSION_SIZE@, @FUSION_CORE@, "@FUSION_GRID@");
    static final Plan FISSION = new Plan(@FISSION_SIZE@, @FISSION_CORE@, "@FISSION_GRID@");

    record Plan(int width, int height, int depth, int cx, int cy, int cz, String grid) {
        boolean manager(int x, int y, int z) { return x == cx && y == cy && z == cz; }
        char cell(int x, int y, int z) { return grid.charAt((y * width + x) * depth + z); }

        BlockPos position(BlockPos projector, Direction facing, int x, int y, int z) {
            return projector.relative(facing, depth + 1 - x).relative(facing.getClockWise(), z - depth / 2).above(y);
        }

        char at(BlockPos core, Direction face, BlockPos target) {
            var offset = target.subtract(core);
            int x = cx - offset.getX() * face.getStepX() - offset.getZ() * face.getStepZ();
            var right = face.getClockWise();
            int z = cz + offset.getX() * right.getStepX() + offset.getZ() * right.getStepZ();
            int y = cy + offset.getY();
            return x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth || manager(x, y, z) ? '.' : cell(x, y, z);
        }

        BlockPos around(BlockPos core, Direction facing, int x, int y, int z) {
            return core.relative(facing, cx - x).relative(facing.getClockWise(), z - cz).above(y - cy);
        }
    }

    private ReactorBlueprints() {}
}
