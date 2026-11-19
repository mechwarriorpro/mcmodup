package @PACKAGE@;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
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
    private static final Map<String, String> MINING = Map.ofEntries(
@MINING_CASES@
    );
    // Adapt the vanilla ore items which became raw metals in modern Minecraft.
    private static final Map<Item, String> DROP_IDS = Map.of(Items.COBBLESTONE, "4:0", Items.RAW_GOLD, "14:0",
        Items.RAW_IRON, "15:0", Items.COAL_ORE, "16:0", Items.COAL, "263:0", Items.DIAMOND, "264:0", Items.GOLD_INGOT, "266:0");
    private static final List<String> WOOL_IDS = List.of("white_wool", "orange_wool", "magenta_wool", "light_blue_wool", "yellow_wool",
        "lime_wool", "pink_wool", "gray_wool", "light_gray_wool", "cyan_wool", "purple_wool", "blue_wool", "brown_wool", "green_wool", "red_wool", "black_wool");

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

    @GameTest(template = "test_room")
    public static void @PREFIX@PolytoolDrops(GameTestHelper test) {
        withPlayer(test, actor -> {
            tuned(test, actor);
            loot(test, actor, Blocks.COAL_ORE, "plain_coal", 1, false);
            loot(test, actor, Blocks.DIAMOND_ORE, "plain_diamond", 1, false);
            loot(test, actor, Blocks.GOLD_ORE, "plain_gold", 1, false);
            loot(test, actor, Blocks.BLACK_WOOL, "wool_plain", 1, false);
            silk(test, actor.getMainHandItem());
            loot(test, actor, Blocks.COAL_ORE, "silk_plain_coal", 1, false);
            tuned(test, actor, "c:2");
            loot(test, actor, Blocks.COAL_ORE, "carbon2_coal", 1, false);
            tuned(test, actor, "c:8");
            loot(test, actor, Blocks.DIAMOND_ORE, "carbon8_diamond", 512, false);
            tuned(test, actor, "c:64");
            loot(test, actor, Blocks.COAL_ORE, "carbon64_coal", 512, false);
            loot(test, actor, Blocks.STONE, "carbon64_stone", 1, false);
            tuned(test, actor, "br:1");
            loot(test, actor, Blocks.GOLD_ORE, "bromine1_gold", 1, false);
            loot(test, actor, Blocks.IRON_ORE, "bromine_iron", 1, false);
            silk(test, actor.getMainHandItem());
            loot(test, actor, Blocks.GOLD_ORE, "silk_bromine_gold", 1, false);
            tuned(test, actor, "br:64");
            loot(test, actor, Blocks.GOLD_ORE, "bromine64_gold", 512, false);
            tuned(test, actor, "cr:1");
            loot(test, actor, Blocks.BLACK_WOOL, "wool_chromium1", 512, false);
            tuned(test, actor, "cr:10");
            loot(test, actor, Blocks.BLACK_WOOL, "wool_chromium10", 512, false);
            tuned(test, actor, "fe:1");
            loot(test, actor, Blocks.COAL_ORE, "iron_only", 1, true);
            loot(test, actor, Blocks.DIAMOND_ORE, "iron_only_diamond", 1, true);
            tuned(test, actor, "fe:1", "br:1");
            loot(test, actor, Blocks.GOLD_ORE, "iron_bromine1", 1, true);
            tuned(test, actor, "fe:1", "c:1");
            loot(test, actor, Blocks.COAL_ORE, "iron_carbon1", 1, true);
            var combined = tuned(test, actor, "fe:1", "c:64");
            loot(test, actor, Blocks.COAL_ORE, "iron_carbon64", 512, true);
            silk(test, combined);
            actor.setItemInHand(InteractionHand.MAIN_HAND, load(test, combined));
            loot(test, actor, Blocks.COAL_ORE, "silk_iron_carbon64", 512, true);
        });
        test.succeed();
    }

    private static void loot(GameTestHelper test, ServerPlayer actor, Block ore, String key, int trials, boolean vein) {
        var level = test.getLevel();
        var pos = test.absolutePos(new BlockPos(1, 2, 2));
        var primary = new TreeSet<String>();
        var extras = new TreeSet<String>();
        for (int i = 0; i < trials; i++) {
            if (vein) for (int dx = 1; dx < 3; dx++) place(level, pos.east(dx), ore);
            breakIt(test, actor, pos, ore);
            test.assertTrue(!vein || (level.isEmptyBlock(pos.east()) && level.getBlockState(pos.east(2)).is(ore)),
                key + " did not break exactly one extra ore");
            var drops = collect(level, pos, vein);
            if (key.equals("wool_chromium1")) {
                test.assertTrue(drops[0].matches("\\{35:(?:[0-9]|1[0-5])=1}"), "Expected one wool: " + drops[0]);
                primary.add(drops[0].equals("{35:15=1}") ? "unchanged" : "recolored");
            } else primary.add(drops[0]);
            extras.add(drops[1]);
        }
        if (vein) place(level, pos.east(2), Blocks.AIR);
        var result = primary + (vein ? "/" + extras : "");
        test.assertTrue(MINING.get(key).equals(result), key + ": expected " + MINING.get(key) + ", got " + result);
    }

    @GameTest(template = "polytool_room", timeoutTicks = 150)
    public static void @PREFIX@PolytoolUtility(GameTestHelper test) {
        var level = test.getLevel();
        for (int x = 0; x < 10; x++) for (int y = 1; y <= 6; y++) for (int z = 0; z < 10; z++) {
            boolean shell = y == 1 || y == 6 || x == 0 || x == 9 || z == 0 || z == 9;
            place(level, test.absolutePos(new BlockPos(x, y, z)), shell ? Blocks.STONE : Blocks.AIR);
        }
        place(level, test.absolutePos(new BlockPos(1, 2, 2)), Blocks.GLOWSTONE);
        var bright = test.absolutePos(new BlockPos(4, 2, 2));
        var dim = bright.east();
        var ore = test.absolutePos(new BlockPos(6, 6, 5));
        test.startSequence().thenWaitUntil(() -> test.assertTrue(level.getMaxLocalRawBrightness(bright) == 12 && level.getMaxLocalRawBrightness(dim) == 11,
            "Expected stone room brightness 12/11, got " + level.getMaxLocalRawBrightness(bright) + "/" + level.getMaxLocalRawBrightness(dim)))
            .thenExecute(() -> withPlayer(test, (actor, channel) -> {
                tuned(test, actor, "na:35");
                // Check the bright side first, before a newly placed torch can change the light.
                sodium(test, actor, ore, bright, "sodium_glowstone_3");
                sodium(test, actor, ore, dim, "sodium_glowstone_4");
                tuned(test, actor, "na:1");
                for (int roll = 0; roll <= 1; roll++) {
                    place(level, ore, Blocks.STONE);
                    seedRoll(level.random, 35, roll);
                    test.assertTrue(actor.gameMode.destroyBlock(ore), "Sodium test ore could not be mined");
                    test.assertTrue(MINING.get("sodium_rank1_roll" + roll).equals(torchCode(level, dim)), "Wrong Sodium rank one roll " + roll);
                    collect(level, ore, false);
                    place(level, dim, Blocks.AIR);
                }
                tuned(test, actor, "na:35");
                place(level, dim, Blocks.COBBLESTONE);
                breakIt(test, actor, ore, Blocks.STONE);
                test.assertTrue(level.getBlockState(dim).is(Blocks.COBBLESTONE), "Sodium must not overwrite occupied space");
                place(level, dim, Blocks.AIR);
                place(level, dim.below(), Blocks.AIR);
                breakIt(test, actor, ore, Blocks.STONE);
                test.assertTrue(level.isEmptyBlock(dim), "Sodium must have torch support");
                place(level, dim.below(), Blocks.STONE);
                collect(level, ore, false);

                tuned(test, actor, "f:1");
                for (int roll = 0; roll <= 1; roll++) hunger(test, actor, ore, 10, 0F, roll, "fluorine_rank1_roll" + roll);
                tuned(test, actor, "f:10");
                hunger(test, actor, ore, 19, 19F, 9, "fluorine_cap");

                tuned(test, actor, "ni:2");
                var near = itemEntity(level, actor.position().add(.75, .5, -.25));
                var far = itemEntity(level, actor.position().add(3., 0., 0.));
                breakIt(test, actor, ore, Blocks.STONE);
                test.assertTrue(MINING.get("nickel").equals(motion(near) + "/" + motion(far)), "Nickel did not pull only drops in range");
                near.discard();
                far.discard();
                collect(level, ore, false);

                tuned(test, actor, "hg:1");
                for (int depth = 1; depth <= 5; depth++) place(level, ore.below(depth), Blocks.AIR);
                place(level, ore.below(5), Blocks.LAVA);
                mercury(test, actor, channel, ore, "mercury_beyond");
                place(level, ore.below(4), Blocks.LAVA);
                mercury(test, actor, channel, ore, "mercury_source");
                level.setBlockAndUpdate(ore.below(4), Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 2));
                place(level, ore.below(2), Blocks.LAVA);
                mercury(test, actor, channel, ore, "mercury_two_levels");
                place(level, ore.below(2), Blocks.AIR);
                mercury(test, actor, channel, ore, "mercury_flow");
            })).thenSucceed();
    }

    @GameTest(template = "test_room")
    public static void @PREFIX@PolytoolProtection(GameTestHelper test) {
        withPlayer(test, actor -> {
            var level = test.getLevel();
            var pos = test.absolutePos(new BlockPos(1, 2, 2));
            tuned(test, actor, "fe:1", "c:64", "f:10");
            for (int dx = 0; dx < 3; dx++) place(level, pos.east(dx), Blocks.COAL_ORE);
            actor.getFoodData().setFoodLevel(10);
            Consumer<BlockEvent.BreakEvent> noBreak = event -> {
                if (event.getPlayer() == actor && (event.getPos().equals(pos) || event.getPos().equals(pos.east()))) event.setCanceled(true);
            };
            NeoForge.EVENT_BUS.addListener(noBreak);
            try {
                test.assertTrue(!actor.gameMode.destroyBlock(pos) && level.getBlockState(pos).is(Blocks.COAL_ORE) &&
                    level.getBlockState(pos.east()).is(Blocks.COAL_ORE) && actor.getFoodData().getFoodLevel() == 10 && collect(level, pos, true)[0].equals("{}"),
                    "Canceled primary break still ran Polytool effects");
            } finally { NeoForge.EVENT_BUS.unregister(noBreak); }
            Consumer<BlockEvent.BreakEvent> noExtra = event -> {
                if (event.getPlayer() == actor && event.getPos().equals(pos.east())) event.setCanceled(true);
            };
            NeoForge.EVENT_BUS.addListener(noExtra);
            try {
                test.assertTrue(actor.gameMode.destroyBlock(pos) && level.isEmptyBlock(pos) &&
                    level.getBlockState(pos.east()).is(Blocks.COAL_ORE) && level.getBlockState(pos.east(2)).is(Blocks.COAL_ORE) &&
                    collect(level, pos, true)[1].equals("{}"), "Iron broke or traversed protected ore");
            } finally { NeoForge.EVENT_BUS.unregister(noExtra); }
            place(level, pos.east(), Blocks.AIR);
            place(level, pos.east(2), Blocks.AIR);

            tuned(test, actor, "br:1");
            Consumer<BlockDropsEvent> noDrops = event -> {
                if (event.getBreaker() == actor && event.getPos().equals(pos)) event.setCanceled(true);
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, noDrops);
            try {
                breakIt(test, actor, pos, Blocks.GOLD_ORE);
                test.assertTrue(collect(level, pos, false)[0].equals("{}"), "Bromine bypassed drop event cancellation");
            } finally { NeoForge.EVENT_BUS.unregister(noDrops); }
            var rule = level.getGameRules().getRule(GameRules.RULE_DOBLOCKDROPS);
            boolean before = rule.get();
            try {
                rule.set(false, level.getServer());
                breakIt(test, actor, pos, Blocks.GOLD_ORE);
                test.assertTrue(collect(level, pos, false)[0].equals("{}"), "Bromine bypassed doTileDrops");
            } finally { rule.set(before, level.getServer()); }
            actor.setGameMode(GameType.CREATIVE);
            breakIt(test, actor, pos, Blocks.GOLD_ORE);
            test.assertTrue(collect(level, pos, false)[0].equals("{}"), "Creative drop leakage");
        });
        test.succeed();
    }

    private static void sodium(GameTestHelper test, ServerPlayer actor, BlockPos ore, BlockPos feet, String key) {
        var level = test.getLevel();
        actor.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5, 0, 0);
        int brightness = level.getMaxLocalRawBrightness(feet);
        if (key.equals("sodium_glowstone_4")) {
            var canceled = new boolean[1];
            Consumer<BlockEvent.EntityPlaceEvent> noTorch = event -> {
                if (event.getEntity() == actor && event.getPos().equals(feet)) {
                    canceled[0] = true;
                    event.setCanceled(true);
                }
            };
            NeoForge.EVENT_BUS.addListener(noTorch);
            try {
                breakIt(test, actor, ore, Blocks.STONE);
                test.assertTrue(canceled[0] && level.isEmptyBlock(feet), "Sodium bypassed protected torch placement");
            } finally { NeoForge.EVENT_BUS.unregister(noTorch); }
        }
        breakIt(test, actor, ore, Blocks.STONE);
        test.assertTrue(MINING.get(key).equals(brightness + "/" + torchCode(level, feet)), "Wrong torch at brightness " + brightness);
        collect(level, ore, false);
        place(level, feet, Blocks.AIR);
    }

    private static void hunger(GameTestHelper test, ServerPlayer actor, BlockPos pos, int start, float sat, int roll, String key) {
        var level = test.getLevel();
        var food = actor.getFoodData();
        food.setFoodLevel(start);
        food.setSaturation(sat);
        place(level, pos, Blocks.STONE);
        seedRoll(level.random, 10, roll);
        test.assertTrue(actor.gameMode.destroyBlock(pos), "Fluorine test block could not be mined");
        var actual = String.format(Locale.ROOT, "%d/%.2f", food.getFoodLevel(), food.getSaturationLevel());
        test.assertTrue(MINING.get(key).equals(actual), key + " produced " + actual);
        collect(level, pos, false);
    }

    private static void mercury(GameTestHelper test, ServerPlayer actor, EmbeddedChannel channel, BlockPos pos, String key) {
        chat(channel);
        breakIt(test, actor, pos, Blocks.STONE);
        var actual = chat(channel);
        var expected = MINING.get(key).equals("[]") ? List.of() : List.of(
            Component.translatable("gui." + ID + ".polytool.lava_warning").withStyle(ChatFormatting.RED));
        test.assertTrue(expected.equals(actual), key + " sent the wrong lava warning: " + actual);
        collect(test.getLevel(), pos, false);
    }

    private static List<Component> chat(EmbeddedChannel channel) {
        var messages = new ArrayList<Component>();
        channel.runPendingTasks();
        Object packet;
        while ((packet = channel.readOutbound()) != null) {
            if (packet instanceof ClientboundSystemChatPacket chat && !chat.overlay()) messages.add(chat.content());
            ReferenceCountUtil.release(packet);
        }
        return messages;
    }

    private static ItemEntity itemEntity(ServerLevel level, Vec3 pos) {
        var drop = new ItemEntity(level, pos.x, pos.y, pos.z, new ItemStack(Items.COAL));
        drop.setDeltaMovement(Vec3.ZERO);
        if (!level.addFreshEntity(drop)) throw new AssertionError("Could not spawn test drop");
        return drop;
    }

    private static String motion(ItemEntity drop) {
        var v = drop.getDeltaMovement();
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f", v.x, v.y, v.z);
    }

    private static String torchCode(ServerLevel level, BlockPos pos) { return level.getBlockState(pos).is(Blocks.TORCH) ? "50" : "0"; }

    private static void seedRoll(RandomSource random, int bound, int roll) {
        long seed = 0;
        while (RandomSource.create(seed).nextInt(bound) != roll) seed++;
        random.setSeed(seed);
    }

    private static void place(ServerLevel level, BlockPos pos, Block block) { level.setBlockAndUpdate(pos, block.defaultBlockState()); }

    private static void breakIt(GameTestHelper test, ServerPlayer actor, BlockPos pos, Block block) {
        var level = test.getLevel();
        place(level, pos, block);
        test.assertTrue(actor.gameMode.destroyBlock(pos) && level.isEmptyBlock(pos), "Could not harvest " + block + " at " + pos);
    }

    private static String[] collect(ServerLevel level, BlockPos pos, boolean vein) {
        var primary = new TreeMap<String, Integer>();
        var extras = new TreeMap<String, Integer>();
        var region = new AABB(pos).expandTowards(vein ? 2 : 0, 0, 0).inflate(.5);
        for (var drop : level.getEntitiesOfClass(ItemEntity.class, region)) {
            var loot = vein && drop.blockPosition().getX() == pos.getX() + 1 ? extras : primary;
            var item = drop.getItem().getItem();
            var id = BuiltInRegistries.ITEM.getKey(item);
            int wool = WOOL_IDS.indexOf(id.getPath());
            var key = id.getNamespace().equals("minecraft") && wool >= 0 ? "35:" + wool : DROP_IDS.getOrDefault(item, id.toString());
            loot.merge(key, drop.getItem().getCount(), Integer::sum);
            drop.discard();
        }
        return new String[]{primary.toString(), extras.toString()};
    }

    private static void silk(GameTestHelper test, ItemStack tool) {
        tool.enchant(test.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.SILK_TOUCH), 1);
    }

    private static ItemStack tuned(GameTestHelper test, ServerPlayer actor, String... upgrades) {
        var tool = item("polytool", 1);
        if (upgrades.length == 0) {
            actor.setItemInHand(InteractionHand.MAIN_HAND, tool);
            return tool;
        }
        var menu = open(test, actor, tool);
        for (var upgrade : upgrades) {
            var spec = upgrade.split(":");
            for (int i = 0; i < Integer.parseInt(spec[1]); i++) infuse(test, actor, menu, item("element_" + spec[0], 64));
        }
        actor.closeContainer();
        return tool;
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
        withPlayer(test, (player, channel) -> action.accept(player));
    }

    private static void withPlayer(GameTestHelper test, BiConsumer<ServerPlayer, EmbeddedChannel> action) {
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
            action.accept(player, channel);
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
