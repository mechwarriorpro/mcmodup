package @PACKAGE@;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

@GameTestHolder("@TEST_NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class Release394Checks {
    private static final String MOD = "minechem_chemistry_394";
    private static final String[] TRACE = {
@TRACE@
    };

    @GameTest(template = "test_room")
    public static void materialDecompositionMatchesTrace(GameTestHelper test) {
        var machine = place(test);
        test.assertTrue(!machine.input().isItemValid(0, new ItemStack(Items.DEEPSLATE_IRON_ORE)), "Do not guess a new ore recipe");
        for (String row : TRACE) {
            String[] fields = row.split("=", 2);
            ItemStack stack = stack(fields[0]);
            test.assertTrue(!stack.isEmpty(), "Missing ported input for " + row);
            test.assertTrue(machine.input().insertItem(0, stack, false).isEmpty(), "Didn't accept " + row);
            charge(test, machine);
            tick(machine);
            test.assertTrue(machine.input().getStackInSlot(0).isEmpty(), "Did not consume " + row);
            requireEmpty(test, machine, "Intake emitted output for " + row);
            for (String output : fields[1].split(",")) {
                String[] part = output.split("@", 2);
                emit(test, machine, stack(part[0]), Integer.parseInt(part[1]));
            }
            tick(machine);
            requireEmpty(test, machine, "Unexpected extra output for " + row);
        }
        test.succeed();
    }

    @GameTest(template = "test_room")
    public static void waterTankRoundTripAndPriority(GameTestHelper test) {
        var machine = place(test);
        var tank = machine.tank();
        var oversized = new FluidStack(Fluids.WATER, 7_000);
        test.assertTrue(tank.getTanks() == 1 && tank.getTankCapacity(0) == 5_000, "Wrong .394 water tank geometry");
        test.assertTrue(tank.fill(oversized, FluidAction.SIMULATE) == 5_000 && tank.getFluidInTank(0).isEmpty(), "Simulated fill mutated the tank");
        test.assertTrue(tank.fill(oversized, FluidAction.EXECUTE) == 5_000, "Wrong fill size");
        test.assertTrue(tank.fill(new FluidStack(Fluids.LAVA, 1), FluidAction.EXECUTE) == 0, "A single-fluid tank cannot mix lava with water");
        test.assertTrue(tank.drain(4001, FluidAction.SIMULATE).getAmount() == 4001 && tank.getFluidInTank(0).getAmount() == 5000,
                "Simulated drain mutated the tank");
        test.assertTrue(tank.drain(4001, FluidAction.EXECUTE).getAmount() == 4001 && tank.getFluidInTank(0).getAmount() == 999,
                "Drain did not leave 999 mB");
        charge(test, machine);
        tick(machine);
        test.assertTrue(tank.getFluidInTank(0).getAmount() == 999 && machine.power().getEnergyStored() == 1000,
                "Less than a bucket started decomposition");
        test.assertTrue(tank.fill(new FluidStack(Fluids.WATER, 1001), FluidAction.EXECUTE) == 1001, "Second fill failed");
        test.assertTrue(machine.input().insertItem(0, new ItemStack(Items.IRON_INGOT), false).isEmpty(), "Couldn't add an item with water present");
        tick(machine);
        test.assertTrue(machine.input().getStackInSlot(0).isEmpty() && tank.getFluidInTank(0).getAmount() == 2000,
                "Fluid was preferred to a valid item input");
        requireEmpty(test, machine, "Iron emitted during intake");
        emit(test, machine, stack(MOD + ":element_fe"), 16);
        charge(test, machine);
        tick(machine);
        test.assertTrue(tank.getFluidInTank(0).getAmount() == 1000 && machine.power().getEnergyStored() == 0, "Wrong fluid intake or cost");
        requireEmpty(test, machine, "Water emitted during intake");
        var state = machine.entity().saveWithFullMetadata(test.getLevel().registryAccess());
        test.getLevel().removeBlockEntity(machine.entity().getBlockPos());
        test.setBlock(BlockPos.ZERO, Blocks.AIR);
        var loaded = place(test);
        loaded.entity().loadWithComponents(state, test.getLevel().registryAccess());
        test.assertTrue(loaded.tank().getFluidInTank(0).getAmount() == 1000, "Reload lost the unconsumed water");
        emit(test, loaded, stack(MOD + ":element_h"), 2);
        emit(test, loaded, stack(MOD + ":element_o"), 1);
        tick(loaded);
        requireEmpty(test, loaded, "Water produced an extra tube");
        test.assertTrue(loaded.tank().getFluidInTank(0).getAmount() == 1000, "Unpowered water was consumed after the saved batch");
        test.succeed();
    }

    private record Machine(BlockEntity entity, IItemHandler input, IItemHandler output, IEnergyStorage power, IFluidHandler tank) {}

    private static Machine place(GameTestHelper test) {
        var level = test.getLevel();
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(MOD, "decomposer"));
        test.setBlock(BlockPos.ZERO, block);
        var pos = test.absolutePos(BlockPos.ZERO);
        var entity = level.getBlockEntity(pos);
        var input = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
        var output = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.NORTH);
        var power = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.NORTH);
        var tank = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, Direction.NORTH);
        test.assertTrue(entity != null && input != null && output != null && power != null && tank != null, "Missing .394 machine or capabilities");
        return new Machine(entity, input, output, power, tank);
    }

    private static ItemStack stack(String id) { return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id))); }

    private static void charge(GameTestHelper test, Machine machine) {
        test.assertTrue(machine.power().getEnergyStored() == 0, "Expected an empty FE store before charging");
        for (int i = 0; i < 10; i++) test.assertTrue(machine.power().receiveEnergy(100, false) == 100, "Could not charge");
    }

    @SuppressWarnings("unchecked")
    private static void tick(Machine machine) {
        var tile = machine.entity();
        var state = tile.getBlockState();
        var ticker = ((EntityBlock) state.getBlock()).getTicker(tile.getLevel(), state, (BlockEntityType<BlockEntity>) tile.getType());
        ticker.tick(tile.getLevel(), tile.getBlockPos(), state, tile);
    }

    private static void emit(GameTestHelper test, Machine machine, ItemStack expected, int count) {
        test.assertTrue(!expected.isEmpty(), "Missing tube registry entry");
        for (int i = 0; i < count; i++) {
            tick(machine);
            var actual = machine.output().getStackInSlot(0);
            test.assertTrue(actual.is(expected.getItem()) && actual.getCount() == 1 && machine.power().getEnergyStored() == 0,
                    "Expected one " + expected + " with no charge on output tick " + (i + 1) + "; got " + actual);
            machine.output().extractItem(0, 1, false);
            requireEmpty(test, machine, "More than one output on one tick");
        }
    }

    private static void requireEmpty(GameTestHelper test, Machine machine, String reason) {
        for (int slot = 0; slot < machine.output().getSlots(); slot++) test.assertTrue(machine.output().getStackInSlot(slot).isEmpty(), reason);
    }
}
