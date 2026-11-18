package mcmodup.thaumcraftcheck;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import thaumcraft.api.ItemApi;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.research.ResearchCategories;
import thaumcraft.api.wands.WandHelper;

/** Uses the wand's normal right-click dispatcher, not a direct call to the integration. */
@GameTestHolder(ThaumcraftPackSmoke.ID)
@PrefixGameTestTemplate(false)
public final class EnderRiftPackSmoke {
    private static final ResourceLocation FOCUS = ResourceLocation.parse("thaumictinkerer:focus_ender_chest");

    @GameTest(template = "test_room")
    public static void unlinkedEnderRiftOpensVanillaAndUsesTheRightResearchPage(GameTestHelper test) {
        String page = ModList.get().isLoaded("enderstorage") ? "ES" : "0";
        var entry = ResearchCategories.getResearch("FOCUS_ENDER_CHEST");
        test.assertTrue(entry != null && entry.getPages()[0].text.equals("ttresearch.page.FOCUS_ENDER_CHEST." + page),
                "Ender Rift must select the research text for the current pack");
        withPlayer(test, actor -> {
            var wand = wand(test);
            fill(wand);
            assertVanilla(test, actor, wand);
        });
        test.succeed();
    }

    static void withPlayer(GameTestHelper test, Consumer<ServerPlayer> run) {
        var level = test.getLevel();
        var players = level.getServer().getPlayerList();
        var player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "RiftFixture"), ClientInformation.createDefault());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        try {
            NetworkRegistry.configureMockConnection(connection);
            players.placeNewPlayer(connection, player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
            player.setGameMode(GameType.SURVIVAL);
            var at = test.absoluteVec(new Vec3(2.5, 1.0, 3.5));
            player.moveTo(at.x, at.y, at.z, -90F, 0F);
            player.setYHeadRot(-90F);
            run.accept(player);
        } finally {
            if (players.getPlayer(player.getUUID()) == player) {
                player.closeContainer();
                players.remove(player);
            }
            player.discard();
            channel.finishAndReleaseAll();
        }
    }

    static ItemStack wand(GameTestHelper test) {
        var wand = ItemApi.getItem("itemWandCasting", 0);
        var focus = new ItemStack(BuiltInRegistries.ITEM.get(FOCUS));
        CustomData.update(DataComponents.CUSTOM_DATA, focus, tag -> tag.putString("fixture", "keep"));
        test.assertTrue(WandHelper.setFocus(wand, focus), "mounted an actual Ender Rift");
        return wand;
    }

    static void fill(ItemStack wand) {
        for (var aspect : Aspect.getPrimalAspects()) WandHelper.addRealVis(wand, aspect, 1000, true);
    }

    static void cast(ServerPlayer actor, ItemStack wand, boolean sneak) {
        actor.setShiftKeyDown(sneak);
        actor.setItemInHand(InteractionHand.MAIN_HAND, wand);
        wand.getItem().use(actor.serverLevel(), actor, InteractionHand.MAIN_HAND);
    }

    static void assertSpend(GameTestHelper test, ItemStack wand, ServerPlayer actor, AspectList before, boolean cast) {
        for (var aspect : Aspect.getPrimalAspects()) {
            boolean required = cast && (aspect.equals(Aspect.ORDER) || aspect.equals(Aspect.ENTROPY));
            int cost = required ? (int) (100 * WandHelper.getConsumptionModifier(wand, actor, aspect, false)) : 0;
            test.assertTrue(before.getAmount(aspect) - WandHelper.getVis(wand, aspect) == cost, "Ender Rift vis for " + aspect.getTag());
        }
    }

    static void assertVanilla(GameTestHelper test, ServerPlayer actor, ItemStack wand) {
        actor.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, 2));
        var before = WandHelper.getAllVis(wand);
        cast(actor, wand, false);
        test.assertTrue(actor.containerMenu instanceof ChestMenu menu && menu.getContainer() == actor.getEnderChestInventory()
                && menu.getSlot(0).getItem().is(Items.EMERALD), "the unlinked focus opens the player's vanilla inventory");
        assertSpend(test, wand, actor, before, true);
        actor.closeContainer();
    }
}
