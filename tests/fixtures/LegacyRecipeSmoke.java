package mcmodup.recipecheck;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runs against the pinned historical Forge server; uses its own registries and tile ticks. */
@Mod(modid="mcmodup_recipecheck", name="Recipe parity probe", version="1.0.0")
public final class LegacyRecipeSmoke {
    private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private final boolean crafting = "1.7.10".equals(System.getProperty("mcmodup.recipe.source"));
    private Set<Object> vanillaCrafting;
    private Map<?, ?> vanillaSmelting;

    @PRE_INIT@
    public void before(FMLPreInitializationEvent event) throws Exception {
        // The original fixtures register in Init, not PreInit. Take the baseline
        // using the actual legacy registry before their original code runs.
        if (crafting) {
            vanillaCrafting = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            vanillaCrafting.addAll(craftingList());
        } else {
            vanillaSmelting = new HashMap<Object, Object>(smeltingList());
        }
    }

    @SERVER_STARTED@
    public void check(FMLServerStartedEvent event) {
        try {
            List<String> rows = Files.readAllLines(Paths.get(System.getProperty("mcmodup.recipe.cases")), StandardCharsets.UTF_8);
            String[] meta = rows.get(0).split("\t");
            require(Loader.isModLoaded(meta[3]), "Original mod did not load: " + meta[3]);
            Object server = stat(type("server.MinecraftServer"), "func_71276_C", new Class<?>[0]);
            Object world = call(server, "func_71218_a", new Class<?>[]{int.class}, 0);
            int count = Integer.parseInt(meta[2]);
            if (crafting) {
                List<Object> added = new ArrayList<Object>(craftingList());
                added.removeAll(vanillaCrafting);
                require(added.size() == count, "Different number of original crafting recipes: " + added.size());
                Map<String, Object> mapped = new HashMap<String, Object>();
                for (String row : rows.subList(1, rows.size())) craft(world, added, mapped, row.split("\t"));
                require(mapped.size() == count && mapped.values().containsAll(added), "Extra original recipe(s)");
            } else {
                Map<?, ?> recipes = smeltingList();
                int changed = 0;
                for (Map.Entry<?, ?> entry : recipes.entrySet()) {
                    if (entry.getValue() != vanillaSmelting.get(entry.getKey())) changed++;
                }
                require(changed == count, "Different number of original furnace registrations: " + changed);
                for (String row : rows.subList(1, rows.size())) smelt(server, world, row.split("\t"));
            }
            String difference = "";
            for (String row : rows) if (row.startsWith("A\t")) {
                difference = "; 1 XP after two automated extractions and one player pickup";
            }
            System.out.println("MCMODUP_RECIPE_PASS " + (rows.size() - 1) + " runtime cases for " + meta[3] + difference);
        } catch (Throwable error) {
            System.err.println("MCMODUP_RECIPE_FAIL " + error);
            error.printStackTrace();
        }
    }

    private static Object manager() throws Exception {
        return stat(type("item.crafting.CraftingManager"), "func_77594_a", new Class<?>[0]);
    }

    private static List<?> craftingList() throws Exception {
        return (List<?>) call(manager(), "func_77592_b", new Class<?>[0]);
    }

    private static Object furnaceRecipes() throws Exception {
        return stat(type("item.crafting.FurnaceRecipes"), "func_77602_a", new Class<?>[0]);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> smeltingList() throws Exception {
        Object recipes = furnaceRecipes();
        return (Map<Object, Object>) field(recipes, "field_77604_b");
    }

    private static void craft(Object world, List<Object> added, Map<String, Object> mapped, String[] c) throws Exception {
        require(c[0].equals("C"), "Expected crafting case");
        int width = Integer.parseInt(c[5]), height = Integer.parseInt(c[6]);
        Class<?> inventory = type("inventory.InventoryCrafting");
        Class<?> worldType = type("world.World");
        Object grid = inventory.getConstructor(type("inventory.Container"), int.class, int.class).newInstance(null, width, height);
        Object stacks = field(grid, "field_70466_a");
        String[] ids = c[7].split(",");
        for (int i = 0; i < ids.length; i++) if (!ids[i].equals(".")) Array.set(stacks, i, stack17(ids[i]));
        boolean shouldMatch = c[2].equals("1");
        int count = Integer.parseInt(c[4]);
        Object recipe = mapped.get(c[1]);
        Class<?>[] matchSignature = {inventory, worldType};
        if (recipe == null) {
            require(shouldMatch, "First case for a recipe must be positive");
            for (Object candidate : added) {
                if (mapped.containsValue(candidate)) continue;
                if (Boolean.TRUE.equals(call(candidate, "func_77569_a", matchSignature, grid, world)) &&
                    same17(call(candidate, "func_77572_b", new Class<?>[]{inventory}, grid), c[3], count)) {
                    require(recipe == null, "Ambiguous original recipes for " + c[1]);
                    recipe = candidate;
                }
            }
            require(recipe != null, "Missing original matching recipe: " + c[1] + " " + c[7]);
            mapped.put(c[1], recipe);
        }
        require(Boolean.valueOf(shouldMatch).equals(call(recipe, "func_77569_a", matchSignature, grid, world)),
            "Changed crafting acceptance: " + c[1] + " [" + c[7] + "] expected " + shouldMatch);
        if (shouldMatch) {
            Object result = call(manager(), "func_82787_a", matchSignature, grid, world);
            require(same17(result, c[3], count), "Wrong original registry result for " + c[1]);
        }
    }

    private static Object stack17(String id) throws Exception {
        String path = id.substring("minecraft:".length());
        int damage = 0;
        for (int i = 0; i < COLORS.length; i++) {
            if (path.equals(COLORS[i] + "_wool")) { path = "wool"; damage = i; break; }
        }
        Object item = stat(Class.forName("cpw.mods.fml.common.registry.GameRegistry"), "findItem", new Class<?>[]{String.class, String.class}, "minecraft", path);
        require(item != null, "Missing historical registry item: " + path);
        return type("item.ItemStack").getConstructor(type("item.Item"), int.class, int.class).newInstance(item, 1, damage);
    }

    private static boolean same17(Object actual, String id, int count) throws Exception {
        if (actual == null) return false;
        Object expected = stack17(id);
        return stackCount(actual) == count && call(actual, "func_77973_b", new Class<?>[0]) == call(expected, "func_77973_b", new Class<?>[0]) &&
            call(actual, "func_77960_j", new Class<?>[0]).equals(call(expected, "func_77960_j", new Class<?>[0]));
    }

    private static void smelt(Object server, Object world, String[] c) throws Exception {
        boolean automated = c[0].equals("A");
        require((automated || c[0].equals("S")) && c[2].equals("minecraft:rotten_flesh") && c[3].equals("minecraft:leather"),
            "Unexpected historical furnace fixture");
        Object recipes = furnaceRecipes();
        Object registered = smeltingList().get(367);
        require(registered != null && (Integer) field(registered, "field_77993_c") == 334, "Original registry did not map rotten flesh to leather");
        require(!smeltingList().containsKey(280), "A stick must not smelt");
        final int ticks = Integer.parseInt(c[5]), expectedCount = Integer.parseInt(c[4]);
        final float rawXp = Float.parseFloat(c[6]);
        final int xp = Integer.parseInt(c[7]);
        require(call(recipes, "func_77601_c", new Class<?>[]{int.class}, 334).equals(rawXp), "Unexpected original registered XP");
        Object spawn = call(world, "func_72861_E", new Class<?>[0]);
        int x = (Integer) field(spawn, "field_71574_a") + (automated ? 12 : 0);
        int y = (Integer) field(spawn, "field_71572_b") + 4, z = (Integer) field(spawn, "field_71573_c");
        call(world, "func_94575_c", new Class<?>[]{int.class, int.class, int.class, int.class}, x, y, z, 61);
        Object furnace = call(world, "func_72796_p", new Class<?>[]{int.class, int.class, int.class}, x, y, z);
        Class<?> stack = type("item.ItemStack");
        int operations = automated ? 3 : 1;
        call(furnace, "func_70299_a", new Class<?>[]{int.class, stack}, 0,
            stack.getConstructor(int.class, int.class, int.class).newInstance(367, operations + 1, 0));
        call(furnace, "func_70299_a", new Class<?>[]{int.class, stack}, 1,
            stack.getConstructor(int.class, int.class, int.class).newInstance(263, 1, 0));
        Object output = null;
        for (int operation = 1; operation <= operations; operation++) {
            for (int i = 1; i <= ticks; i++) {
                call(furnace, "func_70316_g", new Class<?>[0]);
                if (i < ticks) require(slot(furnace, 2) == null, "Early legacy furnace output at " + i + " ticks");
            }
            output = slot(furnace, 2);
            require(output != null && (Integer) field(output, "field_77993_c") == 334 && stackCount(output) == expectedCount,
                "Wrong legacy furnace result at tick " + ticks);
            require(stackCount(slot(furnace, 0)) == operations + 1 - operation, "Legacy furnace consumed the wrong amount");
            if (operation < operations) {
                // Pull straight from the inventory, as automation does. This
                // deliberately does not invoke SlotFurnace's player pickup hook.
                call(furnace, "func_70298_a", new Class<?>[]{int.class, int.class}, 2, expectedCount);
            }
        }
        Object manager = type("item.ItemInWorldManager").getConstructor(type("world.World")).newInstance(world);
        Object player = type("entity.player.EntityPlayerMP").getConstructor(type("server.MinecraftServer"), type("world.World"), String.class, manager.getClass())
            .newInstance(server, world, "RecipeProbe", manager);
        final Class<?> playerType = type("entity.player.EntityPlayer");
        final Object resultSlot = type("inventory.SlotFurnace").getConstructor(playerType, type("inventory.IInventory"), int.class, int.class, int.class)
            .newInstance(player, furnace, 2, 0, 0);
        int before = xpOrbs(world);
        Object removed = call(resultSlot, "func_75209_a", new Class<?>[]{int.class}, stackCount(output));
        call(resultSlot, "func_82870_a", new Class<?>[]{playerType, stack}, player, removed);
        require(xpOrbs(world) - before == xp, "Legacy furnace awarded different experience");
    }

    private static Object slot(Object furnace, int index) throws Exception {
        return call(furnace, "func_70301_a", new Class<?>[]{int.class}, index);
    }

    private static int xpOrbs(Object world) throws Exception {
        int count = 0;
        for (Object entity : (List<?>) field(world, "field_72996_f")) {
            if (type("entity.item.EntityXPOrb").isInstance(entity)) count += (Integer) field(entity, "field_70530_e");
        }
        return count;
    }

    private static int stackCount(Object stack) throws Exception { return (Integer) field(stack, "field_77994_a"); }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName("net.minecraft." + name); }
    private static Object stat(Class<?> owner, String name, Class<?>[] signature, Object... args) throws Exception {
        return owner.getMethod(name, signature).invoke(null, args);
    }
    private static Object call(Object owner, String name, Class<?>[] signature, Object... args) throws Exception {
        return owner.getClass().getMethod(name, signature).invoke(owner, args);
    }
    private static Object field(Object owner, String name) throws Exception {
        for (Class<?> current = owner.getClass(); current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
