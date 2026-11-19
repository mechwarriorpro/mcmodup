package @PACKAGE@;

import mcmodup_kernel.atomic_thermometer.Accelerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("@NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class AcceleratorChecks {
    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath("atomic_thermometer", name); }

    @GameTest(template = "accel_room", timeoutTicks = 80)
    public static void builderAndAccelerator(GameTestHelper test) {
        var level = test.getLevel();
        var origin = test.absolutePos(new BlockPos(7, 4, 7));
        var injector = origin.west(5);
        level.setBlockAndUpdate(origin.west(5).north(3), Blocks.STONE.defaultBlockState());
        test.assertTrue(!Accelerator.CreativeBuilder.build(level, origin, 5) && level.getBlockState(injector).isAir(),
                "Builder changed a partly obstructed area");
        level.setBlockAndUpdate(origin.west(5).north(3), Blocks.AIR.defaultBlockState());
        test.assertTrue(Accelerator.CreativeBuilder.build(level, origin, 5), "Could not build the translated accelerator ring");
        var magnetGlass = BuiltInRegistries.BLOCK.get(id("electromagnet_glass"));
        test.assertTrue(level.getBlockState(injector.east()).isAir() && level.getBlockState(injector.east().above()).is(magnetGlass), "Incorrect beam channel");
        test.assertTrue(level.getBlockEntity(injector) instanceof Accelerator.Beam, "Missing accelerator block entity");
        var beam = (Accelerator.Beam) level.getBlockEntity(injector);
        var power = level.getCapability(Capabilities.EnergyStorage.BLOCK, injector, Direction.DOWN);
        var items = level.getCapability(Capabilities.ItemHandler.BLOCK, injector, Direction.UP);
        test.assertTrue(power != null && items != null && items.getSlots() == 4 && power.getMaxEnergyStored() == 9_600_000,
                "Incorrect accelerator inventories/power limits");
        var registry = level.registryAccess();
        var tag = beam.saveWithFullMetadata(registry);
        tag.putInt("antimatter_mg", 120);
        beam.loadWithComponents(tag, registry);
        var cell = new ItemStack(BuiltInRegistries.ITEM.get(id("empty_cell")));
        test.assertTrue(items.insertItem(1, cell, false).isEmpty() && items.insertItem(0, new ItemStack(Items.COBBLESTONE), false).isEmpty(), "Could not supply empty cell and sample");
        test.assertTrue(power.receiveEnergy(Integer.MAX_VALUE, true) == 240_000 && power.getEnergyStored() == 0, "Simulation charged the accelerator");
        level.setBlockAndUpdate(injector.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        // Replay a real ticker, including powered input, speed buildup and collection; not a fake completion call.
        var state = level.getBlockState(injector);
        for (int i = 0; i < 1_575; i++) {
            test.assertTrue(power.receiveEnergy(240_000, false) >= 0, "Power rejected a nonnegative request");
            Accelerator.Beam.tick(level, injector, state, beam);
        }
        var made = items.extractItem(2, 1, true);
        test.assertTrue(made.is(BuiltInRegistries.ITEM.get(id("antimatter_cell"))) && items.getStackInSlot(0).isEmpty() && items.getStackInSlot(1).isEmpty(),
                "A powered beam did not complete and bottle at 125 mg");
        beam.loadWithComponents(beam.saveWithFullMetadata(registry), registry);
        test.assertTrue(ItemStack.matches(made, items.extractItem(2, 1, false)) && items.extractItem(2, 1, false).isEmpty(),
                "Saved output was lost or duplicated");
        test.succeed();
    }

    @GameTest(template = "accel_room", timeoutTicks = 60)
    public static void orientedBuilderAndCreativeSupply(GameTestHelper test) {
        var level = test.getLevel();
        var origin = test.absolutePos(new BlockPos(7, 4, 7));
        test.assertTrue(Accelerator.CreativeBuilder.build(level, origin, 5, Direction.NORTH), "North-facing ring refused an empty site");
        var injector = origin.north(5);
        test.assertTrue(level.getBlockState(injector).is(BuiltInRegistries.BLOCK.get(id("particle_accelerator"))) &&
                level.getBlockState(injector).getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.SOUTH, "Incorrect rotated injector");
        level.setBlockAndUpdate(injector.north(), BuiltInRegistries.BLOCK.get(id("creative_fe_supply")).defaultBlockState());
        test.runAfterDelay(5, () -> {
            var input = level.getCapability(Capabilities.EnergyStorage.BLOCK, injector, Direction.NORTH);
            test.assertTrue(input != null && input.getEnergyStored() >= 240_000, "Creative FE source did not reach the actual accelerator sink");
            test.succeed();
        });
    }
}
