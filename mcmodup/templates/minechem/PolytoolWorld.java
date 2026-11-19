package @PACKAGE@;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

import static @PACKAGE@.PolytoolRules.*;

/** Polytool world interactions on the logical server only. */
final class PolytoolWorld {
    private static final int MAX_FIRE_RADIUS = 8; // Bound work on a single synchronous harvest.
    private static final String OTHER_RELEASE = RELEASE_394 ? "minechem_chemistry" : "minechem_chemistry_394";

    static void hit(ServerLevel level, Entity target, int atomic, int rank) {
        switch (atomic) {
            case HYDROGEN -> { if (target instanceof Mob && target.isOnFire()) blast(level, target, target.position(), rank); }
            case RUBIDIUM -> { if (target.isInWater()) blast(level, target, target.position(), rank); }
            case FRANCIUM -> { if (level.isRaining()) blast(level, RELEASE_394 ? null : target, target.position(), rank); }
            default -> {}
        }
    }

    static void mine(ServerPlayer player, BlockState mined, BlockPos pos, int atomic, int rank) {
        var level = player.serverLevel();
        if (level.captureBlockSnapshots || level.restoringBlockSnapshots) return;
        switch (atomic) {
            case PHOSPHORUS -> fire(level, player, mined, pos, Math.min(rank, MAX_FIRE_RADIUS));
            case RUBIDIUM -> { if (player.isInWater()) blast(level, player, player.position(), rank); }
            case CAESIUM -> { if (mined.is(Blocks.STONE)) blast(level, player, player.position(), rank); }
            case FRANCIUM -> {
                if (level.isRaining()) {
                    // The shipped .394 callback hard-coded Z=0.5 for its mining blast.
                    var center = RELEASE_394 ? new Vec3(pos.getX() + .5, pos.getY() + .5, .5) : player.position();
                    blast(level, RELEASE_394 ? null : player, center, rank);
                }
            }
            default -> {}
        }
    }

    private static void blast(ServerLevel level, Entity source, Vec3 center, int rank) {
        var pos = BlockPos.containing(center);
        if (available(level, pos)) level.explode(source, center.x, center.y, center.z, rank, Level.ExplosionInteraction.BLOCK);
    }

    private static boolean available(ServerLevel level, BlockPos pos) {
        return level.isInWorldBounds(pos) && level.hasChunkAt(pos) && level.getWorldBorder().isWithinBounds(pos);
    }

    private static void fire(ServerLevel level, ServerPlayer player, BlockState mined, BlockPos pos, int radius) {
        if (!mined.isFlammable(level, pos, Direction.UP)) return;
        for (var candidate : BlockPos.betweenClosed(pos.offset(-radius, -radius, -radius), pos.offset(radius - 1, radius - 1, radius - 1))) {
            if (!available(level, candidate) || !level.isEmptyBlock(candidate) || !player.mayInteract(level, candidate)) continue;
            var state = BaseFireBlock.getState(level, candidate);
            if (!state.canSurvive(level, candidate)) continue;
            var previous = BlockSnapshot.create(level.dimension(), level, candidate);
            if (level.setBlockAndUpdate(candidate, state) && EventHooks.onBlockPlace(player, previous, Direction.UP)) previous.restore();
        }
    }

    static void gold(ServerLevel level, Entity carrier, int rank) {
        if (level.random.nextInt(35000) >= rank) return;
        var bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) return;
        bolt.setPos(carrier.getX(), carrier.getY(), carrier.getZ());
        if (!level.addFreshEntity(bolt) || !(carrier instanceof Player player)) return;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.isEmpty() || BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals(OTHER_RELEASE)) continue;
            var storage = stack.getCapability(Capabilities.EnergyStorage.ITEM);
            if (storage != null && storage.canReceive()) storage.receiveEnergy(5_000_000, false);
        }
    }
}
