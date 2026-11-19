package mcmodup.mimicrycheck;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.registry.GameRegistry;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;

/** Execute recipes in the exact Forge crafting manager, not a translated recipe list. */
@Mod(modid = "mcmodup_mimicry_check", name = "Mimicry catalyst probe", version = "1.0.0")
public final class LegacyMimicryCatalyst {
    @Mod.EventHandler
    public void check(FMLServerStartedEvent event) {
        try {
            ModContainer mod = null;
            for (ModContainer candidate : Loader.instance().getActiveModList()) {
                if (candidate.getModId().equalsIgnoreCase("mimicry")) {
                    require(mod == null, "Duplicate Mimicry container");
                    mod = candidate;
                }
            }
            require(mod != null, "The original Mimicry did not load");
            File source = mod.getSource().getCanonicalFile();
            Object server = type("server.MinecraftServer").getMethod("func_71276_C").invoke(null);
            Object world = server.getClass().getMethod("func_71218_a", int.class).invoke(server, 0);
            ForgeCrafting crafting = new ForgeCrafting(world);
            List<Integer> candidates = ownedItems(mod.getModId(), source);
            List<String> attempts = new ArrayList<String>();
            int catalyst = -1;
            for (int id : candidates) {
                String result = describe(crafting.craft(id, 0, 15, 0, false));
                attempts.add(id + " -> " + result);
                if ("265:0x2".equals(result)) {
                    require(catalyst < 0, "More than one Mimicry item doubles smelting iron ore: " + catalyst + "/" + id);
                    catalyst = id;
                }
            }
            require(catalyst >= 0, "No default Mimicry catalyst made two iron ingots; tried " + attempts);
            Object reversed = crafting.craft(catalyst, 0, 15, 0, true);
            require("265:0x2".equals(describe(reversed)), "Reversing the inputs should still give two iron ingots");
            require(crafting.craft(-1, 0, 15, 0, false) == null, "The control crafted iron ore without a catalyst");
            require(crafting.craft(catalyst, 0, 3, 0, false) == null, "Non-smeltable dirt should not turn into a crafting output");
            reportCases(mod, candidates, crafting, catalyst);
        } catch (Throwable error) {
            System.err.println("MCMODUP_MIMICRY_FAIL " + error);
            error.printStackTrace();
        }
    }

    private static void reportCases(ModContainer mod, List<Integer> items, ForgeCrafting crafting, int catalyst) throws Exception {
        Object item = itemArray()[catalyst];
        GameRegistry.UniqueIdentifier key = (GameRegistry.UniqueIdentifier) GameRegistry.class
                .getMethod("findUniqueIdentifierFor", type("item.Item")).invoke(null, item);
        String name = key == null ? "unregistered_name" : key.modId + ":" + key.name;
        String other = "gold_ore=" + describe(crafting.craft(catalyst, 0, 14, 0, false))
                + "; sand=" + describe(crafting.craft(catalyst, 0, 12, 0, false))
                + "; iron_ingot=" + describe(crafting.craft(catalyst, 0, 265, 0, false))
                + "; lapis_dye=" + describe(crafting.craft(catalyst, 0, 351, 4, false));
        System.out.println("MCMODUP_MIMICRY_PASS " + mod.getModId() + " " + mod.getVersion() + "; catalyst=" + name
                + " " + item.getClass().getName() + "/" + catalyst + ":0; " + items.size() + " candidate items; "
                + "iron_ore=265:0x2 both orders; no output without catalyst or with dirt; " + other);
    }

    private static List<Integer> ownedItems(String modid, File source) throws Exception {
        Object[] items = itemArray();
        Object[] blocks = (Object[]) type("block.Block").getField("field_71973_m").get(null);
        Class<?> itemType = type("item.Item");
        Method identifier = GameRegistry.class.getMethod("findUniqueIdentifierFor", itemType);
        List<Integer> ids = new ArrayList<Integer>();
        for (int id = 0; id < items.length; id++) {
            if (items[id] == null) continue;
            GameRegistry.UniqueIdentifier key = (GameRegistry.UniqueIdentifier) identifier.invoke(null, items[id]);
            if (owned(items[id], source) || (id < blocks.length && owned(blocks[id], source))
                    || key != null && modid.equalsIgnoreCase(key.modId)) {
                ids.add(id);
            }
        }
        require(!ids.isEmpty(), "No items attributable to the exact Mimicry ZIP");
        return ids;
    }

    private static boolean owned(Object value, File source) throws Exception {
        if (value == null) return false;
        CodeSource code = value.getClass().getProtectionDomain().getCodeSource();
        return code != null && "file".equals(code.getLocation().getProtocol())
                && source.equals(new File(code.getLocation().toURI()).getCanonicalFile());
    }

    private static Object[] itemArray() throws Exception { return (Object[]) type("item.Item").getField("field_77698_e").get(null); }
    private static int itemId(Object stack) throws Exception { return stack == null ? -1 : stack.getClass().getField("field_77993_c").getInt(stack); }
    private static int count(Object stack) throws Exception { return stack.getClass().getField("field_77994_a").getInt(stack); }
    private static String describe(Object stack) throws Exception {
        return stack == null ? "none" : itemId(stack) + ":" + stack.getClass().getMethod("func_77960_j").invoke(stack) + "x" + count(stack);
    }

    private static final class ForgeCrafting {
        final Object world, manager;
        final java.lang.reflect.Constructor<?> grid, stack;
        final Field slots;
        final Method match;

        ForgeCrafting(Object world) throws Exception {
            this.world = world;
            Class<?> inventory = type("inventory.InventoryCrafting"), mgr = type("item.crafting.CraftingManager");
            grid = inventory.getConstructor(type("inventory.Container"), int.class, int.class);
            stack = type("item.ItemStack").getConstructor(int.class, int.class, int.class);
            slots = inventory.getDeclaredField("field_70466_a");
            slots.setAccessible(true);
            manager = mgr.getMethod("func_77594_a").invoke(null);
            match = mgr.getMethod("func_82787_a", inventory, type("world.World"));
        }

        Object craft(int catalyst, int catalystMeta, int ingredient, int meta, boolean reversed) throws Exception {
            Object input = grid.newInstance(null, 3, 3);
            Object[] values = (Object[]) slots.get(input);
            if (catalyst >= 0) values[reversed ? 8 : 0] = stack.newInstance(catalyst, 1, catalystMeta);
            values[reversed ? 0 : 8] = stack.newInstance(ingredient, 1, meta);
            return match.invoke(manager, input, world);
        }
    }

    private static Class<?> type(String name) throws Exception { return Class.forName("net.minecraft." + name); }
    private static void require(boolean ok, String failure) { if (!ok) throw new AssertionError(failure); }
}
