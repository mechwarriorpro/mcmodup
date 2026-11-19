package mcmodup.geostrataoracle;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLServerStartedEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
            Map<Integer, String> natural = new LinkedHashMap<Integer, String>();
            StringBuilder specs = new StringBuilder();
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
                        natural.put(id * 16 + meta, ((Enum<?>) material).name());
                        if (specs.length() > 0) specs.append(';');
                        specs.append(material).append('=').append(rock.getField("blockHardness").getFloat(material))
                             .append(',').append(blast.invoke(blocks[id], null, world, x, y, z, 0D, 0D, 0D))
                             .append(',').append(rock.getMethod("getHarvestMin").invoke(material))
                             .append(',').append(rock.getField("minY").getInt(material)).append(',').append(rock.getField("maxY").getInt(material))
                             .append(',').append(rock.getField("rarity").getFloat(material));
                    }
                    placed++;
                }
                Object baked = getItem.invoke(material, brick);
                crafts += checkCraft(crafting, getItem, shapes, material, raw, baked, "BRICK", 4, "SS", "SS");
                crafts += checkCraft(crafting, getItem, shapes, material, raw, baked, "ROUND", 4, "BB", "BB");
                crafts += checkCraft(crafting, getItem, shapes, material, raw, baked, "FITTED", 2, "BB");
                crafts += checkCraft(crafting, getItem, shapes, material, raw, baked, "TILE", 4, " S ", "S S", " S ");
                crafts += checkCraft(crafting, getItem, shapes, material, raw, baked, "INSCRIBED", 3, "B", "S", "B");
                crafts += checkCraft(crafting, getItem, shapes, material, raw, baked, "ENGRAVED", 2, "SB", "BS");
                crafts += checkCraft(crafting, getItem, shapes, material, raw, baked, "ENGRAVED", 2, "BS", "SB");
                crafts += checkCraft(crafting, getItem, shapes, material, raw, baked, "CONNECTED", 8, "SSS", "S S", "SSS");
                require(oneOf(crafting.smelt(broken), raw, 1), "Wrong original cobble-to-smooth smelt for " + material);
                crafts++;
            }
            require(placed == 153 && natural.size() == 17 && crafts == 153, "The original rock families changed");
            int connectedCases = checkConnected(world, place, getItem, blocks, rocks, shapes, x, y, z);
            String generated = rockGeneration(world, worldType, place, getId, getMeta, natural);
            String terrain = terrainRules(world, worldType, place, getId, getMeta, x, z, rocks);
            System.out.println("MCMODUP_GEO_PASS " + rocks.length + " rock types; " + shapes.length + " shapes; "
                    + placed + " exact placements and drops; " + crafts + " crafting or smelting cases | " + specs + " | " + generated + " | " + terrain + " | " + connectedCases + " connected-face masks match; same-rock variants connect; different rock does not");
        } catch (Throwable failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            System.err.println("MCMODUP_GEO_FAIL " + cause + " (probe: " + failure.getStackTrace()[0] + ")");
            failure.printStackTrace();
        }
    }

    /** Exhaust each of the 256 possible in-plane neighbor patterns on all six faces of a real v23b wall. */
    private static int checkConnected(Object world, Method place, Method getItem, Object[] blocks,
                                      Object[] rocks, Object[] shapes, int x, int y, int z) throws Exception {
        Object granite = constant(rocks, "GRANITE"), marble = constant(rocks, "MARBLE");
        Object connected = constant(shapes, "CONNECTED"), smooth = constant(shapes, "SMOOTH"), brick = constant(shapes, "BRICK");
        Object center = getItem.invoke(granite, connected), stone = getItem.invoke(granite, smooth);
        Object edge = getItem.invoke(granite, brick), unlike = getItem.invoke(marble, connected);
        Class<?> faces = Class.forName("net.minecraftforge.common.ForgeDirection");
        Method masks = blocks[itemId(center)].getClass().getMethod("getEdgesForFace", mc("world.IBlockAccess"),
                int.class, int.class, int.class, faces, ((Enum<?>) granite).getDeclaringClass());
        final String[] sides = {"DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"};
        // Each row is [side mapped to mask 2, side mapped to mask 4]; the corner masks are 1/3/7/9.
        final int[][] advances = {{0, 0, 1, 1, 0, 0}, {0, 0, 1, 1, 0, 0}, {1, 0, 0, 0, 1, 0},
                                  {1, 0, 0, 0, 1, 0}, {0, 0, 1, 0, 1, 0}, {0, 0, 1, 0, 1, 0}};
        final int[] two = {1, 1, 1, 0, 0, -1, -1, -1}, four = {1, 0, -1, 1, -1, 1, 0, -1};
        final int[] ids = {1, 2, 3, 4, 6, 7, 8, 9};
        place.invoke(world, x, y, z, itemId(center), meta(center), 2);
        for (int i = 0; i < sides.length; i++) {
            Object face = faces.getField(sides[i]).get(null);
            int[] dir = advances[i];
            for (int pattern = 0; pattern < 256; pattern++) {
                for (int index = 0; index < ids.length; index++) {
                    Object next = (pattern & (1 << index)) == 0 ? unlike : index % 2 == 0 ? stone : edge;
                    place.invoke(world, x + dir[0] * two[index] + dir[3] * four[index], y + dir[1] * two[index] + dir[4] * four[index],
                            z + dir[2] * two[index] + dir[5] * four[index], itemId(next), meta(next), 2);
                }
                int removed = 0;
                for (int index = 0; index < ids.length; index++) {
                    if ((pattern & (1 << index)) == 0) continue;
                    int mask = ids[index];
                    if (two[index] != 0 && four[index] != 0 && ((pattern & (two[index] > 0 ? 2 : 64)) == 0 ||
                            (pattern & (four[index] > 0 ? 8 : 16)) == 0)) continue;
                    removed |= 1 << mask;
                }
                int actual = 0;
                for (Object mask : (java.util.Collection<?>) masks.invoke(blocks[itemId(center)], world, x, y, z, face, granite)) {
                    require(mask instanceof Integer && (Integer) mask >= 0 && (Integer) mask < 10, "Bad overlay index " + mask);
                    actual |= 1 << (Integer) mask;
                }
                require(actual == (0x3fe & ~removed), "Unexpected " + sides[i] + " connected masks for " + pattern + ": " + actual + " vs " + (0x3fe & ~removed));
            }
            for (int index = 0; index < ids.length; index++) {
                place.invoke(world, x + dir[0] * two[index] + dir[3] * four[index], y + dir[1] * two[index] + dir[4] * four[index],
                        z + dir[2] * two[index] + dir[5] * four[index], 0, 0, 2);
            }
        }
        return 6 * 256;
    }

    private static int checkCraft(Crafting crafting, Method getItem, Object[] shapes, Object rock,
                                  Object smooth, Object brick, String shape, int count, String... pattern) throws Exception {
        Object expected = getItem.invoke(rock, constant(shapes, shape));
        require(oneOf(crafting.shaped(smooth, brick, pattern), expected, count), "Wrong original " + shape + " craft for " + rock);
        return 1;
    }

    private static String rockGeneration(Object world, Class<?> worldType, Method place, Method getId,
                                         Method getMeta, Map<Integer, String> natural) throws Exception {
        // Isolate the registered class from other generators on a fresh flat test world.
        Class<?> generator = Class.forName("Reika.GeoStrata.World.RockGenerator"), chunks = mc("world.chunk.IChunkProvider");
        Object instance = generator.newInstance(), provider = worldType.getMethod("func_72863_F").invoke(world);
        Method generate = generator.getMethod("generate", Random.class, int.class, int.class, worldType, chunks, chunks);
        final int chunk = 16, first = (chunk - 1) * 16, last = first + 48, seed = 237,
                  stone = 1, ore = 56, maxHeight = 129;
        // Fill a 48×48 stone volume so cross-chunk parts of the generated veins have targets.
        // Two sparse marker pillars must survive as ore rather than being treated as stone.
        for (int x = first; x < last; x++) for (int z = first; z < last; z++) for (int y = 4; y < maxHeight; y++) {
            place.invoke(world, x, y, z, (x == 259 && (z == 263 || z == 265)) ? ore : stone, 0, 2);
        }
        OriginRandom random = new OriginRandom(seed);
        generate.invoke(instance, random, chunk, chunk, world, provider, provider);
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        int changed = 0, untouched = 0;
        for (int x = first; x < last; x++) for (int z = first; z < last; z++) for (int y = 4; y < maxHeight; y++) {
            int id = (Integer) getId.invoke(world, x, y, z);
            boolean marker = x == 259 && (z == 263 || z == 265);
            require(!marker || id == ore, "The original generator replaced an existing ore");
            if (marker || id == stone) { untouched++; continue; }
            String kind = natural.get(id * 16 + (Integer) getMeta.invoke(world, x, y, z));
            require(kind != null, "Unexpected block " + id + " in generated stone volume");
            Integer before = counts.get(kind);
            counts.put(kind, before == null ? 1 : before + 1);
            changed++;
        }
        require(counts.size() >= 4 && changed > 0 && untouched > changed, "No credible original rock veins: " + counts);
        return "seed=" + seed + "; center chunk=" + chunk + "," + chunk + "; original replaced " + changed
                + " stones; left " + untouched + " cells including both ore pillars; counts=" + counts + "; direct generator RNG bounds=" + random.bounds;
    }

    /** Inspect the original generator's settings and exercise its origin predicate in a controlled chunk. */
    private static String terrainRules(Object world, Class<?> worldType, Method place, Method getId, Method getMeta,
                                       int x, int z, Object[] rocks) throws Exception {
        Class<?> generator = Class.forName("Reika.GeoStrata.World.RockGenerator");
        Object instance = generator.newInstance();
        Class<?> rocksType = ((Enum<?>) rocks[0]).getDeclaringClass();
        Method density = getter(generator, "getDensityFactor", rocksType);
        Method dimension = getter(generator, "canGenInDimension", int.class);
        Method canGen = rocksType.getMethod("canGenerateAt", worldType, int.class, int.class, int.class, Random.class);
        int base = generator.getField("BASE_GEN").getInt(null), size = generator.getField("VEIN_SIZE").getInt(null);
        require(base > 0 && size > 0, "Missing original rock generator parameters");
        Map<Integer, Boolean> dimensions = new LinkedHashMap<Integer, Boolean>();
        for (int id : new int[]{0, -1, 1, 7, 42}) dimensions.put(id, (Boolean) dimension.invoke(instance, id));
        Object chunk = worldType.getMethod("func_72938_d", int.class, int.class).invoke(world, x, z);
        Method setBiomes = chunk.getClass().getMethod("func_76616_a", byte[].class);
        byte[] previous = ((byte[]) chunk.getClass().getMethod("func_76605_m").invoke(chunk)).clone();
        Class<?> biomeType = mc("world.biome.BiomeGenBase");
        Method getBiome = worldType.getMethod("func_72807_a", int.class, int.class);
        Field biomeId = biomeType.getField("field_76756_M");
        Object[] biomes = {biomeType.getField("field_76772_c").get(null), biomeType.getField("field_76769_d").get(null),
                           biomeType.getField("field_76774_n").get(null)};
        Class<?> block = mc("block.Block");
        int lava = block.getField("field_71990_ca").getInt(block.getField("field_71938_D").get(null));
        Map<String, String> results = new LinkedHashMap<String, String>();
        try {
            for (Object rock : rocks) {
                int lo = rocksType.getField("minY").getInt(rock), hi = rocksType.getField("maxY").getInt(rock);
                int y = Math.max(4, (lo + hi) / 2);
                int old = (Integer) getId.invoke(world, x + 1, y, z);
                int metadata = (Integer) getMeta.invoke(world, x + 1, y, z);
                StringBuilder predicates = new StringBuilder();
                try {
                    place.invoke(world, x + 1, y, z, 0, 0, 2);
                    for (Object biome : biomes) {
                        byte[] all = new byte[previous.length];
                        Arrays.fill(all, (byte) biomeId.getInt(biome));
                        setBiomes.invoke(chunk, (Object) all);
                        require(getBiome.invoke(world, x, z) == biome,
                            "Could not select original sample biome");
                        predicates.append(allowed(canGen, rock, world, x, y, z) ? '1' : '0');
                    }
                    byte[] plains = new byte[previous.length];
                    Arrays.fill(plains, (byte) biomeId.getInt(biomes[0]));
                    setBiomes.invoke(chunk, (Object) plains);
                    place.invoke(world, x + 1, y, z, lava, 0, 2);
                    predicates.append(allowed(canGen, rock, world, x, y, z) ? '1' : '0');
                    require(!allowed(canGen, rock, world, x, lo - 1, z) && !allowed(canGen, rock, world, x, hi + 1, z),
                        "Rock origin was accepted outside the recorded height range: " + rock);
                } finally {
                    place.invoke(world, x + 1, y, z, old, metadata, 2);
                }
                float rarity = rocksType.getField("rarity").getFloat(rock);
                double factor = ((Number) density.invoke(instance, rock)).doubleValue();
                results.put(((Enum<?>) rock).name(), factor + "/" + (int) Math.ceil(base * rarity * factor) + "/" + predicates);
            }
        } finally {
            setBiomes.invoke(chunk, (Object) previous);
        }
        return "base=" + base + "; vein size=" + size + "; dimensions=" + dimensions
            + "; density/effective ceiling/plains-desert-snow-lava=" + results;
    }

    private static boolean allowed(Method predicate, Object rock, Object world, int x, int y, int z) throws Exception {
        return (Boolean) predicate.invoke(rock, world, x, y, z, new Random(237));
    }

    private static Method getter(Class<?> owner, String name, Class<?> arg) throws Exception {
        Method method = owner.getDeclaredMethod(name, arg);
        method.setAccessible(true);
        return method;
    }

    private static final class OriginRandom extends Random {
        private static final long serialVersionUID = 1L;
        final Map<Integer, Integer> bounds = new LinkedHashMap<Integer, Integer>();
        OriginRandom(long seed) { super(seed); }
        @Override public int nextInt(int bound) {
            StackTraceElement[] callers = new Throwable().getStackTrace();
            if (callers.length > 1 && callers[1].getClassName().equals("Reika.GeoStrata.World.RockGenerator")) {
                Integer seen = bounds.get(bound);
                bounds.put(bound, seen == null ? 1 : seen + 1);
            }
            return super.nextInt(bound);
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

        Object shaped(Object smooth, Object brick, String[] pattern) throws Exception {
            Object container = grid.newInstance(null, 3, 3);
            Object[] stacks = (Object[]) slots.get(container);
            for (int y = 0; y < pattern.length; y++) for (int x = 0; x < pattern[y].length(); x++) {
                char token = pattern[y].charAt(x);
                if (token != ' ') stacks[y * 3 + x] = copy.invoke(token == 'S' ? smooth : brick);
            }
            return craft.invoke(manager, container, world);
        }
        Object smelt(Object input) throws Exception { return smelt.invoke(furnace, input); }
    }
}
