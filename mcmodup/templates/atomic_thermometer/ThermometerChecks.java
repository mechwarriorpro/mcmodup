package mcmodup_kernel.atomic_thermometer;

import java.io.File;
import javax.xml.parsers.ParserConfigurationException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.JUnitLikeTestReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "@TEST_NAMESPACE@")
@GameTestHolder("@TEST_NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class ThermometerChecks {
    public static final BlockPos LOCAL = new BlockPos(1, 1, 1);
    public static final BlockPos WARM = new BlockPos(2, 1, 1);
    public static final BlockPos COOL = new BlockPos(3, 1, 1);

    static {
        if (System.getProperty("mcmodup.gameReport") != null) {
            try {
                GlobalTestReporter.replaceWith(new JUnitLikeTestReporter(new File(System.getProperty("mcmodup.gameReport"))));
            } catch (ParserConfigurationException error) {
                throw new IllegalStateException(error);
            }
        }
    }

    @SubscribeEvent
    public static void sources(RegisterCapabilitiesEvent event) {
        // Test-only heat inputs, never attached to vanilla blocks in the output JAR.
        event.registerBlock(Thermometer.TEMPERATURE, (level, pos, state, entity, context) -> () -> 1000F, Blocks.GOLD_BLOCK);
        event.registerBlock(Thermometer.TEMPERATURE, (level, pos, state, entity, context) -> () -> 999F, Blocks.IRON_BLOCK);
    }

    @GameTest(template = "empty")
    public static void thresholdAndSettings(GameTestHelper test) {
        var sensor = place(test);
        threshold(test, sensor, 1000);
        sensor.adjust(false, 1, Vec3.ZERO, false);
        threshold(test, sensor, 900);
        sensor.adjust(true, 1, Vec3.ZERO, false);
        threshold(test, sensor, 1000);
        sensor.adjust(true, 1, Vec3.ZERO, true);
        threshold(test, sensor, 990);
        sensor.adjust(false, 1, Vec3.ZERO, true);
        threshold(test, sensor, 1000);
        for (int step = 0; step < 10; step++) sensor.adjust(false, 1, Vec3.ZERO, false);
        threshold(test, sensor, 5000);
        sensor.adjust(false, 1, Vec3.ZERO, true);
        threshold(test, sensor, 10);
        sensor.adjust(false, 1, Vec3.ZERO, false);
        threshold(test, sensor, 5000);
        test.succeed();
    }

    @GameTest(template = "empty")
    public static void trackedHeatAndSignal(GameTestHelper test) {
        var sensor = place(test);
        var world = test.getLevel();
        var pos = sensor.getBlockPos();
        var tracked = test.absolutePos(WARM);
        test.setBlock(WARM, Blocks.GOLD_BLOCK);
        test.setBlock(COOL, Blocks.IRON_BLOCK);
        var item = new ItemStack(ThermometerMod.ITEM.get());
        Thermometer.target(item, tracked);
        ThermometerMod.BLOCK.get().setPlacedBy(world, pos, world.getBlockState(pos), null, item);
        for (int i = 0; i < 9; i++) tick(test, sensor);
        power(test, pos, 0);
        tick(test, sensor);
        power(test, pos, 15);
        var saved = sensor.saveWithFullMetadata(world.registryAccess());
        test.setBlock(LOCAL, Blocks.AIR);
        var restored = place(test);
        test.assertTrue(restored != sensor, "Expected a fresh sensor for restoration");
        restored.loadWithComponents(saved, world.registryAccess());
        threshold(test, restored, 1000);
        for (int i = 0; i < 10; i++) tick(test, restored);
        power(test, pos, 15);
        restored.track(test.absolutePos(COOL));
        for (int i = 0; i < 9; i++) tick(test, restored);
        power(test, pos, 15);
        tick(test, restored);
        power(test, pos, 0);
        Thermometer.target(item, null);
        test.assertTrue(Thermometer.target(item) == null, "The held item should clear its tracked position");
        test.succeed();
    }

    private static Thermometer.Sensor place(GameTestHelper test) {
        test.setBlock(LOCAL, ThermometerMod.BLOCK.get());
        return (Thermometer.Sensor) test.getLevel().getBlockEntity(test.absolutePos(LOCAL));
    }

    private static void threshold(GameTestHelper test, Thermometer.Sensor sensor, int expected) {
        var tag = sensor.saveWithFullMetadata(test.getLevel().registryAccess());
        test.assertTrue(tag.getInt("threshold") == expected, "Wrong threshold, expected " + expected + ", found " + tag.getInt("threshold"));
    }

    private static void tick(GameTestHelper test, Thermometer.Sensor sensor) {
        Thermometer.Sensor.tick(test.getLevel(), sensor.getBlockPos(), sensor.getBlockState(), sensor);
    }

    private static void power(GameTestHelper test, BlockPos pos, int expected) {
        var world = test.getLevel();
        var state = world.getBlockState(pos);
        for (var side : Direction.values()) {
            test.assertTrue(state.getSignal(world, pos, side) == expected, "Wrong weak output at " + side + ", expected " + expected);
            test.assertTrue(state.getDirectSignal(world, pos, side) == expected, "Wrong strong output at " + side + ", expected " + expected);
        }
    }
}
