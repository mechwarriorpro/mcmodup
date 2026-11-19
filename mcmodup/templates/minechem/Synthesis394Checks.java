package @PACKAGE@;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

@GameTestHolder("@TEST_NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class Synthesis394Checks {
    private static final String ID = "minechem_chemistry_394";
    private static final int NEXT = 1, CLEAR = 4, OUTPUT = 9;
    private static final String[] TRACE = {
@TRACE@
    };

    @GameTest(template = "test_room")
    public static void exactSynthesisBatches(GameTestHelper test) {
        var machine = place(test, BlockPos.ZERO);
        test.assertTrue(machine.input().getSlots() == 18 && machine.output().getSlots() == 1, "Unexpected exposed synthesizer slots");
        test.assertTrue(machine.power().getMaxEnergyStored() == 100_000 && machine.power().receiveEnergy(1000, true) == 100,
                "Wrong .394 synthesis FE limits");
        for (String row : TRACE) {
            String[] recipe = row.split(";", 4);
            var expected = item(recipe[0], Integer.parseInt(recipe[1]));
            int cost = Integer.parseInt(recipe[2]);
            String[] pattern = recipe[3].split(",");
            test.assertTrue(machine.menu().clickMenuButton(machine.player(), NEXT), "Cannot set .394 ghost pattern");
            checkStack(test, machine.menu().getSlot(OUTPUT).getItem(), expected, "wrong output template for " + recipe[0]);
            test.assertTrue(machine.output().getStackInSlot(0).isEmpty(), "Uncharged ghost was exposed as inventory output");
            int index = 0;
            for (int slot = 0; slot < pattern.length; ++slot) {
                var part = part(pattern[slot]);
                checkStack(test, machine.menu().getSlot(slot).getItem(), part, "wrong ghost slot " + slot + " for " + recipe[0]);
                for (int left = part.getCount(); left > 0;) {
                    int count = Math.min(left, part.getMaxStackSize());
                    test.assertTrue(machine.input().insertItem(index++, part.copyWithCount(count), false).isEmpty(), "Test could not load " + part);
                    left -= count;
                }
            }
            charge(test, machine, cost - 1);
            test.assertTrue(machine.output().extractItem(0, 64, false).isEmpty(), "Synthesis charged below original threshold for " + recipe[0]);
            charge(test, machine, 1);
            test.assertTrue(machine.output().extractItem(0, expected.getCount() - 1, false).isEmpty(), "Unexpected partial result batch");
            checkStack(test, machine.output().extractItem(0, 64, true), expected, "wrong simulated extraction");
            test.assertTrue(machine.power().getEnergyStored() == cost, "Simulation spent energy for " + recipe[0]);
            checkStack(test, machine.output().extractItem(0, 64, false), expected, "wrong concrete extraction");
            test.assertTrue(machine.power().getEnergyStored() == 0 && machine.output().extractItem(0, 64, false).isEmpty(),
                    "Synthesis didn't charge exactly once for " + recipe[0]);
            for (int slot = 0; slot < index; ++slot) test.assertTrue(machine.input().getStackInSlot(slot).isEmpty(), "Synthesis left an ingredient");
            checkStack(test, machine.menu().getSlot(OUTPUT).getItem(), expected, "the ghost should still show the result");
        }
        test.succeed();
    }

    @GameTest(template = "test_room")
    public static void ghostEditingAndSavedSynthesis(GameTestHelper test) {
        var machine = place(test, BlockPos.ZERO);
        var fe = item(ID + ":element_fe", 16);
        machine.menu().setCarried(fe);
        // Shapeless templates can be placed in any ghost slot without moving the real stack.
        machine.menu().clicked(8, 0, ClickType.PICKUP, machine.player());
        checkStack(test, machine.menu().getCarried(), fe, "ghost edit moved the held stack");
        var iron = new ItemStack(Items.IRON_INGOT);
        checkStack(test, machine.menu().getSlot(OUTPUT).getItem(), iron, "shapeless iron template failed");
        test.assertTrue(machine.menu().clickMenuButton(machine.player(), CLEAR) && machine.menu().getSlot(8).getItem().isEmpty(), "could not clear pattern");
        machine.menu().clicked(4, 0, ClickType.PICKUP, machine.player());
        test.assertTrue(machine.input().insertItem(0, fe.copyWithCount(10), false).isEmpty(), "input slot 0 rejected Fe");
        test.assertTrue(machine.input().insertItem(1, fe.copyWithCount(6), false).isEmpty(), "input slot 1 rejected Fe");
        charge(test, machine, 3017);
        var registry = test.getLevel().registryAccess();
        var saved = machine.entity().saveWithFullMetadata(registry);
        var loaded = place(test, new BlockPos(2, 0, 0));
        loaded.entity().loadWithComponents(saved, registry);
        test.assertTrue(loaded.power().getEnergyStored() == 3017, "saved FE did not reload");
        checkStack(test, loaded.menu().getSlot(4).getItem(), fe, "original ghost position was not saved");
        loaded.menu().setCarried(new ItemStack(Items.DIRT));
        loaded.menu().clicked(OUTPUT, 0, ClickType.PICKUP, loaded.player());
        test.assertTrue(loaded.power().getEnergyStored() == 3017, "blocked cursor spent energy");
        loaded.menu().clicked(OUTPUT, 0, ClickType.THROW, loaded.player());
        test.assertTrue(loaded.power().getEnergyStored() == 3017, "drop click withdrew a ghost");
        loaded.menu().setCarried(ItemStack.EMPTY);
        loaded.menu().clicked(OUTPUT, 0, ClickType.PICKUP, loaded.player());
        checkStack(test, loaded.menu().getCarried(), iron, "extract did not place a real ingot on cursor");
        test.assertTrue(loaded.power().getEnergyStored() == 17 && loaded.input().getStackInSlot(0).isEmpty() &&
                loaded.input().getStackInSlot(1).isEmpty(), "extraction should consume both split stacks and exact cost");
        loaded.menu().clicked(OUTPUT, 0, ClickType.PICKUP, loaded.player());
        checkStack(test, loaded.menu().getCarried(), iron, "ghost may not be withdrawn a second time");
        test.succeed();
    }

    private static ItemStack part(String key) {
        if (key.equals("_")) return ItemStack.EMPTY;
        String[] fields = key.split("@", 2);
        return item(fields[0], Integer.parseInt(fields[1]));
    }

    private static ItemStack item(String key, int amount) {
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(key)), amount);
    }

    private static Machine place(GameTestHelper test, BlockPos relative) {
        var pos = test.absolutePos(relative);
        var level = test.getLevel();
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(ID, "synthesizer"));
        test.setBlock(relative, block);
        var entity = level.getBlockEntity(pos);
        test.assertTrue(entity instanceof MenuProvider, "Missing .394 synthesizer entity and menu");
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        var menu = ((MenuProvider) entity).createMenu(0, player.getInventory(), player);
        var input = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
        var output = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.DOWN);
        var power = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.NORTH);
        test.assertTrue(menu != null && input != null && output != null && power != null, "Missing synthesis menu or capability");
        return new Machine(entity, player, menu, input, output, power);
    }

    private static void charge(GameTestHelper test, Machine machine, int amount) {
        int left = amount;
        while (left > 0) {
            int added = machine.power().receiveEnergy(left, false);
            test.assertTrue(added > 0 && added <= 100 && added <= left, "Invalid .394 synthesis receive");
            left -= added;
        }
    }

    private static void checkStack(GameTestHelper test, ItemStack got, ItemStack expected, String message) {
        test.assertTrue(ItemStack.matches(got, expected), message + ": got " + got + ", expected " + expected);
    }

    private record Machine(BlockEntity entity, Player player, AbstractContainerMenu menu, IItemHandler input,
                           IItemHandler output, IEnergyStorage power) {}
}
