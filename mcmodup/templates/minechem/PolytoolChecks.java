package @PACKAGE@;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Also compiled as a separate test mod against the staged production JAR. */
@GameTestHolder("@TEST_NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class @CLASS@ {
    private static final boolean RELEASE_394 = @RELEASE_394@;
    private static final String ID = "@NAMESPACE@";
    private static final int INPUT = 0, HELD = 1, SECOND_HOTBAR = 2, PLAYER_MAIN = 10;
    private static final Block[] SAMPLES = {Blocks.COBWEB, Blocks.COAL_ORE, Blocks.STONE, Blocks.OAK_LOG, Blocks.DIRT,
        Blocks.COBBLESTONE, Blocks.DIAMOND_ORE, Blocks.OAK_PLANKS, Blocks.SAND, Blocks.OBSIDIAN, Blocks.OAK_LEAVES, Blocks.GLASS};
    private static final String[] CASES = {
@CASES@
    };

    @GameTest(template = "test_room")
    public static void @PREFIX@PolytoolInfusion(GameTestHelper test) {
        withPlayer(test, actor -> {
            var tool = item("polytool", 1);
            var pristine = tool.copy();
            var menu = open(test, actor, tool);
            test.assertTrue(menu.slots.size() == 37 && menu.getSlot(INPUT).x == 8 && menu.getSlot(INPUT).y == 17 &&
                menu.getSlot(HELD).y == 194 && menu.getSlot(PLAYER_MAIN).y == 136,
                "Expected the original infusion, hotbar and main inventory slots");
            reject(test, actor, menu, item("element_al", 63), 0);
            reject(test, actor, menu, item("element_al", 65), 0);
            reject(test, actor, menu, item("element_al", 64), 1);
            reject(test, actor, menu, item("element_cu", 64), 0);
            reject(test, actor, menu, item("molecule_water", 64), 0);
            reject(test, actor, menu, new ItemStack(Items.DIAMOND, 64), 0);
            String other = RELEASE_394 ? "minechem_chemistry" : "minechem_chemistry_394";
            var foreign = ResourceLocation.fromNamespaceAndPath(other, "element_al");
            if (BuiltInRegistries.ITEM.containsKey(foreign)) reject(test, actor, menu, new ItemStack(BuiltInRegistries.ITEM.get(foreign), 64), 0);
            test.assertTrue(ItemStack.matches(tool, pristine), "Invalid click modified the held tool");
            infuse(test, actor, menu, item("element_al", 64));
            test.assertTrue(tool.getDestroySpeed(Blocks.OAK_LOG.defaultBlockState()) == 16 && tool.getDestroySpeed(Blocks.COAL_ORE.defaultBlockState()) == 4,
                "Full stack did not apply the observed aluminium trade-off");
            var before = tool.copy();
            reject(test, actor, menu, item("element_al", 64), 0);
            reject(test, actor, menu, item("element_ga", 64), 0);
            test.assertTrue(ItemStack.matches(tool, before), "A non-positive alloy changed the tool");
            menu.setCarried(ItemStack.EMPTY);
            // .394 pays a stack from inventory via quick move; FINAL never does.
            menu.getSlot(SECOND_HOTBAR).set(item("element_o", 64));
            menu.clicked(SECOND_HOTBAR, 0, ClickType.QUICK_MOVE, actor);
            test.assertTrue(menu.getSlot(SECOND_HOTBAR).getItem().getCount() == (RELEASE_394 ? 0 : 64) &&
                            (RELEASE_394 != ItemStack.matches(tool, before)), "Wrong release-specific shift-click outcome");
            menu.getSlot(SECOND_HOTBAR).set(item("element_al", 64));
            menu.clicked(SECOND_HOTBAR, 0, ClickType.QUICK_MOVE, actor);
            test.assertTrue(menu.getSlot(SECOND_HOTBAR).getItem().getCount() == 64, "Rejected shift-infusion lost its stack");
            menu.clicked(HELD, 0, ClickType.PICKUP, actor);
            menu.clicked(PLAYER_MAIN, 0, ClickType.SWAP, actor);
            menu.clicked(HELD, 1, ClickType.THROW, actor);
            test.assertTrue(actor.getMainHandItem() == tool && menu.getCarried().isEmpty(), "Bound item could be picked up, swapped or dropped");
            actor.getInventory().selected = 1;
            reject(test, actor, menu, item("element_o", 64), 0);
            test.assertTrue(!menu.stillValid(actor), "Selection change did not invalidate the server menu");
            actor.getInventory().selected = 0;
            var replacement = item("polytool", 1);
            actor.setItemInHand(InteractionHand.MAIN_HAND, replacement);
            reject(test, actor, menu, item("element_o", 64), 0);
            test.assertTrue(!menu.stillValid(actor) && ItemStack.matches(replacement, pristine), "Stale menu updated a replacement Polytool");
            menu.setCarried(ItemStack.EMPTY);
        });
        test.succeed();
    }

    @GameTest(template = "test_room")
    public static void @PREFIX@PolytoolAlloysAndSave(GameTestHelper test) {
        withPlayer(test, actor -> {
            var plain = item("polytool", 1);
            test.assertTrue(plain.getMaxStackSize() == 1 && attack(plain) == (RELEASE_394 ? 8 : 7) &&
                plain.isCorrectToolForDrops(Blocks.DIAMOND_ORE.defaultBlockState()) &&
                !plain.isCorrectToolForDrops(Blocks.OBSIDIAN.defaultBlockState()), "Wrong native base damage or iron harvest tier");
            test.assertTrue(CASES.length == (RELEASE_394 ? 48 : 49), "Incomplete trace coverage");
            for (String row : CASES) {
                var data = row.split("\\|");
                var tube = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(data[0])), 64);
                var tool = plain.copy();
                var menu = open(test, actor, tool);
                infuse(test, actor, menu, tube);
                test.assertTrue(!ItemStack.matches(tool, plain), "Missing applied rank for " + data[0]);
                var expected = data[1].split(",");
                for (int i = 0; i < SAMPLES.length; i++) {
                    int speed = Integer.parseInt(expected[i]);
                    test.assertTrue(tool.getDestroySpeed(SAMPLES[i].defaultBlockState()) == speed,
                        "Wrong single-rank speed: " + data[0] + " on " + SAMPLES[i] + " expected " + speed);
                }
            }
            var tool = plain.copy();
            var menu = open(test, actor, tool);
            for (int i = 0; i < 3; i++) infuse(test, actor, menu, item("element_v", 64));
            reject(test, actor, menu, item("element_v", 64), 0);
            menu.setCarried(ItemStack.EMPTY);
            var saved = load(test, tool);
            test.assertTrue(ItemStack.matches(tool, saved) && !ItemStack.matches(plain, saved) &&
                saved.getDestroySpeed(Blocks.OAK_LOG.defaultBlockState()) == 32 && saved.getDestroySpeed(Blocks.DIRT.defaultBlockState()) == 2 &&
                attack(saved) == (RELEASE_394 ? 2 : 7), "Repeated ranks, stat floor or save round trip failed");
            infuse(test, actor, menu, item("element_o", 64));
            test.assertTrue(!ItemStack.matches(tool, saved), "A saved/copied tool shares its ranks with the mutated source");
            var swordTrade = plain.copy();
            menu = open(test, actor, swordTrade);
            infuse(test, actor, menu, item("element_sc", 64));
            infuse(test, actor, menu, item("element_y", 64));
            if (RELEASE_394) reject(test, actor, menu, item("element_sc", 64), 0);
            else infuse(test, actor, menu, item("element_sc", 64));
            test.assertTrue(swordTrade.getDestroySpeed(Blocks.STONE.defaultBlockState()) == (RELEASE_394 ? 12 : 8) &&
                attack(swordTrade) == (RELEASE_394 ? 4 : 7), "Wrong sword-vs-cobweb validation difference");
            menu.setCarried(ItemStack.EMPTY);
            var r = plain.copy();
            infuse(test, actor, open(test, actor, r), item("element_rh", 64));
            test.assertTrue(attack(r) == (RELEASE_394 ? 16 : 7) && r.getDestroySpeed(Blocks.COBWEB.defaultBlockState()) == 8,
                "The .394 blade stat and original cobweb digging should not be conflated");
        });
        test.succeed();
    }

    @GameTest(template = "test_room")
    public static void @PREFIX@PolytoolMiningAndCharge(GameTestHelper test) {
        withPlayer(test, actor -> {
            var tool = item("polytool", 1);
            var menu = open(test, actor, tool);
            infuse(test, actor, menu, item("element_fe", 64));
            infuse(test, actor, menu, item("element_s", 64));
            for (int i = 0; i < 2; i++) infuse(test, actor, menu, item("element_o", 64));
            actor.closeContainer();
            var level = test.getLevel();
            var coal = test.absolutePos(new BlockPos(1, 2, 2));
            for (int i = 0; i < 3; i++) level.setBlockAndUpdate(coal.east(i), Blocks.COAL_ORE.defaultBlockState());
            level.setBlockAndUpdate(coal.south(), Blocks.IRON_ORE.defaultBlockState());
            actor.setAirSupply(100);
            test.assertTrue(actor.gameMode.destroyBlock(coal) && level.isEmptyBlock(coal) && level.isEmptyBlock(coal.east()) &&
                level.getBlockState(coal.east(2)).is(Blocks.COAL_ORE) && level.getBlockState(coal.south()).is(Blocks.IRON_ORE),
                "Iron rank one must mine one adjacent matching ore, not the entire vein or a different ore");
            test.assertTrue(actor.getAirSupply() == 102 && tool.getDamageValue() == 0, "Oxygen was not applied exactly once or mining wore down the tool");
            var pig = EntityType.PIG.create(level);
            tool.hurtEnemy(pig, actor);
            test.assertTrue(pig.isOnFire() && tool.getDamageValue() == 0, "Sulfur did not ignite a melee target or changed durability");
            pig.discard();
            test.assertTrue(tool.getCapability(Capabilities.EnergyStorage.ITEM) == null, "Plain/non-Lithium upgrade exposed FE storage");
            menu = open(test, actor, tool);
            if (RELEASE_394) {
                reject(test, actor, menu, item("element_li", 64), 0);
                test.assertTrue(tool.getCapability(Capabilities.EnergyStorage.ITEM) == null, ".394 gained FINAL Lithium charging");
                menu.setCarried(ItemStack.EMPTY);
            } else {
                for (int i = 0; i < 2; i++) infuse(test, actor, menu, item("element_li", 64));
                var charge = tool.getCapability(Capabilities.EnergyStorage.ITEM);
                test.assertTrue(charge != null && charge.canReceive() && !charge.canExtract() && charge.getMaxEnergyStored() == Integer.MAX_VALUE,
                    "Wrong FINAL Lithium FE adapter");
                test.assertTrue(charge.receiveEnergy(100, true) == 100 && charge.getEnergyStored() == 0 &&
                    charge.receiveEnergy(100, false) == 100 && charge.getEnergyStored() == 200 && charge.extractEnergy(100, false) == 0,
                    "Simulated, paid or rank-scaled recharge wrong");
                test.assertTrue(charge.receiveEnergy(5_000_000, false) == 5_000_000 && charge.getEnergyStored() == 10_000_200 && attack(tool) == 8,
                    "Charge crossed the observed logarithmic threshold without a melee bonus");
                var loaded = load(test, tool);
                var restored = loaded.getCapability(Capabilities.EnergyStorage.ITEM);
                test.assertTrue(restored != null && restored.getEnergyStored() == 10_000_200 && attack(loaded) == 8,
                    "Charge or melee bonus failed to survive a reload");
                int remaining = (int) ((Integer.MAX_VALUE - 10_000_200L + 1) / 2);
                test.assertTrue(restored.receiveEnergy(Integer.MAX_VALUE, false) == remaining && restored.getEnergyStored() == Integer.MAX_VALUE &&
                    restored.receiveEnergy(1, false) == 0 && charge.getEnergyStored() == 10_000_200, "Charge overflow, overpayment or shared state");
            }
        });
        test.succeed();
    }

    private static ItemStack item(String name, int count) {
        var id = ResourceLocation.fromNamespaceAndPath(ID, name);
        if (!BuiltInRegistries.ITEM.containsKey(id)) throw new AssertionError("Missing " + id);
        return new ItemStack(BuiltInRegistries.ITEM.get(id), count);
    }

    private static double attack(ItemStack tool) {
        return tool.getAttributeModifiers().modifiers().stream().filter(entry -> entry.attribute().equals(Attributes.ATTACK_DAMAGE))
            .mapToDouble(entry -> entry.modifier().amount()).sum();
    }

    private static ItemStack load(GameTestHelper test, ItemStack tool) {
        var lookup = test.getLevel().registryAccess();
        return ItemStack.parse(lookup, tool.save(lookup)).orElseThrow();
    }

    private static AbstractContainerMenu open(GameTestHelper test, ServerPlayer player, ItemStack tool) {
        if (player.containerMenu != player.inventoryMenu) player.closeContainer();
        player.getInventory().selected = 0;
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        tool.getItem().use(player.serverLevel(), player, InteractionHand.MAIN_HAND);
        var menu = player.containerMenu;
        test.assertTrue(menu != player.inventoryMenu && menu.getType() == BuiltInRegistries.MENU.get(ResourceLocation.fromNamespaceAndPath(ID, "polytool")),
            "Using the held Polytool did not open its registered menu");
        return menu;
    }

    private static void infuse(GameTestHelper test, ServerPlayer actor, AbstractContainerMenu menu, ItemStack tube) {
        menu.setCarried(tube);
        menu.clicked(INPUT, 0, ClickType.PICKUP, actor);
        test.assertTrue(menu.getCarried().isEmpty() && !menu.getSlot(INPUT).hasItem(), "Full supported stack was not consumed: " + tube);
    }

    private static void reject(GameTestHelper test, ServerPlayer actor, AbstractContainerMenu menu, ItemStack tube, int button) {
        var before = tube.copy();
        menu.setCarried(tube);
        menu.clicked(INPUT, button, ClickType.PICKUP, actor);
        test.assertTrue(ItemStack.matches(menu.getCarried(), before) && !menu.getSlot(INPUT).hasItem(), "Rejected click consumed or stored tubes: " + before);
    }

    private static void withPlayer(GameTestHelper test, Consumer<ServerPlayer> action) {
        var level = test.getLevel();
        var players = level.getServer().getPlayerList();
        var profile = new GameProfile(UUID.randomUUID(), RELEASE_394 ? "Poly394Test" : "PolyFinalTest");
        var player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        try {
            NetworkRegistry.configureMockConnection(connection);
            players.placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
            player.setGameMode(GameType.SURVIVAL);
            var pos = test.absolutePos(new BlockPos(2, 1, 1));
            player.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
            action.accept(player);
        } finally {
            if (players.getPlayer(player.getUUID()) == player) {
                player.closeContainer();
                players.remove(player);
            }
            player.discard();
            channel.finishAndReleaseAll();
        }
    }
}
