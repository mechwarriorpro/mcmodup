/* Bedrock population from GTNH Thaumic Tinkerer 2.13.0 (CC BY-NC-SA 3.0). */
package thaumic.tinkerer.common.dim;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;

final class LegacyBedrockSeed {
    private LegacyBedrockSeed() {}

    static RandomSource population(long worldSeed, int chunkX, int chunkZ) {
        // ChunkProviderBedrock.populate used java.util.Random, not the ore feature's index/step seed.
        var random = new LegacyRandomSource(worldSeed);
        long xMultiplier = random.nextLong() / 2L * 2L + 1L;
        long zMultiplier = random.nextLong() / 2L * 2L + 1L;
        random.setSeed((chunkX * xMultiplier + chunkZ * zMultiplier) ^ worldSeed);
        // The now-unused lake coordinates were still drawn before the first cluster.
        random.nextInt(16);
        random.nextInt(128);
        random.nextInt(16);
        return random;
    }
}
