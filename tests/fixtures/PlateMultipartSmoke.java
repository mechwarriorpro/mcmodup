package mcmodup.plateintegration;

import codechicken.multipart.api.RedstoneInteractions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@Mod(PlateMultipartSmoke.ID)
@GameTestHolder(PlateMultipartSmoke.ID)
@PrefixGameTestTemplate(false)
public final class PlateMultipartSmoke {
    public static final String ID = "mcmodup_plate_integration";

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void multipartReadsPlatePower(GameTestHelper test) {
        test.assertTrue(ModList.get().isLoaded("cb_multipart") && ModList.get().isLoaded("codechickenlib") &&
                !ModList.get().isLoaded("legacyconnector"), "Wrong integration runtime");
        var level = test.getLevel();
        var plate = test.absolutePos(new BlockPos(1, 1, 1));
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("icbm_glass_plate:glass_pressure_plate"));
        level.setBlockAndUpdate(plate.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(plate, block.defaultBlockState());
        var adjacent = plate.east();
        int side = Direction.WEST.get3DDataValue();
        test.assertTrue(RedstoneInteractions.getPowerTo(level, adjacent, side, 0x1F) == 0, "Unexpected idle Multipart input");
        var item = new ItemEntity(level, plate.getX() + 0.5, plate.getY() + 0.05, plate.getZ() + 0.5, new ItemStack(Items.STICK));
        level.addFreshEntity(item);
        level.getBlockState(plate).entityInside(level, plate, item);
        test.assertTrue(RedstoneInteractions.getPowerTo(level, adjacent, side, 0x1F) == 15, "Native Multipart did not see the converted plate's power");
        item.discard();
        test.runAfterDelay(11, () -> {
            test.assertTrue(RedstoneInteractions.getPowerTo(level, adjacent, side, 0x1F) == 0, "Multipart saw power after release");
            test.succeed();
        });
    }
}
