package mcmodup.minechemoracle;

import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Read recipe identities from the live .394 registry, but measure yield and order by ticking its real tile. */
final class LegacyMinechemMaterials {
    private static final String[] VANILLA = {
        "apple", "arrow", "bone", "bucket", "coal_ore", "diamond", "diamond_block", "diamond_ore", "emerald",
        "emerald_block", "emerald_ore", "glass", "glass_pane", "glowstone", "glowstone_dust", "gold_block", "gold_ingot",
        "gold_ore", "iron_block", "iron_ingot", "iron_ore", "obsidian", "quartz", "sand", "water_bucket"
    };
    private static final int COST = 1000;
    private final Class<?> tile, worldType, stack, recipe;
    private final Object world, element, molecule;
    private final Constructor<?> makeStack;
    private final Method item, meta, find, get, set, tick, insertEnergy, energy, take, valid;
    private final Field count;

    private LegacyMinechemMaterials(Object placed, Object world) throws Exception {
        this.world = world;
        tile = placed.getClass();
        worldType = mc("world.World");
        stack = mc("item.ItemStack");
        Class<?> itemType = mc("item.Item");
        Class<?> items = Class.forName("minechem.MinechemItemsRegistration");
        recipe = Class.forName("minechem.tileentity.decomposer.DecomposerRecipe");
        element = items.getField("element").get(null);
        molecule = items.getField("molecule").get(null);
        makeStack = stack.getConstructor(itemType, int.class, int.class);
        item = stack.getMethod("func_77973_b");
        meta = stack.getMethod("func_77960_j");
        count = stack.getField("field_77994_a");
        find = Class.forName("cpw.mods.fml.common.registry.GameRegistry").getMethod("findItem", String.class, String.class);
        get = tile.getMethod("func_70301_a", int.class);
        set = tile.getMethod("func_70299_a", int.class, stack);
        take = tile.getMethod("func_70298_a", int.class, int.class);
        tick = tile.getMethod("func_145845_h");
        insertEnergy = tile.getMethod("receiveEnergy", int.class, boolean.class);
        energy = tile.getMethod("getEnergyStored");
        valid = tile.getMethod("func_94041_b", int.class, stack);
    }

    static String survey(Object placed, Object world) throws Exception {
        LegacyMinechemMaterials probe = new LegacyMinechemMaterials(placed, world);
        Map<String, String> result = new TreeMap<String, String>();
        Method getRecipe = probe.recipe.getMethod("get", probe.stack);
        for (String name : VANILLA) {
            Object input = probe.vanilla(name);
            Object rule = getRecipe.invoke(null, input);
            require(rule != null && rule.getClass() == probe.recipe, "Not a direct deterministic recipe: " + name);
            result.put(name, probe.itemBatch(input, name));
        }
        Collection<?> rules = ((Map<?, ?>) probe.recipe.getField("recipes").get(null)).values();
        Method getInput = probe.recipe.getMethod("getInput");
        int compounds = 0;
        for (Object rule : rules) {
            Object input = getInput.invoke(rule);
            if (input == null || probe.item.invoke(input) != probe.molecule) continue;
            String key = "m" + probe.meta.invoke(input);
            require(rule.getClass() == probe.recipe && getRecipe.invoke(null, input) == rule, "Nonliteral molecule recipe " + key);
            require(result.put(key, probe.itemBatch(input, key)) == null, "Duplicate molecule recipe " + key);
            compounds++;
        }
        require(compounds > 100, "The original molecule registry was not populated: " + compounds);
        result.put("fluid_water", probe.water());
        // Only observations leave the scratch world, not recipes classes or game files.
        try (PrintWriter file = new PrintWriter("minechem-materials.txt", "UTF-8")) {
            for (Map.Entry<String, String> row : result.entrySet()) file.println(row.getKey() + "=" + row.getValue());
            require(!file.checkError(), "Could not record material observations");
        }
        return VANILLA.length + " deterministic vanilla items; " + compounds + " molecule inputs; water tank and item priority";
    }

    private Object vanilla(String name) throws Exception {
        Object value = find.invoke(null, "minecraft", name);
        require(value != null, "Missing original vanilla item " + name);
        return makeStack.newInstance(value, 1, 0);
    }

    private Object fresh() throws Exception {
        Object machine = tile.newInstance();
        tile.getMethod("func_145834_a", worldType).invoke(machine, world);
        return machine;
    }

    private String itemBatch(Object input, String name) throws Exception {
        Object machine = fresh();
        require((Boolean) valid.invoke(machine, 0, input), "Original rejected " + name);
        Object copy = stack.getMethod("func_77946_l").invoke(input);
        count.setInt(copy, 1);
        set.invoke(machine, 0, copy);
        charge(machine);
        checkIntake(machine, name);
        require(get.invoke(machine, 0) == null, "Original did not consume one " + name);
        return drainBatch(machine, name);
    }

    private String water() throws Exception {
        Class<?> fluids = Class.forName("net.minecraftforge.fluids.FluidRegistry"), fluid = Class.forName("net.minecraftforge.fluids.Fluid");
        Class<?> fluidStack = Class.forName("net.minecraftforge.fluids.FluidStack");
        Class<?> direction = Class.forName("net.minecraftforge.common.util.ForgeDirection");
        Object side = direction.getField("NORTH").get(null), water = fluids.getField("WATER").get(null), lava = fluids.getField("LAVA").get(null);
        Constructor<?> makeFluid = fluidStack.getConstructor(fluid, int.class);
        Method fill = tile.getMethod("fill", direction, fluidStack, boolean.class);
        Method drain = tile.getMethod("drain", direction, int.class, boolean.class);
        Field tank = tile.getField("tank"), amount = fluidStack.getField("amount");
        Object machine = fresh();
        Object oversized = makeFluid.newInstance(water, 7000);
        require((Integer) fill.invoke(machine, side, oversized, false) == 5000 && tank.get(machine) == null, "Water fill should simulate 5000 mB with no mutation");
        require((Integer) fill.invoke(machine, side, oversized, true) == 5000, "Unexpected live tank capacity");
        require((Integer) fill.invoke(machine, side, makeFluid.newInstance(lava, 1), true) == 0, "Second fluid mixed into original tank");
        require(amount.getInt(drain.invoke(machine, side, 4001, false)) == 4001 && amount.getInt(tank.get(machine)) == 5000, "Simulated drain mutated the tank");
        require(amount.getInt(drain.invoke(machine, side, 4001, true)) == 4001 && amount.getInt(tank.get(machine)) == 999, "Actual drain did not leave 999 mB");
        charge(machine);
        tick.invoke(machine);
        require(amount.getInt(tank.get(machine)) == 999 && (Integer) energy.invoke(machine) == COST, "Less than a bucket is enough in the original");
        require((Integer) fill.invoke(machine, side, makeFluid.newInstance(water, 1001), true) == 1001, "Original water tank did not accept more water");
        set.invoke(machine, 0, vanilla("iron_ingot"));
        checkIntake(machine, "item with water present");
        require(amount.getInt(tank.get(machine)) == 2000 && get.invoke(machine, 0) == null, "Original must prioritize the item over the fluid");
        require(drainBatch(machine, "iron with water present").equals("e26x16"), "Wrong iron output when water is present");
        charge(machine);
        checkIntake(machine, "water");
        require(amount.getInt(tank.get(machine)) == 1000, "Original did not consume exactly one bucket");
        Class<?> tag = mc("nbt.NBTTagCompound");
        Object save = tag.newInstance(), loaded = fresh();
        tile.getMethod("func_145841_b", tag).invoke(machine, save);
        tile.getMethod("func_145839_a", tag).invoke(loaded, save);
        require(amount.getInt(tank.get(loaded)) == 1000, "Original did not save the remaining fluid");
        return drainBatch(loaded, "saved water batch");
    }

    private void charge(Object machine) throws Exception {
        for (int i = 0; i < 10; i++) require((Integer) insertEnergy.invoke(machine, 100, false) == 100, "Charging .394 failed");
        require((Integer) energy.invoke(machine) == COST, "Wrong charge before intake");
    }

    private void checkIntake(Object machine, String name) throws Exception {
        tick.invoke(machine);
        require((Integer) energy.invoke(machine) == 0 && get.invoke(machine, 1) == null, "Wrong .394 intake cost or early output for " + name);
    }

    private String drainBatch(Object machine, String name) throws Exception {
        List<String> parts = new ArrayList<String>();
        String previous = "";
        int tally = 0;
        for (int ticks = 0; ticks <= 4096; ticks++) {
            tick.invoke(machine);
            Object output = get.invoke(machine, 1);
            require((Integer) energy.invoke(machine) == 0 && get.invoke(machine, 2) == null, "Extra output or energy change for " + name);
            if (output == null) {
                require(tally > 0, "Empty output for " + name);
                parts.add(previous + "x" + tally);
                return join(parts);
            }
            require(count.getInt(output) == 1, "The original must emit one tube per tick for " + name);
            Object outputItem = item.invoke(output);
            require(outputItem == element || outputItem == molecule, "Unexpected output item for " + name);
            String key = (outputItem == element ? "e" : "m") + meta.invoke(output);
            if (!key.equals(previous)) {
                if (tally > 0) parts.add(previous + "x" + tally);
                previous = key;
                tally = 0;
            }
            tally++;
            take.invoke(machine, 1, 1);
        }
        throw new AssertionError("More than 4096 output ticks for " + name);
    }

    private static String join(List<String> parts) {
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (result.length() > 0) result.append(',');
            result.append(part);
        }
        return result.toString();
    }
    private static Class<?> mc(String name) throws Exception { return Class.forName("net.minecraft." + name); }
    private static void require(boolean ok, String error) { if (!ok) throw new AssertionError(error); }
}
