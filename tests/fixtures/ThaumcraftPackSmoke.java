package mcmodup.thaumcraftcheck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
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
    public static void standalonePortLoadsFromItsStagedJar(GameTestHelper helper) throws IOException {
        var mods = ModList.get();
        helper.assertTrue(mods.isLoaded("thaumcraft"), "Thaumcraft native port was not loaded");
        helper.assertTrue(mods.isLoaded("architectury"), "Staged Architectury library was not loaded");
        helper.assertTrue(!mods.isLoaded("legacyconnector"), "Found the Connector in the native pack");
        helper.assertTrue(mods.isLoaded("computercraft") == Boolean.getBoolean("mcmodup.withComputercraft"),
            "The optional ComputerCraft mod must be present only in its dedicated test run");
        var dir = Path.of("mods").toRealPath();
        for (var id : new String[]{"thaumcraft", "architectury", "thaumictinkerer", "computercraft"}) {
            if (!mods.isLoaded(id)) continue;
            var path = mods.getModFileById(id).getFile().getFilePath().toRealPath();
            helper.assertTrue(Files.isRegularFile(path) && dir.equals(path.getParent()),
                "Expected " + id + " to come from a staged production JAR: " + path);
        }
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse("thaumcraft:thaumometer")),
            "The staged release did not register a thaumometer");
        var crucible = ResourceLocation.parse("thaumcraft:crucible");
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(crucible), "The staged release did not register a crucible");
        BlockPos pos = helper.absolutePos(BlockPos.ZERO);
        helper.getLevel().setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.get(crucible).defaultBlockState());
        helper.assertTrue(helper.getLevel().getBlockEntity(pos) != null,
            "A placed crucible did not create a block entity");
        helper.succeed();
    }
}
