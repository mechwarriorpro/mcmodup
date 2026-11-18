package mcmodup.packcheck.addon;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@Mod(NativeUpdateSmoke.ID)
@GameTestHolder(NativeUpdateSmoke.ID)
@PrefixGameTestTemplate(false)
public final class NativeUpdateSmoke {
    public static final String ID = "mcmodup_update_addon";

    @GameTest(template = "empty")
    public static void nativeAddonUsesUpdatedBase(GameTestHelper helper) {
        helper.assertTrue(ModList.get().isLoaded("retro_ruby"), "Native base was not loaded");
        helper.assertTrue(ModList.get().isLoaded(ID), "Native add-on was not loaded");
        helper.assertTrue(ModList.get().isLoaded("mcmodup_update_lib"), "Automatic dependency was not loaded");
        helper.assertTrue(!ModList.get().isLoaded("legacyconnector"), "The native stack unexpectedly loaded Connector");
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse("retro_ruby:ruby")),
            "The add-on cannot see the base mod's native item");
        helper.succeed();
    }
}
