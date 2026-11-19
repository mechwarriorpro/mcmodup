package @PACKAGE@;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** No implementation imports: replay the exact Forge observations against the staged registries and menus. */
@GameTestHolder("@TEST_NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class SynthesisFinalChecks {
    private static final String ID = "minechem_chemistry";
    private static final int OUT = 0, STORE = 10, JOURNAL = 19, PLAYER = 20;
    private static final int CAPACITY = 1_022_220;
    private static final String[] TRACE = {
@TRACE@
    };

    @GameTest(template = "synthesis_room")
    public static void finalGhostsAndPayment(GameTestHelper test) {
        var m = place(test, new BlockPos(1, 1, 1));
        var menu = m.menu();
        test.assertTrue(menu.slots.size() == 56 && menu.getSlot(OUT).x == 134 && menu.getSlot(1).x == 62 &&
            menu.getSlot(STORE).y == 84 && menu.getSlot(JOURNAL).x == 26, "Wrong FINAL slot order or original coordinates");
        var foreignIron = ResourceLocation.parse("minechem_chemistry_394:element_fe");
        if (BuiltInRegistries.ITEM.containsKey(foreignIron)) {
            var foreign = stack(foreignIron, 16);
            edit(m, 1, foreign, 0);
            test.assertTrue(menu.getSlot(1).getItem().isEmpty() && !menu.getSlot(STORE).mayPlace(foreign), "FINAL accepted .394's iron into its pattern/menu");
            test.assertTrue(!menu.getSlot(JOURNAL).mayPlace(stack(ResourceLocation.parse("minechem_chemistry_394:chemist_journal"), 1)),
                "FINAL accepted a .394 journal");
        }
        var silicon = item("element_si", 2);
        edit(m, 1, silicon, 0);
        var oxygen = item("element_o", 2);
        edit(m, 4, oxygen, 1);
        test.assertTrue(m.result().isEmpty() && menu.getSlot(4).getItem().getCount() == 1, "Too little oxygen matched stone");
        edit(m, 4, oxygen, 1);
        test.assertTrue(ItemStack.matches(m.result(), new ItemStack(Items.STONE, 7)) && ItemStack.matches(m.menu().getCarried(), oxygen),
            "Shaped at-least counts or ghost cursor not preserved");
        edit(m, 9, oxygen, 1);
        test.assertTrue(m.result().is(Items.STONE), "Trailing position rejected by short shaped rule");
        edit(m, 2, oxygen, 1);
        test.assertTrue(m.result().isEmpty(), "Internal shaped blank was ignored");
        edit(m, 2, ItemStack.EMPTY, 0);
        edit(m, 4, ItemStack.EMPTY, 0);
        test.assertTrue(m.result().isEmpty() && menu.getSlot(4).getItem().isEmpty(), "Clearing a required ghost retained the result");
        edit(m, 4, oxygen, 0);
        menu.setCarried(ItemStack.EMPTY);
        m.input().insertItem(0, item("element_si", 3), false);
        m.input().insertItem(8, item("element_o", 6), false);
        charge(test, m.power(), 499);
        menu.clicked(OUT, 0, ClickType.PICKUP, m.player());
        test.assertTrue(menu.getCarried().isEmpty() && m.power().getEnergyStored() == 499, "499 FE paid for 500 FE stone");
        charge(test, m.power(), 1001);
        test.runAfterDelay(23, () -> {
            test.assertTrue(m.input().getStackInSlot(0).getCount() == 3 && m.power().getEnergyStored() == 1500,
                "Preview created a timed job or spent FE without a click");
            menu.clicked(OUT, 0, ClickType.PICKUP, m.player());
            test.assertTrue(ItemStack.matches(menu.getCarried(), new ItemStack(Items.STONE, 3)) && m.power().getEnergyStored() == 0 &&
                m.input().getStackInSlot(0).isEmpty() && m.input().getStackInSlot(8).isEmpty(), "Click didn't pay per stone as observed in FINAL");
            menu.setCarried(ItemStack.EMPTY);
            m.input().insertItem(0, item("element_si", 2), false);
            m.input().insertItem(8, item("element_o", 4), false);
            charge(test, m.power(), 1000);
            menu.clicked(OUT, 0, ClickType.QUICK_MOVE, m.player());
            test.assertTrue(countInPlayer(m, Items.STONE.getDefaultInstance()) == 14 && m.power().getEnergyStored() == 0,
                "Shift-click did not output two complete batches of seven at 500 FE each");
            // Shapeless counts are exact, even when the metal is in an arbitrary position.
            clear(m);
            edit(m, 9, item("element_fe", 17), 0);
            test.assertTrue(m.result().isEmpty(), "Shapeless rule accepted excess ghost quantity");
            edit(m, 9, ItemStack.EMPTY, 0);
            edit(m, 9, item("element_fe", 16), 0);
            test.assertTrue(m.result().is(Items.IRON_INGOT), "Shapeless rule did not accept a ghost in position 9");
            test.succeed();
        });
    }

    @GameTest(template = "synthesis_room")
    public static void finalJournalAndRoundTrip(GameTestHelper test) {
        var m = place(test, new BlockPos(1, 1, 1));
        var level = test.getLevel();
        var registry = level.registryAccess();
        var microscope = placeBlock(test, new BlockPos(3, 1, 1), "microscope");
        var inspection = ((MenuProvider) microscope).createMenu(2, m.player().getInventory(), m.player());
        inspection.getSlot(0).set(new ItemStack(Items.IRON_BLOCK));
        inspection.getSlot(1).set(item("chemist_journal", 1));
        var book = inspection.getSlot(1).remove(1);
        m.menu().getSlot(JOURNAL).set(book);
        test.assertTrue(m.result().isEmpty(), "Unselected journal set a recipe");
        book = m.menu().getSlot(JOURNAL).remove(1);
        learnSelection(test, microscope.getBlockPos(), book);
        m.player().getInventory().setItem(0, book.copy());
        m.menu().quickMoveStack(m.player(), PLAYER + 27);
        test.assertTrue(m.player().getInventory().getItem(0).isEmpty() && m.result().is(Items.IRON_BLOCK),
            "The selected journal did not move to slot 19 or fill 144 iron ghosts");
        m.input().insertItem(0, item("element_fe", 64), false);
        m.input().insertItem(1, item("element_fe", 64), false);
        m.input().insertItem(8, item("element_fe", 16), false);
        charge(test, m.power(), 1000);
        var snapshot = m.block().saveWithFullMetadata(registry);
        test.assertTrue(snapshot.getCompound("Pattern").getCompound("0").getInt("Count") == 144,
            "The exact 144-tube ghost was truncated to a physical stack limit");
        var replica = place(test, new BlockPos(1, 1, 3));
        replica.block().loadWithComponents(snapshot, registry);
        test.assertTrue(replica.result().is(Items.IRON_BLOCK) && replica.power().getEnergyStored() == 1000,
            "FINAL recipe or charge did not reload: " + replica.result() + "/" + replica.power().getEnergyStored());
        test.assertTrue(ItemStack.matches(replica.menu().getSlot(JOURNAL).getItem(), book), "FINAL journal components did not reload");
        replica.menu().clicked(OUT, 0, ClickType.PICKUP, replica.player());
        test.assertTrue(replica.menu().getCarried().is(Items.IRON_BLOCK) && replica.power().getEnergyStored() == 0 &&
            replica.input().getStackInSlot(0).isEmpty() && replica.input().getStackInSlot(1).isEmpty() &&
            replica.input().getStackInSlot(8).isEmpty(), "Reloaded synthesis did not pay all 144 real iron tubes");
        replica.menu().quickMoveStack(replica.player(), JOURNAL);
        test.assertTrue(replica.menu().slots.subList(0, JOURNAL + 1).stream().noneMatch(slot -> slot.hasItem()) &&
            countInPlayer(replica, book) == 1, "Journal retrieval did not clear only the virtual pattern");
        test.succeed();
    }

    @GameTest(template = "synthesis_room")
    public static void finalSidesAndCraft(GameTestHelper test) throws Exception {
        var m = place(test, new BlockPos(1, 1, 1));
        var level = test.getLevel();
        test.assertTrue(m.power().getMaxEnergyStored() == CAPACITY && m.power().receiveEnergy(1000, true) == 200 &&
            m.power().getEnergyStored() == 0 && !m.power().canExtract(), "Wrong FINAL native energy limits");
        var iron = item("element_fe", 16);
        edit(m, 1, iron, 0);
        m.menu().setCarried(ItemStack.EMPTY);
        m.input().insertItem(1, iron, false);
        charge(test, m.power(), 9_999);
        for (var side : Direction.values()) {
            var face = level.getCapability(Capabilities.ItemHandler.BLOCK, m.block().getBlockPos(), side);
            test.assertTrue(face.getSlots() == 9 && face.extractItem(1, 1, false).isEmpty(), "Idle FINAL exposed extraction or journal on " + side);
        }
        charge(test, m.power(), 1);
        for (var side : Direction.values()) {
            var face = level.getCapability(Capabilities.ItemHandler.BLOCK, m.block().getBlockPos(), side);
            boolean top = side == Direction.UP;
            test.assertTrue(face.getSlots() == (top ? 9 : 1) && (top || face.getStackInSlot(0).is(Items.IRON_INGOT)) &&
                face.extractItem(top ? 1 : 0, 1, false).isEmpty() && (top || face.insertItem(0, iron, true).getCount() == 16),
                "Wrong ready/default sided capability on " + side);
        }
        // Toggle only the real registered server option; the fixture uses the public capabilities to withdraw.
        var field = m.block().getClass().getEnclosingClass().getDeclaredField("AUTOMATION");
        field.setAccessible(true);
        var allow = (ModConfigSpec.BooleanValue) field.get(null);
        try {
            allow.set(true);
            var output = level.getCapability(Capabilities.ItemHandler.BLOCK, m.block().getBlockPos(), Direction.NORTH);
            test.assertTrue(output.extractItem(1, 1, true).isEmpty() && output.extractItem(0, 1, true).is(Items.IRON_INGOT) &&
                m.input().extractItem(1, 1, true).getCount() == 1 && m.power().getEnergyStored() == 10_000 &&
                m.input().getStackInSlot(1).getCount() == 16, "Simulation mutated FINAL storage or power or exposed the journal");
            test.assertTrue(output.extractItem(0, 1, false).is(Items.IRON_INGOT) && m.power().getEnergyStored() == 0 &&
                m.input().getStackInSlot(1).isEmpty() && output.getSlots() == 9 && output.extractItem(0, 1, false).isEmpty(),
                "Enabled FINAL did not atomically charge for a side withdrawal");
        } finally {
            allow.set(false);
        }
        var piston = new ItemStack(Items.PISTON);
        var manipulator = item("atomic_manipulator", 1);
        craft(test, List.of(piston, piston, piston, piston, new ItemStack(Items.IRON_BLOCK), piston,
            piston, piston, piston), manipulator);
        var ingot = new ItemStack(Items.IRON_INGOT);
        craft(test, List.of(ingot, new ItemStack(Items.REDSTONE), ingot, ingot, manipulator, ingot,
            ingot, new ItemStack(Items.DIAMOND), ingot), item("synthesizer", 1));
        test.succeed();
    }

    @GameTest(template = "synthesis_room", timeoutTicks = 150)
    public static void finalOrderedRecipes(GameTestHelper test) {
        var m = place(test, new BlockPos(1, 1, 1));
        var registry = test.getLevel().registryAccess();
        int paid = 0, shadowed = 0, unavailable = 0;
        for (int i = 0; i < TRACE.length; i++) {
            var row = TRACE[i].split(";");
            String expected = row[4];
            int winning = expected.startsWith("shadow:") ? Integer.parseInt(expected.substring(7)) : i;
            var winner = TRACE[winning].split(";");
            String id = winner[0];
            int quantity = Integer.parseInt(winner[1]), cost = Integer.parseInt(winner[2]);
            var tag = new CompoundTag();
            var ghosts = new CompoundTag();
            var pattern = row[3].split(",");
            for (int slot = 0; slot < pattern.length; ++slot) {
                if (pattern[slot].equals("_")) continue;
                var part = pattern[slot].split("@");
                var info = new CompoundTag();
                info.putString("Id", part[0]);
                info.putInt("Count", Integer.parseInt(part[1]));
                ghosts.put(Integer.toString(slot), info);
            }
            tag.put("Pattern", ghosts);
            tag.putInt("Energy", Math.min(CAPACITY, cost - 1));
            m.block().loadWithComponents(tag, registry);
            m.player().getInventory().clearContent();
            if (!winner[5].equals("true")) {
                test.assertTrue(m.result().isEmpty() && m.menu().quickMoveStack(m.player(), OUT).isEmpty(), "Unported result fell through to another rule at " + i);
                unavailable++;
                continue;
            }
            var output = stack(ResourceLocation.parse(id), quantity);
            if (output.is(Items.POTION)) output.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.WATER));
            test.assertTrue(ItemStack.matches(m.result(), output), "Row " + i + ": expected " + output + ", found " + m.result());
            if (winning != i) {
                shadowed++;
                continue;
            }
            int storage = 0;
            for (var token : pattern) {
                if (token.equals("_")) continue;
                var part = token.split("@");
                int rest = Integer.parseInt(part[1]);
                while (rest > 0) {
                    var input = stack(ResourceLocation.parse(part[0]), Math.min(rest, 64));
                    test.assertTrue(m.input().insertItem(storage++, input, false).isEmpty(), "Inputs did not fit on row " + i);
                    rest -= input.getCount();
                }
            }
            test.assertTrue(m.menu().quickMoveStack(m.player(), OUT).isEmpty() && m.power().getEnergyStored() == Math.min(CAPACITY, cost - 1),
                "Underpowered recipe paid on row " + i);
            if (expected.equals("power")) { unavailable++; continue; }
            charge(test, m.power(), 1);
            test.assertTrue(ItemStack.matches(m.menu().quickMoveStack(m.player(), OUT), output) && m.power().getEnergyStored() == 0 &&
                countInPlayer(m, output) == quantity, "Wrong paid result at exact cost on row " + i);
            for (int slot = 0; slot < 9; slot++) test.assertTrue(m.input().getStackInSlot(slot).isEmpty(), "Storage not consumed on row " + i);
            paid++;
        }
        test.assertTrue(TRACE.length == 301 && paid == 277 && shadowed == 22 && unavailable == 2, "Unexpected FINAL trace coverage");
        test.succeed();
    }

    private static void learnSelection(GameTestHelper test, BlockPos pos, ItemStack book) {
        var level = test.getLevel();
        var players = level.getServer().getPlayerList();
        var actor = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "FinalJournalTest"), ClientInformation.createDefault());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        try {
            NetworkRegistry.configureMockConnection(connection);
            players.placeNewPlayer(connection, actor, CommonListenerCookie.createInitial(actor.getGameProfile(), false));
            actor.setGameMode(GameType.SURVIVAL);
            actor.moveTo(pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5, 0F, 0F);
            actor.setItemInHand(InteractionHand.MAIN_HAND, book);
            book.getItem().use(level, actor, InteractionHand.MAIN_HAND);
            test.assertTrue(actor.containerMenu != actor.inventoryMenu && actor.containerMenu.clickMenuButton(actor, 1), "Could not select the FINAL discovery");
            String active = book.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                .getCompound("ChemistJournal").getString("Active");
            test.assertTrue(active.equals("minecraft:iron_block"), "Microscope did not discover iron block synthesis");
            actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        } finally {
            if (players.getPlayer(actor.getUUID()) == actor) {
                actor.closeContainer();
                players.remove(actor);
            }
            actor.discard();
            channel.finishAndReleaseAll();
        }
    }

    private static void craft(GameTestHelper test, List<ItemStack> grid, ItemStack expected) {
        var level = test.getLevel();
        var input = CraftingInput.of(3, 3, grid);
        var recipe = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level).orElseThrow();
        test.assertTrue(ItemStack.matches(recipe.value().assemble(input, level.registryAccess()), expected), "Wrong FINAL survival recipe for " + expected);
    }

    private record Machine(BlockEntity block, Player player, AbstractContainerMenu menu, IItemHandler input, IEnergyStorage power) {
        ItemStack result() { return menu.getSlot(OUT).getItem(); }
    }

    private static Machine place(GameTestHelper test, BlockPos local) {
        var tile = placeBlock(test, local, "synthesizer");
        var pos = tile.getBlockPos();
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
        var level = test.getLevel();
        var menu = ((MenuProvider) tile).createMenu(0, player.getInventory(), player);
        return new Machine(tile, player, menu, level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP),
            level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.NORTH));
    }

    private static BlockEntity placeBlock(GameTestHelper test, BlockPos local, String name) {
        var id = ResourceLocation.fromNamespaceAndPath(ID, name);
        test.assertTrue(BuiltInRegistries.BLOCK.containsKey(id), "Missing FINAL block " + id);
        test.setBlock(local, BuiltInRegistries.BLOCK.get(id));
        var tile = test.getLevel().getBlockEntity(test.absolutePos(local));
        test.assertTrue(tile instanceof MenuProvider, "Wrong block entity at " + local);
        return tile;
    }

    private static void edit(Machine m, int slot, ItemStack cursor, int button) {
        m.menu().setCarried(cursor);
        m.menu().clicked(slot, button, ClickType.PICKUP, m.player());
    }

    private static void clear(Machine m) {
        for (int i = 1; i < STORE; ++i) edit(m, i, ItemStack.EMPTY, 0);
    }

    private static ItemStack item(String path, int n) { return stack(ResourceLocation.fromNamespaceAndPath(ID, path), n); }
    private static ItemStack stack(ResourceLocation id, int n) {
        if (!BuiltInRegistries.ITEM.containsKey(id)) throw new AssertionError("Missing mapped FINAL item " + id);
        return new ItemStack(BuiltInRegistries.ITEM.get(id), n);
    }

    private static int countInPlayer(Machine m, ItemStack sample) {
        return m.menu().slots.subList(PLAYER, m.menu().slots.size()).stream().map(slot -> slot.getItem())
            .filter(stack -> ItemStack.isSameItemSameComponents(stack, sample)).mapToInt(ItemStack::getCount).sum();
    }

    private static void charge(GameTestHelper test, IEnergyStorage power, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int accepted = power.receiveEnergy(remaining, false);
            test.assertTrue(accepted > 0 && accepted <= 200, "FINAL accepted an invalid FE transfer");
            remaining -= accepted;
        }
    }
}
