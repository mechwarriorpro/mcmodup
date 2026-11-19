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

    static void surveyPower(Object world, boolean paid, String profile, Map<String, String> rows) throws Exception {
        new LegacyMinechemSynthesis394(world).tunedStone(paid, profile, rows);
    }

    private void tunedStone(boolean paid, String profile, Map<String, String> rows) throws Exception {
        Class<?> handler = Class.forName("minechem.tileentity.synthesis.SynthesisRecipeHandler");
        Object lookup = handler.getField("instance").get(null);
        Object stone = Class.forName("cpw.mods.fml.common.registry.GameRegistry").getMethod("findItem", String.class, String.class)
            .invoke(null, "minecraft", "stone");
        Object sample = makeStack.newInstance(stone, 1, 0);
        Object rule = handler.getMethod("getRecipeFromOutput", stack).invoke(lookup, sample);
        int cost = (Integer) recipe.getMethod("energyCost").invoke(rule);
        Object machine = fresh();
        rows.put(profile + "_synthesizer_storage", LegacyMinechemPower394.storage(machine));
        choose.invoke(machine, rule);
        for (int i = 1, at = 10; i <= 9; ++i) {
            Object ingredient = get.invoke(machine, i);
            if (ingredient != null) set.invoke(machine, at++, stack.getMethod("func_77946_l").invoke(ingredient));
        }
        tick.invoke(machine);
        int needed = (Integer) tile.getMethod("getEnergyNeeded").invoke(machine);
        charge(machine, paid ? cost - 1 : 17);
        boolean below = (Boolean) canTake.invoke(machine, false);
        if (paid) charge(machine, 1);
        Object result = extract.invoke(machine, true, 64);
        Object output = result == null ? null : Array.get(result, 0);
        rows.put(profile + "_synthesizer_run", cost + "/" + needed + "/" + below + "/" +
            (same(sample, output) ? quantity(output) : 0) + "/" + stored.invoke(machine));
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
        String ports = automation(recipes, lookup, candidates.get("iron_ingot"));
        return "synthesis slots=29 (9 ghost + 18 storage + output + journal); capacity=" + capacity + "; receive=" + receiveLimit +
                "; " + observed.size() + " exact extraction traces; unresolved ghost collisions=" + mismatches + " | " + ports + " | " + containerClicks(recipes, lookup, candidates.get("stone"));
    }

    private String automation(Object recipes, Method lookup, Object iron) throws Exception {
        Object machine = fresh(), rule = lookup.invoke(recipes, iron);
        Object journalItem = Class.forName("minechem.MinechemItemsRegistration").getField("journal").get(null);
        Object journal = makeStack.newInstance(journalItem, 1, 0);
        Object tubes = makeStack.newInstance(element, 16, 26);
        Method insert = tile.getMethod("func_102007_a", int.class, stack, int.class);
        Method take = tile.getMethod("func_102008_b", int.class, stack, int.class);
        Method remove = tile.getMethod("func_70298_a", int.class, int.class);
        for (int side = 0; side < 6; side++) {
            int[] idle = (int[]) sided.invoke(machine, side);
            require(idle.length == 19 && idle[0] == 10 && idle[18] == 28, "Idle .394 side is not storage plus journal: " + side);
            require((Boolean) insert.invoke(machine, 28, journal, side) &&
                    (Boolean) insert.invoke(machine, 10, tubes, side) == (side != 0), "Wrong .394 input advice: " + side);
        }
        set.invoke(machine, 28, journal);
        choose.invoke(machine, rule);
        tick.invoke(machine);
        set.invoke(machine, 10, tubes);
        charge(machine, (Integer) recipe.getMethod("energyCost").invoke(rule));
        tick.invoke(machine);
        for (int side = 0; side < 6; side++) {
            int[] active = (int[]) sided.invoke(machine, side);
            require(active[active.length - 1] == 28 && active.length == (side == 1 ? 19 : 2) && active[0] == (side == 1 ? 10 : 0),
                    "Wrong .394 ready side: " + side);
            require((Boolean) take.invoke(machine, 0, iron, side) == (side == 0) &&
                    (Boolean) take.invoke(machine, 28, journal, side) == (side != 0) &&
                    !(Boolean) insert.invoke(machine, 28, journal, side), "Wrong .394 output or filled-journal advice: " + side);
        }
        Object result = remove.invoke(machine, 0, 1);
        require(same(result, iron) && quantity(result) == 1 && get.invoke(machine, 10) == null && (Integer) stored.invoke(machine) == 0,
                "Original sided output extraction did not consume one iron batch");
        require(same(remove.invoke(machine, 28, 1), journal), "Could not extract original journal");
        for (int slot = 1; slot <= 9; slot++) require(get.invoke(machine, slot) == null, "Original journal extraction left a ghost");
        return "automation: journal on all sides (extract except bottom); storage except ready non-top faces, chemical insert except bottom; bottom result only, paid on take";
    }

    private String containerClicks(Object recipes, Method lookup, Object stone) throws Exception {
        Class<?> playerType = mc("entity.player.EntityPlayer"), inventoryType = mc("entity.player.InventoryPlayer");
        Class<?> profileType = Class.forName("com.mojang.authlib.GameProfile");
        Constructor<?> profile = profileType.getConstructor(java.util.UUID.class, String.class);
        Constructor<?> fake = Class.forName("net.minecraftforge.common.util.FakePlayer").getConstructor(mc("world.WorldServer"), profileType);
        Constructor<?> container = Class.forName("minechem.tileentity.synthesis.SynthesisContainer").getConstructor(inventoryType, tile);
        Method clicked = mc("inventory.Container").getMethod("func_75144_a", int.class, int.class, int.class, playerType);
        Method cursor = inventoryType.getMethod("func_70445_o");
        Method at = inventoryType.getMethod("func_70301_a", int.class), put = inventoryType.getMethod("func_70299_a", int.class, stack);
        Object rule = lookup.invoke(recipes, stone);
        require(rule != null, "Missing stone recipe for original click replay");
        for (int mode = 0; mode < 4; mode++) {
            Object player = fake.newInstance(world, profile.newInstance(java.util.UUID.randomUUID(), "[Craft]"));
            Object inventory = playerType.getField("field_71071_by").get(player);
            Object machine = fresh();
            choose.invoke(machine, rule);
            tick.invoke(machine);
            int index = 10;
            for (int slot = 1; slot <= 9; slot++) {
                Object ingredient = get.invoke(machine, slot);
                if (ingredient != null) set.invoke(machine, index++, makeStack.newInstance(item.invoke(ingredient), 3 * quantity(ingredient), damage.invoke(ingredient)));
            }
            charge(machine, 900);
            tick.invoke(machine);
            Object menu = container.newInstance(inventory, machine);
            if (mode == 3) {
                Object dirt = mc("item.Item").getMethod("func_150899_d", int.class).invoke(null, 3);
                for (int slot = 0; slot < 36; slot++) put.invoke(inventory, slot, makeStack.newInstance(dirt, 64, 0));
                put.invoke(inventory, 0, makeStack.newInstance(item.invoke(stone), 48, 0));
            }
            clicked.invoke(menu, 0, mode == 1 ? 1 : 0, mode >= 2 ? 1 : 0, player);
            int energy = (Integer) stored.invoke(machine);
            if (mode < 2) {
                Object taken = cursor.invoke(inventory);
                require(same(taken, stone) && quantity(taken) == 16 && energy == 600, "Original .394 regular click did not take 16 stone for 300 RF (button " + mode + ")");
            } else {
                int acquired = 0;
                for (int slot = 0; slot < 36; slot++) {
                    Object taken = at.invoke(inventory, slot);
                    if (same(taken, stone)) acquired += quantity(taken);
                }
                require(acquired == (mode == 2 ? 48 : 64) && energy == 0,
                        "Original .394 shift click: room-limited=" + (mode == 3) + ", total=" + acquired + ", charge=" + energy);
            }
        }
        return ".394 container stone: left/right each yield 16 for 300 RF, shift yields 48 for 900; with room for 16, original still paid 900";
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
