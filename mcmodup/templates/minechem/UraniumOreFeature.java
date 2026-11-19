package @PACKAGE@;

import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.registries.DeferredRegister;

final class UraniumOreFeature extends Feature<NoneFeatureConfiguration> {
    private static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, CatalogPort.MOD_ID);
    private static final boolean RELEASE_394 = @RELEASE_394@;
    private static final ModConfigSpec CONFIG;
    private static final ModConfigSpec.BooleanValue GENERATE_ORE;
    private static final ModConfigSpec.IntValue DENSITY, CLUSTER_SIZE;

    static {
        var builder = new ModConfigSpec.Builder();
        GENERATE_ORE = builder.comment("Generate this release's uranium ore in new Overworld chunks.").define("generateOre", @ENABLED@);
        DENSITY = builder.comment("Attempts per chunk minus one (-1 makes no attempts).").defineInRange("uraniumOreDensity", @DENSITY@, -1, 64);
        // Keep the largest roll (41) within the adjacent chunks available at the features step.
        CLUSTER_SIZE = builder.comment("Each attempt rolls its vein size from 0 through this value plus 9.")
            .defineInRange("uraniumOreClusterSize", @CLUSTER@, -9, 32);
        CONFIG = builder.build();
    }

    static void register(IEventBus bus, ModContainer mod) {
        FEATURES.register("uranium_veins", UraniumOreFeature::new);
        FEATURES.register(bus);
@CRAFT_REGISTER@
        mod.registerConfig(ModConfig.Type.SERVER, CONFIG, CatalogPort.MOD_ID + "-worldgen.toml");
    }

    private UraniumOreFeature() { super(NoneFeatureConfiguration.CODEC); }

    @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        var dimension = level.getLevel().dimensionType();
        if (!GENERATE_ORE.get() || !dimension.natural() || !dimension.hasSkyLight() || dimension.hasCeiling()) return false;
        int density = DENSITY.get(), sizeBound = CLUSTER_SIZE.get() + 10;
        int chunkX = context.origin().getX() >> 4, chunkZ = context.origin().getZ() >> 4;
        long seed = level.getSeed();
        // The two originals never shared a world. If installed together they would otherwise
        // roll the same veins; retain FINAL's seed and give .394 its own deterministic stream.
        if (RELEASE_394 && ModList.get().isLoaded("minechem_chemistry")) seed ^= 0x394L;
        var random = new Random(seed);
        long xSeed = random.nextLong() >> 3, zSeed = random.nextLong() >> 3;
        random.setSeed((xSeed * chunkX + zSeed * chunkZ) ^ seed);

        var ore = ShieldedStorage.ORE.get().defaultBlockState();
        var cursor = new BlockPos.MutableBlockPos();
        boolean placed = false;
        for (int i = 0; i <= density; i++) {
            int x = (chunkX << 4) + random.nextInt(16);
            int y = random.nextInt(50);
            int z = (chunkZ << 4) + random.nextInt(16);
            int size = random.nextInt(sizeBound);
            placed |= vein(level, ore, cursor, random, x, y, z, size);
        }
        return placed;
    }

    private static boolean vein(WorldGenLevel level, BlockState ore, BlockPos.MutableBlockPos pos, Random random,
                                int x, int y, int z, int size) {
        float angle = random.nextFloat() * Mth.PI;
        // Vanilla's old endpoints are rounded in float (including the absolute position).
        float dx = Mth.sin(angle) * size / 8.0F, dz = Mth.cos(angle) * size / 8.0F;
        double x0 = x + 8 + dx, x1 = x + 8 - dx, z0 = z + 8 + dz, z1 = z + 8 - dz;
        double y0 = y + random.nextInt(3) - 2, y1 = y + random.nextInt(3) - 2;
        boolean placed = false;
        for (int step = 0; step <= size; step++) {
            double thickness = random.nextDouble() * size / 16.0D;
            if (size == 0) break; // No blocks, but the old inclusive loop still consumes this draw.
            double bx = x0 + (x1 - x0) * step / size;
            double by = y0 + (y1 - y0) * step / size;
            double bz = z0 + (z1 - z0) * step / size;
            double radius = ((Mth.sin(step * Mth.PI / size) + 1.0F) * thickness + 1.0D) / 2.0D;
            int maxX = Mth.floor(bx + radius), maxY = Mth.floor(by + radius), maxZ = Mth.floor(bz + radius);
            for (int px = Mth.floor(bx - radius); px <= maxX; px++) {
                double nx = (px + 0.5D - bx) / radius;
                double xx = nx * nx;
                if (xx >= 1.0D) continue;
                // Retain the old Y=0 floor even when 1.21.1 puts stone below it.
                for (int py = Math.max(0, Mth.floor(by - radius)); py <= maxY; py++) {
                    double ny = (py + 0.5D - by) / radius;
                    double xy = xx + ny * ny;
                    if (xy >= 1.0D) continue;
                    for (int pz = Mth.floor(bz - radius); pz <= maxZ; pz++) {
                        double nz = (pz + 0.5D - bz) / radius;
                        if (xy + nz * nz >= 1.0D) continue;
                        pos.set(px, py, pz);
                        if (!level.isOutsideBuildHeight(pos) && level.ensureCanWrite(pos) && level.getBlockState(pos).is(Blocks.STONE))
                            placed |= level.setBlock(pos, ore, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        return placed;
    }
}
