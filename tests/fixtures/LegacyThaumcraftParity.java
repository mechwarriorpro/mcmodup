package mcmodup.tc4oracle;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLServerStartedEvent;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.TreeMap;

/** Observe the original release on Forge, not the native port's interpretation of its tags or NBT. */
@Mod(modid = "mcmodup_tc4oracle", name = "TC4 parity oracle", version = "1.0", dependencies = "required-after:Thaumcraft")
public final class LegacyThaumcraftParity {
    @Mod.EventHandler
    public void check(FMLServerStartedEvent event) {
        try {
            require(Loader.isModLoaded("Thaumcraft") && Loader.isModLoaded("Baubles"), "Wrong original mod set");
            Class<?> serverType = type("server.MinecraftServer"), worldType = type("world.World");
            Object server = serverType.getMethod("func_71276_C").invoke(null);
            Object world = serverType.getMethod("func_71218_a", int.class).invoke(server, 0);
            Class<?> stack = type("item.ItemStack");
            Class<?> inventory = type("inventory.InventoryCrafting");
            Class<?> registry = Class.forName("cpw.mods.fml.common.registry.GameRegistry");
            Object grid = inventory.getConstructor(type("inventory.Container"), int.class, int.class).newInstance(null, 3, 3);
            Field slots = inventory.getDeclaredField("field_70466_a");
            slots.setAccessible(true);
            for (int slot = 0; slot < 9; slot++) {
                Object item = registry.getMethod("findItem", String.class, String.class)
                        .invoke(null, "minecraft", slot == 4 ? "apple" : "gold_block");
                require(item != null, "Missing original enchanted apple ingredient");
                Array.set(slots.get(grid), slot, stack.getConstructor(type("item.Item"), int.class, int.class).newInstance(item, 1, 0));
            }
            Class<?> recipes = type("item.crafting.CraftingManager");
            Object crafted = recipes.getMethod("func_82787_a", inventory, worldType).invoke(recipes.getMethod("func_77594_a").invoke(null), grid, world);
            Object goldenApple = registry.getMethod("findItem", String.class, String.class).invoke(null, "minecraft", "golden_apple");
            require(crafted != null && stack.getMethod("func_77973_b").invoke(crafted) == goldenApple &&
                    (Integer) stack.getMethod("func_77960_j").invoke(crafted) == 1, "Wrong original enchanted golden apple recipe");

            Class<?> manager = Class.forName("thaumcraft.common.lib.crafting.ThaumcraftCraftingManager");
            Object aspects = manager.getMethod("getObjectTags", stack).invoke(null, crafted);
            require(aspects != null, "Original enchanted apple has no aspect tags");
            Map<String, Integer> tags = tags(aspects);
            require(tags.toString().equals("{lucrum=64, messis=1, metallum=64, praecantatio=4, sano=8}"),
                    "Unexpected original enchanted golden apple aspect tags: " + tags);

            Class<?> nodeType = Class.forName("thaumcraft.common.tiles.TileNode");
            Object node = nodeType.newInstance();
            Class<?> tile = type("tileentity.TileEntity");
            tile.getMethod("func_145834_a", worldType).invoke(node, world);
            set(node, 12, 64, -13);
            String first = id(node);
            set(node, 14, 65, -15);
            String moved = id(node);
            Class<?> compound = type("nbt.NBTTagCompound");
            Object data = compound.newInstance();
            tile.getMethod("func_145841_b", compound).invoke(node, data);
            Object restored = nodeType.newInstance();
            tile.getMethod("func_145834_a", worldType).invoke(restored, world);
            tile.getMethod("func_145839_a", compound).invoke(restored, data);
            String saved = id(restored);
            Object fresh = nodeType.newInstance();
            tile.getMethod("func_145834_a", worldType).invoke(fresh, world);
            set(fresh, 14, 65, -15);
            String next = id(fresh);
            require(first.equals("0:12:64:-13") && first.equals(moved) && first.equals(saved) && next.equals("0:14:65:-15"),
                    "Unexpected coordinate generation, caching or NBT load: " + first + "/" + moved + "/" + saved + "/" + next + "; NBT " + data);
            System.out.println("MCMODUP_TC4_ORIGINAL_PASS enchanted_golden_apple=" + tags + "; node_first=" + first
                    + "; node_moved=" + moved + "; node_restored=" + saved + "; node_fresh=" + next + "; NBT " + data);
        } catch (Throwable error) {
            System.err.println("MCMODUP_TC4_ORIGINAL_FAIL " + error);
            error.printStackTrace();
        }
    }

    private static Map<String, Integer> tags(Object aspects) throws Exception {
        Map<String, Integer> rows = new TreeMap<String, Integer>();
        Class<?> aspectType = Class.forName("thaumcraft.api.aspects.Aspect");
        Object[] keys = (Object[]) aspects.getClass().getMethod("getAspects").invoke(aspects);
        for (Object key : keys) {
            String name = (String) aspectType.getMethod("getTag").invoke(key);
            rows.put(name, (Integer) aspects.getClass().getMethod("getAmount", aspectType).invoke(aspects, key));
        }
        return rows;
    }

    private static String id(Object node) throws Exception {
        return (String) node.getClass().getMethod("getId").invoke(node);
    }

    private static void set(Object node, int x, int y, int z) throws Exception {
        Class<?> tile = type("tileentity.TileEntity");
        tile.getField("field_145851_c").setInt(node, x);
        tile.getField("field_145848_d").setInt(node, y);
        tile.getField("field_145849_e").setInt(node, z);
    }

    private static Class<?> type(String name) throws Exception { return Class.forName("net.minecraft." + name); }
    private static void require(boolean ok, String failure) { if (!ok) throw new AssertionError(failure); }
}
