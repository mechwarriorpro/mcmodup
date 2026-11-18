package mcmodup.thaumcraftcheck;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@Mod(ThaumcraftPackSmoke.ID)
@GameTestHolder(ThaumcraftPackSmoke.ID)
@PrefixGameTestTemplate(false)
public final class ThaumcraftPackSmoke {
    public static final String ID = "mcmodup_thaumcraft_check";

    @GameTest(template = "empty")
    public static void standalonePortLoadsFromItsStagedJar(GameTestHelper helper) {
        helper.assertTrue(ModList.get().isLoaded("thaumcraft"), "Thaumcraft native port was not loaded");
        helper.assertTrue(ModList.get().isLoaded("architectury"), "Staged Architectury library was not loaded");
        helper.assertTrue(!ModList.get().isLoaded("legacyconnector"), "Found the Connector in the native pack");
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse("thaumcraft:thaumometer")),
            "The staged release did not register a thaumometer");
        var crucible = ResourceLocation.parse("thaumcraft:crucible");
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(crucible), "The staged release did not register a crucible");
        helper.setBlock(0, 0, 0, BuiltInRegistries.BLOCK.get(crucible));
        helper.assertTrue(helper.getBlockEntity(new net.minecraft.core.BlockPos(0, 0, 0)) != null,
            "A placed crucible did not create a block entity");
        helper.succeed();
    }
}
