package mcmodup.minechemoracle;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Crafting registry and regular ore dictionary fallbacks in the exact loaded releases. */
final class LegacyMinechemOreInterop {
    private final Class<?> stack = mc("item.ItemStack"), item = mc("item.Item"), block = mc("block.Block"), w = mc("world.World");
    private final Class<?> crafting = mc("item.crafting.CraftingManager"), gridType = mc("inventory.InventoryCrafting");
    private final Method getItem = stack.getMethod("func_77973_b"), metadata = stack.getMethod("func_77960_j");
    private final Method register = Class.forName("net.minecraftforge.oredict.OreDictionary").getMethod("registerOre", String.class, stack);
    private final Field count = stack.getField("field_77994_a");
    private final Object world, nativeOre, grid, manager, oreItem;
    private final Object[] slots;
    private final boolean old;
    private final Map<String, String> rows = new TreeMap<String, String>();

    private LegacyMinechemOreInterop(Object world, boolean old) throws Exception {
        this.world = world;
        this.old = old;
        Object ore = Class.forName("minechem.MinechemBlocksGeneration").getField("uranium").get(null);
        nativeOre = stack.getConstructor(block, int.class, int.class).newInstance(ore, 1, 0);
        oreItem = getItem.invoke(nativeOre);
        grid = gridType.getConstructor(mc("inventory.Container"), int.class, int.class).newInstance(null, 3, 3);
        Field raw = gridType.getDeclaredField("field_70466_a");
        raw.setAccessible(true);
        slots = (Object[])raw.get(grid);
        manager = crafting.getMethod("func_77594_a").invoke(null);
    }

    static String survey(Object world, boolean old) throws Exception {
        LegacyMinechemOreInterop test = new LegacyMinechemOreInterop(world, old);
        test.crafting();
        if (!old) {
            test.decomposition();
            test.oreMatrix();
        }
        try (PrintWriter out = new PrintWriter("minechem-ore-interop.txt", "UTF-8")) {
            for (Map.Entry<String, String> row : test.rows.entrySet()) out.println(row.getKey() + "=" + row.getValue());
            if (out.checkError()) throw new AssertionError("Could not save ore interoperability measurements");
        }
        return test.rows.size() + " registered ore interop controls";
    }

    private void crafting() throws Exception {
        rows.put("craft_setting", old ? String.valueOf(Class.forName("minechem.Settings").getField("UraniumOreCraftable").getBoolean(null)) : "absent");
        int recipes = 0;
        Method output = mc("item.crafting.IRecipe").getMethod("func_77571_b");
        for (Object recipe : (List<?>)crafting.getMethod("func_77592_b").invoke(manager)) {
            Object result = output.invoke(recipe);
            if (result != null && getItem.invoke(result) == oreItem) recipes++;
        }
        rows.put("craft_recipes", Integer.toString(recipes));
        Object external = vanilla(7, 1, 0);
        rows.put("craft_untagged", craft(0, external));
        register.invoke(null, "oreUranium", external);
        rows.put("craft_foreign", craft(0, external));
        rows.put("craft_shifted", craft(7, external));
        rows.put("craft_full_stack", craft(0, vanilla(7, 64, 0)));
        rows.put("craft_extra", craft(0, external, vanilla(4, 1, 0)));
        rows.put("craft_native", craft(0, nativeOre));
    }

    private String craft(int slot, Object... ingredients) throws Exception {
        Arrays.fill(slots, null);
        System.arraycopy(ingredients, 0, slots, slot, ingredients.length);
        Object result = crafting.getMethod("func_82787_a", gridType, w).invoke(manager, grid, world);
        return result == null ? "-" : (getItem.invoke(result) == oreItem ? "native" : "other") + "x" + count.getInt(result) + ":" + metadata.invoke(result);
    }

    private void decomposition() throws Exception {
        Class<?> recipes = Class.forName("minechem.MinechemRecipes");
        Object registry = recipes.getMethod("getInstance").invoke(null);
        Method add = recipes.getMethod("registerOre", String.class);
        Object external = vanilla(7, 1, 0);
        rows.put("external_uranium_before_scan", products(external));
        rows.put("native_uranium_before_scan", products(nativeOre));
        add.invoke(registry, "oreUranium");
        rows.put("external_uranium_after_scan", products(external));
        rows.put("native_uranium_after_scan", products(nativeOre));
        // Use distinct metadata so all forms can be exercised in one reference registry.
        int meta = 0x100;
        for (String ore : new String[]{"oreNetherUranium", "ingotUranium", "nuggetUranium", "dustDirtyUranium",
                "dustSmallUranium", "dustUranium", "plateUranium", "gemUranium", "crystalUranium", "blockUranium",
                "oreSalpeter", "dustSmallSalpeter", "oreObsidian", "oreRuby", "oreUnknown"}) {
            Object input = vanilla(7, 1, meta++);
            register.invoke(null, ore, input);
            add.invoke(registry, ore);
            rows.put(ore, products(input));
        }
        Object iron = vanilla(15, 1, 0);
        rows.put("explicit_iron_before", products(iron));
        register.invoke(null, "oreUranium", iron);
        add.invoke(registry, "oreUranium");
        rows.put("explicit_iron_after", products(iron));
    }

    private void oreMatrix() throws Exception {
        Class<?> recipes = Class.forName("minechem.MinechemRecipes");
        Object registry = recipes.getMethod("getInstance").invoke(null);
        Method add = recipes.getMethod("registerOre", String.class);
        int meta = 0x400;
        Map<String, String> values = new TreeMap<String, String>();
        // Two distinct providers and both dust sizes reveal the real precedence and shaped-slot shifts.
        Object first = tagged(registry, add, "ingotThorium", meta++);
        Object second = tagged(registry, add, "ingotThorium", meta++);
        values.put("synth_ingot_first", LegacyMinechemChemistry.synthesisRule(world, first));
        values.put("synth_ingot_second", LegacyMinechemChemistry.synthesisRule(world, second));
        Object dust = tagged(registry, add, "dustThorium", meta++);
        values.put("synth_ingot_after_dust", LegacyMinechemChemistry.synthesisRule(world, first));
        values.put("synth_dust", LegacyMinechemChemistry.synthesisRule(world, dust));
        Object small = tagged(registry, add, "dustSmallThorium", meta++);
        values.put("synth_small_dust", LegacyMinechemChemistry.synthesisRule(world, small));
        Object late = tagged(registry, add, "ingotThorium", meta++);
        values.put("synth_ingot_late", LegacyMinechemChemistry.synthesisRule(world, late));
        values.put("synth_dust_after_small", LegacyMinechemChemistry.synthesisRule(world, dust));
        // The reverse order must be observed too; the enum includes several non-metals with fractional outputs.
        tagged(registry, add, "dustSmallOsmium", meta++);
        values.put("synth_ingot_small_first", LegacyMinechemChemistry.synthesisRule(world, tagged(registry, add, "ingotOsmium", meta++)));
        values.put("synth_gem", LegacyMinechemChemistry.synthesisRule(world, tagged(registry, add, "gemAmethyst", meta++)));
        Class<?> declarations = Class.forName("minechem.oredictionary.OreDictionaryBaseOreEnum");
        Method name = declarations.getMethod("getName");
        for (Object material : declarations.getEnumConstants()) {
            String suffix = (String) name.invoke(material);
            for (String form : new String[]{"oreNether", "ore", "ingot", "nugget", "dustDirty", "dustSmall", "dust", "plate", "gem", "crystal", "block"}) {
                String tag = form + suffix;
                values.put(tag, orderedProducts(tagged(registry, add, tag, meta++)));
            }
        }
        for (String special : new String[]{"oreSaphire", "crystalSaphire", "oreNetherNetherQuartz", "oreChromium", "gemApatite", "plateSilicon",
                "xychoriumBlue", "xychoriumRed", "xychoriumGreen", "xychoriumDark", "xychoriumLight", "cropMaloberry", "cropDuskberry", "cropSkyberry",
                "cropBlightberry", "cropBlueberry", "cropRaspberry", "cropBlackberry"})
            values.put(special, orderedProducts(tagged(registry, add, special, meta++)));
        try (PrintWriter out = new PrintWriter("minechem-ore-forms.txt", "UTF-8")) {
            for (Map.Entry<String, String> row : values.entrySet()) out.println(row.getKey() + "=" + row.getValue());
            if (out.checkError()) throw new AssertionError("Could not save .394 ore forms");
        }
    }

    private Object tagged(Object recipes, Method add, String name, int meta) throws Exception {
        Object input = vanilla(7, 1, meta);
        register.invoke(null, name, input);
        add.invoke(recipes, name);
        return input;
    }

    private String orderedProducts(Object input) throws Exception {
        String value = products(input);
        if (value.equals("-") || value.equals("[]")) return value;
        String[] parts = value.substring(1, value.length() - 1).split(", ");
        Arrays.sort(parts);
        return Arrays.toString(parts);
    }

    private String products(Object input) throws Exception {
        Class<?> handler = Class.forName("minechem.tileentity.decomposer.DecomposerRecipeHandler");
        Object instance = handler.getField("instance").get(null);
        List<?> products = (List<?>)handler.getMethod("getRecipeOutputForInput", stack).invoke(instance, input);
        if (products == null) return "-";
        Class<?> catalog = Class.forName("minechem.MinechemItemsRegistration");
        Object element = catalog.getField("element").get(null), molecule = catalog.getField("molecule").get(null);
        List<String> tokens = new ArrayList<String>();
        for (Object product : products) {
            Object kind = getItem.invoke(product);
            if (kind != element && kind != molecule) throw new AssertionError("Nonchemical ore product");
            tokens.add((kind == element ? "e" : "m") + metadata.invoke(product) + "x" + count.getInt(product));
        }
        return tokens.toString();
    }

    private Object vanilla(int id, int amount, int meta) throws Exception {
        return old ? stack.getConstructor(int.class, int.class, int.class).newInstance(id, amount, meta) :
            stack.getConstructor(item, int.class, int.class).newInstance(item.getMethod("func_150899_d", int.class).invoke(null, id), amount, meta);
    }

    private static Class<?> mc(String type) throws ClassNotFoundException { return Class.forName("net.minecraft." + type); }
}
