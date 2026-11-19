package @PACKAGE@;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("@TEST_NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class MinechemTerrainSmoke {
    @GameTest(template = "test_room", timeoutTicks = 600)
    public static void uraniumGrowsInFreshChunks(GameTestHelper test) {
        var key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("@TEST_NAMESPACE@:uranium_stone"));
        var level = test.getLevel().getServer().getLevel(key);
        test.assertTrue(level != null, "Missing test-only stone dimension");
        var pos = new BlockPos.MutableBlockPos();
        int firstX = -30, firstZ = 41, size = 4;
        // Load a halo first so veins crossing chunk boundaries have run before reading the interior.
        for (int x = firstX - 1; x <= firstX + size; x++) for (int z = firstZ - 1; z <= firstZ + size; z++) level.getChunk(x, z);
        for (var namespace : new String[]{"minechem_chemistry", "minechem_chemistry_394"}) {
            if (!ModList.get().isLoaded(namespace)) continue;
            var ore = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(namespace, "uranium_ore"));
            test.assertTrue(ore != Blocks.AIR, "Missing ore block from " + namespace);
            int count = 0;
            for (int x = firstX; x < firstX + size; x++) for (int z = firstZ; z < firstZ + size; z++) {
                var chunk = level.getChunk(x, z);
                for (int y = -5; y < 56; y++) for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
                    if (!chunk.getBlockState(pos.set(x * 16 + dx, y, z * 16 + dz)).is(ore)) continue;
                    test.assertTrue(y >= 0, namespace + " generated uranium below the original Y=0 floor");
                    count++;
                }
            }
            test.assertTrue(count > 0, namespace + " did not grow uranium in the freshly generated 1.21.1 stone chunks");
        }
        test.succeed();
    }
}
