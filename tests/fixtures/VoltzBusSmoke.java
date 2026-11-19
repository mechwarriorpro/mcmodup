package mcmodup.test;

import java.util.HashMap;
import java.util.Map;
import mcmodup_kernel.icbm_glass_plate.SentryPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = CatalogSmoke.ID)
@GameTestHolder(CatalogSmoke.ID)
@PrefixGameTestTemplate(false)
public final class VoltzBusSmoke {
    private static final Map<BlockPos, EnergyStorage> RECEIVERS = new HashMap<>();

    @SubscribeEvent
    public static void receiver(RegisterCapabilitiesEvent event) {
        event.registerBlock(SentryPlatform.RECEIVER, (level, pos, block, tile, face) ->
            face == Direction.NORTH ? RECEIVERS.get(pos) : null, Blocks.EMERALD_BLOCK);
    }

    @GameTest(template = "turbine_room")
    public static void turbineSuppliesPassivePlatform(GameTestHelper test) {
        var level = test.getLevel();
        var local = new BlockPos(2, 1, 2);
        test.setBlock(local, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("atomic_thermometer:steam_turbine")));
        test.setBlock(local.above(), BuiltInRegistries.BLOCK.get(ResourceLocation.parse("icbm_glass_plate:sentry_platform")));
        var turbine = test.absolutePos(local);
        var terminal = turbine.above();
        var sinkPos = terminal.south();
        var tank = level.getCapability(Capabilities.FluidHandler.BLOCK, turbine, Direction.DOWN);
        var output = level.getCapability(Capabilities.EnergyStorage.BLOCK, turbine, Direction.UP);
        var bus = level.getCapability(Capabilities.EnergyStorage.BLOCK, terminal, Direction.DOWN);
        test.assertTrue(tank != null && output != null && bus != null, "Missing a production-JAR cross-mod capability");
        var steam = BuiltInRegistries.FLUID.get(ResourceLocation.parse("atomic_thermometer:steam"));
        test.assertTrue(tank.fill(new FluidStack(steam, 1000), FluidAction.EXECUTE) == 1000, "Didn't fill the standalone turbine");
        var receiver = new EnergyStorage(500_000, 35_000, 0);
        RECEIVERS.put(sinkPos, receiver);
        try {
            level.setBlockAndUpdate(sinkPos, Blocks.EMERALD_BLOCK.defaultBlockState());
            var entity = level.getBlockEntity(turbine);
            var state = level.getBlockState(turbine);
            @SuppressWarnings("unchecked") var type = (BlockEntityType<BlockEntity>) entity.getType();
            var tick = ((EntityBlock) state.getBlock()).getTicker(level, state, type);
            test.assertTrue(tick != null, "Missing turbine machine ticker");
            tick.tick(level, turbine, state, entity);
            test.assertTrue(tank.getFluidInTank(0).getAmount() == 900 && receiver.getEnergyStored() == 35_000 &&
                output.getEnergyStored() == 264_700 - 35_000 && bus.getEnergyStored() == 0,
                "The production turbine did not deliver exactly the accepted FE through the passive platform");
        } finally {
            RECEIVERS.remove(sinkPos);
        }
        test.succeed();
    }
}
