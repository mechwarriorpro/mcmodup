package @PACKAGE@;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("@TEST_NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class @CLASS@ {
    @GameTest(template = "test_room")
    public static void journalCloningPreservesComponents(GameTestHelper test) {
        var id = ResourceLocation.fromNamespaceAndPath("@NAMESPACE@", "chemist_journal");
        test.assertTrue(BuiltInRegistries.ITEM.containsKey(id), "Missing release-specific journal");
        var source = new ItemStack(BuiltInRegistries.ITEM.get(id));
        var journal = new CompoundTag();
        var discoveries = new ListTag();
        discoveries.add(StringTag.valueOf("minecraft:iron_ingot"));
        journal.put("Discoveries", discoveries);
        journal.putString("Active", "minecraft:iron_ingot");
        journal.putString("Owner", "Crafter");
        var data = new CompoundTag();
        data.put("ChemistJournal", journal);
        source.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        source.set(DataComponents.CUSTOM_NAME, Component.literal("Iron notes"));
        var level = test.getLevel();
        var recipes = level.getRecipeManager();
        var book = new ItemStack(Items.BOOK);
        var input = CraftingInput.of(2, 1, List.of(source, book));
        var match = recipes.getRecipeFor(RecipeType.CRAFTING, input, level).orElseThrow();
        test.assertTrue(match.id().equals(ResourceLocation.fromNamespaceAndPath("@NAMESPACE@", "journal_clone")),
            "Another release's recipe captured this journal: " + match.id());
        var copies = match.value().assemble(input, level.registryAccess());
        test.assertTrue(source.getCount() == 1 && copies.getCount() == 2 && copies.getMaxStackSize() == 64 &&
            ItemStack.isSameItemSameComponents(source, copies), "The registered recipe lost journal components or changed the source stack");
        var loaded = ItemStack.parseOptional(level.registryAccess(), (CompoundTag) copies.save(level.registryAccess()));
        test.assertTrue(ItemStack.matches(copies, loaded), "The resulting stack lost its custom data on serialization");
        CustomData.update(DataComponents.CUSTOM_DATA, copies, tag -> tag.remove("ChemistJournal"));
        test.assertTrue(source.has(DataComponents.CUSTOM_DATA) && !ItemStack.isSameItemSameComponents(source, copies),
            "Editing the copies changed the input journal");
        test.assertTrue(!match.value().matches(CraftingInput.of(2, 1, List.of(book, source)), level) &&
            !match.value().matches(CraftingInput.of(3, 1, List.of(source, book, new ItemStack(Items.STONE))), level),
            "Cloning should not accept reversed order or consume unrelated items");
        test.succeed();
    }
}
