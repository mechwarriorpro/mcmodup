package @PACKAGE@;

import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("@TEST_NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class BuildingChecks {
    private static final String ID = "icbm_glass_plate";
    private record Case(String name, String corner, String center, String edge, float hardness, float resistance) {}
    private static final Case[] CASES = {
        new Case("concrete", "minecraft:sand", "minecraft:water_bucket", "minecraft:gravel", 3.8F, 30F),
        new Case("compact_concrete", ID + ":concrete", ID + ":concrete", "minecraft:obsidian", 3.8F, 38F),
        new Case("reinforced_concrete", ID + ":compact_concrete", ID + ":compact_concrete", "minecraft:iron_ingot", 3.8F, 48F),
        new Case("reinforced_glass", "minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft:glass", 0.6F, 28.8F),
    };

    @GameTest(template = "platform_room")
    public static void measuredBuildingBlocks(GameTestHelper test) {
        var level = test.getLevel();
        var local = new BlockPos(2, 1, 2);
        var pos = test.absolutePos(local);
        for (var row : CASES) {
            var id = ResourceLocation.fromNamespaceAndPath(ID, row.name());
            test.assertTrue(BuiltInRegistries.BLOCK.containsKey(id), "Missing block " + id);
            var block = BuiltInRegistries.BLOCK.get(id);
            var items = new ArrayList<ItemStack>();
            for (int slot = 0; slot < 9; ++slot) {
                var material = ResourceLocation.parse(slot == 4 ? row.center() : slot % 2 == 0 ? row.corner() : row.edge());
                items.add(new ItemStack(BuiltInRegistries.ITEM.get(material)));
            }
            var grid = CraftingInput.of(3, 3, items);
            var match = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level).orElseThrow();
            var result = match.value().assemble(grid, level.registryAccess());
            test.assertTrue(match.id().equals(id) && result.is(block.asItem()) && result.getCount() == 8, "Wrong craft for " + id);
            if (row.name().equals("concrete")) {
                test.assertTrue(match.value().getRemainingItems(grid).get(4).is(Items.BUCKET), "Concrete lost the empty bucket");
            }
            test.setBlock(local, block);
            var state = level.getBlockState(pos);
            test.assertTrue(state.getDestroySpeed(level, pos) == row.hardness() && block.getExplosionResistance() == row.resistance()
                && level.getBlockEntity(pos) == null, "Wrong building properties at " + id);
            var drops = Block.getDrops(state, level, pos, null, null, new ItemStack(Items.IRON_PICKAXE));
            test.assertTrue(drops.size() == 1 && drops.getFirst().is(block.asItem()) && drops.getFirst().getCount() == 1,
                "Wrong drop for " + id + ": " + drops);
            if (row.name().equals("reinforced_glass")) {
                test.assertTrue(state.skipRendering(state, Direction.EAST) && !state.canOcclude(), "Reinforced glass should cull its interior faces, not light");
            }
        }
        test.succeed();
    }
}
