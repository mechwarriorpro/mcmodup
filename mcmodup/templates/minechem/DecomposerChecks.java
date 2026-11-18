package mcmodup.generated.@NAMESPACE@;

import java.io.File;
import javax.xml.parsers.ParserConfigurationException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.JUnitLikeTestReporter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CatalogPort.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DecomposerChecks {
    private static final boolean PREPAID = @PREPAID@;

    static {
        String report = System.getProperty("mcmodup.gameReport");
        if (report != null) {
            try {
                GlobalTestReporter.replaceWith(new JUnitLikeTestReporter(new File(report)));
            } catch (ParserConfigurationException error) {
                throw new IllegalStateException(error);
            }
        }
    }

    @GameTest(template = "test_room")
    public static void savedBufferDrains(GameTestHelper test) {
        var machine = place(test);
        start(test, machine);
        tick(test, machine);
        test.assertTrue(machine.inputs.getStackInSlot(0).isEmpty() && machine.outputs.getStackInSlot(0).isEmpty(),
                        "The intake tick should consume one ingot and emit nothing");
        var level = test.getLevel();
        var saved = machine.saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(machine.getBlockPos());
        test.setBlock(BlockPos.ZERO, Blocks.AIR);
        var restored = place(test);
        test.assertTrue(restored != machine, "Persistence test needs a new block entity");
        restored.loadWithComponents(saved, level.registryAccess());
        var tube = ironTube();
        for (int count = 1; count <= 16; count++) {
            tick(test, restored);
            expect(test, restored, 0, tube, count);
            test.assertTrue(power(test, restored).getEnergyStored() == 0, "Only paid output should remain");
        }
        tick(test, restored);
        expect(test, restored, 0, tube, 16);
        test.succeed();
    }

    @GameTest(template = "test_room")
    public static void linkedSlotRule(GameTestHelper test) {
        var machine = place(test);
        int last = machine.outputs.getSlots() - 1;
        ItemStack tube = ironTube();
        if (PREPAID) {
            machine.outputs.setStackInSlot(0, tube.copyWithCount(60));
            machine.outputs.setStackInSlot(last, tube.copyWithCount(10));
            start(test, machine);
            tick(test, machine);
            // The original intake method checks a matching stack before a later gap,
            // mutates both counts and leaves an unmerged remainder in place.
            expect(test, machine, 0, tube, 64);
            expect(test, machine, last, tube, 6);
            test.assertTrue(machine.outputs.getStackInSlot(1).isEmpty() && power(test, machine).getEnergyStored() == 0,
                            "Do not emit during compaction");
        } else {
            for (int slot = 0; slot < last; slot++) machine.outputs.setStackInSlot(slot, new ItemStack(Items.STONE, 64));
            machine.outputs.setStackInSlot(last, tube.copyWithCount(63));
            start(test, machine);
            tick(test, machine); // intake
            tick(test, machine); // first tube fills the last slot
            tick(test, machine); // jam
            expect(test, machine, last, tube, 64);
            machine.outputs.setStackInSlot(last, tube.copyWithCount(62));
            charge(test, machine, 1);
            tick(test, machine);
            expect(test, machine, last, tube, 62); // even a matching occupied slot is not enough
            test.assertTrue(power(test, machine).getEnergyStored() == 1, "The jam check must not spend power");
            machine.outputs.setStackInSlot(last, ItemStack.EMPTY);
            tick(test, machine);
            expect(test, machine, last, tube, 1);
        }
        test.succeed();
    }

    private static ChemistryMachines.Machine place(GameTestHelper test) {
        var id = ResourceLocation.fromNamespaceAndPath(CatalogPort.MOD_ID, "decomposer");
        var block = BuiltInRegistries.BLOCK.get(id);
        test.setBlock(BlockPos.ZERO, block);
        var entity = test.getLevel().getBlockEntity(test.absolutePos(BlockPos.ZERO));
        test.assertTrue(entity instanceof ChemistryMachines.Machine, "Missing decomposer " + id);
        return (ChemistryMachines.Machine) entity;
    }

    private static ItemStack ironTube() {
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(CatalogPort.MOD_ID, "element_fe")));
    }

    private static void expect(GameTestHelper test, ChemistryMachines.Machine machine, int slot, ItemStack item, int count) {
        var actual = machine.outputs.getStackInSlot(slot);
        test.assertTrue(actual.is(item.getItem()) && actual.getCount() == count,
                        "Expected " + count + " tubes at " + slot + ", got " + actual);
    }

    private static void start(GameTestHelper test, ChemistryMachines.Machine machine) {
        test.assertTrue(machine.inputs.insertItem(0, new ItemStack(Items.IRON_INGOT), false).isEmpty(), "Iron input rejected");
        charge(test, machine, PREPAID ? 1000 : 1);
    }

    private static IEnergyStorage power(GameTestHelper test, ChemistryMachines.Machine machine) {
        var capability = test.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, machine.getBlockPos(), Direction.NORTH);
        test.assertTrue(capability != null, "Missing native energy capability");
        return capability;
    }

    private static void charge(GameTestHelper test, ChemistryMachines.Machine machine, int total) {
        var energy = power(test, machine);
        while (total > 0) {
            int added = energy.receiveEnergy(total, false);
            test.assertTrue(added > 0 && added <= total, "Could not charge decomposer");
            total -= added;
        }
    }

    private static void tick(GameTestHelper test, ChemistryMachines.Machine machine) {
        ChemistryMachines.Machine.tick(test.getLevel(), machine.getBlockPos(), machine.getBlockState(), machine);
    }
}
