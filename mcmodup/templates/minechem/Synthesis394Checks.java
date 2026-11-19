package @PACKAGE@;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

@GameTestHolder("@TEST_NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class Synthesis394Checks {
    private static final String ID = "minechem_chemistry_394";
    private static final int NEXT = 1, CLEAR = 4, OUTPUT = 9, JOURNAL = 28, FIRST_PLAYER_SLOT = 29;
    private static final String[] TRACE = {
@TRACE@
    };

    @GameTest(template = "test_room")
    public static void exactSynthesisBatches(GameTestHelper test) {
        var machine = place(test, BlockPos.ZERO);
        test.assertTrue(machine.input().getSlots() == 19 && machine.output().getSlots() == 19, "Unexpected exposed synthesizer slots");
        test.assertTrue(machine.power().getMaxEnergyStored() == 100_000 && machine.power().receiveEnergy(1000, true) == 100,
                "Wrong .394 synthesis FE limits");
        for (String row : TRACE) {
            String[] recipe = row.split(";", 4);
            var expected = item(recipe[0], Integer.parseInt(recipe[1]));
            if (expected.is(Items.POTION)) expected.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.WATER));
            int cost = Integer.parseInt(recipe[2]);
            String[] pattern = recipe[3].split(",");
            test.assertTrue(machine.menu().clickMenuButton(machine.player(), NEXT), "Cannot set .394 ghost pattern");
            checkStack(test, machine.menu().getSlot(OUTPUT).getItem(), expected, "wrong output template for " + recipe[0]);
            test.assertTrue(machine.output().getStackInSlot(0).isEmpty(), "Uncharged ghost was exposed as inventory output");
            int index = 0;
            for (int slot = 0; slot < pattern.length; ++slot) {
                var part = part(pattern[slot]);
                checkStack(test, machine.menu().getSlot(slot).getItem(), part, "wrong ghost slot " + slot + " for " + recipe[0]);
                for (int left = part.getCount(); left > 0;) {
                    int count = Math.min(left, part.getMaxStackSize());
                    test.assertTrue(machine.input().insertItem(index++, part.copyWithCount(count), false).isEmpty(), "Test could not load " + part);
                    left -= count;
                }
            }
            charge(test, machine, cost - 1);
            test.assertTrue(machine.output().extractItem(0, 64, false).isEmpty(), "Synthesis charged below original threshold for " + recipe[0]);
            charge(test, machine, 1);
            test.assertTrue(machine.output().extractItem(0, expected.getCount() - 1, false).isEmpty(), "Unexpected partial result batch");
            checkStack(test, machine.output().extractItem(0, 64, true), expected, "wrong simulated extraction");
            test.assertTrue(machine.power().getEnergyStored() == cost, "Simulation spent energy for " + recipe[0]);
            checkStack(test, machine.output().extractItem(0, 64, false), expected, "wrong concrete extraction");
            test.assertTrue(machine.power().getEnergyStored() == 0 && machine.output().extractItem(0, 64, false).isEmpty(),
                    "Synthesis didn't charge exactly once for " + recipe[0]);
            for (int slot = 0; slot < index; ++slot) test.assertTrue(machine.input().getStackInSlot(slot).isEmpty(), "Synthesis left an ingredient");
            checkStack(test, machine.menu().getSlot(OUTPUT).getItem(), expected, "the ghost should still show the result");
        }
        test.succeed();
    }

    @GameTest(template = "test_room")
    public static void ghostEditingAndSavedSynthesis(GameTestHelper test) {
        var machine = place(test, BlockPos.ZERO);
        var fe = item(ID + ":element_fe", 16);
        machine.menu().setCarried(fe);
        // Shapeless templates can be placed in any ghost slot without moving the real stack.
        machine.menu().clicked(8, 0, ClickType.PICKUP, machine.player());
        checkStack(test, machine.menu().getCarried(), fe, "ghost edit moved the held stack");
        var iron = new ItemStack(Items.IRON_INGOT);
        checkStack(test, machine.menu().getSlot(OUTPUT).getItem(), iron, "shapeless iron template failed");
        test.assertTrue(machine.menu().clickMenuButton(machine.player(), CLEAR) && machine.menu().getSlot(8).getItem().isEmpty(), "could not clear pattern");
        machine.menu().clicked(4, 0, ClickType.PICKUP, machine.player());
        test.assertTrue(machine.input().insertItem(0, fe.copyWithCount(10), false).isEmpty(), "input slot 0 rejected Fe");
        test.assertTrue(machine.input().insertItem(1, fe.copyWithCount(6), false).isEmpty(), "input slot 1 rejected Fe");
        charge(test, machine, 3017);
        var registry = test.getLevel().registryAccess();
        var saved = machine.entity().saveWithFullMetadata(registry);
        var loaded = place(test, new BlockPos(2, 0, 0));
        loaded.entity().loadWithComponents(saved, registry);
        test.assertTrue(loaded.power().getEnergyStored() == 3017, "saved FE did not reload");
        checkStack(test, loaded.menu().getSlot(4).getItem(), fe, "original ghost position was not saved");
        loaded.menu().setCarried(new ItemStack(Items.DIRT));
        loaded.menu().clicked(OUTPUT, 0, ClickType.PICKUP, loaded.player());
        test.assertTrue(loaded.power().getEnergyStored() == 3017, "blocked cursor spent energy");
        loaded.menu().clicked(OUTPUT, 0, ClickType.THROW, loaded.player());
        test.assertTrue(loaded.power().getEnergyStored() == 3017, "drop click withdrew a ghost");
        loaded.menu().setCarried(ItemStack.EMPTY);
        loaded.menu().clicked(OUTPUT, 0, ClickType.PICKUP, loaded.player());
        checkStack(test, loaded.menu().getCarried(), iron, "extract did not place a real ingot on cursor");
        test.assertTrue(loaded.power().getEnergyStored() == 17 && loaded.input().getStackInSlot(0).isEmpty() &&
                loaded.input().getStackInSlot(1).isEmpty(), "extraction should consume both split stacks and exact cost");
        loaded.menu().clicked(OUTPUT, 0, ClickType.PICKUP, loaded.player());
        checkStack(test, loaded.menu().getCarried(), iron, "ghost may not be withdrawn a second time");
        test.succeed();
    }

    @GameTest(template = "journal_room")
    public static void discoveredJournalRestoresActiveSynthesis(GameTestHelper test) {
        var machine = place(test, new BlockPos(1, 1, 1));
        var microscope = placeMicroscope(test, new BlockPos(3, 1, 1));
        var inspection = ((MenuProvider) microscope).createMenu(1, machine.player().getInventory(), machine.player());
        var iron = new ItemStack(Items.IRON_INGOT);
        var coalOre = new ItemStack(Items.COAL_ORE);
        var book = item(ID + ":chemist_journal", 1);
        var journal = machine.menu().getSlot(JOURNAL);
        test.assertTrue(machine.menu().slots.size() == 65 && journal.getMaxStackSize() == 1 && journal.mayPlace(book) && !journal.mayPlace(iron),
            "Expected the separate real journal slot at 28");
        var other = item("minechem_chemistry:chemist_journal", 1);
        if (!other.isEmpty()) {
            test.assertTrue(!journal.mayPlace(other) && !inspection.getSlot(1).mayPlace(other), "FINAL journal crossed .394's registry boundary");
        }
        inspection.getSlot(0).set(iron.copy());
        inspection.getSlot(1).set(book);
        var discovered = inspection.getSlot(1).getItem();
        test.assertTrue(known(discovered).equals(List.of("minecraft:iron_ingot")), "Journal placed second did not discover the sample");
        inspection.getSlot(0).set(new ItemStack(Items.BEDROCK));
        test.assertTrue(known(discovered).size() == 1, "Bedrock has no supported research entry");
        inspection.getSlot(0).set(coalOre.copy());
        inspection.getSlot(0).set(coalOre.copy());
        test.assertTrue(known(discovered).equals(List.of("minecraft:iron_ingot", "minecraft:coal_ore")) &&
            journalData(discovered).getString("Active").isEmpty(), "Discoveries were duplicated or auto-selected");
        checkStack(test, inspection.getSlot(0).getItem(), coalOre, "Microscope consumed its sample");
        var level = test.getLevel();
        var registry = level.registryAccess();
        var restored = placeMicroscope(test, new BlockPos(3, 1, 3));
        restored.loadWithComponents(microscope.saveWithFullMetadata(registry), registry);
        var savedInspection = ((MenuProvider) restored).createMenu(2, machine.player().getInventory(), machine.player());
        checkStack(test, savedInspection.getSlot(0).getItem(), coalOre, "Sample did not reload");
        var savedBook = savedInspection.getSlot(1).remove(1);
        checkStack(test, savedBook, discovered, "Journal discoveries did not reload");
        journal.set(savedBook);
        test.assertTrue(machine.menu().getSlot(OUTPUT).getItem().isEmpty(), "Unselected journal created a ghost result");
        var retrieved = journal.remove(1);
        withPlayer(test, microscope.getBlockPos(), actor -> {
            actor.setItemInHand(InteractionHand.MAIN_HAND, retrieved);
            retrieved.getItem().use(level, actor, InteractionHand.MAIN_HAND);
            var open = actor.containerMenu;
            test.assertTrue(open != actor.inventoryMenu && BuiltInRegistries.MENU.getKey(open.getType()).equals(id("chemist_journal")),
                "Right-click on .394 journal did not open its own menu");
            test.assertTrue(open.clickMenuButton(actor, NEXT) && journalData(retrieved).getString("Active").equals("minecraft:iron_ingot"),
                "Journal could not choose its first recorded entry");
            actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            test.assertTrue(!open.stillValid(actor) && !open.clickMenuButton(actor, NEXT), "A stale menu changed an unheld book");
        });
        var expectedBook = retrieved.copy();
        machine.player().getInventory().setItem(9, retrieved);
        test.assertTrue(!machine.menu().quickMoveStack(machine.player(), FIRST_PLAYER_SLOT).isEmpty() && machine.player().getInventory().getItem(9).isEmpty(),
            "Shift-click did not move the book into slot 28");
        checkStack(test, journal.getItem(), expectedBook, "Wrong journal after shift-click");
        checkStack(test, machine.menu().getSlot(OUTPUT).getItem(), iron, "Journal did not load an iron synthesis template");
        test.assertTrue(machine.output().extractItem(0, 64, false).isEmpty(), "Journal did not require FE and ingredients");
        var loaded = place(test, new BlockPos(1, 1, 3));
        loaded.entity().loadWithComponents(machine.entity().saveWithFullMetadata(registry), registry);
        var savedJournal = loaded.menu().getSlot(JOURNAL);
        test.assertTrue(known(savedJournal.getItem()).equals(List.of("minecraft:iron_ingot", "minecraft:coal_ore")) &&
            journalData(savedJournal.getItem()).getString("Active").equals("minecraft:iron_ingot"), "Synthesizer did not save its book");
        checkStack(test, loaded.menu().getSlot(OUTPUT).getItem(), iron, "Journal template did not reload");
        var tubes = item(ID + ":element_fe", 16);
        test.assertTrue(loaded.input().insertItem(0, tubes, false).isEmpty(), "Could not load 16 iron tubes");
        charge(test, loaded, 3000);
        checkStack(test, loaded.output().extractItem(0, 64, false), iron, "Journal-selected synthesis did not make one iron ingot");
        test.assertTrue(loaded.power().getEnergyStored() == 0 && loaded.input().getStackInSlot(0).isEmpty(), "Journal bypassed the full cost");
        test.assertTrue(!savedJournal.remove(1).isEmpty() && loaded.menu().slots.subList(0, OUTPUT + 1).stream().noneMatch(slot -> slot.hasItem()),
            "Removing the journal did not clear the ghost grid and result");
        test.succeed();
    }

    private static BlockEntity placeMicroscope(GameTestHelper test, BlockPos local) {
        test.assertTrue(BuiltInRegistries.BLOCK.containsKey(id("microscope")), "Missing .394 microscope");
        test.setBlock(local, BuiltInRegistries.BLOCK.get(id("microscope")));
        var found = test.getLevel().getBlockEntity(test.absolutePos(local));
        test.assertTrue(found instanceof MenuProvider, "No microscope menu provider");
        return found;
    }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(ID, path); }

    private static CompoundTag journalData(ItemStack book) {
        return book.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound("ChemistJournal");
    }

    private static List<String> known(ItemStack book) {
        return journalData(book).getList("Discoveries", Tag.TAG_STRING).stream().map(Tag::getAsString).toList();
    }

    private static void withPlayer(GameTestHelper test, BlockPos pos, Consumer<ServerPlayer> action) {
        var level = test.getLevel();
        var players = level.getServer().getPlayerList();
        var actor = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "Journal394Fixture"), ClientInformation.createDefault());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        try {
            NetworkRegistry.configureMockConnection(connection);
            players.placeNewPlayer(connection, actor, CommonListenerCookie.createInitial(actor.getGameProfile(), false));
            actor.setGameMode(GameType.SURVIVAL);
            actor.moveTo(pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5, 0F, 0F);
            action.accept(actor);
        } finally {
            if (players.getPlayer(actor.getUUID()) == actor) {
                actor.closeContainer();
                players.remove(actor);
            }
            actor.discard();
            channel.finishAndReleaseAll();
        }
    }

    private static ItemStack part(String key) {
        if (key.equals("_")) return ItemStack.EMPTY;
        String[] fields = key.split("@", 2);
        return item(fields[0], Integer.parseInt(fields[1]));
    }

    private static ItemStack item(String key, int amount) {
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(key)), amount);
    }

    private static Machine place(GameTestHelper test, BlockPos relative) {
        var pos = test.absolutePos(relative);
        var level = test.getLevel();
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(ID, "synthesizer"));
        test.setBlock(relative, block);
        var entity = level.getBlockEntity(pos);
        test.assertTrue(entity instanceof MenuProvider, "Missing .394 synthesizer entity and menu");
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        var menu = ((MenuProvider) entity).createMenu(0, player.getInventory(), player);
        var input = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
        var output = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.DOWN);
        var power = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.NORTH);
        test.assertTrue(menu != null && input != null && output != null && power != null, "Missing synthesis menu or capability");
        return new Machine(entity, player, menu, input, output, power);
    }

    private static void charge(GameTestHelper test, Machine machine, int amount) {
        int left = amount;
        while (left > 0) {
            int added = machine.power().receiveEnergy(left, false);
            test.assertTrue(added > 0 && added <= 100 && added <= left, "Invalid .394 synthesis receive");
            left -= added;
        }
    }

    private static void checkStack(GameTestHelper test, ItemStack got, ItemStack expected, String message) {
        test.assertTrue(ItemStack.matches(got, expected), message + ": got " + got + ", expected " + expected);
    }

    private record Machine(BlockEntity entity, Player player, AbstractContainerMenu menu, IItemHandler input,
                           IItemHandler output, IEnergyStorage power) {}
}
