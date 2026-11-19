package @PACKAGE@;

import java.util.HashMap;
import java.util.Map;
import mcmodup_kernel.icbm_glass_plate.SentryPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "@TEST_NAMESPACE@")
@GameTestHolder("@TEST_NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class PlatformChecks {
    private static final BlockPos CENTER = new BlockPos(2, 1, 2);
    private static final Map<BlockPos, Sink> ADJACENT = new HashMap<>();
    private record Sink(Direction face, EnergyStorage energy) {}

    @SubscribeEvent
    public static void testReceivers(RegisterCapabilitiesEvent event) {
        event.registerBlock(SentryPlatform.RECEIVER, (level, pos, state, entity, side) -> {
            var sink = ADJACENT.get(pos);
            return sink != null && sink.face() == side ? sink.energy() : null;
        }, Blocks.COPPER_BLOCK);
    }

    @GameTest(template = "platform_room")
    public static void passiveSentryTransfer(GameTestHelper test) {
        var pos = placePlatform(test, CENTER);
        var level = test.getLevel();
        var power = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.SOUTH);
        test.assertTrue(power != null && power.receiveEnergy(17, true) == 0 && power.receiveEnergy(17, false) == 0,
            "Unconnected platform took power");
        var firstPos = pos.north();
        var secondPos = pos.south();
        var first = new EnergyStorage(100, 100, 100, 95);
        var second = new EnergyStorage(100, 100, 100, 0);
        ADJACENT.put(firstPos, new Sink(Direction.SOUTH, first));
        ADJACENT.put(secondPos, new Sink(Direction.NORTH, second));
        try {
            // Put the south receiver down first so iteration order can't accidentally be placement order.
            level.setBlockAndUpdate(secondPos, Blocks.COPPER_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(firstPos, Blocks.COPPER_BLOCK.defaultBlockState());
            test.assertTrue(power.receiveEnergy(17, true) == 17 && first.getEnergyStored() == 95 && second.getEnergyStored() == 0,
                "The original simulation path must not mutate either receiver");
            test.assertTrue(power.receiveEnergy(17, false) == 17 && first.getEnergyStored() == 100 && second.getEnergyStored() == 12,
                "Expected five FE to north, then the remaining 12 to south");
            test.assertTrue(power.getEnergyStored() == 0 && !power.canExtract() && power.extractEnergy(17, false) == 0,
                "The relay must not retain or extract power");
            level.setBlockAndUpdate(secondPos, Blocks.AIR.defaultBlockState());
            test.assertTrue(power.receiveEnergy(17, false) == 0, "Removed or saturated neighbors still accepted energy");
        } finally {
            ADJACENT.remove(firstPos);
            ADJACENT.remove(secondPos);
        }
        test.succeed();
    }

    @GameTest(template = "platform_room")
    public static void storedSentryInventory(GameTestHelper test) {
        var level = test.getLevel();
        var pos = placePlatform(test, CENTER);
        var entity = level.getBlockEntity(pos);
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        test.assertTrue(entity instanceof MenuProvider, "Missing platform inventory provider");
        var menu = ((MenuProvider) entity).createMenu(1, player.getInventory(), player);
        test.assertTrue(menu != null && menu.stillValid(player) && menu.slots.size() == 56 && menu.getSlot(19).getMaxStackSize() == 64,
            "Wrong terminal and player inventory layout");
        var cobble = new ItemStack(Items.COBBLESTONE, 37);
        test.assertTrue(menu.getSlot(19).mayPlace(cobble), "Manual storage rejected inert material");
        menu.getSlot(19).set(cobble.copyWithCount(30));
        player.getInventory().setItem(9, cobble.copyWithCount(7));
        test.assertTrue(!menu.quickMoveStack(player, 20).isEmpty() && ItemStack.matches(menu.getSlot(19).getItem(), cobble) &&
            player.getInventory().getItem(9).isEmpty(), "Shift-click did not merge seven items into slot 19");
        for (var side : Direction.values()) {
            var inventory = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
            test.assertTrue(inventory != null && inventory.getSlots() == 20 && inventory.getSlotLimit(19) == 64 &&
                !inventory.isItemValid(0, cobble) && ItemStack.matches(inventory.insertItem(0, cobble, false), cobble),
                "Platform automation must be extraction-only on " + side);
            test.assertTrue(ItemStack.matches(inventory.extractItem(19, 37, true), cobble) && ItemStack.matches(inventory.getStackInSlot(19), cobble),
                "Simulation changed manual storage on " + side);
        }
        var saved = entity.saveWithFullMetadata(level.registryAccess());
        var restoredPos = placePlatform(test, CENTER.east(2));
        var restored = level.getBlockEntity(restoredPos);
        test.assertTrue(restored != null && restored != entity, "Expected a fresh platform for the reload");
        restored.loadWithComponents(saved, level.registryAccess());
        var out = level.getCapability(Capabilities.ItemHandler.BLOCK, restoredPos, Direction.DOWN);
        test.assertTrue(out != null && ItemStack.matches(out.extractItem(19, 37, false), cobble) && out.getStackInSlot(19).isEmpty(),
            "Saved platform inventory didn't reload and extract one stack");
        test.succeed();
    }

    private static BlockPos placePlatform(GameTestHelper test, BlockPos relative) {
        var id = ResourceLocation.fromNamespaceAndPath("icbm_glass_plate", "sentry_platform");
        test.assertTrue(BuiltInRegistries.BLOCK.containsKey(id), "Missing native Sentry platform");
        test.setBlock(relative, BuiltInRegistries.BLOCK.get(id));
        return test.absolutePos(relative);
    }
}
