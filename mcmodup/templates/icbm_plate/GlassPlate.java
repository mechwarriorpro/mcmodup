package mcmodup_kernel.icbm_glass_plate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;

/** NeoForge boundary for the pressure plate from ICBM 1.4.1.344. */
public final class GlassPlate extends PressurePlateBlock {
    public GlassPlate() {
        super(BlockSetType.OAK, Properties.of().strength(0.3F, 0.6F).sound(SoundType.GLASS)
            .randomTicks().noCollission().noOcclusion().pushReaction(PushReaction.DESTROY).noLootTable());
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(POWERED)) update(level, pos, 15);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        tick(state, level, pos, random);
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (level instanceof ServerLevel server && !state.getValue(POWERED)) update(server, pos, 0);
    }

    private void update(ServerLevel level, BlockPos pos, int oldPower) {
        PlateLogic.update(new Access(this), new WorldAccess(this, level), pos.getX(), pos.getY(), pos.getZ(), oldPower);
    }

    public static final class Access {
        private final GlassPlate block;

        private Access(GlassPlate block) {
            this.block = block;
        }

        public static int registryId(Access access) {
            return BuiltInRegistries.BLOCK.getId(access.block);
        }

        public int sense(WorldAccess world, int x, int y, int z) {
            // The inherited 1.6.4 binary plate used the EVERYTHING sensitivity,
            // with a 1/8-block inset and 1/4-block detection height. Keep the
            // original float-coordinate calculation at large coordinates too.
            var area = new AABB(x + 0.125F, y, z + 0.125F, x + 1 - 0.125F, y + 0.25, z + 1 - 0.125F);
            return world.level.getEntities((Entity) null, area, entity -> !entity.isSpectator() &&
                !entity.isIgnoringBlockTriggers()).isEmpty() ? 0 : 15;
        }

        public int toMetadata(int signal) {
            return signal > 0 ? 1 : 0;
        }

        public void notifyNeighbors(WorldAccess world, int x, int y, int z) {
            var pos = new BlockPos(x, y, z);
            world.level.updateNeighborsAt(pos, block);
            world.level.updateNeighborsAt(pos.below(), block);
        }

        public int checkDelay(WorldAccess world) {
            return PlateLogic.delay(this, world);
        }
    }

    public static final class WorldAccess {
        private final GlassPlate block;
        private final ServerLevel level;

        private WorldAccess(GlassPlate block, ServerLevel level) {
            this.block = block;
            this.level = level;
        }

        public boolean setMetadata(int x, int y, int z, int meta, int flags) {
            var pos = new BlockPos(x, y, z);
            var current = level.getBlockState(pos);
            return current.is(block) && level.setBlock(pos, current.setValue(POWERED, meta == 1), flags);
        }

        public void markRenderRange(int x, int y, int z, int endX, int endY, int endZ) {
            // This ABI is for the single-block refresh in the pinned method.
            if (x != endX || y != endY || z != endZ) throw new IllegalArgumentException("Expected one plate position");
            var pos = new BlockPos(x, y, z);
            var state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }

        public void schedule(int x, int y, int z, int blockId, int delay) {
            if (blockId != BuiltInRegistries.BLOCK.getId(block)) throw new IllegalArgumentException("Plate registry ID changed");
            level.scheduleTick(new BlockPos(x, y, z), block, delay);
        }
    }
}
