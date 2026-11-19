package @PACKAGE@;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

import static @PACKAGE@.PolytoolRules.*;

/** Break-time modules. Extra ore still goes through the player's protected break path. */
final class PolytoolMining {
    // The original wool metadata order. Chromium never rolls black (15).
    private static final List<Block> WOOL = List.of(Blocks.WHITE_WOOL, Blocks.ORANGE_WOOL, Blocks.MAGENTA_WOOL, Blocks.LIGHT_BLUE_WOOL,
        Blocks.YELLOW_WOOL, Blocks.LIME_WOOL, Blocks.PINK_WOOL, Blocks.GRAY_WOOL, Blocks.LIGHT_GRAY_WOOL, Blocks.CYAN_WOOL,
        Blocks.PURPLE_WOOL, Blocks.BLUE_WOOL, Blocks.BROWN_WOOL, Blocks.GREEN_WOOL, Blocks.RED_WOOL, Blocks.BLACK_WOOL);
    private final Map<ServerPlayer, BlockPos> extraBreaks = new HashMap<>();

    void mine(ServerPlayer player, ItemStack tool, BlockState block, BlockPos pos) {
        var level = player.serverLevel();
        if (extraBreaks.containsKey(player) || player.isCreative() || player.isSpectator() || !player.mayInteract(level, pos)) return;
        var upgrades = state(tool);
        for (var upgrade : upgrades.ranks()) {
            if (player.getMainHandItem() != tool) break;
            int rank = upgrade.rank();
            switch (upgrade.element()) {
                case OXYGEN -> {
                    if (player.getAirSupply() < player.getMaxAirSupply())
                        player.setAirSupply(Math.min(player.getMaxAirSupply(), player.getAirSupply() + rank));
                }
                case FLUORINE -> {
                    // A legacy carrot was four food points with a 0.6 saturation modifier.
                    if (level.random.nextInt(10) < rank) player.getFoodData().eat(4, 0.6F);
                }
                case SODIUM -> light(player, level, rank);
                case IRON -> { if (block.is(Tags.Blocks.ORES)) mineAdjacent(player, tool, block, pos, rank); }
                case NICKEL -> attract(level, player.position(), rank);
                case MERCURY -> warn(player, level, pos, rank);
                default -> {} // Loot effects are applied after a successful harvest.
            }
        }
    }

    private void mineAdjacent(ServerPlayer player, ItemStack tool, BlockState block, BlockPos origin, int budget) {
        var level = player.serverLevel();
        var open = new ArrayDeque<BlockPos>();
        var visited = new HashSet<BlockPos>();
        open.add(origin);
        visited.add(origin);
        while (!open.isEmpty() && budget > 0 && player.getMainHandItem() == tool) {
            var pos = open.removeFirst();
            for (var direction : Direction.values()) {
                var next = pos.relative(direction);
                if (!visited.add(next) || !level.isInWorldBounds(next) || !level.hasChunkAt(next) || !player.mayInteract(level, next)) continue;
                var other = level.getBlockState(next);
                if (!other.is(block.getBlock()) || (RELEASE_394 && other != block) ||
                    other.getDestroySpeed(level, next) < 0 || !tool.isCorrectToolForDrops(other)) continue;
                extraBreaks.put(player, next);
                try {
                    // Refused breaks do not spend rank budget or open a path to more ore.
                    if (player.gameMode.destroyBlock(next) && level.getBlockState(next) != other) {
                        open.add(next);
                        budget--;
                    }
                } finally {
                    extraBreaks.remove(player);
                }
                if (budget == 0 || player.getMainHandItem() != tool) break;
            }
        }
    }

    void drops(BlockDropsEvent event) {
        var level = event.getLevel();
        if (!(event.getBreaker() instanceof ServerPlayer player) || player.serverLevel() != level || player.isCreative() ||
            !level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS) || level.restoringBlockSnapshots) return;
        var block = event.getState().getBlock();
        var tool = event.getTool();
        var upgrades = state(tool);
        int carbon = upgrades.rank(CARBON);
        boolean carbonOre = block == Blocks.COAL_ORE || block == Blocks.DIAMOND_ORE;
        if (extraBreaks.containsKey(player)) {
            if (!event.getPos().equals(extraBreaks.get(player))) return;
            if (RELEASE_394 && carbonOre) {
                // The .394 Iron callback removed these neighbors but skipped harvesting with no Carbon.
                // With Carbon installed it repeated the tool's normal harvest, including Silk Touch.
                if (carbon == 0) event.getDrops().clear();
                else for (int i = 0, repeats = bonus(level.random, carbon); i < repeats; i++)
                    repeatLoot(event, player, tool);
            } else if (!RELEASE_394 && tool.isEnchanted()) {
                // FINAL's neighbors used the unenchanted destruction path.
                event.getDrops().clear();
                repeatLoot(event, null, ItemStack.EMPTY);
            }
            return; // No recursive Carbon, Bromine, Chromium or utility hooks on bonus ore.
        }
        if (carbonOre && carbon > 0) {
            addDrop(event, new ItemStack(block == Blocks.COAL_ORE ? Items.COAL : Items.DIAMOND, bonus(level.random, carbon)));
        } else if (block == Blocks.GOLD_ORE && upgrades.rank(BROMINE) > 0) {
            event.getDrops().removeIf(drop -> drop.getItem().is(Items.RAW_GOLD) || drop.getItem().is(Items.GOLD_ORE));
            addDrop(event, new ItemStack(Items.GOLD_INGOT, 2 + bonus(level.random, upgrades.rank(BROMINE))));
        } else if (WOOL.contains(block) && upgrades.rank(CHROMIUM) > 0 && level.random.nextInt(10) < upgrades.rank(CHROMIUM)) {
            event.getDrops().removeIf(drop -> drop.getItem().is(block.asItem()));
            addDrop(event, new ItemStack(WOOL.get(level.random.nextInt(15))));
        }
    }

    private static int bonus(RandomSource random, int rank) {
        return (int) (random.nextDouble() * Math.log(rank)); // Rank zero is handled by the caller.
    }

    private static void repeatLoot(BlockDropsEvent event, ServerPlayer player, ItemStack tool) {
        for (var stack : Block.getDrops(event.getState(), event.getLevel(), event.getPos(), event.getBlockEntity(), player, tool))
            addDrop(event, stack);
    }

    private static void addDrop(BlockDropsEvent event, ItemStack stack) {
        if (stack.isEmpty()) return;
        var at = event.getPos();
        var drop = new ItemEntity(event.getLevel(), at.getX() + .5, at.getY() + .5, at.getZ() + .5, stack);
        drop.setDefaultPickUpDelay();
        event.getDrops().add(drop); // Let the native event finish before spawning anything.
    }

    private static void light(ServerPlayer player, ServerLevel level, int rank) {
        var feet = player.blockPosition();
        if (level.random.nextInt(35) >= rank || !level.isInWorldBounds(feet) || !level.hasChunkAt(feet) ||
            !player.mayInteract(level, feet) || !level.isEmptyBlock(feet) || level.captureBlockSnapshots || level.restoringBlockSnapshots ||
            (RELEASE_394 && level.getMaxLocalRawBrightness(feet) >= 12)) return;
        // Use a virtual torch, but the real placement checks, support, and cancellable event.
        var torch = new ItemStack(Items.TORCH);
        torch.useOn(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, torch,
            new BlockHitResult(Vec3.atCenterOf(feet), Direction.UP, feet, false)));
    }

    private static void attract(ServerLevel level, Vec3 center, int range) {
        for (var drop : level.getEntitiesOfClass(ItemEntity.class, new AABB(center, center).inflate(range))) {
            drop.setDeltaMovement(center.subtract(drop.position()));
            drop.hasImpulse = true;
        }
    }

    private static void warn(ServerPlayer player, ServerLevel level, BlockPos pos, int rank) {
        for (int depth = 0; depth <= 4 * rank && pos.getY() - depth >= level.getMinBuildHeight(); depth++) {
            if (level.getFluidState(pos.below(depth)).is(FluidTags.LAVA)) {
                player.sendSystemMessage(Component.translatable(TEXT + "lava_warning").withStyle(ChatFormatting.RED));
                return;
            }
        }
    }
}
