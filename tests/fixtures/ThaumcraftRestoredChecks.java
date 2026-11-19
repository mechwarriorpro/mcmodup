package mcmodup.thaumcraftcheck;

import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import thaumcraft.common.config.ThaumcraftContent;
import thaumcraft.common.lib.research.ObjectAspectResolver;
import thaumcraft.common.tiles.TileNode;

@GameTestHolder(ThaumcraftPackSmoke.ID)
@PrefixGameTestTemplate(false)
public final class ThaumcraftRestoredChecks {
    @GameTest(template = "empty")
    public static void enchantedGoldenAppleUsesOriginalAspects(GameTestHelper test) {
        // Measured by LegacyThaumcraftParity with the real 1.7.10 recipe and getObjectTags.
        var tags = ObjectAspectResolver.baseTags(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE), test.getLevel());
        Map<String, Integer> counts = new TreeMap<>();
        for (var tag : tags.getAspects()) counts.put(tag.getTag(), tags.getAmount(tag));
        test.assertTrue(counts.equals(Map.of("lucrum", 64, "messis", 1, "metallum", 64, "praecantatio", 4, "sano", 8)),
                "Enchanted golden apple aspect mismatch: " + counts);
        test.succeed();
    }

    @GameTest(template = "empty")
    public static void nodeIdentityMatchesOriginalCoordinateAndLoadRules(GameTestHelper test) {
        var level = test.getLevel();
        var block = ThaumcraftContent.AURA_NODE.get();
        var first = test.absolutePos(new BlockPos(0, 1, 0));
        var second = first.offset(2, 0, -1);
        level.setBlockAndUpdate(first, block.defaultBlockState());
        level.setBlockAndUpdate(second, block.defaultBlockState());
        test.assertTrue(level.getBlockEntity(first) instanceof TileNode && level.getBlockEntity(second) instanceof TileNode,
                "Expected two real nodes from the registered block");
        var source = (TileNode) level.getBlockEntity(first);
        var target = (TileNode) level.getBlockEntity(second);
        String identity = overworldId(first);
        test.assertTrue(identity.equals(source.getId()) && identity.equals(source.getScanId()), "A fresh overworld node must derive its scan and public id from its position");
        test.assertTrue(overworldId(second).equals(target.getId()), "A fresh node at a different position must derive a different id");
        var tag = source.saveWithId(level.registryAccess());
        test.assertTrue(identity.equals(tag.getString("scanId")), "Node did not save its generated id");
        target.loadWithComponents(tag, level.registryAccess());
        test.assertTrue(target.getBlockPos().equals(second) && identity.equals(target.getId()), "A persisted node id must survive an NBT load at a different position");
        // Removing the old node and placing a new one at the same coordinates has the
        // original coordinate id, rather than a fresh UUID on each placement.
        level.removeBlock(first, false);
        level.setBlockAndUpdate(first, block.defaultBlockState());
        test.assertTrue(level.getBlockEntity(first) instanceof TileNode replacement && replacement != source
                && identity.equals(replacement.getScanId()), "Replacement at the same position did not get the same original id");
        test.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void originalNodeIdentitySurvivesCrossDimensionNbtLoad(GameTestHelper test) {
        var overworld = test.getLevel();
        var nether = overworld.getServer().getLevel(Level.NETHER);
        test.assertTrue(nether != null && nether != overworld, "Expected both dimensions in the staged test world");
        var block = ThaumcraftContent.AURA_NODE.get();
        var from = test.absolutePos(new BlockPos(0, 1, 0));
        var into = new BlockPos(8, 85, 8);
        test.assertTrue(nether.getBlockEntity(into) == null, "Expected an empty block-entity slot in the fresh Nether test world");
        var previous = nether.getBlockState(into);
        try {
            overworld.setBlockAndUpdate(from, block.defaultBlockState());
            var source = (TileNode) overworld.getBlockEntity(from);
            String identity = overworldId(from);
            test.assertTrue(identity.equals(source.getId()), "Wrong identity of node in the original dimension");
            var saved = source.saveWithId(overworld.registryAccess());
            overworld.removeBlock(from, false);
            nether.setBlockAndUpdate(into, block.defaultBlockState());
            var target = (TileNode) nether.getBlockEntity(into);
            test.assertTrue(target != null && ("-1:" + coordinates(into)).equals(target.getId()), "Fresh Nether node did not use the original dimension prefix");
            target.loadWithComponents(saved, nether.registryAccess());
            test.assertTrue(target.getLevel() == nether && target.getBlockPos().equals(into)
                    && identity.equals(target.getId()), "Node did not preserve the saved overworld identity in the Nether");
        } finally {
            nether.setBlockAndUpdate(into, previous);
        }
        test.succeed();
    }

    private static String coordinates(BlockPos pos) { return pos.getX() + ":" + pos.getY() + ":" + pos.getZ(); }
    private static String overworldId(BlockPos pos) { return "0:" + coordinates(pos); }
}
