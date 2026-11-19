package mcmodup.minechemoracle;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Read the .394 ghost templates and exercise extraction on the original tile, not FINAL's workflow. */
final class LegacyMinechemSynthesis394 {
    private final Object world, element, molecule;
    private final Class<?> tile, stack, recipe, itemType, worldType;
    private final Method set, get, count, stored, receive, tick, choose, extract, damage, item, canTake, sided;
    private final Constructor<?> makeStack;

    private LegacyMinechemSynthesis394(Object world) throws Exception {
        this.world = world;
        worldType = mc("world.World");
        itemType = mc("item.Item");
        stack = mc("item.ItemStack");
        Class<?> registration = Class.forName("minechem.MinechemItemsRegistration");
        element = registration.getField("element").get(null);
        molecule = registration.getField("molecule").get(null);
        recipe = Class.forName("minechem.tileentity.synthesis.SynthesisRecipe");
        tile = Class.forName("minechem.tileentity.synthesis.SynthesisTileEntity");
        set = tile.getMethod("func_70299_a", int.class, stack);
        get = tile.getMethod("func_70301_a", int.class);
        count = stack.getMethod("func_77976_d");
        stored = tile.getMethod("getEnergyStored");
        receive = tile.getMethod("receiveEnergy", int.class, boolean.class);
        tick = tile.getMethod("func_145845_h");
        choose = tile.getMethod("setRecipe", recipe);
        extract = tile.getMethod("extractOutput", boolean.class, int.class);
        damage = stack.getMethod("func_77960_j");
        item = stack.getMethod("func_77973_b");
        canTake = tile.getMethod("canTakeOutputStack", boolean.class);
        sided = tile.getMethod("func_94128_d", int.class);
        makeStack = stack.getConstructor(itemType, int.class, int.class);
    }

    static String survey(Object world) throws Exception {
        return new LegacyMinechemSynthesis394(world).run();
    }

    private String run() throws Exception {
        Class<?> registry = Class.forName("cpw.mods.fml.common.registry.GameRegistry");
        Method find = registry.getMethod("findItem", String.class, String.class);
        Map<String, Object> candidates = new TreeMap<String, Object>();
        for (String name : new String[]{"iron_ingot", "gold_ingot", "stone", "cobblestone", "glass", "glass_pane",
                                        "sand", "obsidian", "quartz", "diamond", "iron_block", "gold_block"}) {
            candidates.put(name, makeStack.newInstance(find.invoke(null, "minecraft", name), 1, 0));
        }
        Method output = recipe.getMethod("getOutput");
        for (Object rule : ((Map<?, ?>) recipe.getField("recipes").get(null)).values()) {
            Object result = output.invoke(rule);
            if (item.invoke(result) == molecule) candidates.put(chemical(result), result);
        }
        Class<?> handler = Class.forName("minechem.tileentity.synthesis.SynthesisRecipeHandler");
        Object recipes = handler.getField("instance").get(null);
        Method lookup = handler.getMethod("getRecipeFromOutput", stack), isShaped = recipe.getMethod("isShaped");
        Map<String, String> observed = new TreeMap<String, String>();
        List<String> mismatches = new ArrayList<String>();
        final int capacity = (Integer) tile.getMethod("getMaxEnergyStored").invoke(fresh());
        final int receiveLimit = (Integer) receive.invoke(fresh(), Integer.MAX_VALUE, true);
        for (Map.Entry<String, Object> entry : candidates.entrySet()) {
            Object rule = lookup.invoke(recipes, entry.getValue());
            if (rule == null) continue;
            Object machine = fresh();
            choose.invoke(machine, rule);
            tick.invoke(machine);
            Object selected = tile.getMethod("getCurrentRecipe").invoke(machine);
            Object result = get.invoke(machine, 0);
            if (selected != rule || !same(result, entry.getValue())) {
                // Ambiguous ghosts and recipes which can't be selected aren't claimed as playable.
                mismatches.add(entry.getKey());
                continue;
            }
            int cost = (Integer) recipe.getMethod("energyCost").invoke(rule);
            if (cost < 1 || cost > capacity) continue;
            require(!((Boolean) canTake.invoke(machine, false)), "Uncharged ghost output is withdrawable");
            List<String> inputs = new ArrayList<String>();
            int index = 10;
            for (int slot = 1; slot <= 9; slot++) {
                Object part = get.invoke(machine, slot);
                inputs.add(part == null ? "_" : chemical(part) + "x" + quantity(part));
                if (part == null) continue;
                int remaining = quantity(part);
                while (remaining > 0) {
                    int amount = Math.min(remaining, Math.min(64, (Integer) count.invoke(part)));
                    require(index < 28 && amount > 0, "Unrepresentable storage for " + entry.getKey());
                    set.invoke(machine, index++, makeStack.newInstance(item.invoke(part), amount, damage.invoke(part)));
                    remaining -= amount;
                }
            }
            charge(machine, cost - 1);
            require(extract.invoke(machine, false, 64) == null, "Original extracted at one RF below cost for " + entry.getKey());
            charge(machine, 1);
            tick.invoke(machine);
            require((Integer) stored.invoke(machine) == cost, "Original consumed energy before extraction");
            int[] top = (int[]) sided.invoke(machine, 1), bottom = (int[]) sided.invoke(machine, 0);
            require(top.length == 19 && top[0] == 10 && bottom.length == 2 && bottom[0] == 0,
                    "Unexpected sided shape on ready tile: " + entry.getKey());
            Object simulated = Array.get(extract.invoke(machine, false, 64), 0);
            require(same(simulated, result) && quantity(simulated) == quantity(result) && (Integer) stored.invoke(machine) == cost,
                    "Original preview simulation changed result or energy for " + entry.getKey());
            Object taken = Array.get(extract.invoke(machine, true, 64), 0);
            require(same(taken, result) && quantity(taken) == quantity(result) && (Integer) stored.invoke(machine) == 0,
                    "Unexpected concrete extraction for " + entry.getKey());
            for (int slot = 10; slot < index; slot++) require(get.invoke(machine, slot) == null, "Not all of the inputs were spent");
            require(extract.invoke(machine, true, 64) == null && same(get.invoke(machine, 0), result), "Ghost template was duplicated or destroyed");
            StringBuilder pattern = new StringBuilder();
            for (String part : inputs) {
                if (pattern.length() > 0) pattern.append(',');
                pattern.append(part);
            }
            observed.put(entry.getKey(), cost + ";" + quantity(result) + ";" + ((Boolean) isShaped.invoke(rule) ? "shaped" : "shapeless") + ";" + pattern);
        }
        require(observed.containsKey("iron_ingot") && observed.containsKey("stone") && observed.containsKey("m1"), "Missing .394 starter synthesis rules");
        require(capacity == 100000 && receiveLimit == 100, "Unexpected synthesis power defaults");
        try (java.io.PrintWriter file = new java.io.PrintWriter("minechem-synthesis.txt", "UTF-8")) {
            for (Map.Entry<String, String> row : observed.entrySet()) file.println(row.getKey() + "=" + row.getValue());
            require(!file.checkError(), "Could not write observed synthesis rows");
        }
        return "synthesis slots=29 (9 ghost + 18 storage + output + journal); capacity=" + capacity + "; receive=" + receiveLimit +
                "; " + observed.size() + " exact extraction traces; unresolved ghost collisions=" + mismatches;
    }

    private Object fresh() throws Exception {
        Object machine = tile.newInstance();
        tile.getMethod("func_145834_a", worldType).invoke(machine, world);
        require((Integer) tile.getMethod("func_70302_i_").invoke(machine) == 29, "Expected 29 original synthesizer slots");
        return machine;
    }

    private void charge(Object machine, int amount) throws Exception {
        while (amount > 0) {
            int accepted = (Integer) receive.invoke(machine, amount, false);
            require(accepted > 0 && accepted <= amount, "Could not charge original synthesizer");
            amount -= accepted;
        }
    }

    private int quantity(Object value) throws Exception { return stack.getField("field_77994_a").getInt(value); }
    private boolean same(Object left, Object right) throws Exception {
        return left != null && right != null && item.invoke(left) == item.invoke(right) && damage.invoke(left).equals(damage.invoke(right));
    }
    private String chemical(Object value) throws Exception {
        Object kind = item.invoke(value);
        require(kind == element || kind == molecule, "Unexpected synthesis ingredient identity");
        return (kind == element ? "e" : "m") + damage.invoke(value);
    }
    private static Class<?> mc(String name) throws Exception { return Class.forName("net.minecraft." + name); }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
