package @PACKAGE@;

import java.util.HashMap;
import java.util.Map;
import mcmodup_kernel.atomic_thermometer.Turbine;
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
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "@NAMESPACE@")
@GameTestHolder("@NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class TurbineChecks {
    private static final String ID = "atomic_thermometer";
    private static final Map<BlockPos, EnergyStorage> SINKS = new HashMap<>();
    private static final BlockPos LOCAL = new BlockPos(1, 1, 1);

    @SubscribeEvent
    public static void registerSink(RegisterCapabilitiesEvent event) {
        event.registerBlock(Capabilities.EnergyStorage.BLOCK, (level, pos, state, entity, side) ->
            side == Direction.DOWN ? SINKS.get(pos) : null, Blocks.RAW_GOLD_BLOCK);
    }

    @GameTest(template = "turbine_room")
    public static void standaloneSteamTurbine(GameTestHelper test) {
        var rotor = place(test, LOCAL);
        var level = test.getLevel();
        var pos = rotor.entity().getBlockPos();
        for (var side : Direction.values()) {
            if (side != Direction.DOWN) test.assertTrue(level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side) == null,
                    "Unexpected steam input from " + side);
            if (side != Direction.UP) test.assertTrue(level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side) == null,
                    "Unexpected energy output from " + side);
        }
        var steam = BuiltInRegistries.FLUID.get(ResourceLocation.fromNamespaceAndPath(ID, "steam"));
        var tank = rotor.tank();
        test.assertTrue(tank.getTankCapacity(0) == 100_000 && tank.fill(new FluidStack(Fluids.WATER, 1000), FluidAction.EXECUTE) == 0,
                "Wrong tank capacity or accepted water as steam");
        test.assertTrue(tank.fill(new FluidStack(steam, 101_000), FluidAction.SIMULATE) == 100_000 && tank.getFluidInTank(0).isEmpty(),
                "Steam fill simulation failed or mutated the tank");
        test.assertTrue(tank.fill(new FluidStack(steam, 1000), FluidAction.EXECUTE) == 1000, "Didn't take measured steam amount");
        test.assertTrue(tank.drain(1, FluidAction.EXECUTE).isEmpty() && rotor.power().receiveEnergy(50, false) == 0,
                "Input-only steam or output-only energy violated");
        int[] amounts = {900, 810, 729, 656, 590};
        int[] energy = {264700, 502930, 717337, 910568, 1085270};
        float[] spin = {0.10588F, 0.095292F, 0.0857628F, 0.0772924F, 0.0698808F};
        for (int i = 0; i < amounts.length; ++i) {
            tick(test, rotor);
            test.assertTrue(Math.abs(rotor.entity().angularVelocity() - spin[i]) < 1e-7F, "Wrong rotor speed after tick " + (i + 1));
            test.assertTrue(tank.getFluidInTank(0).getAmount() == amounts[i] && rotor.power().getEnergyStored() == energy[i],
                    "Wrong steam or FE after tick " + (i + 1) + ": " + tank.getFluidInTank(0).getAmount() + "/" + rotor.power().getEnergyStored());
        }
        var saved = rotor.entity().saveWithFullMetadata(level.registryAccess());
        var restored = place(test, new BlockPos(3, 1, 1));
        restored.entity().loadWithComponents(saved, level.registryAccess());
        test.assertTrue(restored.tank().getFluidInTank(0).getAmount() == 590 && restored.power().getEnergyStored() == 1_085_270,
                "Wrong saved rotor state");
        test.assertTrue(restored.power().extractEnergy(120, true) == 120 && restored.power().getEnergyStored() == 1_085_270,
                "Simulated FE output mutated the turbine");
        var above = restored.entity().getBlockPos().above();
        var sink = new EnergyStorage(5000, 2500, 0);
        SINKS.put(above, sink);
        try {
            level.setBlockAndUpdate(above, Blocks.RAW_GOLD_BLOCK.defaultBlockState());
            tick(test, restored);
            test.assertTrue(sink.getEnergyStored() == 2500 && restored.power().getEnergyStored() == 1_085_270 + 59 * 2647 - 2500,
                    "Steam turbine did not transfer exact accepted FE to the upper neighbor");
        } finally {
            SINKS.remove(above);
        }
        test.succeed();
    }

    private static Machine place(GameTestHelper test, BlockPos relative) {
        var key = ResourceLocation.fromNamespaceAndPath(ID, "steam_turbine");
        test.assertTrue(BuiltInRegistries.BLOCK.containsKey(key), "Missing standalone turbine block");
        test.setBlock(relative, BuiltInRegistries.BLOCK.get(key));
        var level = test.getLevel();
        var pos = test.absolutePos(relative);
        var chamber = level.getBlockEntity(pos);
        var tank = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, Direction.DOWN);
        var power = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.UP);
        test.assertTrue(chamber instanceof Turbine.Rotor && tank != null && power != null, "Missing standalone turbine tile and capabilities");
        return new Machine((Turbine.Rotor) chamber, tank, power);
    }

    @SuppressWarnings("unchecked")
    private static void tick(GameTestHelper test, Machine target) {
        var level = test.getLevel();
        var entity = target.entity();
        var pos = entity.getBlockPos();
        var state = level.getBlockState(pos);
        var ticker = ((EntityBlock) state.getBlock()).getTicker(level, state, (BlockEntityType<BlockEntity>) (BlockEntityType<?>) entity.getType());
        if (ticker == null) throw new IllegalStateException("No turbine ticker");
        ticker.tick(level, pos, state, entity);
    }

    private record Machine(Turbine.Rotor entity, IFluidHandler tank, IEnergyStorage power) {}
}
