package mcmodup.test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@Mod(ConnectorSmoke.ID)
@GameTestHolder(ConnectorSmoke.ID)
@PrefixGameTestTemplate(false)
public final class ConnectorSmoke {
    public static final String ID = "mcmodup_connector_smoke";

    @GameTest(template = "empty")
    public static void originalThaumcraftRegisters(GameTestHelper helper) {
        helper.assertTrue(ModList.get().isLoaded("legacyconnector"), "Connector runtime did not load");
        var mod = ModList.get().getModContainerById("thaumcraft");
        helper.assertTrue(mod.isPresent(), "The original Thaumcraft mod was not loaded");
        helper.assertTrue(mod.orElseThrow().getModInfo().getVersion().toString().equals("4.2.3.5"), "Unexpected Thaumcraft build");
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse("thaumcraft:itemthaumonomicon")),
            "Original Thaumonomicon did not register");
        helper.assertTrue(BuiltInRegistries.ENTITY_TYPE.keySet().stream().anyMatch(id -> id.getNamespace().equals("thaumcraft")),
            "None of the original entity types registered");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void crucibleAcceptsWater(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(BlockPos.ZERO);
        var id = ResourceLocation.parse("thaumcraft:blockmetaldevice");
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(id), "Thaumcraft metal device block not registered");
        var block = BuiltInRegistries.BLOCK.get(id);
        level.setBlockAndUpdate(pos, block.defaultBlockState());
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(level.getBlockEntity(pos) != null, "Crucible has no block entity");
            var tank = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
            helper.assertTrue(tank != null, "Crucible did not expose a fluid handler");
            var water = new FluidStack(Fluids.WATER, 1000);
            helper.assertTrue(tank.fill(water, FluidAction.EXECUTE) == 1000, "Crucible did not accept a bucket of water");
            helper.assertTrue(tank.getTanks() > 0 && tank.getFluidInTank(0).getAmount() == 1000,
                "Crucible did not retain the water");
            helper.succeed();
        });
    }
}
