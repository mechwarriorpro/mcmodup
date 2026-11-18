package mcmodup.icbmplate;

import java.io.File;
import java.util.List;
import javax.xml.parsers.ParserConfigurationException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.JUnitLikeTestReporter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("@TEST_NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class PlateChecks {
    private static final ResourceLocation PLATE = ResourceLocation.parse("icbm_glass_plate:glass_pressure_plate");

    static {
        if (System.getProperty("mcmodup.gameReport") != null) {
            try {
                GlobalTestReporter.replaceWith(new JUnitLikeTestReporter(new File(System.getProperty("mcmodup.gameReport"))));
            } catch (ParserConfigurationException error) {
                throw new IllegalStateException(error);
            }
        }
    }

    @GameTest(template = "empty")
    public static void craftingAndDrop(GameTestHelper test) {
        var level = test.getLevel();
        var recipe = (CraftingRecipe) level.getRecipeManager().byKey(PLATE).orElseThrow().value();
        var glass = new ItemStack(Blocks.GLASS);
        var valid = CraftingInput.of(2, 1, List.of(glass.copy(), glass.copy()));
        test.assertTrue(recipe.matches(valid, level), "Two horizontal glass blocks should craft the plate");
        var output = recipe.assemble(valid, level.registryAccess());
        test.assertTrue(output.is(BuiltInRegistries.ITEM.get(PLATE)) && output.getCount() == 1, "Wrong glass plate output");
        test.assertTrue(!recipe.matches(CraftingInput.of(1, 2, List.of(glass.copy(), glass.copy())), level), "Accepted vertical glass");
        test.assertTrue(!recipe.matches(CraftingInput.of(2, 1, List.of(glass, new ItemStack(Blocks.GLASS_PANE))), level), "Accepted a pane");
        var pos = place(test);
        var state = level.getBlockState(pos);
        test.assertTrue(state.getPistonPushReaction() == PushReaction.DESTROY && state.getCollisionShape(level, pos).isEmpty(),
                "The plate must be breakable by pistons and not obstruct entities");
        test.assertTrue(Block.getDrops(state, level, pos, null).isEmpty(), "The original plate must not drop itself");
        test.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void contactAndRelease(GameTestHelper test) {
        var level = test.getLevel();
        var block = BuiltInRegistries.BLOCK.get(PLATE);
        var pos = place(test);
        var outside = new ItemEntity(level, pos.getX() - 0.25, pos.getY() + 0.05, pos.getZ() + 0.5, new ItemStack(Items.STICK));
        outside.setNoGravity(true);
        level.addFreshEntity(outside);
        level.getBlockState(pos).entityInside(level, pos, outside);
        verifySignal(test, pos, 0);
        outside.discard();

        var inside = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.05, pos.getZ() + 0.5, new ItemStack(Items.STICK));
        inside.setNoGravity(true);
        inside.setDeltaMovement(0, 0, 0);
        level.addFreshEntity(inside);
        level.getBlockState(pos).entityInside(level, pos, inside);
        verifySignal(test, pos, 15);
        test.assertTrue(level.getBlockTicks().hasScheduledTick(pos, block), "Pressed plate did not schedule a check");
        test.runAfterDelay(11, () -> {
            verifySignal(test, pos, 15);
            test.assertTrue(level.getBlockTicks().hasScheduledTick(pos, block), "Still-occupied plate stopped scheduling checks");
            inside.discard();
        });
        test.runAfterDelay(18, () -> verifySignal(test, pos, 15));
        test.runAfterDelay(21, () -> {
            verifySignal(test, pos, 0);
            test.assertTrue(!level.getBlockTicks().hasScheduledTick(pos, block), "Unpowered plate kept scheduling checks");
            test.succeed();
        });
    }

    private static BlockPos place(GameTestHelper test) {
        var level = test.getLevel();
        var pos = test.absolutePos(new BlockPos(1, 1, 1));
        test.assertTrue(BuiltInRegistries.BLOCK.containsKey(PLATE), "Glass pressure plate was not registered");
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.get(PLATE).defaultBlockState());
        return pos;
    }

    private static void verifySignal(GameTestHelper test, BlockPos pos, int power) {
        var level = test.getLevel();
        var state = level.getBlockState(pos);
        test.assertTrue(state.getValue(PressurePlateBlock.POWERED) == (power > 0), "Wrong plate state, expected power " + power);
        for (var side : Direction.values()) {
            test.assertTrue(state.getSignal(level, pos, side) == power, "Wrong weak signal for " + side);
            test.assertTrue(state.getDirectSignal(level, pos, side) == (side == Direction.UP ? power : 0),
                    "Wrong direct signal for " + side);
        }
    }
}
