package mcmodup.minechemoracle;

import java.io.PrintWriter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Ordered recipe registration, ghost matching, and payment against the exact FINAL machine. */
final class LegacyMinechemSynthesisFinal {
    private final Object world, unknown, element, molecule;
    private final Class<?> tile, stack, recipe, direction;
    private final Method get, set, choose, current, batch, affords, stored, receive, tick, meta, item, sided;
    private final Field count, rawId;
    private final Constructor<?> make;

    private LegacyMinechemSynthesisFinal(Object world) throws Exception {
        this.world = world;
        Class<?> items = Class.forName("minechem.MinechemItemsGeneration");
        element = items.getField("element").get(null);
        molecule = items.getField("molecule").get(null);
        tile = Class.forName("minechem.tileentity.synthesis.SynthesisTileEntity");
        recipe = Class.forName("minechem.tileentity.synthesis.SynthesisRecipe");
        stack = mc("item.ItemStack");
        direction = Class.forName("net.minecraftforge.common.ForgeDirection");
        unknown = direction.getField("UNKNOWN").get(null);
        count = stack.getField("field_77994_a");
        rawId = stack.getField("field_77993_c");
        meta = stack.getMethod("func_77960_j");
        item = stack.getMethod("func_77973_b");
        make = stack.getConstructor(int.class, int.class, int.class);
        get = tile.getMethod("func_70301_a", int.class);
        set = tile.getMethod("func_70299_a", int.class, stack);
        tick = tile.getMethod("func_70316_g");
        choose = tile.getMethod("setRecipe", recipe);
        current = tile.getMethod("getCurrentRecipe");
        batch = tile.getMethod("getOutput", int.class, boolean.class);
        affords = tile.getMethod("canTakeOutputStack");
        stored = tile.getMethod("getEnergy", direction);
        receive = tile.getMethod("onReceiveEnergy", direction, long.class, boolean.class);
        sided = tile.getMethod("func_94128_d", int.class);
    }

    static String survey(Object world, int x, int y, int z) throws Throwable {
        return new LegacyMinechemSynthesisFinal(world).run(x, y, z);
    }

    private String run(int x, int y, int z) throws Throwable {
        Object block = Class.forName("minechem.MinechemBlocksGeneration").getField("synthesis").get(null);
        int blockId = mc("block.Block").getField("field_71990_ca").getInt(block);
        Class<?> w = mc("world.World");
        require((Boolean) w.getMethod("func_94575_c", int.class, int.class, int.class, int.class).invoke(world, x, y, z, blockId),
                "Could not place original FINAL synthesizer");
        Object placed = w.getMethod("func_72796_p", int.class, int.class, int.class).invoke(world, x, y, z);
        require(tile.isInstance(placed), "Registered block did not produce a synthesizer");
        require((Integer) tile.getMethod("func_70302_i_").invoke(placed) == 20, "FINAL does not have 20 slots");
        long capacity = (Long) tile.getMethod("getEnergyCapacity", direction).invoke(placed, unknown);
        require(capacity == 1_022_220L && (Long) receive.invoke(placed, unknown, 10_000L, false) == 200L && energy(placed) == 0,
                "Unexpected FINAL synthesis power defaults");
        Object iron = make.newInstance(265, 1, 0);
        verifyJournal(placed, iron, x, y, z);

        List<?> recipes = (List<?>) recipe.getField("recipes").get(null);
        require(!recipes.isEmpty() && recipes.size() < 1000, "Unexpected FINAL recipe registry size: " + recipes.size());
        verifyContainer(recipes.get(0), x, y, z);
        Method shape = recipe.getMethod("getShapedRecipe"), shaped = recipe.getMethod("isShaped");
        Method output = recipe.getMethod("getOutput"), cost = recipe.getMethod("energyCost");
        // Looking up every helper signature reflectively links a client-only FontRenderer on the server.
        MethodHandle stacks = MethodHandles.publicLookup().findStatic(Class.forName("minechem.utils.MinechemHelper"),
                "convertChemicalArrayIntoItemStackArray", MethodType.methodType(java.lang.reflect.Array.newInstance(stack, 0).getClass(),
                shape.getReturnType()));
        int paid = 0, shadowed = 0, blocked = 0;
        try (PrintWriter out = new PrintWriter("minechem-final-synthesis.txt", "UTF-8")) {
            for (int i = 0; i < recipes.size(); i++) {
                Object rule = recipes.get(i), product = output.invoke(rule);
                Object[] ingredients = (Object[]) stacks.invoke(shape.invoke(rule));
                int price = (Integer) cost.invoke(rule), quantity = count.getInt(product);
                boolean fixed = (Boolean) shaped.invoke(rule);
                String reference = key(product);
                StringBuilder pattern = new StringBuilder();
                for (int j = 0; j < ingredients.length; j++) {
                    if (j > 0) pattern.append(',');
                    varPart(pattern, ingredients[j]);
                }
                String status;
                if (ingredients.length > 9) {
                    status = "grid";
                    blocked++;
                } else {
                    Object machine = fresh(x, y, z);
                    choose.invoke(machine, rule);
                    Object selected = current.invoke(machine);
                    int winner = recipes.indexOf(selected);
                    if (selected != rule) {
                        status = "shadow:" + winner;
                        shadowed++;
                    } else {
                        require(key(get.invoke(machine, 0)).equals(reference) && count.getInt(get.invoke(machine, 0)) == quantity,
                                "Wrong FINAL preview on row " + i);
                        if (price > capacity) {
                            status = "power";
                            blocked++;
                        } else if (!store(machine, ingredients)) {
                            status = "storage";
                            blocked++;
                        } else {
                            require(price > 0, "Nonpositive FINAL recipe cost");
                            charge(machine, price - 1);
                            require(!(Boolean) affords.invoke(machine), "Output available below original cost at row " + i);
                            charge(machine, 1);
                            require((Boolean) affords.invoke(machine) && energy(machine) == price, "Not ready at exact cost at row " + i);
                            List<?> products = (List<?>) batch.invoke(machine, 1, false);
                            require(products.size() == 1 && key(products.get(0)).equals(reference) && count.getInt(products.get(0)) == quantity
                                    && energy(machine) == 0, "Wrong FINAL batch or payment at row " + i);
                            for (int slot = 10; slot <= 18; slot++) require(get.invoke(machine, slot) == null, "Storage not exhausted at row " + i);
                            require(!(Boolean) affords.invoke(machine), "Unpaid second batch at row " + i);
                            status = "paid";
                            paid++;
                        }
                    }
                }
                out.println(reference + ";" + quantity + ";" + price + ";" + (fixed ? "shaped" : "shapeless") + ";" + pattern + ";" + status);
            }
            require(!out.checkError(), "Couldn't write ordered FINAL test trace");
        }
        require(paid > 100, "Too few observed FINAL synthesis extractions");
        return "FINAL synthesis: 20 slots, 9 ghost/9 storage, cap " + capacity + "/packet 200; default automation never withdraws, " +
                "ready non-top shows only output, journal reload, ghost editing, stone click/shift-click verified; " + recipes.size() + " ordered recipes, " +
                paid + " paid batches, " + shadowed + " shadowed canonical patterns and " + blocked + " grid/power/storage limits";
    }

    private void verifyContainer(Object stone, int x, int y, int z) throws Exception {
        Class<?> playerType = mc("entity.player.EntityPlayer"), invType = mc("entity.player.InventoryPlayer");
        Object player = Class.forName("net.minecraftforge.common.FakePlayer").getConstructor(mc("world.WorldServer"), String.class)
                .newInstance(world, "[MCModUp]");
        Object inv = playerType.getField("field_71071_by").get(player);
        Class<?> container = Class.forName("minechem.tileentity.synthesis.SynthesisContainer");
        Object machine = fresh(x, y, z);
        Object menu = container.getConstructor(invType, tile).newInstance(inv, machine);
        Method click = container.getMethod("func_75144_a", int.class, int.class, int.class, playerType);
        Method cursor = invType.getMethod("func_70437_b", stack), carried = invType.getMethod("func_70445_o");
        int chemical = mc("item.Item").getField("field_77779_bT").getInt(element);
        Object silicon = make.newInstance(chemical, 2, 13), oxygen = make.newInstance(chemical, 2, 7);
        cursor.invoke(inv, silicon);
        click.invoke(menu, 1, 0, 0, player);
        cursor.invoke(inv, oxygen);
        click.invoke(menu, 4, 1, 0, player);
        require(get.invoke(machine, 0) == null && count.getInt(get.invoke(machine, 4)) == 1, "Shaped FINAL ghost accepted too few oxygen tubes");
        click.invoke(menu, 4, 1, 0, player);
        tile.getMethod("func_70296_d").invoke(machine);
        require(current.invoke(machine) == stone && count.getInt(get.invoke(machine, 0)) == 7 && count.getInt(oxygen) == 2,
                "FINAL ghosts didn't copy the held amount, count by right-click or select an at-least shaped rule");
        click.invoke(menu, 9, 1, 0, player);
        require(current.invoke(machine) == stone, "Trailing ghost did not preserve original short shaped rule");
        cursor.invoke(inv, new Object[]{null});
        click.invoke(menu, 4, 0, 0, player);
        require(get.invoke(machine, 4) == null && current.invoke(machine) == null, "Empty cursor did not clear the shaped template");
        choose.invoke(machine, stone);
        require(store(machine, new Object[]{make.newInstance(chemical, 3, 13), make.newInstance(chemical, 6, 7)}), "Stone inputs did not fit");
        charge(machine, 1500);
        click.invoke(menu, 0, 0, 0, player);
        Object result = carried.invoke(inv);
        require(result != null && key(result).equals("v1:0") && count.getInt(result) == 3 && energy(machine) == 0,
                "FINAL click did not charge per requested item when fewer than seven crafts are funded");
        cursor.invoke(inv, new Object[]{null});
        require(store(machine, new Object[]{make.newInstance(chemical, 2, 13), make.newInstance(chemical, 4, 7)}), "Shift stone inputs did not fit");
        charge(machine, 1000);
        container.getMethod("func_82846_b", playerType, int.class).invoke(menu, player, 0);
        int made = 0;
        Method inSlot = invType.getMethod("func_70301_a", int.class);
        for (int i = 0; i < 36; i++) {
            Object found = inSlot.invoke(inv, i);
            if (found != null && key(found).equals("v1:0")) made += count.getInt(found);
        }
        require(made == 14 && energy(machine) == 0 && get.invoke(machine, 10) == null && get.invoke(machine, 11) == null,
                "FINAL shift-click did not craft two seven-stone batches: " + made + ", power=" + energy(machine));
    }

    private void verifyJournal(Object machine, Object target, int x, int y, int z) throws Exception {
        Object journalItem = Class.forName("minechem.MinechemItemsGeneration").getField("journal").get(null);
        Object book = stack.getConstructor(mc("item.Item"), int.class, int.class).newInstance(journalItem, 1, 0);
        journalItem.getClass().getMethod("addItemStackToJournal", stack, stack, mc("world.World")).invoke(journalItem, target, book, world);
        journalItem.getClass().getMethod("setActiveStack", stack, stack).invoke(journalItem, target, book);
        set.invoke(machine, 19, book);
        tick.invoke(machine);
        Object preview = get.invoke(machine, 0);
        require(preview != null && key(preview).equals("v265:0") && count.getInt(preview) == 1 && !(Boolean) affords.invoke(machine),
                "FINAL journal didn't preview unpowered iron synthesis");
        Object input = get.invoke(machine, 1);
        require(input != null && key(input).equals("e25") && count.getInt(input) == 16, "Wrong journal ghost amount for iron");
        for (int i = 2; i <= 18; i++) require(get.invoke(machine, i) == null, "Journal inserted unexpected ghosts or real chemicals");
        set.invoke(machine, 10, make.newInstance(rawId.getInt(input), 7, (Integer) meta.invoke(input)));
        set.invoke(machine, 18, make.newInstance(rawId.getInt(input), 9, (Integer) meta.invoke(input)));
        charge(machine, 9_999);
        require(!(Boolean) affords.invoke(machine), "Iron extracted below 10,000 UE");
        checkSides(machine, false, target);
        charge(machine, 1);
        checkSides(machine, true, target);
        Object[] simulation = (Object[]) tile.getMethod("extractOutput", boolean.class, int.class).invoke(machine, false, 1);
        require(simulation.length == 1 && key(simulation[0]).equals("v265:0") && energy(machine) == 10_000 &&
                count.getInt(get.invoke(machine, 10)) == 7 && count.getInt(get.invoke(machine, 18)) == 9,
                "FINAL simulated output changed actual inventory or charge");
        Class<?> tagType = mc("nbt.NBTTagCompound");
        Object tag = tagType.newInstance();
        tile.getMethod("func_70310_b", tagType).invoke(machine, tag);
        Object restored = fresh(x, y, z);
        tile.getMethod("func_70307_a", tagType).invoke(restored, tag);
        tile.getMethod("func_70312_q").invoke(restored);
        require((Boolean) affords.invoke(restored) && energy(restored) == 10_000 && get.invoke(restored, 19) != null,
                "FINAL did not restore a paid-ready recipe and journal");
        Method remove = tile.getMethod("func_70298_a", int.class, int.class);
        Object result = remove.invoke(restored, 0, 1);
        require(result != null && key(result).equals("v265:0") && energy(restored) == 0 && get.invoke(restored, 10) == null &&
                get.invoke(restored, 18) == null, "Manual FINAL extraction did not consume distributed ingredients and charge");
        Object removed = remove.invoke(restored, 19, 1);
        require(removed != null && item.invoke(removed) == journalItem && get.invoke(restored, 1) == null && get.invoke(restored, 19) == null,
                "Removing FINAL's journal did not clear the ghost grid");
        tile.getMethod("func_70296_d").invoke(restored);
        require(get.invoke(restored, 0) == null, "Removing FINAL's journal did not clear output after a slot notification");
    }

    private void checkSides(Object machine, boolean ready, Object item) throws Exception {
        Method extract = tile.getMethod("func_102008_b", int.class, stack, int.class);
        Field automation = Class.forName("minechem.Settings").getField("AllowAutomation");
        require(!automation.getBoolean(null), "Expected automation disabled in the pinned defaults");
        Method insert = tile.getMethod("func_102007_a", int.class, stack, int.class);
        for (int d = 0; d < 6; d++) {
            int[] visible = (int[]) sided.invoke(machine, d);
            boolean out = ready && d != 1;
            require(visible.length == (out ? 1 : 9) && visible[0] == (out ? 0 : 10), "Wrong FINAL face at " + d);
            require((Boolean) insert.invoke(machine, visible[0], item, d), "Default FINAL refused a sided insert");
            require(!(Boolean) extract.invoke(machine, visible[0], item, d), "Default FINAL allowed item extraction");
            automation.setBoolean(null, true);
            try {
                require((Boolean) extract.invoke(machine, visible[0], item, d) == ready,
                        "Configured FINAL extraction gate did not follow readiness on side " + d);
            } finally {
                automation.setBoolean(null, false);
            }
        }
    }

    private boolean store(Object machine, Object[] pattern) throws Exception {
        List<Object> inputs = new ArrayList<Object>();
        for (Object req : pattern) {
            if (req == null) continue;
            int remaining = count.getInt(req);
            while (remaining > 0) {
                int n = Math.min(remaining, (Integer) stack.getMethod("func_77976_d").invoke(req));
                require(n > 0, "Invalid ingredient stack limit");
                inputs.add(make.newInstance(rawId.getInt(req), n, (Integer) meta.invoke(req)));
                remaining -= n;
            }
        }
        if (inputs.size() > 9) return false;
        for (int i = 0; i < inputs.size(); i++) set.invoke(machine, 10 + i, inputs.get(i));
        return true;
    }

    private Object fresh(int x, int y, int z) throws Exception {
        Object machine = tile.newInstance();
        tile.getField("field_70331_k").set(machine, world);
        tile.getField("field_70329_l").setInt(machine, x);
        tile.getField("field_70330_m").setInt(machine, y);
        tile.getField("field_70327_n").setInt(machine, z);
        return machine;
    }
    private void varPart(StringBuilder pattern, Object part) throws Exception {
        if (part == null) pattern.append('_');
        else pattern.append(key(part)).append('x').append(count.getInt(part));
    }
    private String key(Object value) throws Exception {
        Object type = item.invoke(value);
        int damage = (Integer) meta.invoke(value);
        return type == element ? "e" + damage : type == molecule ? "m" + damage : "v" + rawId.getInt(value) + ":" + damage;
    }
    private long energy(Object machine) throws Exception { return (Long) stored.invoke(machine, unknown); }
    private void charge(Object machine, int amount) throws Exception {
        // Set through the public UE storage API so surveying hundreds of expensive recipes does not perform tens of thousands of calls.
        long before = energy(machine);
        tile.getMethod("setEnergy", direction, long.class).invoke(machine, unknown, before + amount);
        require(energy(machine) == before + amount, "Couldn't supply the requested original UE charge");
    }
    private static Class<?> mc(String suffix) throws ClassNotFoundException { return Class.forName("net.minecraft." + suffix); }
    private static void require(boolean condition, String error) { if (!condition) throw new AssertionError(error); }
}
