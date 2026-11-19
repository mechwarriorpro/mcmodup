package @PACKAGE@;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

@GameTestHolder("@TEST_NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class Sided394Checks {
    private static final String MOD = "minechem_chemistry_394";
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(MOD, path); }

    @GameTest(template = "journal_room")
    public static void sidedJournalAndPaidExtraction(GameTestHelper test) {
        var level = test.getLevel();
        var pos = test.absolutePos(BlockPos.ZERO);
        level.setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.get(id("synthesizer")).defaultBlockState());
        var journal = new ItemStack(BuiltInRegistries.ITEM.get(id("chemist_journal")));
        CustomData.update(DataComponents.CUSTOM_DATA, journal, tag -> {
            var data = new CompoundTag();
            var discoveries = new ListTag();
            discoveries.add(StringTag.valueOf("minecraft:iron_ingot"));
            data.put("Discoveries", discoveries);
            data.putString("Active", "minecraft:iron_ingot");
            tag.put("ChemistJournal", data);
        });
        var tubes = new ItemStack(BuiltInRegistries.ITEM.get(id("element_fe")), 16);
        var faces = new IItemHandler[Direction.values().length];
        for (var side : Direction.values()) {
            var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
            faces[side.ordinal()] = handler;
            test.assertTrue(handler != null && handler.getSlots() == 19, "Idle side lacks storage and journal: " + side);
            test.assertTrue(handler.insertItem(18, journal, true).isEmpty() && handler.getStackInSlot(18).isEmpty(), "Simulated journal insert changed " + side);
            test.assertTrue(handler.insertItem(0, tubes, true).isEmpty() == (side != Direction.DOWN), "Wrong idle tube insertion side " + side);
        }
        var bottom = faces[Direction.DOWN.ordinal()];
        var top = faces[Direction.UP.ordinal()];
        test.assertTrue(bottom.insertItem(18, journal, false).isEmpty() && bottom.extractItem(18, 1, true).isEmpty(), "Bottom must insert but not extract a journal");
        test.assertTrue(top.insertItem(0, tubes, false).isEmpty(), "Top rejected 16 iron tubes");
        var power = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.EAST);
        for (int left = 3_000; left > 0;) {
            int received = power.receiveEnergy(left, false);
            test.assertTrue(received > 0 && received <= left, "Could not charge the synthesis batch");
            left -= received;
        }
        for (var side : Direction.values()) {
            var handler = faces[side.ordinal()];
            int expected = side == Direction.UP ? 19 : 2;
            test.assertTrue(handler.getSlots() == expected, "Ready .394 did not switch slot layout: " + side);
            test.assertTrue(ItemStack.matches(handler.getStackInSlot(expected - 1), journal) &&
                handler.extractItem(expected - 1, 1, true).isEmpty() == (side == Direction.DOWN), "Wrong ready journal side: " + side);
            test.assertTrue(handler.extractItem(0, 1, true).is(Items.IRON_INGOT) == (side == Direction.DOWN), "Wrong paid output side: " + side);
        }
        test.assertTrue(power.getEnergyStored() == 3_000 && top.getStackInSlot(0).getCount() == 16, "Simulated actions paid a batch");
        test.assertTrue(bottom.extractItem(0, 1, false).is(Items.IRON_INGOT) && bottom.getSlots() == 19 &&
            power.getEnergyStored() == 0 && top.getStackInSlot(0).isEmpty(), "Concrete action did not pay exactly once");
        test.assertTrue(ItemStack.matches(top.extractItem(18, 1, false), journal) && top.getStackInSlot(18).isEmpty(), "Top failed to return the independent journal");
        test.succeed();
    }
}
