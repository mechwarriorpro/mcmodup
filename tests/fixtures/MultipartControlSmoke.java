package mcmodup.multipartcheck;

import codechicken.microblock.item.MicroMaterialComponent;
import codechicken.microblock.part.MicroblockPart;
import codechicken.multipart.block.TileMultipart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@Mod(MultipartControlSmoke.ID)
@GameTestHolder(MultipartControlSmoke.ID)
@PrefixGameTestTemplate(false)
public final class MultipartControlSmoke {
    public static final String ID = "mcmodup_multipart_control";

    private static ResourceLocation microblockId(String path) {
        return ResourceLocation.fromNamespaceAndPath("cb_microblock", path);
    }

    @GameTest(template = "empty")
    public static void sawCraftAndSaveMultipart(GameTestHelper test) {
        var level = test.getLevel();
        var registries = level.registryAccess();
        var holder = level.getRecipeManager().byKey(microblockId("microblock")).orElseThrow();
        var craft = (CraftingRecipe) holder.value();
        var sawId = microblockId("diamond_saw");
        test.assertTrue(BuiltInRegistries.ITEM.containsKey(sawId), "Native saw was not registered");
        var saw = new ItemStack(BuiltInRegistries.ITEM.get(sawId));
        var input = CraftingInput.of(2, 1, List.of(saw, new ItemStack(Blocks.STONE)));
        test.assertTrue(craft.matches(input, level), "Native saw couldn't cut stone");
        test.assertTrue(!craft.matches(CraftingInput.of(2, 1, List.of(new ItemStack(Items.STICK), new ItemStack(Blocks.STONE))), level),
                "The recipe accepted a non-saw");
        var cut = craft.assemble(input, registries);
        var component = MicroMaterialComponent.getComponent(cut);
        test.assertTrue(cut.is(BuiltInRegistries.ITEM.get(microblockId("microblock"))) && cut.getCount() == 2 && component != null,
                "The recipe did not produce two covers with the native component");
        test.assertTrue(component.size() == 4 && component.material().getItem().is(Items.STONE) && component.factory() != null,
                "Cut lost the cover thickness, material or factory");
        var savedStack = ItemStack.parseOptional(registries, (CompoundTag) cut.save(registries));
        test.assertTrue(ItemStack.isSameItemSameComponents(cut, savedStack) && cut.getCount() == savedStack.getCount(),
                "The native material component did not round-trip");

        // Test the server placement API (not clicking, networking or rendering).
        BlockPos pos = test.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        var first = component.factory().create(false, component.material());
        var second = component.factory().create(false, component.material());
        first.setShape(component.size(), Direction.DOWN.get3DDataValue());
        second.setShape(component.size(), Direction.UP.get3DDataValue());
        test.assertTrue(TileMultipart.isUnobstructed(level, pos, first), "Cover couldn't occupy test space");
        var tile = TileMultipart.addPart(level, pos, first);
        var duplicate = component.factory().create(false, component.material());
        duplicate.setShape(component.size(), Direction.DOWN.get3DDataValue());
        test.assertTrue(!tile.canAddPart(duplicate) && tile.canAddPart(second), "Multipart did not enforce slot occupancy");
        tile = TileMultipart.addPart(level, pos, second);
        test.assertTrue(level.getBlockEntity(pos) == tile && tile.getPartList().size() == 2, "Multipart didn't keep both covers");

        var restored = TileMultipart.fromNBT(tile.saveWithFullMetadata(registries), pos, registries);
        test.assertTrue(restored != null && restored.getPartList().size() == 2, "Saved tile didn't restore both parts");
        for (var direction : List.of(Direction.DOWN, Direction.UP)) {
            var expected = direction.get3DDataValue();
            test.assertTrue(restored.getPartList().stream().anyMatch(part -> part instanceof MicroblockPart cover &&
                            cover.getShapeSlot() == expected && cover.getSize() == 4 && cover.getMaterial() == component.material() &&
                            cover.getShape(CollisionContext.empty()).bounds().getYsize() == 0.5),
                    "Restored cover lost geometry, position or material for " + direction);
        }
        test.succeed();
    }
}
