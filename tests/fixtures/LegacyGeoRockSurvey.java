package mcmodup.geostrataoracle;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLServerStartedEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Observe the rock IDs, placements, drops and crafts in the exact original world. */
@Mod(modid = "mcmodup_geo_oracle", name = "GeoStrata rock oracle", version = "1.0",
     dependencies = "required-after:GeoStrata;required-after:DragonAPI")
public final class LegacyGeoRockSurvey {
    @Mod.EventHandler
    public void check(FMLServerStartedEvent event) {
        try {
            require(Loader.isModLoaded("GeoStrata") && Loader.isModLoaded("DragonAPI"), "Missing original GeoStrata or DragonAPI");
            Class<?> worldType = mc("world.World"), serverType = mc("server.MinecraftServer");
            Object server = serverType.getMethod("func_71276_C").invoke(null);
            Object world = serverType.getMethod("func_71218_a", int.class).invoke(server, 0);
            Object spawn = worldType.getMethod("func_72861_E").invoke(world);
            int x = coord(spawn, "field_71574_a"), y = coord(spawn, "field_71572_b") + 4, z = coord(spawn, "field_71573_c");
            Method place = worldType.getMethod("func_72832_d", int.class, int.class, int.class, int.class, int.class, int.class);
            Method getId = worldType.getMethod("func_72798_a", int.class, int.class, int.class);
            Method getMeta = worldType.getMethod("func_72805_g", int.class, int.class, int.class);
            Class<?> rock = Class.forName("Reika.GeoStrata.Registry.RockTypes");
            Class<?> shape = Class.forName("Reika.GeoStrata.Registry.RockShapes");
            Method getItem = rock.getMethod("getItem", shape);
            Object[] rocks = rock.getEnumConstants(), shapes = shape.getEnumConstants();
            Class<?> block = mc("block.Block");
            Object[] blocks = (Object[]) block.getField("field_71973_m").get(null);
            Method drops = block.getMethod("getBlockDropped", worldType, int.class, int.class, int.class, int.class, int.class);
            Method blast = block.getMethod("getExplosionResistance", mc("entity.Entity"), worldType, int.class, int.class, int.class,
                    double.class, double.class, double.class);
            Object smooth = constant(shapes, "SMOOTH"), cobble = constant(shapes, "COBBLESTONE"), brick = constant(shapes, "BRICK");
            Crafting crafting = new Crafting(world, worldType);
            int placed = 0, crafts = 0;
            for (Object material : rocks) {
                Object raw = getItem.invoke(material, smooth), broken = getItem.invoke(material, cobble);
                for (Object form : shapes) {
                    Object stack = getItem.invoke(material, form);
                    int id = itemId(stack), meta = meta(stack);
                    place.invoke(world, x, y, z, id, meta, 2);
                    require((Integer) getId.invoke(world, x, y, z) == id && (Integer) getMeta.invoke(world, x, y, z) == meta,
                            "Original block placement changed " + material + "/" + form);
                    List<?> result = (List<?>) drops.invoke(blocks[id], world, x, y, z, meta, 0);
                    Object expected = form == smooth ? broken : stack;
                    require(result.size() == 1 && oneOf(result.get(0), expected, 1), "Wrong original drop for " + material + "/" + form + ": " + result);
                    if (form == smooth) {
                        Object resistance = blast.invoke(blocks[id], null, world, x, y, z, 0D, 0D, 0D);
                        System.out.println("MCMODUP_GEO_ROCK " + ((Enum<?>) material).name() + " "
                                + rock.getField("blockHardness").getFloat(material) + " " + resistance + " "
                                + rock.getMethod("getHarvestMin").invoke(material) + " " + rock.getField("minY").getInt(material)
                                + " " + rock.getField("maxY").getInt(material) + " " + rock.getField("rarity").getFloat(material));
                    }
                    placed++;
                }
                require(oneOf(crafting.bricks(raw), getItem.invoke(material, brick), 4), "Wrong original four-brick craft for " + material);
                require(oneOf(crafting.smelt(broken), raw, 1), "Wrong original cobble-to-smooth smelt for " + material);
                crafts += 2;
            }
            require(placed > 9 && crafts > 2, "No material families exercised");
            System.out.println("MCMODUP_GEO_PASS " + rocks.length + " rock types; " + shapes.length + " shapes; "
                    + placed + " placements and drops; " + crafts + " brick recipes or smelts through the original managers");
        } catch (Throwable failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            System.err.println("MCMODUP_GEO_FAIL " + cause + " (probe: " + failure.getStackTrace()[0] + ")");
            failure.printStackTrace();
        }
    }

    private static Object constant(Object[] constants, String name) {
        for (Object value : constants) if (((Enum<?>) value).name().equals(name)) return value;
        throw new AssertionError("Missing original rock shape " + name);
    }

    private static boolean oneOf(Object stack, Object item, int amount) throws Exception {
        return stack != null && itemId(stack) == itemId(item) && meta(stack) == meta(item)
                && stack.getClass().getField("field_77994_a").getInt(stack) == amount;
    }

    private static int itemId(Object stack) throws Exception { return stack.getClass().getField("field_77993_c").getInt(stack); }
    private static int meta(Object stack) throws Exception { return (Integer) stack.getClass().getMethod("func_77960_j").invoke(stack); }
    private static int coord(Object pos, String name) throws Exception { return pos.getClass().getField(name).getInt(pos); }
    private static Class<?> mc(String name) throws Exception { return Class.forName("net.minecraft." + name); }
    private static void require(boolean ok, String error) { if (!ok) throw new AssertionError(error); }

    private static final class Crafting {
        private final Object manager, furnace, world;
        private final Constructor<?> grid;
        private final Field slots;
        private final Method craft, copy, smelt;

        Crafting(Object world, Class<?> worldType) throws Exception {
            this.world = world;
            Class<?> inventory = mc("inventory.InventoryCrafting"), managerType = mc("item.crafting.CraftingManager");
            grid = inventory.getConstructor(mc("inventory.Container"), int.class, int.class);
            slots = inventory.getDeclaredField("field_70466_a");
            slots.setAccessible(true);
            manager = managerType.getMethod("func_77594_a").invoke(null);
            craft = managerType.getMethod("func_82787_a", inventory, worldType);
            Class<?> stack = mc("item.ItemStack"), furnaceType = mc("item.crafting.FurnaceRecipes");
            copy = stack.getMethod("func_77946_l");
            furnace = furnaceType.getMethod("func_77602_a").invoke(null);
            smelt = furnaceType.getMethod("getSmeltingResult", stack);
        }

        Object bricks(Object input) throws Exception {
            Object container = grid.newInstance(null, 2, 2);
            Object[] stacks = (Object[]) slots.get(container);
            for (int i = 0; i < 4; i++) stacks[i] = copy.invoke(input);
            return craft.invoke(manager, container, world);
        }
        Object smelt(Object input) throws Exception { return smelt.invoke(furnace, input); }
    }
}
