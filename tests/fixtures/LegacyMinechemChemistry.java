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
        if (id >= 2256 && id <= 2267 || id > 0 && id < (old ? 423 : 500)) return "v" + id + ":" + meta;
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

    private int synthesis() throws Exception {
        Class<?> type = Class.forName("minechem.tileentity.synthesis.SynthesisRecipe");
        Map<String, String> rows = new TreeMap<String, String>();
        for (Object rule : ((Map<?, ?>) type.getField("recipes").get(null)).values()) {
            Object output = type.getMethod("getOutput").invoke(rule);
            String key = stackKey(output);
            if (key == null) continue;
            boolean shaped = (Boolean) type.getMethod("isShaped").invoke(rule);
            Object pattern = type.getMethod(shaped ? "getShapedRecipe" : "getShapelessRecipe").invoke(rule);
            if (pattern == null) continue;
            StringBuilder ghost = new StringBuilder();
            for (int i = 0; i < Array.getLength(pattern); i++) {
                if (i > 0) ghost.append(',');
                ghost.append(part(Array.get(pattern, i)));
            }
            rows.put(key, size.getInt(output) + ";" + type.getMethod("energyCost").invoke(rule) + ";" + (shaped ? "shaped" : "shapeless") + ";" + ghost);
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
            for (Object v : m.getEnumConstants()) if ((Boolean) gives.invoke(null, v)) rows.put("m" + m.getMethod("id").invoke(v), "coatable");
            // Check a real effect against the original entity implementation.
            Object pig = mc("entity.passive.EntityPig").getConstructor(mc("world.World")).newInstance(world);
            Object sucrose = m.getField("sucrose").get(null);
            p.getMethod("triggerPlayerEffect", m, mc("entity.EntityLivingBase")).invoke(null, sucrose, pig);
            @SuppressWarnings("unchecked") Collection<Object> active = (Collection<Object>) pig.getClass().getMethod("func_70651_bq").invoke(pig);
            Object effect = active.iterator().next();
            require(active.size() == 1 && effect.getClass().getMethod("func_76456_a").invoke(effect).equals(1) &&
                    effect.getClass().getMethod("func_76458_c").invoke(effect).equals(1) &&
                    effect.getClass().getMethod("func_76459_b").invoke(effect).equals(1000), "FINAL sucrose did not give speed for 50 seconds");
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
