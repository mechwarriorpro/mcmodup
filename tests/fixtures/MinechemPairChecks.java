package mcmodup.test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import static mcmodup.test.CatalogSmoke.*;

@GameTestHolder(CatalogSmoke.ID)
@PrefixGameTestTemplate(false)
public final class MinechemPairChecks {
    private static final String RELEASE_394 = "minechem_chemistry_394";

    @GameTest(template = "test_room")
    public static void differentMinechemReleasesKeepTheirOwnRules(GameTestHelper test) {
        var left = placeMachine(test, BlockPos.ZERO, MINECHEM, "decomposer", false);
        var relative394 = new BlockPos(2, 0, 0);
        var right = placeMachine(test, relative394, RELEASE_394, "decomposer", false);
        test.assertTrue(left.entity().getType() != right.entity().getType() && left.menu().getType() != right.menu().getType(),
                "The releases must not share a block entity or menu registration");
        test.assertTrue(left.entity().getBlockState().is(BlockTags.MINEABLE_WITH_PICKAXE) &&
                right.entity().getBlockState().is(BlockTags.MINEABLE_WITH_PICKAXE), "Both releases must add their blocks to the vanilla mining tag");
        test.assertTrue(left.input().getSlots() == 1 && left.output().getSlots() == 9 &&
                right.input().getSlots() == 1 && right.output().getSlots() == 18, "Expected two different sided inventories");
        for (var side : Direction.values()) {
            var handler = test.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, right.entity().getBlockPos(), side);
            test.assertTrue(handler == (side == Direction.UP ? right.input() : right.output()), "Wrong .394 sided inventory at " + side);
        }
        test.assertTrue(!BuiltInRegistries.BLOCK.containsKey(ResourceLocation.fromNamespaceAndPath(RELEASE_394, "synthesizer")),
                "The .394 preview must not register FINAL's synthesizer");
        test.assertTrue(right.power().getMaxEnergyStored() == 10_000 && right.power().receiveEnergy(1000, true) == 100 &&
                right.power().getEnergyStored() == 0, "Wrong .394 capacity or simulated receive limit");
        var finalWater = stack(test, MINECHEM, "molecule_water");
        var otherWater = stack(test, RELEASE_394, "molecule_water");
        test.assertTrue(left.input().isItemValid(0, finalWater) && !left.input().isItemValid(0, otherWater),
                "The FINAL recipe accepted a .394 catalog item of the same name");
        put(test, left.input(), 0, new ItemStack(Items.IRON_INGOT));
        put(test, right.input(), 0, new ItemStack(Items.IRON_INGOT));
        charge(test, left.power(), 1);
        charge(test, right.power(), 999);
        tick(left);
        tick(right);
        test.assertTrue(left.input().getStackInSlot(0).isEmpty() && left.power().getEnergyStored() == 1,
                "FINAL should take its input at 1 FE without an upfront charge");
        test.assertTrue(right.input().getStackInSlot(0).getCount() == 1 && right.power().getEnergyStored() == 999 &&
                right.output().getStackInSlot(0).isEmpty(), ".394 took an input before seeing 1,000 FE");
        charge(test, right.power(), 1);
        tick(right);
        test.assertTrue(right.input().getStackInSlot(0).isEmpty() && right.power().getEnergyStored() == 0 &&
                right.output().getStackInSlot(0).isEmpty(), ".394 must charge upfront and not emit on the intake tick");
        var loaded = reloaded(test, relative394, right);
        var finalTube = stack(test, MINECHEM, "element_fe");
        var otherTube = stack(test, RELEASE_394, "element_fe");
        test.assertTrue(!finalTube.is(otherTube.getItem()), "Chemical items from the two ports must have distinct IDs");
        for (int i = 1; i <= 16; i++) {
            tick(left);
            tick(loaded);
            checkOutput(test, left, 0, finalTube, i);
            checkOutput(test, loaded, 0, otherTube, i);
        }
        tick(loaded);
        checkOutput(test, loaded, 0, otherTube, 16);
        test.succeed();
    }

    @GameTest(template = "empty")
    public static void prepaidJamRecoversWithoutMorePower(GameTestHelper test) {
        var machine = placeMachine(test, BlockPos.ZERO, RELEASE_394, "decomposer", false);
        var iron = stack(test, RELEASE_394, "element_fe");
        var outputs = (IItemHandlerModifiable) machine.output();
        int last = outputs.getSlots() - 1;
        outputs.setStackInSlot(last, iron.copyWithCount(63));
        put(test, machine.input(), 0, new ItemStack(Items.IRON_INGOT));
        charge(test, machine.power(), 1000);
        tick(machine);
        checkOutput(test, machine, 0, iron, 63);
        test.assertTrue(outputs.getStackInSlot(last).isEmpty(), ".394 should compact existing output on intake without emitting a tube");
        // Recreate a gap during the running batch, after intake compaction.
        outputs.setStackInSlot(0, ItemStack.EMPTY);
        outputs.setStackInSlot(last, iron.copyWithCount(63));
        tick(machine);
        test.assertTrue(outputs.getStackInSlot(0).isEmpty(), ".394 should merge a matching stack before using an empty slot");
        checkOutput(test, machine, last, iron, 64);
        for (int slot = 0; slot < last; slot++) outputs.setStackInSlot(slot, new ItemStack(Items.STONE, 64));
        tick(machine);
        var loaded = reloaded(test, BlockPos.ZERO, machine);
        checkOutput(test, loaded, last, iron, 64);
        var restoredOutputs = (IItemHandlerModifiable) loaded.output();
        restoredOutputs.setStackInSlot(last, iron.copyWithCount(63));
        tick(loaded); // A matching partial stack can accept an output even with no empty slots and no charge.
        checkOutput(test, loaded, last, iron, 64);
        restoredOutputs.setStackInSlot(0, ItemStack.EMPTY);
        for (int i = 1; i <= 14; i++) {
            tick(loaded);
            checkOutput(test, loaded, 0, iron, i);
        }
        tick(loaded);
        checkOutput(test, loaded, 0, iron, 14);
        test.succeed();
    }

    private static Machine reloaded(GameTestHelper test, BlockPos relative, Machine machine) {
        var level = test.getLevel();
        var pos = machine.entity().getBlockPos();
        var tag = machine.entity().saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(pos);
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        var loaded = placeMachine(test, relative, RELEASE_394, "decomposer", false);
        test.assertTrue(loaded.entity() != machine.entity(), "Expected to load a new .394 block entity");
        loaded.entity().loadWithComponents(tag, level.registryAccess());
        return loaded;
    }

    private static void checkOutput(GameTestHelper test, Machine machine, int slot, ItemStack expected, int count) {
        var actual = machine.output().getStackInSlot(slot);
        test.assertTrue(actual.is(expected.getItem()) && actual.getCount() == count && machine.power().getEnergyStored() == 0,
                "Expected " + count + " stored of " + expected + " and zero energy; got " + actual);
    }
}
