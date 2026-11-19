package mcmodup.minechemoracle;

import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Capture registered in-game rules, not guessed formula inversions or modern crafting recipes. */
final class LegacyMinechemChemistry {
    private final boolean old;
    private final Object world, elements, molecules, polytool;
    private final Class<?> item, stack, element, molecule, decomposition;
    private final Method damage, itemOf, itemId;
    private final Field size, chemicalCount;

    private LegacyMinechemChemistry(Object world, boolean old) throws Exception {
        this.world = world;
        this.old = old;
        Class<?> items = Class.forName(old ? "minechem.MinechemItemsGeneration" : "minechem.MinechemItemsRegistration");
        elements = items.getField("element").get(null);
        molecules = items.getField("molecule").get(null);
        polytool = items.getField("polytool").get(null);
        element = Class.forName("minechem.item.element.Element");
        molecule = Class.forName("minechem.item.molecule.Molecule");
        chemicalCount = Class.forName("minechem.potion.PotionChemical").getField("amount");
        item = mc("item.Item");
        stack = mc("item.ItemStack");
        damage = stack.getMethod("func_77960_j");
        size = stack.getField("field_77994_a");
        itemOf = stack.getMethod("func_77973_b");
        itemId = old ? null : item.getMethod("func_150891_b", item);
        decomposition = Class.forName("minechem.tileentity.decomposer.DecomposerRecipe");
    }

    static String survey(Object world, boolean old) throws Exception {
        return new LegacyMinechemChemistry(world, old).run();
    }

    private String run() throws Exception {
        // FINAL's lookup is a first-match list, .394 uses a keyed registry and explicit weighted references.
        Object registry = decomposition.getField("recipes").get(null);
        Map<String, Object> items = new LinkedHashMap<String, Object>();
        Map<Object, String> indexed = new LinkedHashMap<Object, String>();
        Collection<?> values = registry instanceof Map ? ((Map<?, ?>) registry).values() : (Collection<?>) registry;
        int count = 0, derived = 0;
        for (Object rule : values) {
            String key = inputKey(rule);
            if (key == null) continue;
            items.put(key, items.containsKey(key) ? items.get(key) : rule);
        }
        int registered = items.size();
        if (!old) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) registry).entrySet()) {
                indexed.put(entry.getKey(), inputKey(entry.getValue()));
            }
        } else {
            // Observe stable outcomes through the original's bounded recursive fallback.
            Constructor<?> numeric = stack.getConstructor(int.class, int.class, int.class);
            Class<?> handler = Class.forName("minechem.tileentity.decomposer.DecomposerRecipeHandler");
            Object resolver = handler.getField("instance").get(null);
            Method lookup = handler.getMethod("getRecipe", stack);
            for (int id = 1; id <= 2300; id++) {
                if (id > 422 && id < 2256) continue;
                String key = "v" + id + ":0";
                if (items.containsKey(key) || items.containsKey("v" + id + ":-1")) continue;
                Object sample = numeric.newInstance(id, 1, 0);
                if (itemOf.invoke(sample) == null) continue;
                Object found = lookup.invoke(resolver, sample);
                if (found == null || found.getClass() != decomposition) continue;
                String first = node(found, indexed);
                if (first.equals("-")) continue;
                boolean stable = true;
                for (int check = 0; check < 3; check++) {
                    Object next = lookup.invoke(resolver, sample);
                    if (next == null || !node(next, indexed).equals(first)) { stable = false; break; }
                }
                if (stable) { items.put(key, found); derived++; }
            }
        }
        try (PrintWriter out = new PrintWriter("minechem-all-decomposition.txt", "UTF-8")) {
            for (Map.Entry<String, Object> entry : new TreeMap<String, Object>(items).entrySet()) {
                String value = node(entry.getValue(), indexed);
                if (!value.equals("-")) {
                    out.println(entry.getKey() + "=" + value);
                    count++;
                }
            }
            require(!out.checkError(), "Could not write decomposition evidence");
        }
        int effects = pharmacology();
        foodAndSword();
        int synthesis = old ? 0 : synthesis();
        return "material survey " + count + " rows (" + registered + " registered inputs, " + derived +
                " stable FINAL fallback results), " + effects + " effect observations, " + synthesis + " additional synthesis catalogue observations";
    }

    private String inputKey(Object rule) throws Exception {
        if (rule.getClass().getSimpleName().startsWith("DecomposerFluidRecipe")) {
            Object fluidStack = rule.getClass().getField("inputFluid").get(rule);
            Object fluid = fluidStack.getClass().getMethod("getFluid").invoke(fluidStack);
            if (fluid == null) return null;
            String name = (String) fluid.getClass().getMethod("getName").invoke(fluid);
            return "f" + name + ":" + fluidStack.getClass().getField("amount").getInt(fluidStack);
        }
        return stackKey(decomposition.getMethod("getInput").invoke(rule));
    }

    private String stackKey(Object input) throws Exception {
        if (input == null) return null;
        Object of = itemOf.invoke(input);
        if (of == null) return null;
        int meta = (Integer) damage.invoke(input);
        if (of == elements) return "e" + meta;
        if (of == molecules) return "m" + meta;
        if (of == polytool) return "cPolytool:" + meta;
        int id = old ? stack.getField("field_77993_c").getInt(input) : (Integer) itemId.invoke(null, of);
        boolean vanilla = id >= 2256 && id <= 2267 || id > 0 && id < 423;
        if (!old) {
            Object registry = item.getField("field_150901_e").get(null);
            Object name = registry.getClass().getMethod("func_148750_c", Object.class).invoke(registry, of);
            vanilla = name != null && name.toString().startsWith("minecraft:");
        }
        if (vanilla) return "v" + id + ":" + meta;
        String key = (String) stack.getMethod("func_77977_a").invoke(input);
        return "c" + key + ":" + meta;
    }

    private String part(Object value) throws Exception {
        if (value == null) return "_";
        boolean e = element.isInstance(value);
        require(e || molecule.isInstance(value), "Unexpected product type: " + value.getClass());
        Object kind = (e ? element : molecule).getField(e ? "element" : "molecule").get(value);
        int id = (Integer) kind.getClass().getMethod(e ? (old ? "ordinal" : "atomicNumber") : "id").invoke(kind);
        return (e ? "e" : "m") + id + "x" + chemicalCount.getInt(value);
    }

    private String parts(Iterable<?> products) throws Exception {
        StringBuilder row = new StringBuilder();
        for (Object value : products) {
            if (value == null || chemicalCount.getInt(value) <= 0) continue;
            if (row.length() > 0) row.append(',');
            row.append(part(value));
        }
        return row.length() == 0 ? "-" : row.toString();
    }

    private String node(Object rule, Map<Object, String> indexed) throws Exception {
        String kind = rule.getClass().getSimpleName();
        if (kind.endsWith("Select")) {
            List<?> choices = (List<?>) rule.getClass().getMethod("getAllPossibleRecipes").invoke(rule);
            StringBuilder result = new StringBuilder("S").append(rule.getClass().getMethod("getChance").invoke(rule)).append('!');
            for (int i = 0; i < choices.size(); i++) {
                if (i > 0) result.append('|');
                result.append(node(choices.get(i), indexed));
            }
            return result.toString();
        }
        if (kind.endsWith("Super")) {
            Object base = rule.getClass().getMethod("getGuaranteedOutput").invoke(rule);
            Map<?, ?> refs = (Map<?, ?>) field(rule, "recipes");
            StringBuilder row = new StringBuilder("R").append(parts((List<?>) base));
            for (Map.Entry<?, ?> ref : refs.entrySet()) {
                String name = indexed.get(ref.getKey());
                if (name != null) row.append('|').append(name).append('@').append(ref.getValue());
            }
            return row.toString();
        }
        String products = parts((Iterable<?>) decomposition.getMethod("getOutputRaw").invoke(rule));
        return kind.endsWith("Chance") ? "C" + rule.getClass().getMethod("getChance").invoke(rule) + "!" + products : products;
    }

    static String synthesisRule(Object world, Object output) throws Exception {
        LegacyMinechemChemistry probe = new LegacyMinechemChemistry(world, false);
        Class<?> handler = Class.forName("minechem.tileentity.synthesis.SynthesisRecipeHandler");
        Object rule = handler.getMethod("getRecipeFromOutput", probe.stack).invoke(handler.getField("instance").get(null), output);
        return rule == null ? "-" : probe.synthesisRow(rule);
    }

    private String synthesisRow(Object rule) throws Exception {
        Class<?> type = Class.forName("minechem.tileentity.synthesis.SynthesisRecipe");
        Object output = type.getMethod("getOutput").invoke(rule);
        boolean shaped = (Boolean) type.getMethod("isShaped").invoke(rule);
        Object pattern = type.getMethod(shaped ? "getShapedRecipe" : "getShapelessRecipe").invoke(rule);
        if (pattern == null) return null;
        StringBuilder ghost = new StringBuilder();
        for (int i = 0; i < Array.getLength(pattern); i++) {
            if (i > 0) ghost.append(',');
            ghost.append(part(Array.get(pattern, i)));
        }
        return size.getInt(output) + ";" + type.getMethod("energyCost").invoke(rule) + ";" + (shaped ? "shaped" : "shapeless") + ";" + ghost;
    }

    private int synthesis() throws Exception {
        Class<?> type = Class.forName("minechem.tileentity.synthesis.SynthesisRecipe");
        Map<String, String> rows = new TreeMap<String, String>();
        for (Object rule : ((Map<?, ?>) type.getField("recipes").get(null)).values()) {
            String key = stackKey(type.getMethod("getOutput").invoke(rule));
            if (key == null) continue;
            String row = synthesisRow(rule);
            if (row != null) rows.put(key, row);
        }
        output("minechem-all-synthesis.txt", rows);
        return rows.size();
    }

    private int pharmacology() throws Exception {
        Class<?> m = Class.forName("minechem.item.molecule.MoleculeEnum");
        Map<String, String> rows = new TreeMap<String, String>();
        if (old) {
            Class<?> p = Class.forName("minechem.potion.PotionPharmacologyEffect");
            Method gives = p.getMethod("givesEffect", m);
            for (Object v : m.getEnumConstants()) {
                Object player = player();
                Object hunger = foodStats(player);
                Field foodLevel = hunger.getClass().getDeclaredField("field_75127_a");
                foodLevel.setAccessible(true);
                foodLevel.setInt(hunger, 0);
                Field saturation = hunger.getClass().getDeclaredField("field_75125_b");
                saturation.setAccessible(true);
                saturation.setFloat(hunger, 0F);
                Class<?> potion = mc("potion.PotionEffect");
                player.getClass().getMethod("func_70690_d", potion).invoke(player, potion.getConstructor(int.class, int.class, int.class).newInstance(13, 400, 0));
                int id = (Integer) m.getMethod("id").invoke(v);
                Object tube = makeStack(molecules, 2, id);
                // Drive the actual drink completion, including decrement and server-side effects.
                Object result = stack.getMethod("func_77950_b", mc("world.World"), mc("entity.player.EntityPlayer")).invoke(tube, world, player);
                require(size.getInt(result) == 1, "FINAL drink did not consume one tube: " + id);
                Collection<?> active = (Collection<?>) player.getClass().getMethod("func_70651_bq").invoke(player);
                List<String> effects = new ArrayList<String>();
                Map<Integer, String> potions = new TreeMap<Integer, String>();
                boolean cleared = true;
                for (Object effect : active) {
                    int potionId = (Integer) potion.getMethod("func_76456_a").invoke(effect);
                    if (potionId == 13) { cleared = false; continue; }
                    potions.put(potionId, "p" + potionId + ":" + potion.getMethod("func_76459_b").invoke(effect) + ":" + potion.getMethod("func_76458_c").invoke(effect));
                }
                if (cleared) effects.add("c-1");
                effects.addAll(potions.values());
                int food = (Integer) hunger.getClass().getMethod("func_75116_a").invoke(hunger);
                if (food > 0) effects.add("f" + food + ":" + (saturation.getFloat(hunger) / (2 * food)));
                Object pig = pig();
                p.getMethod("triggerPlayerEffect", m, mc("entity.EntityLivingBase")).invoke(null, v, pig);
                float lost = 10F - (Float) pig.getClass().getMethod("func_110143_aJ").invoke(pig);
                if (lost > 0) effects.add("d" + lost);
                Field burn = mc("entity.Entity").getDeclaredField("field_70151_c");
                burn.setAccessible(true);
                if (burn.getInt(pig) > 0) effects.add("b" + burn.getInt(pig) / 20);
                rows.put("m" + id, ((Boolean) gives.invoke(null, v) ? "yes;" : "no;") + join(effects));
            }
        } else {
            Class<?> registry = Class.forName("minechem.potion.PharmacologyEffectRegistry");
            for (Map.Entry<?, ?> row : ((Map<?, ?>) staticField(registry, "effects")).entrySet()) {
                List<String> parts = new ArrayList<String>();
                for (Object value : (List<?>) row.getValue()) {
                    String kind = value.getClass().getSimpleName();
                    if (kind.equals("Potion")) parts.add("p" + field(value, "potionId") + ":" + field(value, "duration") + ":" + field(value, "power"));
                    else if (kind.equals("Food")) parts.add("f" + field(value, "level") + ":" + field(value, "saturation"));
                    else if (kind.equals("Cure")) parts.add("c" + field(value, "potionId"));
                    else if (kind.equals("Burn")) parts.add("b" + field(value, "duration"));
                    else if (kind.equals("Damage")) parts.add("d" + field(value, "damage"));
                    else throw new AssertionError("Unexpected effect type " + kind);
                }
                rows.put("m" + m.getMethod("id").invoke(row.getKey()), join(parts));
            }
        }
        output("minechem-pharmacology.txt", rows);
        return rows.size();
    }

    private Object player() throws Exception {
        return fakePlayer(world, old, "[Chemistry]");
    }

    static Object fakePlayer(Object world, boolean old, String name) throws Exception {
        if (old) {
            Object fake = Class.forName("net.minecraftforge.common.FakePlayer").getConstructor(mc("world.World"), String.class)
                    .newInstance(world, name);
            Class<?> server = mc("server.MinecraftServer"), manager = mc("network.INetworkManager"), mp = mc("entity.player.EntityPlayerMP");
            Object sink = java.lang.reflect.Proxy.newProxyInstance(manager.getClassLoader(), new Class<?>[]{manager},
                    new java.lang.reflect.InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method method, Object[] args) {
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            return null;
                        }
                    });
            Object connection = mc("network.NetServerHandler").getConstructor(server, manager, mp)
                    .newInstance(server.getMethod("func_71276_C").invoke(null), sink, fake);
            mp.getField("field_71135_a").set(fake, connection);
            return fake;
        }
        Object fake = Class.forName("net.minecraftforge.common.util.FakePlayer").getConstructor(mc("world.WorldServer"), Class.forName("com.mojang.authlib.GameProfile"))
                .newInstance(world, Class.forName("com.mojang.authlib.GameProfile").getConstructor(java.util.UUID.class, String.class)
                        .newInstance(java.util.UUID.randomUUID(), name));
        Class<?> server = mc("server.MinecraftServer"), manager = mc("network.NetworkManager");
        Class<?> mp = mc("entity.player.EntityPlayerMP");
        Object network = manager.getConstructor(boolean.class).newInstance(true);
        Object connection = mc("network.NetHandlerPlayServer").getConstructor(server, manager, mp)
                .newInstance(server.getMethod("func_71276_C").invoke(null), network, fake);
        mp.getField("field_71135_a").set(fake, connection);
        return fake;
    }

    private Object pig() throws Exception {
        return mc("entity.passive.EntityPig").getConstructor(mc("world.World")).newInstance(world);
    }

    private Object foodStats(Object player) throws Exception {
        return player.getClass().getMethod("func_71024_bL").invoke(player);
    }

    private Object makeStack(Object type, int count, int damage) throws Exception {
        return stack.getConstructor(item, int.class, int.class).newInstance(type, count, damage);
    }

    private void foodAndSword() throws Exception {
        Class<?> playerType = mc("entity.player.EntityPlayer");
        Class<?> craft = mc("inventory.InventoryCrafting");
        Object player = player();
        Object inventory = playerType.getField("field_71071_by").get(player);
        Object crafting = mc("inventory.ContainerWorkbench").getConstructor(mc("entity.player.InventoryPlayer"), mc("world.World"), int.class, int.class, int.class)
                .newInstance(inventory, world, 0, 64, 0);
        Object grid = craft.getConstructor(mc("inventory.Container"), int.class, int.class).newInstance(crafting, 3, 3);
        Method set = craft.getMethod("func_70299_a", int.class, stack);
        Method equip = playerType.getMethod("func_70062_b", int.class, stack);
        Class<?> enumType = Class.forName("minechem.item.molecule.MoleculeEnum");
        int sucrose = (Integer) enumType.getMethod("id").invoke(enumType.getField("sucrose").get(null));
        Object sugar = makeStack(molecules, 1, sucrose);
        Object sword, bread;
        if (old) {
            sword = stack.getConstructor(int.class, int.class, int.class).newInstance(267, 1, 7);
            bread = stack.getConstructor(int.class, int.class, int.class).newInstance(297, 1, 0);
        } else {
            Method find = Class.forName("cpw.mods.fml.common.registry.GameRegistry").getMethod("findItem", String.class, String.class);
            sword = makeStack(find.invoke(null, "minecraft", "iron_sword"), 1, 7);
            bread = makeStack(find.invoke(null, "minecraft", "bread"), 1, 0);
        }
        Class<?> managerType = mc("item.crafting.CraftingManager");
        Object manager = managerType.getMethod("func_77594_a").invoke(null);
        Method match = managerType.getMethod("func_82787_a", craft, mc("world.World"));
        set.invoke(grid, 0, sword);
        set.invoke(grid, 1, sugar);
        Object coated = match.invoke(manager, grid, world);
        require(coated != null && itemOf.invoke(coated) == itemOf.invoke(sword) && damage.invoke(coated).equals(7) &&
                stack.getMethod("func_77986_q").invoke(coated) != null, "Trading a sword and a tube did not preserve sword damage + coating");
        equip.invoke(player, 0, coated);
        Object victim = pig();
        Class<?> source = mc("util.DamageSource");
        Object hit = source.getMethod("func_76365_a", playerType).invoke(null, player);
        victim.getClass().getMethod("func_70097_a", source, float.class).invoke(victim, hit, 1F);
        verifySpeed(victim, old ? 1000 : 400);
        set.invoke(grid, 0, bread);
        Object poisoned = match.invoke(manager, grid, world);
        require(poisoned != null && itemOf.invoke(poisoned) == itemOf.invoke(bread), "Spiking bread did not return bread");
        Object tag = stack.getMethod("func_77978_p").invoke(poisoned);
        require(tag != null && (Boolean) tag.getClass().getMethod("func_74767_n", String.class).invoke(tag, "minechem.isPoisoned"), "Missing poisoning tag");
        equip.invoke(player, 0, poisoned);
        if (old) {
            Method flag = mc("entity.Entity").getDeclaredMethod("func_70052_a", int.class, boolean.class);
            flag.setAccessible(true);
            flag.invoke(player, 4, true);
            Method tick = Class.forName("minechem.tickhandler.ScheduledTickHandler").getDeclaredMethod("checkForPoison", playerType);
            tick.setAccessible(true);
            tick.invoke(Class.forName("minechem.tickhandler.ScheduledTickHandler").newInstance(), player);
            require(playerType.getMethod("func_70694_bm").invoke(player) == null, "FINAL scheduled handoff did not consume the spiked food");
        } else {
            Object taken = stack.getMethod("func_77950_b", mc("world.World"), playerType).invoke(poisoned, world, player);
            Class<?> finish = Class.forName("net.minecraftforge.event.entity.player.PlayerUseItemEvent$Finish");
            Class<?> event = Class.forName("cpw.mods.fml.common.eventhandler.Event");
            Object bus = Class.forName("net.minecraftforge.common.MinecraftForge").getField("EVENT_BUS").get(null);
            bus.getClass().getMethod("post", event).invoke(bus, finish.getConstructor(playerType, stack, int.class, stack).newInstance(player, poisoned, 0, taken));
            require(size.getInt(taken) == 0, ".394 food use did not consume one item");
        }
        verifySpeed(player, old ? 1000 : 400);
        foodRecipeSurvey(grid, set, match, manager, bread, enumType);
    }

    private void foodRecipeSurvey(Object grid, Method set, Method match, Object manager, Object bread, Class<?> enumType) throws Exception {
        Map<String, String> rows = new TreeMap<String, String>();
        List<?> values;
        if (old) values = java.util.Arrays.asList(enumType.getEnumConstants());
        else values = new ArrayList<Object>(((Map<?, ?>) staticField(Class.forName("minechem.potion.PharmacologyEffectRegistry"), "effects")).keySet());
        set.invoke(grid, 0, bread);
        for (Object molecule : values) {
            int id = (Integer) enumType.getMethod("id").invoke(molecule);
            set.invoke(grid, 1, makeStack(molecules, 1, id));
            Object result = match.invoke(manager, grid, world);
            if (result == null || itemOf.invoke(result) != itemOf.invoke(bread)) continue;
            Object tag = stack.getMethod("func_77978_p").invoke(result);
            if (tag != null && (Boolean) tag.getClass().getMethod("func_74767_n", String.class).invoke(tag, "minechem.isPoisoned")) rows.put("m" + id, "yes");
        }
        output("minechem-foods.txt", rows);
    }

    private void verifySpeed(Object entity, int duration) throws Exception {
        Class<?> potion = mc("potion.PotionEffect");
        for (Object active : (Collection<?>) entity.getClass().getMethod("func_70651_bq").invoke(entity)) {
            if (potion.getMethod("func_76456_a").invoke(active).equals(1) && potion.getMethod("func_76458_c").invoke(active).equals(1) &&
                    potion.getMethod("func_76459_b").invoke(active).equals(duration)) return;
        }
        throw new AssertionError("Expected speed II for " + duration + " ticks on " + entity);
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static Object staticField(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
    private static String join(List<String> values) {
        StringBuilder joined = new StringBuilder();
        for (String s : values) { if (joined.length() > 0) joined.append(','); joined.append(s); }
        return joined.toString();
    }
    private static void output(String path, Map<String, String> rows) throws Exception {
        try (PrintWriter out = new PrintWriter(path, "UTF-8")) {
            for (Map.Entry<String, String> ref : rows.entrySet()) out.println(ref.getKey() + "=" + ref.getValue());
            require(!out.checkError(), "Unable to write " + path);
        }
    }
    private static Class<?> mc(String suffix) throws Exception { return Class.forName("net.minecraft." + suffix); }
    private static void require(boolean condition, String error) { if (!condition) throw new AssertionError(error); }
}
