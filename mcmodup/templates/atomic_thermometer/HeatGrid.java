package mcmodup_kernel.atomic_thermometer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Bounded, loaded-chunk adaptation of the single-source Voltz heat spread. */
public final class HeatGrid {
    private static final Map<ServerLevel, HeatGrid> LEVELS = new WeakHashMap<>();
    private static final float SOLID_LOSS = 0.1F, AIR_LOSS = 0.15F, SPREAD = 1F / 7F, STEP = 0.05F;
    private static final double MIN_EXCESS = 0.4D;
    private static final int MAX_CELLS = 8192;
    private final Map<BlockPos, Float> cells = new LinkedHashMap<>();

    static void register() {
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                var grid = LEVELS.get(level);
                if (grid != null) grid.step(level);
            }
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel level) LEVELS.remove(level);
        });
    }

    /** Adds a fictional in-game temperature delta. Call from a server-level tick or interaction. */
    public static boolean addHeat(ServerLevel level, BlockPos pos, float delta) {
        if (!level.getServer().isSameThread() || !usable(level, pos) || !Float.isFinite(delta) || delta <= 0F) return false;
        return LEVELS.computeIfAbsent(level, key -> new HeatGrid()).add(level, pos, delta);
    }

    public static float sample(ServerLevel level, BlockPos pos) {
        if (!level.getServer().isSameThread() || !usable(level, pos)) return Float.NaN;
        var grid = LEVELS.get(level);
        return grid == null ? ambient(level, pos) : grid.temperature(level, pos);
    }

    static int ambient(Level level, BlockPos pos) {
        var biome = level.getBiome(pos).value();
        int temperature = 273 + (int) ((biome.getBaseTemperature() - 0.4D) * 50.0D);
        return (int) (temperature + temperature * (level.isDay() ? 0.05D : -0.05D));
    }

    private static boolean usable(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.hasChunkAt(pos);
    }

    float temperature(ServerLevel level, BlockPos pos) {
        Float known = cells.get(pos);
        return known == null ? ambient(level, pos) : known;
    }

    boolean add(ServerLevel level, BlockPos pos, float delta) {
        float next = temperature(level, pos) + delta;
        if (!Float.isFinite(next)) return false;
        // Ignore cells too near ambient and give already active sources priority.
        if (Math.abs(next - ambient(level, pos)) <= MIN_EXCESS) {
            cells.remove(pos);
            return true;
        }
        if (cells.size() >= MAX_CELLS && !cells.containsKey(pos)) return false;
        cells.put(pos.immutable(), next);
        return true;
    }

    void step(ServerLevel level) {
        // Snapshot sources: newly heated neighbors don't spread until the next tick.
        for (var pos : List.copyOf(cells.keySet())) {
            if (!usable(level, pos)) {
                cells.remove(pos);
                continue;
            }
            float delta = ambient(level, pos) - temperature(level, pos);
            float loss = level.getBlockState(pos).isAir() ? AIR_LOSS : SOLID_LOSS;
            add(level, pos, Math.copySign(Math.min(Math.abs(delta), loss), delta));
            if (!cells.containsKey(pos)) continue;
            for (var side : Direction.values()) {
                var neighbor = pos.relative(side);
                if (!usable(level, neighbor)) continue;
                float difference = temperature(level, pos) - temperature(level, neighbor);
                if (difference <= 0F) continue;
                float conductance = level.getBlockState(neighbor).blocksMotion() ? SPREAD : SPREAD / 2F;
                float transfer = difference * (conductance * STEP);
                if (add(level, neighbor, transfer)) add(level, pos, -transfer);
            }
        }
    }

}
