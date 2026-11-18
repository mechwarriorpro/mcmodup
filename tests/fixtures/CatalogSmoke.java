package mcmodup.test;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@Mod(CatalogSmoke.ID)
@GameTestHolder(CatalogSmoke.ID)
@PrefixGameTestTemplate(false)
public final class CatalogSmoke {
    public static final String ID = "mcmodup_smoke";

    private static ItemStack stack(GameTestHelper helper, String namespace, String name) {
        var id = ResourceLocation.fromNamespaceAndPath(namespace, name);
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(id), "Missing converted item " + id);
        return new ItemStack(BuiltInRegistries.ITEM.get(id));
    }

    private static void check(GameTestHelper helper, CraftingInput input, String namespace, String result) {
        var level = helper.getLevel();
        var recipes = level.getRecipeManager();
        var match = recipes.getRecipeFor(RecipeType.CRAFTING, input, level);
        helper.assertTrue(match.isPresent(), "Missing recipe for " + namespace + ":" + result);
        var output = match.orElseThrow().value().assemble(input, level.registryAccess());
        helper.assertTrue(ItemStack.isSameItem(output, stack(helper, namespace, result)) && output.getCount() == 1,
                "Unexpected result " + output + " for " + match.orElseThrow().id());
        helper.succeed();
    }

    private static final String MINECHEM = "minechem_chemistry";
    private static final int WATER_INDEX = @WATER_INDEX@;

    private record Machine(BlockEntity entity, Player user, AbstractContainerMenu menu, IItemHandler input,
                           IItemHandler output, IEnergyStorage power) {}

    private static Machine placeMachine(GameTestHelper helper, String name, boolean waterRecipe) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(BlockPos.ZERO);
        var id = ResourceLocation.fromNamespaceAndPath(MINECHEM, name);
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

    private static void put(GameTestHelper helper, IItemHandler input, int slot, ItemStack stack) {
        helper.assertTrue(input.insertItem(slot, stack, false).isEmpty(), "Machine rejected " + stack);
    }

    private static void charge(GameTestHelper helper, IEnergyStorage power, int amount) {
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
        var out = (IItemHandlerModifiable) reloaded.output;
        for (int i = 0; i < out.getSlots(); i++) out.setStackInSlot(i, new ItemStack(Items.STONE, 64));
        helper.runAfterDelay(22, () -> {
            helper.assertTrue(reloaded.power.getEnergyStored() == 600 && reloaded.input.getStackInSlot(0).getCount() == 2,
                "A blocked output consumed power or inputs");
            out.setStackInSlot(0, ItemStack.EMPTY);
            helper.assertTrue(reloaded.menu.clickMenuButton(reloaded.user, 0), "Couldn't retry restored recipe");
            helper.runAfterDelay(22, () -> {
                helper.assertTrue(ItemStack.isSameItem(out.getStackInSlot(0), stack(helper, MINECHEM, "molecule_water"))
                    && reloaded.power.getEnergyStored() == 100, "Restored selection could not complete synthesis");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty")
    public static void aspectFromComponents(GameTestHelper helper) {
        String ns = "thaumcraft_aspects";
        check(helper, CraftingInput.of(2, 1, List.of(stack(helper, ns, "aspect_ignis"), stack(helper, ns, "aspect_aer"))), ns, "aspect_lux");
    }
}
