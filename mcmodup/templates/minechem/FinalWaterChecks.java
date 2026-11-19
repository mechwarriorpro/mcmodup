package @PACKAGE@;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("@TEST_NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class FinalWaterChecks {
    private static final String MOD = "minechem_chemistry";
    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath(MOD, name); }

    @GameTest(template = "water_room", timeoutTicks = 80)
    public static void finalWaterIntakeAndSave(GameTestHelper test) {
        var world = test.getLevel();
        var pos = test.absolutePos(new BlockPos(1, 2, 1));
        var machine = BuiltInRegistries.BLOCK.get(id("decomposer"));
        var hydrogen = BuiltInRegistries.ITEM.get(id("element_h"));
        var oxygen = BuiltInRegistries.ITEM.get(id("element_o"));
        int[] offered = {144, 145, 10_000, 10_001, 10_145};
        int[] retained = {144, 1, 9_856, 1, 1};
        var lookup = world.registryAccess();
        for (int i = 0; i < offered.length; i++) {
            world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            world.setBlockAndUpdate(pos, machine.defaultBlockState());
            var tank = world.getCapability(Capabilities.FluidHandler.BLOCK, pos, Direction.NORTH);
            test.assertTrue(tank != null, "FINAL does not expose the native fluid capability");
            var water = new FluidStack(Fluids.WATER, offered[i]);
            test.assertTrue(tank.fill(water, FluidAction.SIMULATE) == offered[i] && tank.getFluidInTank(0).isEmpty(), "Simulation filled the tank");
            test.assertTrue(tank.fill(water, FluidAction.EXECUTE) == offered[i] && tank.drain(1, FluidAction.EXECUTE).isEmpty(), "FINAL fluid intake is not fill-only");
            var tile = world.getBlockEntity(pos);
            @SuppressWarnings("unchecked")
            var type = (BlockEntityType<BlockEntity>) tile.getType();
            var ticker = machine.getTicker(world, world.getBlockState(pos), type);
            // The compile-time entity type here is BlockEntity; the real ticker validates it.
            ticker.tick(world, pos, world.getBlockState(pos), tile);
            test.assertTrue(tank.getFluidInTank(0).getAmount() == retained[i], "Wrong unpowered intake boundary for " + offered[i]);
            var saved = tile.saveWithFullMetadata(lookup);
            world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            world.setBlockAndUpdate(pos, machine.defaultBlockState());
            tile = world.getBlockEntity(pos);
            tile.loadWithComponents(saved, lookup);
            tank = world.getCapability(Capabilities.FluidHandler.BLOCK, pos, Direction.NORTH);
            test.assertTrue(tank.getFluidInTank(0).isEmpty(), "FINAL serialized the original volatile fluid remainder");
            var power = world.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.UP);
            var outputs = world.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.DOWN);
            test.assertTrue(power.receiveEnergy(1, false) == 1 && outputs.getSlots() == 9, "Wrong FINAL FE or output capability");
            for (int tick = 0; tick < 7; tick++) ticker.tick(world, pos, world.getBlockState(pos), tile);
            int h = 0, o = 0;
            for (int slot = 0; slot < outputs.getSlots(); slot++) {
                var output = outputs.getStackInSlot(slot);
                if (output.is(hydrogen)) h += output.getCount();
                else if (output.is(oxygen)) o += output.getCount();
                else test.assertTrue(output.isEmpty(), "Unexpected FINAL fluid output");
            }
            test.assertTrue(h == (i == 0 ? 0 : 2) && o == (i == 0 ? 0 : 1), "Wrong saved water outputs for " + offered[i]);
            for (int slot = 0; slot < outputs.getSlots(); slot++) outputs.extractItem(slot, 64, false);
        }
        world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        test.succeed();
    }
}
