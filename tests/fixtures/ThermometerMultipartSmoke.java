package mcmodup.atomicintegration;

import codechicken.multipart.api.RedstoneInteractions;
import mcmodup_kernel.atomic_thermometer.Thermometer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@Mod(ThermometerMultipartSmoke.ID)
@EventBusSubscriber(modid = ThermometerMultipartSmoke.ID)
@GameTestHolder(ThermometerMultipartSmoke.ID)
@PrefixGameTestTemplate(false)
public final class ThermometerMultipartSmoke {
    public static final String ID = "mcmodup_atomic_integration";
    private static final BlockPos SENSOR = new BlockPos(1, 1, 1);
    private static final BlockPos SOURCE = new BlockPos(3, 1, 1);

    @SubscribeEvent
    public static void heatSources(RegisterCapabilitiesEvent event) {
        event.registerBlock(Thermometer.TEMPERATURE, (level, pos, state, entity, context) -> () -> 1_000F, Blocks.GOLD_BLOCK);
        event.registerBlock(Thermometer.TEMPERATURE, (level, pos, state, entity, context) -> () -> 999F, Blocks.IRON_BLOCK);
    }

    @GameTest(template = "empty", timeoutTicks = 45)
    public static void trackedSignalReachesMultipart(GameTestHelper test) {
        test.assertTrue(ModList.get().isLoaded("cb_multipart") && ModList.get().isLoaded("codechickenlib") &&
                        !ModList.get().isLoaded("legacyconnector"), "Expected native Multipart and CCL without Connector");
        var world = test.getLevel();
        var pos = test.absolutePos(SENSOR);
        var temp = test.absolutePos(SOURCE);
        var id = ResourceLocation.parse("atomic_thermometer:thermometer");
        var item = BuiltInRegistries.ITEM.get(id);
        var stack = new ItemStack(item);
        var actor = test.makeMockPlayer(GameType.SURVIVAL);
        actor.setItemInHand(InteractionHand.MAIN_HAND, stack);
        actor.setShiftKeyDown(true);
        test.setBlock(SOURCE, Blocks.GOLD_BLOCK);
        test.setBlock(SENSOR.below(), Blocks.STONE);
        test.assertTrue(item.useOn(click(actor, temp)).consumesAction() && stack.getCount() == 1,
                        "Crouching use should track a sample position without placing the sensor");
        actor.setShiftKeyDown(false);
        test.assertTrue(item.useOn(click(actor, pos.below())).consumesAction() && world.getBlockState(pos).is(BuiltInRegistries.BLOCK.get(id)),
                        "Placement failed to install the staged thermometer");
        checkPower(test, pos, 0);
        test.runAfterDelay(12, () -> {
            checkPower(test, pos, 15);
            test.setBlock(SOURCE, Blocks.IRON_BLOCK);
            checkPower(test, pos, 15);
            test.runAfterDelay(12, () -> {
                checkPower(test, pos, 0);
                test.succeed();
            });
        });
    }

    private static UseOnContext click(net.minecraft.world.entity.player.Player player, BlockPos hit) {
        return new UseOnContext(player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(hit), Direction.UP, hit, false));
    }

    private static void checkPower(GameTestHelper test, BlockPos sensor, int expected) {
        int observed = RedstoneInteractions.getPowerTo(test.getLevel(), sensor.east(), Direction.WEST.get3DDataValue(), 0x1F);
        test.assertTrue(observed == expected, "Multipart expected power " + expected + ", got " + observed);
    }
}
