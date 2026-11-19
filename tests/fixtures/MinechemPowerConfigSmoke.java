package mcmodup.testbedcheck;

import java.util.Map;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mcmodup_testbed_settings")
@PrefixGameTestTemplate(false)
public final class MinechemPowerConfigSmoke {
    private static final boolean PAID = Boolean.getBoolean("mcmodup.testbed.paid");
    private static final String PROFILE = PAID ? "paid" : "free";
    private static final String NEWER = "minechem_chemistry_394";
    private static final Map<String, String> ROWS = Map.ofEntries(
@ROWS@
    );
    private static final Grid FISSION = new Grid(@FISSION_SIZE@, @FISSION_CORE@, "@FISSION_GRID@");
    private static final Grid FUSION = new Grid(@FUSION_SIZE@, @FUSION_CORE@, "@FUSION_GRID@");

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void configuredDecomposer(GameTestHelper test) {
        var pos = test.absolutePos(new BlockPos(2, 1, 2));
        place(test, pos, "decomposer");
        var power = power(test, pos, "decomposer");
        var level = test.getLevel();
        var input = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
        test.assertTrue(input.insertItem(0, new ItemStack(Items.IRON_INGOT), false).isEmpty(), "Could not insert the original RF sample");
        int cost = value("costDecomposition");
        charge(test, power, PAID ? cost - 1 : 17);
        test.runAfterDelay(3, () -> {
            var below = !input.getStackInSlot(0).isEmpty() + "/" + power.getEnergyStored();
            if (PAID) charge(test, power, 1);
            test.runAfterDelay(2, () -> {
                var started = input.getStackInSlot(0).isEmpty() + "/" + power.getEnergyStored();
                // Reload the already-paid (or free) queue with an independent block entity.
                var saved = level.getBlockEntity(pos).saveWithFullMetadata(level.registryAccess());
                level.removeBlockEntity(pos);
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                place(test, pos, "decomposer");
                level.getBlockEntity(pos).loadWithComponents(saved, level.registryAccess());
                test.runAfterDelay(20, () -> {
                    var output = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.DOWN);
                    var result = output.getStackInSlot(0);
                    int number = result.is(item("element_fe", 1).getItem()) ? 26 : 0;
                    int stored = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.UP).getEnergyStored();
                    verify(test, PROFILE + "_decomposer_run", below + "/" + started + "/" + number + "/" + result.getCount() + "/" + stored);
                    test.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty")
    public static void configuredSynthesis(GameTestHelper test) {
        var pos = test.absolutePos(new BlockPos(1, 1, 1));
        var body = stone(test, pos, 1);
        var power = power(test, pos, "synthesizer");
        var output = test.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.DOWN);
        int cost = Integer.parseInt(ROWS.get("paid_synthesizer_run").split("/")[0]);
        if (PAID) {
            charge(test, power, cost - 1);
            body.menu.clicked(9, 0, ClickType.PICKUP, body.player);
            test.assertTrue(body.menu.getCarried().isEmpty() && output.extractItem(0, 64, true).isEmpty() && power.getEnergyStored() == cost - 1,
                "Paid .394 synthesis ran below the configured multiplier's threshold");
            charge(test, power, 1);
            test.assertTrue(output.extractItem(0, 64, true).getCount() == 16 && power.getEnergyStored() == cost, "FE simulation spent charge");
            body.menu.clicked(9, 0, ClickType.PICKUP, body.player);
            test.assertTrue(body.menu.getCarried().is(Items.STONE) && body.menu.getCarried().getCount() == 16 && power.getEnergyStored() == 0,
                "A paid .394 batch did not spend the exact configured amount");
        } else {
            body.menu.clicked(9, 0, ClickType.PICKUP, body.player);
            verify(test, "free_synthesis_click", body.menu.getCarried().getCount() + "/" + power.getEnergyStored());
            test.assertTrue(body.menu.getCarried().is(Items.STONE), "A free .394 batch produced the wrong item");
            var bulkPos = pos.east();
            var bulk = stone(test, bulkPos, 3);
            bulk.menu.clicked(9, 0, ClickType.QUICK_MOVE, bulk.player);
            int stored = test.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, bulkPos, Direction.UP).getEnergyStored();
            int count = bulk.player.getInventory().countItem(Items.STONE);
            verify(test, "free_synthesis_shift_click", count + "/" + stored);
            var sidedPos = pos.south();
            stone(test, sidedPos, 1);
            var face = test.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, sidedPos, Direction.DOWN);
            var result = face.extractItem(0, 16, false);
            test.assertTrue(result.is(Items.STONE) && !face.extractItem(0, 16, false).is(Items.STONE), "Disabled power duplicated the sided result");
            stored = test.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, sidedPos, Direction.UP).getEnergyStored();
            verify(test, "free_synthesis_sided", true + "/" + result.getCount() + "/" + stored);
        }
        test.succeed();
    }

    @GameTest(template = "reactor_room")
    public static void configuredFission(GameTestHelper test) {
        var core = FISSION.build(test, "fission_core");
        test.runAfterDelay(3, () -> {
            var machine = menu(test, core);
            var power = power(test, core, "fission");
            int cost = value("fissionMultiplier") * 9;
            machine.menu.getSlot(0).set(item("element_f", 1));
            if (PAID) charge(test, power, cost - 1);
            test.runAfterDelay(3, () -> {
                boolean below = !machine.menu.getSlot(1).getItem().isEmpty();
                if (PAID) charge(test, power, 1);
                test.runAfterDelay(3, () -> {
                    var result = machine.menu.getSlot(1).getItem();
                    boolean batch = result.is(item("element_be", 1).getItem()) && result.getCount() == 2;
                    verify(test, PROFILE + "_fission_run", cost + "/" + below + "/" + batch + "/" + power.getEnergyStored());
                    test.succeed();
                });
            });
        });
    }

    @GameTest(template = "reactor_room")
    public static void configuredFusion(GameTestHelper test) {
        var core = FUSION.build(test, "fusion_core");
        test.runAfterDelay(3, () -> {
            var machine = menu(test, core);
            var power = power(test, core, "fusion");
            int cost = value("fusionMultiplier") * 2;
            machine.menu.getSlot(0).set(item("element_h", 1));
            machine.menu.getSlot(1).set(item("element_h", 1));
            test.runAfterDelay(3, () -> {
                boolean zero = !machine.menu.getSlot(2).getItem().isEmpty();
                charge(test, power, cost);
                test.runAfterDelay(3, () -> {
                    boolean equal = !machine.menu.getSlot(2).getItem().isEmpty();
                    charge(test, power, 1);
                    test.runAfterDelay(3, () -> {
                        var result = machine.menu.getSlot(2).getItem();
                        boolean batch = result.is(item("element_he", 1).getItem()) && result.getCount() == 1;
                        verify(test, PROFILE + "_fusion_run", cost + "/" + zero + "/" + equal + "/" + batch + "/" + power.getEnergyStored());
                        test.succeed();
                    });
                });
            });
        });
    }

    @GameTest(template = "reactor_room")
    public static void configuredReactorWalls(GameTestHelper test) {
        var orphan = test.absolutePos(new BlockPos(1, 1, 3));
        place(test, orphan, "reactor_casing");
        var fresh = power(test, orphan, "wall");
        int packet = value("energyPacketSize");
        int detachedIntake = fresh.receiveEnergy(packet, false);
        tickWall(test, orphan);
        var level = test.getLevel();
        var saved = level.getBlockEntity(orphan).saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(orphan);
        level.setBlockAndUpdate(orphan, Blocks.AIR.defaultBlockState());
        place(test, orphan, "reactor_casing");
        level.getBlockEntity(orphan).loadWithComponents(saved, level.registryAccess());
        var reloaded = level.getCapability(Capabilities.EnergyStorage.BLOCK, orphan, Direction.SOUTH);
        verify(test, PROFILE + "_wall_unlinked", detachedIntake + "/" + reloaded.getEnergyStored() + "/" + reloaded.receiveEnergy(packet, true));

        var fission = FISSION.build(test, "fission_core");
        var shell = FISSION.position(test, 0, 0, 0);
        test.runAfterDelay(3, () -> {
            var core = power(test, fission, "fission");
            var wall = power(test, shell, "wall");
            int intake = wall.receiveEnergy(packet, false);
            tickWall(test, shell);
            String first = wall.getEnergyStored() + "/" + core.getEnergyStored();
            tickWall(test, shell);
            verify(test, PROFILE + "_wall_forward", intake + "/" + first + "/" + wall.getEnergyStored() + "/" + core.getEnergyStored());
            charge(test, core, core.getMaxEnergyStored() - 5 - core.getEnergyStored());
            int secondIntake = wall.receiveEnergy(packet, false);
            tickWall(test, shell);
            String partly = wall.getEnergyStored() + "/" + core.getEnergyStored();
            tickWall(test, shell);
            verify(test, PROFILE + "_wall_partial", secondIntake + "/" + partly + "/" + wall.getEnergyStored() + "/" + core.getEnergyStored());
            test.succeed();
        });
    }

    @SuppressWarnings("unchecked")
    private static void tickWall(GameTestHelper test, BlockPos pos) {
        var level = test.getLevel();
        var state = level.getBlockState(pos);
        var tile = level.getBlockEntity(pos);
        var ticker = ((EntityBlock) state.getBlock()).getTicker(level, state, (BlockEntityType<BlockEntity>) tile.getType());
        test.assertTrue(ticker != null, "Missing server ticker for configured reactor wall");
        ticker.tick(level, pos, state, tile);
    }

    private static PowerMenu stone(GameTestHelper test, BlockPos pos, int batches) {
        place(test, pos, "synthesizer");
        var machine = menu(test, pos);
        var input = test.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
        for (int i = 0; i < 400 && !machine.menu.getSlot(9).getItem().is(Items.STONE); ++i)
            machine.menu.clickMenuButton(machine.player, 0);
        test.assertTrue(machine.menu.getSlot(9).getItem().is(Items.STONE), "Missing measured stone template");
        test.assertTrue(input.insertItem(0, item("element_si", batches), false).isEmpty()
                && input.insertItem(1, item("element_o", 5 * batches), false).isEmpty()
                && input.insertItem(2, item("element_al", 2 * batches), false).isEmpty(), "Missing local ingredients");
        return machine;
    }

    private record PowerMenu(AbstractContainerMenu menu, Player player) {}
    private static PowerMenu menu(GameTestHelper test, BlockPos pos) {
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        var provider = (MenuProvider) test.getLevel().getBlockEntity(pos);
        var menu = provider.createMenu(0, player.getInventory(), player);
        test.assertTrue(menu.stillValid(player), "Missing menu for configured machine at " + pos);
        return new PowerMenu(menu, player);
    }

    private record Grid(int width, int height, int depth, int cx, int cy, int cz, String contents) {
        BlockPos position(GameTestHelper test, int x, int y, int z) {
            return test.absolutePos(new BlockPos(2, 1, 8)).offset(width + 1 - x, y, z - depth / 2);
        }
        BlockPos build(GameTestHelper test, String core) {
            for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) for (int z = 0; z < depth; ++z) {
                char c = contents.charAt((y * width + x) * depth + z);
                if (c == 'A' || c == 'C') place(test, position(test, x, y, z), c == 'A' ? "reactor_casing" : "reactor_coil");
            }
            var at = position(test, cx, cy, cz);
            place(test, at, core);
            return at;
        }
    }

    private static void place(GameTestHelper test, BlockPos pos, String block) {
        var state = BuiltInRegistries.BLOCK.get(id(block)).defaultBlockState();
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        test.getLevel().setBlockAndUpdate(pos, state);
    }

    private static IEnergyStorage power(GameTestHelper test, BlockPos pos, String machine) {
        var storage = test.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.NORTH);
        test.assertTrue(storage != null, "Missing native FE capability for " + machine);
        verify(test, PROFILE + "_" + machine + "_storage", storage.getMaxEnergyStored() + "/" +
            storage.receiveEnergy(Integer.MAX_VALUE, true) + "/" + storage.getEnergyStored());
        return storage;
    }

    private static void charge(GameTestHelper test, IEnergyStorage power, int amount) {
        while (amount > 0) {
            int got = power.receiveEnergy(amount, false);
            test.assertTrue(got == Math.min(amount, value("energyPacketSize")), "Configured FE packet limit changed");
            amount -= got;
        }
    }

    private static void verify(GameTestHelper test, String key, String got) {
        test.assertTrue(got.equals(ROWS.get(key)), "Power config " + key + " expected " + ROWS.get(key) + ", got " + got);
    }
    private static int value(String key) { return Integer.parseInt(ROWS.get(key)); }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(NEWER, path); }
    private static ItemStack item(String key, int count) { return new ItemStack(BuiltInRegistries.ITEM.get(id(key)), count); }
}
