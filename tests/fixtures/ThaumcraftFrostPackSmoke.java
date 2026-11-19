package mcmodup.thaumcraftcheck;

import java.util.Map;
import java.util.TreeMap;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import thaumcraft.common.config.ThaumcraftFoci;
import thaumcraft.common.testing.FrostFocusRuntimeChecks;
import thaumcraft.common.testing.FrostShardRuntimeChecks;

@GameTestHolder(ThaumcraftPackSmoke.ID)
@PrefixGameTestTemplate(false)
public final class ThaumcraftFrostPackSmoke {
    @GameTest(template = "frost_room", timeoutTicks = 36000, batch = "staged_frost_focus")
    public static void stagedFocusMatchesOriginalConstantsAndCasts(GameTestHelper test) {
        // Original values measured by LegacyThaumcraftParity on Forge 1.7.10.
        var focus = ThaumcraftFoci.FROST_FOCUS.get();
        var bare = new ItemStack(focus);
        var cost = focus.getVisCost(bare);
        Map<String, Integer> vis = new TreeMap<>();
        for (var aspect : cost.getAspects()) vis.put(aspect.getTag(), cost.getAmount(aspect));
        test.assertTrue(vis.equals(Map.of("aqua", 5, "ignis", 2, "perditio", 2)), "Original base Frost centivis mismatch: " + vis);
        test.assertTrue(focus.getFocusColor(bare) == 5204428, "Original Frost color mismatch");
        test.assertTrue(focus.getActivationCooldown(bare) == 200, "Original Frost cooldown mismatch");
        FrostFocusRuntimeChecks.run(test, test.makeMockServerPlayerInLevel());
    }

    @GameTest(template = "frost_room", timeoutTicks = 600)
    public static void stagedShardRicochetsAndPersists(GameTestHelper test) {
        FrostShardRuntimeChecks.run(test);
    }
}
