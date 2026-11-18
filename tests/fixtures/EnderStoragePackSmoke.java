package mcmodup.thaumcraftcheck;

import codechicken.enderstorage.api.Frequency;
import codechicken.enderstorage.container.ContainerEnderItemStorage;
import codechicken.enderstorage.init.EnderStorageModContent;
import codechicken.enderstorage.manager.EnderStorageManager;
import codechicken.enderstorage.storage.EnderItemStorage;
import codechicken.enderstorage.tile.TileEnderChest;
import codechicken.lib.colour.EnumColour;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import thaumcraft.api.wands.WandHelper;

import static mcmodup.thaumcraftcheck.EnderRiftPackSmoke.*;

@GameTestHolder(ThaumcraftPackSmoke.ID)
@PrefixGameTestTemplate(false)
public final class EnderStoragePackSmoke {
    @GameTest(template = "test_room")
    public static void enderRiftLinksAnOwnerFrequencyAndResetsForFree(GameTestHelper test) {
        var pos = new BlockPos(4, 2, 3);
        test.setBlock(pos, EnderStorageModContent.ENDER_CHEST_BLOCK.get());
        var chest = (TileEnderChest) test.getBlockEntity(pos);
        withPlayer(test, actor -> {
            var color = new Frequency(EnumColour.RED, EnumColour.BLUE, EnumColour.GREEN);
            var linked = color.withOwner(actor);
            chest.setFreq(linked);
            var privateStorage = chest.getStorage();
            privateStorage.setItem(0, new ItemStack(Items.DIAMOND, 7));
            var sharedStorage = EnderStorageManager.instance(false).getStorage(color, EnderItemStorage.TYPE);
            test.assertTrue(sharedStorage != privateStorage, "owner UUID distinguishes the two inventories at the same colour");
            sharedStorage.setItem(0, new ItemStack(Items.STICK));

            var empty = wand(test);
            var before = WandHelper.getAllVis(empty);
            cast(actor, empty, true);
            test.assertTrue(actor.containerMenu == actor.inventoryMenu, "sneak-cast binds a real chest without opening a menu");
            assertSpend(test, empty, actor, before, false);
            var focus = WandHelper.getFocusItem(empty);
            var lines = new ArrayList<Component>();
            focus.getItem().appendHoverText(focus, Item.TooltipContext.EMPTY, lines, TooltipFlag.NORMAL);
            test.assertTrue(lines.contains(actor.getName()), "the focus tooltip gives the linked owner");
            test.assertTrue(WandHelper.getFocus(empty).getSortingHelper(focus).endsWith(actor.getName().getString()),
                    "the sorting helper includes the linked owner");

            // Keep the saved link, not a reference to the live chest, across a real ItemStack round trip.
            var registries = test.getLevel().registryAccess();
            var reloaded = ItemStack.parse(registries, empty.save(registries)).orElseThrow();
            chest.setFreq(color);
            test.setBlock(pos, Blocks.AIR);
            cast(actor, reloaded, false);
            test.assertTrue(actor.containerMenu == actor.inventoryMenu, "a linked cast without vis opens nothing");
            fill(reloaded);
            var charged = WandHelper.getAllVis(reloaded);
            cast(actor, reloaded, false);
            test.assertTrue(actor.containerMenu instanceof ContainerEnderItemStorage menu && menu.chestInv == privateStorage
                    && menu.getSlot(0).getItem().is(Items.DIAMOND) && menu.getSlot(0).getItem().getCount() == 7,
                    "an unmounted chest's saved colour and owner open the correct remote inventory, not the shared one");
            assertSpend(test, reloaded, actor, charged, true);
            actor.closeContainer();

            test.setBlock(pos, Blocks.OBSIDIAN);
            var paid = WandHelper.getAllVis(reloaded);
            cast(actor, reloaded, true);
            assertSpend(test, reloaded, actor, paid, false);
            test.assertTrue(actor.containerMenu == actor.inventoryMenu, "obsidian unlinks without opening a menu");
            var cleared = WandHelper.getFocusItem(reloaded);
            test.assertTrue(WandHelper.getFocus(reloaded).getSortingHelper(cleared).equals("TTEC-VANILLA")
                    && cleared.get(DataComponents.CUSTOM_DATA).copyTag().getString("fixture").equals("keep"),
                    "only the link changed; other focus data stays");
            assertVanilla(test, actor, reloaded);
        });
        test.succeed();
    }
}
