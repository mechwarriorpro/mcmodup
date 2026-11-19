package mcmodup.icbmcheck;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLServerStartedEvent;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Random;

/** Independent golden cases on the real Voltz server, not a legacy API stub. */
@Mod(modid = "mcmodup_plate_check", name = "ICBM plate comparison probe", version = "1.0.0", dependencies = "required-after:ICBM")
public final class LegacyIcbmPlateSmoke {
    @Mod.EventHandler
    public void check(FMLServerStartedEvent event) {
        try {
            require(Loader.isModLoaded("ICBM"), "ICBM not loaded");
            Object block = Class.forName("icbm.core.ICBMCore").getField("blockGlassPlate").get(null);
            require(block != null && block.getClass().getName().equals("icbm.contraption.block.BlockGlassPressurePlate"), "Wrong legacy block");
            int id = blockType().getField("field_71990_ca").getInt(block);
            Object server = type("server.MinecraftServer").getMethod("func_71276_C").invoke(null);
            Object world = server.getClass().getMethod("func_71218_a", int.class).invoke(server, 0);
            Object result = craft(world, 2, 1, 20, 20);
            require(itemId(result) == id && stackType().getField("field_77994_a").getInt(result) == 1, "Two horizontal glass blocks did not make one plate");
            require(itemId(craft(world, 1, 2, 20, 20)) != id && itemId(craft(world, 2, 1, 20, 102)) != id, "Unexpected alternate plate recipe");
            require((Integer) block.getClass().getMethod("func_71925_a", Random.class).invoke(block, new Random(24)) == 0, "Quantity dropped was not zero");
            require((Integer) block.getClass().getMethod("func_71915_e").invoke(block) == 1, "Plate push reaction was not destroy");
            require((Boolean) block.getClass().getMethod("func_71881_r").invoke(block), "Plate was not registered for random rechecks");
            Class<?> worldType = type("world.World"), entityType = type("entity.Entity");
            require((Integer) block.getClass().getMethod("func_71859_p_", worldType).invoke(block, world) == 10, "Unexpected plate tick delay");
            Object spawn = worldType.getMethod("func_72861_E").invoke(world);
            int x = coord(spawn, "field_71574_a"), y = coord(spawn, "field_71572_b") + 3, z = coord(spawn, "field_71573_c");
            setBlock(world, x, y - 1, z, 1);
            setBlock(world, x, y, z, id);
            Class<?>[] signal = new Class<?>[]{type("world.IBlockAccess"), int.class, int.class, int.class, int.class};
            Method collide = block.getClass().getMethod("func_71869_a", worldType, int.class, int.class, int.class, entityType);
            Object outside = item(world, x - 0.25, y + 0.05, z + 0.5);
            collide.invoke(block, world, x, y, z, outside);
            power(block, world, signal, x, y, z, 0);
            Object inside = item(world, x + 0.5, y + 0.05, z + 0.5);
            collide.invoke(block, world, x, y, z, inside);
            power(block, world, signal, x, y, z, 15);
            Object info = worldType.getMethod("func_72912_H").invoke(world);
            long now = (Long) worldType.getMethod("func_82737_E").invoke(world);
            tick(world, info, now + 10);
            power(block, world, signal, x, y, z, 15);
            entityType.getMethod("func_70107_b", double.class, double.class, double.class).invoke(inside, x - 0.25, y + 0.05, z + 0.5);
            tick(world, info, now + 19);
            power(block, world, signal, x, y, z, 15);
            tick(world, info, now + 20);
            power(block, world, signal, x, y, z, 0);
            System.out.println("MCMODUP_PLATE_PASS 1 output from horizontal glass, no panel/vertical recipe, no drops; item triggers 15 and releases after the second 10-tick check | " + building(world, x + 5, y, z));
        } catch (Throwable error) {
            System.err.println("MCMODUP_PLATE_FAIL " + error);
            error.printStackTrace();
        }
    }

    private static String building(Object world, int x, int y, int z) throws Exception {
        Class<?> core = Class.forName("icbm.core.ICBMCore"), block = blockType(), worldType = type("world.World");
        Object concrete = core.getField("blockConcrete").get(null), glass = core.getField("blockReinforcedGlass").get(null);
        int concreteId = block.getField("field_71990_ca").getInt(concrete), glassId = block.getField("field_71990_ca").getInt(glass);
        Object[][] inputs = {
            {stack(12), stack(13), stack(12), stack(13), stack(326), stack(13), stack(12), stack(13), stack(12)},
            ring(concreteId, 0, 49), ring(concreteId, 1, 265), ring(265, 0, 20)
        };
        Class<?> gridType = type("inventory.InventoryCrafting"), managerType = type("item.crafting.CraftingManager");
        java.lang.reflect.Field raw = gridType.getDeclaredField("field_70466_a");
        raw.setAccessible(true);
        Method craft = managerType.getMethod("func_82787_a", gridType, worldType);
        Object manager = managerType.getMethod("func_77594_a").invoke(null);
        Method metadata = stackType().getMethod("func_77960_j");
        Method drops = block.getMethod("getBlockDropped", worldType, int.class, int.class, int.class, int.class, int.class);
        Method place = worldType.getMethod("func_72832_d", int.class, int.class, int.class, int.class, int.class, int.class);
        Method resistance = block.getMethod("getExplosionResistance", type("entity.Entity"), worldType, int.class, int.class, int.class,
                double.class, double.class, double.class);
        Method hardness = block.getMethod("func_71934_m", worldType, int.class, int.class, int.class);
        for (int i = 0; i < inputs.length; i++) {
            int id = i == 3 ? glassId : concreteId, meta = i == 3 ? 0 : i;
            Object expected = i == 3 ? glass : concrete;
            Object grid = gridType.getConstructor(type("inventory.Container"), int.class, int.class).newInstance(null, 3, 3);
            System.arraycopy(inputs[i], 0, raw.get(grid), 0, 9);
            Object made = craft.invoke(manager, grid, world);
            require(itemId(made) == id && (Integer) metadata.invoke(made) == meta &&
                stackType().getField("field_77994_a").getInt(made) == 8, "Unexpected build recipe/metadata for case " + i);
            require((Boolean) place.invoke(world, x, y, z, id, meta, 2), "Could not place build variant " + i);
            java.util.List<?> result = (java.util.List<?>) drops.invoke(expected, world, x, y, z, meta, 0);
            require(result.size() == 1 && itemId(result.get(0)) == id && (Integer) metadata.invoke(result.get(0)) == meta,
                    "Original building block dropped a different variant at " + i);
            float blast = (Float) resistance.invoke(expected, null, world, x, y, z, 0D, 0D, 0D);
            require(Math.abs(blast - new float[]{30F, 38F, 48F, 28.8F}[i]) < .001F &&
                    (Float) hardness.invoke(expected, world, x, y, z) == (i == 3 ? 0.6F : 3.8F), "Wrong physical properties for " + i + ": " + blast);
            require(!(Boolean) block.getMethod("hasTileEntity", int.class).invoke(expected, meta), "Building block has a tile: " + i);
        }
        Method side = block.getMethod("func_71877_c", type("world.IBlockAccess"), int.class, int.class, int.class, int.class);
        require(!(Boolean) side.invoke(glass, world, x, y, z, 5) && (Boolean) side.invoke(glass, world, x + 1, y, z, 5),
                "Reinforced glass does not cull only its interior face in air");
        return "concrete/compact/reinforced/glass each craft 8 and drop their own variant; hardness 3.8/3.8/3.8/0.6; " +
               "resistance 30/38/48/28.8; reinforced glass culls inner face; no building block tiles";
    }

    private static Object[] ring(int a, int meta, int b) throws Exception {
        Object first = stackType().getConstructor(int.class, int.class, int.class).newInstance(a, 1, meta);
        Object second = stack(b);
        return new Object[]{first, second, first, second, first, second, first, second, first};
    }

    private static Object craft(Object world, int width, int height, int first, int second) throws Exception {
        Class<?> inventory = type("inventory.InventoryCrafting");
        Object grid = inventory.getConstructor(type("inventory.Container"), int.class, int.class).newInstance(null, width, height);
        putCraftingItems(grid, first, second);
        Class<?> mgr = type("item.crafting.CraftingManager");
        return mgr.getMethod("func_82787_a", inventory, type("world.World")).invoke(mgr.getMethod("func_77594_a").invoke(null), grid, world);
    }

    private static void putCraftingItems(Object grid, int first, int second) throws Exception {
        java.lang.reflect.Field slots = grid.getClass().getDeclaredField("field_70466_a");
        slots.setAccessible(true);
        Object stacks = slots.get(grid);
        Array.set(stacks, 0, stack(first));
        Array.set(stacks, 1, stack(second));
    }

    private static int itemId(Object item) throws Exception {
        return item == null ? -1 : stackType().getField("field_77993_c").getInt(item);
    }

    private static Object stack(int id) throws Exception {
        return stackType().getConstructor(int.class, int.class, int.class).newInstance(id, 1, 0);
    }

    private static Object item(Object world, double x, double y, double z) throws Exception {
        Object entity = type("entity.item.EntityItem").getConstructor(type("world.World"), double.class, double.class, double.class, stackType())
                .newInstance(world, x, y, z, stack(280));
        require((Boolean) world.getClass().getMethod("func_72838_d", type("entity.Entity")).invoke(world, entity), "Item did not spawn");
        return entity;
    }

    private static int coord(Object pos, String name) throws Exception {
        return pos.getClass().getField(name).getInt(pos);
    }

    private static void setBlock(Object world, int x, int y, int z, int id) throws Exception {
        require((Boolean) world.getClass().getMethod("func_94575_c", int.class, int.class, int.class, int.class).invoke(world, x, y, z, id), "Block did not place");
    }

    private static void power(Object block, Object world, Class<?>[] sig, int x, int y, int z, int expected) throws Exception {
        int meta = (Integer) world.getClass().getMethod("func_72805_g", int.class, int.class, int.class).invoke(world, x, y, z);
        require(meta == (expected == 0 ? 0 : 1), "Wrong original plate metadata: " + meta);
        for (int side = 0; side < 6; side++) {
            require((Integer) block.getClass().getMethod("func_71865_a", sig).invoke(block, world, x, y, z, side) == expected, "Wrong legacy weak power");
            require((Integer) block.getClass().getMethod("func_71855_c", sig).invoke(block, world, x, y, z, side) == (side == 1 ? expected : 0), "Wrong legacy strong power");
        }
    }

    private static void tick(Object world, Object info, long time) throws Exception {
        info.getClass().getMethod("func_82572_b", long.class).invoke(info, time);
        world.getClass().getMethod("func_72955_a", boolean.class).invoke(world, false);
    }

    private static Class<?> blockType() throws Exception { return type("block.Block"); }
    private static Class<?> stackType() throws Exception { return type("item.ItemStack"); }
    private static Class<?> type(String name) throws Exception { return Class.forName("net.minecraft." + name); }
    private static void require(boolean ok, String failure) { if (!ok) throw new AssertionError(failure); }
}
