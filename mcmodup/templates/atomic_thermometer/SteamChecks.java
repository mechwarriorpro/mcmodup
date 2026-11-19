package @PACKAGE@;

import java.util.List;
import mcmodup_kernel.atomic_thermometer.HeatGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("@NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class SteamChecks {
    private static final BlockPos NEAR = new BlockPos(1, 1, 1), FAR = new BlockPos(3, 1, 3);

    @GameTest(template = "turbine_room")
    public static void heatedWaterFeedsTurbine(GameTestHelper test) {
        var level = test.getLevel();
        enclose(test, NEAR);
        enclose(test, FAR);
        test.setBlock(NEAR.above(), block("steam_funnel"));
        test.setBlock(FAR.above(), Blocks.STONE);
        test.setBlock(FAR.above(2), block("steam_funnel"));
        warm(test, NEAR, 372F);
        warm(test, FAR, 373F);
        test.runAfterDelay(1, () -> {
            var first = fluid(test, NEAR.above(), Direction.UP);
            var second = fluid(test, FAR.above(2), Direction.UP);
            test.assertTrue(level.getBlockState(test.absolutePos(NEAR)).is(Blocks.WATER) && first.getFluidInTank(0).isEmpty(),
                "372 K water should not boil");
            test.assertTrue(level.getBlockState(test.absolutePos(FAR)).isAir() && second.getFluidInTank(0).getAmount() == 1000,
                "373 K water should give 1000 mB two blocks above, even with an intervening block");
            warm(test, NEAR, 373F);
            test.runAfterDelay(1, () -> {
                test.assertTrue(level.getBlockState(test.absolutePos(NEAR)).isAir() && first.getFluidInTank(0).getAmount() == 500,
                    "373 K water should give 500 mB one block above");
                test.setBlock(NEAR.above(2), block("steam_turbine"));
                test.runAfterDelay(3, () -> {
                    var power = level.getCapability(Capabilities.EnergyStorage.BLOCK, test.absolutePos(NEAR.above(2)), Direction.UP);
                    test.assertTrue(first.getFluidInTank(0).isEmpty() && power != null && power.getEnergyStored() > 0,
                        "Boiled water did not reach the native turbine as steam and generate FE");
                    test.succeed();
                });
            });
        });
    }

    @GameTest(template = "turbine_room")
    public static void funnelCraftSidesAndSave(GameTestHelper test) {
        var level = test.getLevel();
        var iron = new ItemStack(Items.IRON_INGOT);
        var empty = ItemStack.EMPTY;
        var grid = CraftingInput.of(3, 3, List.of(empty, iron, empty, iron, empty, iron, iron, empty, iron));
        var match = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level).orElseThrow();
        var made = match.value().assemble(grid, level.registryAccess());
        test.assertTrue(match.id().equals(id("steam_funnel")) && made.is(block("steam_funnel").asItem()) && made.getCount() == 2,
            "Wrong Voltz steam funnel recipe");
        test.setBlock(NEAR, block("steam_funnel"));
        var pos = test.absolutePos(NEAR);
        var input = fluid(test, NEAR, Direction.DOWN);
        var output = fluid(test, NEAR, Direction.UP);
        test.assertTrue(level.getCapability(Capabilities.FluidHandler.BLOCK, pos, Direction.EAST) == null &&
            input.getTankCapacity(0) == 16000 && input.fill(new FluidStack(Fluids.WATER, 1000), FluidAction.EXECUTE) == 0,
            "Funnel must use a 16000 mB gas-only tank with no horizontal port");
        var steam = new FluidStack(BuiltInRegistries.FLUID.get(id("steam")), 20000);
        test.assertTrue(input.fill(steam, FluidAction.SIMULATE) == 16000 && input.getFluidInTank(0).isEmpty() &&
            output.fill(steam, FluidAction.EXECUTE) == 0, "Funnel simulation or input side changed");
        test.assertTrue(input.fill(steam, FluidAction.EXECUTE) == 16000 && input.drain(1, FluidAction.EXECUTE).isEmpty() &&
            output.drain(600, FluidAction.SIMULATE).getAmount() == 600 && output.getFluidInTank(0).getAmount() == 16000,
            "Incorrect bottom input, top extraction or non-mutating simulation");
        var original = level.getBlockEntity(pos);
        var saved = original.saveWithFullMetadata(level.registryAccess());
        test.setBlock(FAR, block("steam_funnel"));
        var restored = level.getBlockEntity(test.absolutePos(FAR));
        restored.loadWithComponents(saved, level.registryAccess());
        var loaded = fluid(test, FAR, Direction.UP);
        test.assertTrue(loaded.getFluidInTank(0).getAmount() == 16000 && loaded.drain(600, FluidAction.EXECUTE).getAmount() == 600 &&
            loaded.getFluidInTank(0).getAmount() == 15400, "Fresh native block entity lost the stored gas on reload");
        test.succeed();
    }

    private static void enclose(GameTestHelper test, BlockPos pos) {
        test.setBlock(pos.below(), Blocks.STONE);
        for (var side : Direction.Plane.HORIZONTAL) test.setBlock(pos.relative(side), Blocks.STONE);
        test.setBlock(pos, Blocks.WATER);
    }

    private static void warm(GameTestHelper test, BlockPos relative, float temperature) {
        var level = test.getLevel();
        var pos = test.absolutePos(relative);
        test.assertTrue(HeatGrid.addHeat(level, pos, temperature - HeatGrid.sample(level, pos)), "Could not set fixture temperature");
    }

    private static IFluidHandler fluid(GameTestHelper test, BlockPos pos, Direction side) {
        var handler = test.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, test.absolutePos(pos), side);
        test.assertTrue(handler != null, "Expected steam capability at " + pos + ":" + side);
        return handler;
    }

    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath("atomic_thermometer", name); }
    private static net.minecraft.world.level.block.Block block(String name) { return BuiltInRegistries.BLOCK.get(id(name)); }
}
