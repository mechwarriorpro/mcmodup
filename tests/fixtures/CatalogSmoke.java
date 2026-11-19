package mcmodup.test;

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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.AABB;

@Mod(CatalogSmoke.ID)
@GameTestHolder(CatalogSmoke.ID)
@PrefixGameTestTemplate(false)
public final class CatalogSmoke {
    public static final String ID = "mcmodup_smoke";

    static ItemStack stack(GameTestHelper helper, String namespace, String name) {
        var id = ResourceLocation.fromNamespaceAndPath(namespace, name);
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(id), "Missing converted item " + id);
        return new ItemStack(BuiltInRegistries.ITEM.get(id));
    }

    static void check(GameTestHelper helper, CraftingInput input, String namespace, String result) {
        var level = helper.getLevel();
        var recipes = level.getRecipeManager();
        var match = recipes.getRecipeFor(RecipeType.CRAFTING, input, level);
        helper.assertTrue(match.isPresent(), "Missing recipe for " + namespace + ":" + result);
        var output = match.orElseThrow().value().assemble(input, level.registryAccess());
        helper.assertTrue(ItemStack.isSameItem(output, stack(helper, namespace, result)) && output.getCount() == 1,
                "Unexpected result " + output + " for " + match.orElseThrow().id());
    }

    static final String MINECHEM = "minechem_chemistry";
    private static final boolean FINAL_BUFFER = @BUFFERED_DECOMPOSER@;
    private static final int WATER_INDEX = @WATER_INDEX@;

    record Machine(BlockEntity entity, Player user, AbstractContainerMenu menu, IItemHandler input,
                           IItemHandler output, IEnergyStorage power) {}

    private static Machine placeMachine(GameTestHelper helper, String name, boolean waterRecipe) {
        return placeMachine(helper, BlockPos.ZERO, MINECHEM, name, waterRecipe);
    }

    static Machine placeMachine(GameTestHelper helper, BlockPos relative, String namespace, String name, boolean waterRecipe) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(relative);
        var id = ResourceLocation.fromNamespaceAndPath(namespace, name);
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(id), "Machine isn't registered: " + id);
        level.setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.get(id).defaultBlockState());
        BlockEntity entity = level.getBlockEntity(pos);
        helper.assertTrue(entity instanceof MenuProvider, "Missing machine block entity/menu");
        var user = helper.makeMockPlayer(GameType.SURVIVAL);
        user.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        var menu = ((MenuProvider) entity).createMenu(0, user.getInventory(), user);
        var input = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
        var output = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.DOWN);
        var power = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.NORTH);
        helper.assertTrue(menu != null && input != null && output != null && power != null, "Missing machine capabilities");
        if (waterRecipe) {
            for (int i = 0; i < WATER_INDEX / 10; i++) helper.assertTrue(menu.clickMenuButton(user, 4), "Cannot scroll recipes");
            for (int i = 0; i < WATER_INDEX % 10; i++) helper.assertTrue(menu.clickMenuButton(user, 2), "Cannot select recipe");
        }
        return new Machine(entity, user, menu, input, output, power);
    }

    static void put(GameTestHelper helper, IItemHandler input, int slot, ItemStack stack) {
        helper.assertTrue(input.insertItem(slot, stack, false).isEmpty(), "Machine rejected " + stack);
    }

    static void charge(GameTestHelper helper, IEnergyStorage power, int amount) {
        while (amount > 0) {
            int inserted = power.receiveEnergy(amount, false);
            helper.assertTrue(inserted > 0 && inserted <= amount, "Couldn't charge the machine");
            amount -= inserted;
        }
    }

    private static void loadIngredients(GameTestHelper helper, Machine machine) {
        put(helper, machine.input, 0, stack(helper, MINECHEM, "element_h").copyWithCount(2));
        put(helper, machine.input, 1, stack(helper, MINECHEM, "element_o"));
    }

    @GameTest(template = "empty")
    public static void poweredSynthesis(GameTestHelper helper) {
        var machine = placeMachine(helper, "synthesizer", true);
        loadIngredients(helper, machine);
        helper.assertTrue(machine.menu.clickMenuButton(machine.user, 0), "Run button wasn't accepted");
        helper.runAfterDelay(22, () -> {
            helper.assertTrue(machine.input.getStackInSlot(0).getCount() == 2 && machine.output.getStackInSlot(0).isEmpty(),
                "An unpowered machine changed the inventory");
            charge(helper, machine.power, 600);
            helper.assertTrue(machine.power.extractEnergy(50, false) == 0, "Machine unnecessarily exports power");
            helper.assertTrue(machine.menu.clickMenuButton(machine.user, 0), "Couldn't start synthesis");
            helper.runAfterDelay(22, () -> {
                helper.assertTrue(machine.input.getStackInSlot(0).isEmpty() && machine.input.getStackInSlot(1).isEmpty(), "Inputs were not consumed");
                var output = machine.output.extractItem(0, 64, false);
                helper.assertTrue(ItemStack.isSameItem(output, stack(helper, MINECHEM, "molecule_water")) && output.getCount() == 1, "Wrong synthesis output");
                helper.assertTrue(machine.power.getEnergyStored() == 100, "Synthesis did not charge the recovered cost");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty")
    public static void poweredDecomposition(GameTestHelper helper) {
        if (FINAL_BUFFER) { finalDecompositionTimeline(helper); return; }
        var machine = placeMachine(helper, "decomposer", false);
        put(helper, machine.input, 0, new ItemStack(Items.WATER_BUCKET));
        put(helper, machine.input, 18, new ItemStack(Items.REDSTONE));
        helper.assertTrue(machine.menu.clickMenuButton(machine.user, 0), "Couldn't start the decomposer");
        helper.runAfterDelay(18, () -> {
            var water = machine.output.extractItem(0, 64, false);
            helper.assertTrue(ItemStack.isSameItem(water, stack(helper, MINECHEM, "molecule_water")) && water.getCount() == 16,
                "Wrong starter decomposition");
            helper.assertTrue(machine.input.getStackInSlot(0).isEmpty() && machine.input.getStackInSlot(18).isEmpty(), "Water bucket/fuel wasn't consumed");
            put(helper, machine.input, 0, water.copyWithCount(1));
            helper.assertTrue(machine.menu.clickMenuButton(machine.user, 0), "Couldn't decompose a molecule");
            helper.runAfterDelay(5, () -> {
                var hydrogen = machine.output.extractItem(0, 64, false);
                var oxygen = machine.output.extractItem(1, 64, false);
                helper.assertTrue(ItemStack.isSameItem(hydrogen, stack(helper, MINECHEM, "element_h")) && hydrogen.getCount() == 2,
                    "Hydrogen output does not match the historical molecule");
                helper.assertTrue(ItemStack.isSameItem(oxygen, stack(helper, MINECHEM, "element_o")) && oxygen.getCount() == 1,
                    "Oxygen output does not match the historical molecule");
                helper.assertTrue(machine.power.getEnergyStored() == 481, "Decomposition charged an incorrect amount");
                helper.succeed();
            });
        });
    }

    @SuppressWarnings("unchecked")
    static void tick(Machine machine) {
        var entity = machine.entity;
        var state = entity.getBlockState();
        var ticker = ((EntityBlock) state.getBlock()).getTicker(entity.getLevel(), state, (BlockEntityType<BlockEntity>) entity.getType());
        if (ticker == null) throw new AssertionError("Missing registered production block ticker");
        ticker.tick(entity.getLevel(), entity.getBlockPos(), state, entity);
    }

    private static void finalDecompositionTimeline(GameTestHelper helper) {
        var machine = placeMachine(helper, "decomposer", false);
        var level = helper.getLevel();
        var pos = machine.entity.getBlockPos();
        helper.assertTrue(machine.input.getSlots() == 1 && machine.output.getSlots() == 9
                && level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.NORTH) == machine.output,
                "FINAL must expose one top input and nine lateral/downward outputs");
        helper.assertTrue(machine.power.receiveEnergy(100, true) == 20 && machine.power.getEnergyStored() == 0,
                "FINAL must accept up to 20 energy per receive without mutating during simulation");
        put(helper, machine.input, 0, new ItemStack(Items.IRON_INGOT));
        tick(machine);
        helper.assertTrue(machine.input.getStackInSlot(0).is(Items.IRON_INGOT) && machine.output.getStackInSlot(0).isEmpty(),
                "An unpowered FINAL decomposer started intake");
        charge(helper, machine.power, 1);
        tick(machine);
        helper.assertTrue(machine.input.getStackInSlot(0).isEmpty() && machine.output.getStackInSlot(0).isEmpty() && machine.power.getEnergyStored() == 1,
                "Intake should consume exactly one ingot without producing or charging on the same tick");
        var tag = machine.entity.saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(pos);
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        var restored = placeMachine(helper, "decomposer", false);
        helper.assertTrue(restored.entity != machine.entity, "Need a new block entity to test persistence");
        restored.entity.loadWithComponents(tag, level.registryAccess());
        var iron = stack(helper, MINECHEM, "element_fe");
        for (int i = 1; i <= 16; i++) {
            tick(restored);
            var output = restored.output.getStackInSlot(0);
            helper.assertTrue(output.is(iron.getItem()) && output.getCount() == i && restored.power.getEnergyStored() == 0,
                    "Saved FINAL queue did not emit exactly one iron unit on tick " + i + ": " + output);
        }
        tick(restored);
        helper.assertTrue(restored.output.getStackInSlot(0).getCount() == 16 && restored.input.getStackInSlot(0).isEmpty(),
                "The paid queue produced an extra item");
        helper.succeed();
    }

    private static void finalDecompositionJam(GameTestHelper helper) {
        var machine = placeMachine(helper, "decomposer", false);
        var iron = stack(helper, MINECHEM, "element_fe");
        var outputs = (IItemHandlerModifiable) machine.output;
        for (int slot = 0; slot < outputs.getSlots() - 1; slot++) outputs.setStackInSlot(slot, new ItemStack(Items.STONE, 64));
        int last = outputs.getSlots() - 1;
        outputs.setStackInSlot(last, iron.copyWithCount(63));
        charge(helper, machine.power, 1);
        put(helper, machine.input, 0, new ItemStack(Items.IRON_INGOT));
        tick(machine); // take one ingot
        tick(machine); // releases one item and consumes the last unit of charge
        tick(machine); // jammed, 15 units stay in the buffer
        helper.assertTrue(outputs.getStackInSlot(last).getCount() == 64 && machine.power.getEnergyStored() == 0,
                "The jammed machine did not spend only for the released unit");
        outputs.setStackInSlot(last, ItemStack.EMPTY);
        tick(machine);
        helper.assertTrue(outputs.getStackInSlot(last).isEmpty(), "An empty slot alone restarted a jammed unpowered FINAL decomposer");
        charge(helper, machine.power, 1);
        for (int i = 1; i <= 15; i++) tick(machine);
        helper.assertTrue(outputs.getStackInSlot(last).is(iron.getItem()) && outputs.getStackInSlot(last).getCount() == 15
                && machine.power.getEnergyStored() == 0, "The original resume rule did not preserve the remaining queue");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void machinesPersistAndDoNotSpendWhenJammed(GameTestHelper helper) {
        var machine = placeMachine(helper, "synthesizer", true);
        loadIngredients(helper, machine);
        charge(helper, machine.power, 600);
        helper.assertTrue(machine.menu.clickMenuButton(machine.user, 0), "Could not start process to save");
        var level = helper.getLevel();
        var snapshot = machine.entity.saveWithFullMetadata(level.registryAccess());
        var pos = machine.entity.getBlockPos();
        level.removeBlockEntity(pos);
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        var reloaded = placeMachine(helper, "synthesizer", false);
        helper.assertTrue(reloaded.entity != machine.entity, "Test must restore data into a new block entity");
        reloaded.entity.loadWithComponents(snapshot, level.registryAccess());
        helper.assertTrue(reloaded.power.getEnergyStored() == 600 && reloaded.input.getStackInSlot(0).getCount() == 2,
            "NBT did not restore inventory and energy");
        helper.runAfterDelay(22, () -> {
            var water = stack(helper, MINECHEM, "molecule_water");
            helper.assertTrue(ItemStack.isSameItem(reloaded.output.extractItem(0, 64, false), water)
                && reloaded.power.getEnergyStored() == 100 && reloaded.input.getStackInSlot(0).isEmpty(),
                "The in-flight job did not resume after reload");
            loadIngredients(helper, reloaded);
            charge(helper, reloaded.power, 500);
            helper.assertTrue(reloaded.menu.clickMenuButton(reloaded.user, 0), "Couldn't start another job");
            // Make the output fill while a job is active to exercise the commit-time check.
            var out = (IItemHandlerModifiable) reloaded.output;
            for (int i = 0; i < out.getSlots(); i++) out.setStackInSlot(i, new ItemStack(Items.STONE, 64));
            helper.runAfterDelay(22, () -> {
                helper.assertTrue(reloaded.power.getEnergyStored() == 600 && reloaded.input.getStackInSlot(0).getCount() == 2,
                    "A blocked output consumed power or inputs");
                out.setStackInSlot(0, ItemStack.EMPTY);
                helper.assertTrue(reloaded.menu.clickMenuButton(reloaded.user, 0), "Couldn't retry the blocked recipe");
                helper.runAfterDelay(22, () -> {
                    helper.assertTrue(ItemStack.isSameItem(out.getStackInSlot(0), water) && reloaded.power.getEnergyStored() == 100,
                        "The machine could not recover after an output jam");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty")
    public static void automaticDecompositionResumes(GameTestHelper helper) {
        if (FINAL_BUFFER) { finalDecompositionJam(helper); return; }
        var machine = placeMachine(helper, "decomposer", false);
        var water = stack(helper, MINECHEM, "molecule_water");
        helper.assertTrue(machine.output.insertItem(0, water.copy(), false).getCount() == 1, "Output accepted inserts");
        helper.assertTrue(machine.menu.clickMenuButton(machine.user, 5), "Could not enable automation");
        var level = helper.getLevel();
        var snapshot = machine.entity.saveWithFullMetadata(level.registryAccess());
        var pos = machine.entity.getBlockPos();
        level.removeBlockEntity(pos);
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        var reloaded = placeMachine(helper, "decomposer", false);
        reloaded.entity.loadWithComponents(snapshot, level.registryAccess());
        put(helper, reloaded.input, 0, water.copyWithCount(2));
        helper.runAfterDelay(12, () -> {
            helper.assertTrue(reloaded.input.getStackInSlot(0).getCount() == 2 && reloaded.output.getStackInSlot(0).isEmpty(),
                "Automation started without power");
            charge(helper, reloaded.power, 40);
            helper.runAfterDelay(18, () -> {
                var hydrogen = reloaded.output.extractItem(0, 64, false);
                var oxygen = reloaded.output.extractItem(1, 64, false);
                helper.assertTrue(hydrogen.is(stack(helper, MINECHEM, "element_h").getItem()) && hydrogen.getCount() == 4
                    && oxygen.is(stack(helper, MINECHEM, "element_o").getItem()) && oxygen.getCount() == 2,
                    "Automation did not process two batches after power was supplied");
                helper.assertTrue(reloaded.power.getEnergyStored() == 34 && reloaded.input.getStackInSlot(0).isEmpty(),
                    "Automation did not account for both transactions");
                helper.assertTrue(reloaded.menu.clickMenuButton(reloaded.user, 5), "Could not disable automation");
                put(helper, reloaded.input, 0, water);
                helper.runAfterDelay(10, () -> {
                    helper.assertTrue(reloaded.input.getStackInSlot(0).getCount() == 1 && reloaded.output.getStackInSlot(0).isEmpty(),
                        "Disabled automation still processed an input");
                    helper.succeed();
                });
            });
        });
    }

    private static BlockEntity placeMicroscope(GameTestHelper test, BlockPos pos) {
        var id = ResourceLocation.fromNamespaceAndPath(MINECHEM, "microscope");
        test.assertTrue(BuiltInRegistries.BLOCK.containsKey(id), "Missing microscope registration");
        test.getLevel().setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.get(id).defaultBlockState());
        var entity = test.getLevel().getBlockEntity(pos);
        test.assertTrue(entity instanceof MenuProvider, "Missing microscope inventory/menu");
        return entity;
    }

    private static AbstractContainerMenu microscopeMenu(BlockEntity entity, Player actor) {
        return ((MenuProvider) entity).createMenu(0, actor.getInventory(), actor);
    }

    private static CompoundTag journalTag(ItemStack journal) {
        return journal.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound("ChemistJournal");
    }

    private static List<String> discoveries(ItemStack journal) {
        return journalTag(journal).getList("Discoveries", Tag.TAG_STRING).stream().map(Tag::getAsString).toList();
    }

    @GameTest(template = "empty")
    public static void microscopeRecordsRecipesWithoutConsumingTheSample(GameTestHelper test) {
        var level = test.getLevel();
        var pos = test.absolutePos(BlockPos.ZERO);
        var actor = test.makeMockPlayer(GameType.SURVIVAL);
        actor.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        var microscope = placeMicroscope(test, pos);
        var menu = microscopeMenu(microscope, actor);
        var water = stack(test, MINECHEM, "molecule_water");
        var book = stack(test, MINECHEM, "chemist_journal");
        test.assertTrue(!menu.getSlot(0).mayPlace(book) && !menu.getSlot(1).mayPlace(water), "Only the journal belongs in the journal slot");
        actor.getInventory().setItem(0, water.copyWithCount(3));
        menu.quickMoveStack(actor, 2 + 27);
        test.assertTrue(menu.getSlot(0).getItem().is(water.getItem()) && menu.getSlot(0).getItem().getCount() == 1
                && actor.getInventory().getItem(0).getCount() == 2, "Shift-inserting a stack takes exactly one sample");
        var journalItem = book.getItem();
        actor.getInventory().setItem(1, book);
        menu.quickMoveStack(actor, 2 + 27 + 1);
        var recorded = menu.getSlot(1).getItem();
        test.assertTrue(recorded.is(journalItem) && actor.getInventory().getItem(1).isEmpty()
                && discoveries(recorded).equals(List.of(MINECHEM + ":molecule_water")),
                "Inserting the journal after a supported sample discovers that recipe: " + discoveries(recorded));
        menu.getSlot(0).set(new ItemStack(Items.DIRT)); // Not among the recovered recipes.
        test.assertTrue(discoveries(recorded).size() == 1, "Unsupported samples must not create discoveries");
        menu.getSlot(0).set(new ItemStack(Items.WATER_BUCKET));
        menu.getSlot(0).set(new ItemStack(Items.WATER_BUCKET));
        test.assertTrue(discoveries(recorded).equals(List.of(MINECHEM + ":molecule_water", "minecraft:water_bucket")),
                "A decomposition-only sample is recorded exactly once when inserted after the journal");
        var snapshot = microscope.saveWithFullMetadata(level.registryAccess());
        var savedBook = recorded.copy();
        level.removeBlockEntity(pos);
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        var reloaded = placeMicroscope(test, pos);
        reloaded.loadWithComponents(snapshot, level.registryAccess());
        var restored = microscopeMenu(reloaded, actor);
        test.assertTrue(!menu.stillValid(actor) && restored.stillValid(actor), "Only the live microscope's menu remains valid");
        test.assertTrue(restored.getSlot(0).getItem().is(Items.WATER_BUCKET)
                && ItemStack.matches(restored.getSlot(1).getItem(), savedBook), "Save/reload preserves both real slots and the journal's discoveries");
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1)).stream().map(ItemEntity::getItem).toList();
        test.assertTrue(drops.size() == 2 && drops.stream().anyMatch(item -> item.is(Items.WATER_BUCKET))
                && drops.stream().anyMatch(item -> ItemStack.matches(item, savedBook)), "Breaking the microscope drops only the sample and journal, never preview products");
        test.succeed();
    }

    @GameTest(template = "test_room")
    public static void savedJournalSelectsTheSynthesizerRecipe(GameTestHelper test) {
        var machine = placeMachine(test, "synthesizer", false);
        var level = test.getLevel();
        var pos = test.absolutePos(new BlockPos(2, 1, 2));
        var microscope = placeMicroscope(test, pos);
        var inspected = microscopeMenu(microscope, machine.user);
        var water = stack(test, MINECHEM, "molecule_water");
        inspected.getSlot(0).set(water.copy());
        inspected.getSlot(1).set(stack(test, MINECHEM, "chemist_journal"));
        var book = inspected.getSlot(1).remove(1);
        withPlayer(test, pos, actor -> {
            actor.setItemInHand(InteractionHand.MAIN_HAND, book);
            book.getItem().use(level, actor, InteractionHand.MAIN_HAND);
            var menu = actor.containerMenu;
            test.assertTrue(menu != actor.inventoryMenu && BuiltInRegistries.MENU.getKey(menu.getType()).equals(
                ResourceLocation.fromNamespaceAndPath(MINECHEM, "chemist_journal")), "Right-clicking the journal opened its menu");
            test.assertTrue(!menu.clickMenuButton(actor, 770) && menu.clickMenuButton(actor, 1), "Only a supported journal action may select an entry");
            test.assertTrue(journalTag(book).getString("Active").equals(MINECHEM + ":molecule_water")
                    && journalTag(book).getString("Owner").equals(actor.getGameProfile().getName()),
                    "The selected recipe and reader are saved on the held journal");
            menu.clicked(0, 0, ClickType.PICKUP, actor);
            test.assertTrue(menu.getCarried().isEmpty() && ItemStack.matches(menu.getSlot(0).getItem(), book),
                    "The book on the journal screen is only a display, not a movable item");
            actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            test.assertTrue(!menu.stillValid(actor) && !menu.clickMenuButton(actor, 1), "A stale journal menu cannot change another stack");
        });
        var reloaded = ItemStack.parse(level.registryAccess(), book.save(level.registryAccess())).orElseThrow();
        machine.user.getInventory().setItem(0, reloaded);
        var slot = machine.menu.getSlot(37);
        test.assertTrue(slot.isActive() && slot.mayPlace(reloaded) && !slot.mayPlace(water), "Synthesizer has a dedicated journal slot");
        test.assertTrue(WATER_INDEX > 0, "The journal must change the synthesizer's default selection in this fixture");
        machine.menu.quickMoveStack(machine.user, 38 + 27);
        test.assertTrue(ItemStack.matches(slot.getItem(), book) && machine.user.getInventory().getItem(0).isEmpty(),
                "The journal and its data move to the synthesizer slot");
        test.assertTrue(machine.input.getSlots() == 19 && machine.output.getSlots() == 18, "The journal does not enter the automated input/output inventory");
        loadIngredients(test, machine);
        charge(test, machine.power, 500);
        test.assertTrue(machine.menu.clickMenuButton(machine.user, 0) && !slot.mayPlace(book), "Start the journal's recipe without editing selection during a job");
        test.runAfterDelay(22, () -> {
            test.assertTrue(machine.output.getStackInSlot(0).is(water.getItem()) && machine.power.getEnergyStored() == 0
                    && journalTag(slot.getItem()).getString("Active").equals(MINECHEM + ":molecule_water"),
                    "The selected recipe produced water with the usual FE cost and kept the journal");
            test.succeed();
        });
    }

    private static void withPlayer(GameTestHelper test, BlockPos pos, Consumer<ServerPlayer> action) {
        var level = test.getLevel();
        var players = level.getServer().getPlayerList();
        var actor = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "JournalFixture"), ClientInformation.createDefault());
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

    @GameTest(template = "empty")
    public static void machinesCanBeCraftedInSurvival(GameTestHelper helper) {
        var iron = new ItemStack(Items.IRON_INGOT);
        var piston = new ItemStack(Items.PISTON);
        var dust = new ItemStack(Items.REDSTONE);
        var diamond = new ItemStack(Items.DIAMOND);
        check(helper, CraftingInput.of(3, 3, List.of(iron, iron, iron, iron, piston, iron, iron, dust, iron)), MINECHEM, "decomposer");
        check(helper, CraftingInput.of(3, 3, List.of(iron, dust, iron, iron, piston, iron, iron, diamond, iron)), MINECHEM, "synthesizer");
        var lensId = ResourceLocation.fromNamespaceAndPath(MINECHEM, "microscope_lens");
        var microscopeLens = new ItemStack(BuiltInRegistries.ITEM.containsKey(lensId) ? BuiltInRegistries.ITEM.get(lensId) : Items.SPYGLASS);
        check(helper, CraftingInput.of(3, 3, List.of(ItemStack.EMPTY, microscopeLens, iron,
                ItemStack.EMPTY, new ItemStack(Items.GLASS_PANE), iron, iron, iron, iron)), MINECHEM, "microscope");
        check(helper, CraftingInput.of(2, 1, List.of(new ItemStack(Items.BOOK), new ItemStack(Items.GLASS))), MINECHEM, "chemist_journal");
        helper.succeed();
    }
}
