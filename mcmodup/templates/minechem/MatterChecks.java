package @PACKAGE@;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** These probes exercise the public surface of the generated or staged production JAR. */
@GameTestHolder("@TEST_NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class @CLASS@ {
    private static final String MOD = "@NAMESPACE@";
    private static final boolean NEWER = @RELEASE_394@;
    private static final long LIFE = @LIFETIME@L;
    private static final String[] REGISTERED_FLUIDS = {
@FLUIDS@
    };

    @GameTest(template = "test_room")
    public static void @PREFIX@FluidRegistryAndTank(GameTestHelper test) {
        var level = test.getLevel();
        for (String path : REGISTERED_FLUIDS) {
            var fluid = BuiltInRegistries.FLUID.get(id(path));
            test.assertTrue(fluid != Fluids.EMPTY && fluid.defaultFluidState().isSource(), "No source fluid for " + path);
            test.assertTrue(BuiltInRegistries.BLOCK.containsKey(id(path)) == NEWER,
                "Wrong placeability for " + path);
            test.assertTrue(BuiltInRegistries.ITEM.containsKey(id("bucket_" + path.substring(6))) == NEWER, "Wrong bucket availability for " + path);
        }
        var pos = place(test, "decomposer", new BlockPos(1, 1, 1));
        var tank = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, Direction.NORTH);
        var fluid = BuiltInRegistries.FLUID.get(id("fluid_element_fe"));
        var other = BuiltInRegistries.FLUID.get(ResourceLocation.fromNamespaceAndPath(
            NEWER ? "minechem_chemistry" : "minechem_chemistry_394", "fluid_element_fe"));
        if (other != Fluids.EMPTY) test.assertTrue(other != fluid && tank.fill(new FluidStack(other, 125), FluidAction.SIMULATE) == 0,
            "A decomposer accepted the other release's iron fluid");
        var power = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.UP);
        if (NEWER) {
            var offered = new FluidStack(fluid, 124);
            test.assertTrue(tank.fill(offered, FluidAction.SIMULATE) == 124 && tank.getFluidInTank(0).isEmpty(), "Fluid simulation changed the tank");
            test.assertTrue(tank.fill(offered, FluidAction.EXECUTE) == 124 && offered.getAmount() == 124, "Fill didn't retain source contents");
            charge(test, pos, 1000);
            tick(test, pos, 1);
            test.assertTrue(tank.getFluidInTank(0).getAmount() == 124 && power.getEnergyStored() == 1000, "124 mB incorrectly started a batch");
            test.assertTrue(tank.fill(new FluidStack(Fluids.WATER, 1), FluidAction.EXECUTE) == 0, "Second fluid mixed into the .394 tank");
            test.assertTrue(tank.fill(new FluidStack(fluid, 2), FluidAction.EXECUTE) == 2, "126 mB fill failed");
            tick(test, pos, 1);
            var output = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.DOWN);
            test.assertTrue(power.getEnergyStored() == 0 && tank.getFluidInTank(0).getAmount() == 1 && output.getStackInSlot(0).isEmpty(),
                "First tick must prepay, leave 1 mB and not emit a tube");
            reload(test, pos, "decomposer");
            tank = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, Direction.NORTH);
            var simulated = tank.drain(125, FluidAction.SIMULATE);
            test.assertTrue(simulated.is(fluid) && simulated.getAmount() == 1 && tank.getFluidInTank(0).getAmount() == 1,
                "Saved .394 fluid type or amount was lost");
            tick(test, pos, 1);
            output = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.DOWN);
            test.assertTrue(ItemStack.matches(output.getStackInSlot(0), item("element_fe", 1)), "Paid reaction didn't emit one iron vial on the next tick");
        } else {
            var chemicalWater = BuiltInRegistries.FLUID.get(id("fluid_molecule_water"));
            test.assertTrue(tank.getTanks() == 1 && tank.fill(new FluidStack(fluid, 500), FluidAction.SIMULATE) == 500
                && tank.getTanks() == 1, "FINAL simulation allocated a tank");
            test.assertTrue(tank.fill(new FluidStack(fluid, 500), FluidAction.EXECUTE) == 500 &&
                tank.fill(new FluidStack(chemicalWater, 500), FluidAction.EXECUTE) == 500 && tank.getTanks() == 3,
                "FINAL no longer accepts separate inert chemicals");
            charge(test, pos, 5);
            tick(test, pos, 8);
            test.assertTrue(tank.getFluidInTank(1).getAmount() == 500 && tank.getFluidInTank(2).getAmount() == 500 && power.getEnergyStored() == 5 &&
                tank.drain(125, FluidAction.EXECUTE).isEmpty(), "Inert FINAL fluids were drained or converted");
            reload(test, pos, "decomposer");
            tank = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, Direction.NORTH);
            test.assertTrue(tank.getTanks() == 1 && tank.getFluidInTank(0).isEmpty(), "FINAL saved an inert fluid");
        }
        test.succeed();
    }

    @GameTest(template = "test_room")
    public static void @PREFIX@StorageAndOre(GameTestHelper test) {
        var level = test.getLevel();
        var recipe = new ArrayList<ItemStack>();
        for (int i = 0; i < 9; i++) recipe.add(i == 4 ? new ItemStack(Items.CHEST) : item("element_pb", 1));
        test.assertTrue(craft(test, CraftingInput.of(3, 3, recipe)).is(item("leaded_chest", 1).getItem()), "Leaded chest needs eight local lead tubes");
        if (!NEWER) test.assertTrue(craft(test, CraftingInput.of(1, 1, List.of(item("chemical_storage", 1)))).is(item("leaded_chest", 1).getItem()),
                "FINAL chemical storage cannot be converted to a leaded chest");
        var pos = place(test, "leaded_chest", new BlockPos(1, 1, 1));
        var storage = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
        var u = radioactive(test, "element_u", 3, LIFE - 1, level.getGameTime() - 100);
        var before = u.copy();
        test.assertTrue(storage.getSlots() == 9 && storage.getSlotLimit(8) == 64 && storage.insertItem(0, u, false).isEmpty(), "Wrong leaded storage slots or write rules");
        test.assertTrue(ItemStack.matches(u, before), "Inserting a stack changed the caller's decay clock");
        reload(test, pos, "leaded_chest");
        storage = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.DOWN);
        var stored = storage.getStackInSlot(0).copy();
        test.assertTrue(stored.is(u.getItem()) && clock(test, stored).getLong("age") == LIFE - 1, "Saved leaded inventory altered radioactivity");
        test.assertTrue(storage.extractItem(0, 3, true).getCount() == 3 && ItemStack.matches(storage.getStackInSlot(0), stored), "Simulated storage extraction changed contents");
        var player = player(test, pos);
        var menu = ((MenuProvider) level.getBlockEntity(pos)).createMenu(1, player.getInventory(), player);
        test.assertTrue(menu.slots.size() == 45 && menu.stillValid(player), "Wrong leaded chest menu");
        menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        int back = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var recovered = player.getInventory().getItem(i);
            if (!recovered.is(u.getItem())) continue;
            back += recovered.getCount();
            test.assertTrue(clock(test, recovered).getLong("age") == LIFE - 1 && clock(test, recovered).getLong("tick") == level.getGameTime(),
                "Shield storage time wasn't paused");
        }
        test.assertTrue(back == 3 && storage.getStackInSlot(0).isEmpty(), "Chest shift-click lost or duplicated uranium");
        if (!NEWER) {
            var cabinet = place(test, "chemical_storage", new BlockPos(0, 1, 1));
            test.assertTrue(level.getCapability(Capabilities.ItemHandler.BLOCK, cabinet, Direction.UP).getSlots() == 27,
                "FINAL cabinet lacks its 27 slots");
        }
        pos = place(test, "decomposer", new BlockPos(2, 1, 1));
        var machine = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
        test.assertTrue(machine.insertItem(0, item("uranium_ore", 1), false).isEmpty(), "Decomposer accepts no local uranium ore");
        charge(test, pos, NEWER ? 1000 : 48);
        tick(test, pos, 53);
        var outputs = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.DOWN);
        test.assertTrue(outputs.getStackInSlot(0).is(u.getItem()) && outputs.getStackInSlot(0).getCount() == 48, "Uranium ore did not give 48 local tubes");
        test.succeed();
    }

    @GameTest(template = "test_room", timeoutTicks = 65)
    public static void @PREFIX@DecayBoundaryAndCatchup(GameTestHelper test) {
        // FINAL's original player scheduler updates every second; align this manual tick with it.
        long clock = test.getLevel().getGameTime();
        int delay = (int)(20 - (clock % 20));
        test.runAfterDelay(delay, () -> {
            var level = test.getLevel();
            var actor = player(test, test.absolutePos(new BlockPos(1, 1, 1)));
            for (long age : new long[]{LIFE - 1, LIFE, LIFE + 1, LIFE * 2 + 1}) {
                var sample = radioactive(test, "element_u", 3, age, level.getGameTime());
                actor.getInventory().setItem(0, sample);
                NeoForge.EVENT_BUS.post(new PlayerTickEvent.Pre(actor));
                var result = actor.getInventory().getItem(0);
                String expected = age < LIFE || (NEWER && age == LIFE) ? "element_u" :
                    (!NEWER && age > LIFE * 2 ? "element_th" : "element_pa");
                test.assertTrue(result.is(item(expected, 1).getItem()) && result.getCount() == 3, "Decay step did not match " + expected + " at " + age);
            }
            test.succeed();
        });
    }

@BUCKET_CHECKS@
@AUTOMATION_CHECKS@

    private static BlockPos place(GameTestHelper test, String path, BlockPos relative) {
        var absolute = test.absolutePos(relative);
        test.getLevel().setBlockAndUpdate(absolute, BuiltInRegistries.BLOCK.get(id(path)).defaultBlockState());
        return absolute;
    }
    private static void reload(GameTestHelper test, BlockPos position, String path) {
        var level = test.getLevel();
        var snapshot = level.getBlockEntity(position).saveWithFullMetadata(level.registryAccess());
        level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(position, BuiltInRegistries.BLOCK.get(id(path)).defaultBlockState());
        level.getBlockEntity(position).loadWithComponents(snapshot, level.registryAccess());
    }
    private static void charge(GameTestHelper test, BlockPos position, int amount) {
        var power = test.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, position, Direction.UP);
        while (amount > 0) {
            int accepted = power.receiveEnergy(amount, false);
            test.assertTrue(accepted > 0, "Could not charge test decomposer");
            amount -= accepted;
        }
    }
    private static void tick(GameTestHelper test, BlockPos position, int times) {
        var level = test.getLevel();
        var state = level.getBlockState(position);
        var tile = level.getBlockEntity(position);
        @SuppressWarnings("unchecked") var type = (BlockEntityType<BlockEntity>) tile.getType();
        var ticker = ((EntityBlock) state.getBlock()).getTicker(level, state, type);
        for (int i = 0; i < times; i++) ticker.tick(level, position, state, tile);
    }
    private static ItemStack radioactive(GameTestHelper test, String name, int count, long age, long tick) {
        var encoded = (CompoundTag) item(name, count).save(test.getLevel().registryAccess());
        var components = encoded.getCompound("components");
        var clock = new CompoundTag();
        clock.putLong("age", age);
        clock.putLong("tick", tick);
        clock.putString("dimension", test.getLevel().dimension().location().toString());
        components.put(MOD + ":decay_clock", clock);
        encoded.put("components", components);
        return ItemStack.parse(test.getLevel().registryAccess(), encoded).orElseThrow();
    }
    private static CompoundTag clock(GameTestHelper test, ItemStack stack) {
        return ((CompoundTag) stack.save(test.getLevel().registryAccess())).getCompound("components").getCompound(MOD + ":decay_clock");
    }
    private static ItemStack craft(GameTestHelper test, CraftingInput input) {
        var level = test.getLevel();
        return level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level)
            .map(recipe -> recipe.value().assemble(input, level.registryAccess())).orElse(ItemStack.EMPTY);
    }
    private static Player player(GameTestHelper test, BlockPos pos) {
        var actor = test.makeMockPlayer(GameType.SURVIVAL);
        actor.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 1.5);
        return actor;
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(MOD, path); }
    private static ItemStack item(String path, int amount) { return new ItemStack(BuiltInRegistries.ITEM.get(id(path)), amount); }
}
