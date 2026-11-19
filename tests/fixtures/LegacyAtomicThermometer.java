package mcmodup.atomiccheck;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLServerStartedEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Measure the exact 1.2.0 Voltz sensor, with the original Calclavia thermal grid. */
@Mod(modid = "mcmodup_atomic_check", name = "Atomic Science thermometer probe", version = "1.0.0",
     dependencies = "required-after:AtomicScience")
public final class LegacyAtomicThermometer {
    @Mod.EventHandler
    public void check(FMLServerStartedEvent event) {
        try {
            require(Loader.isModLoaded("AtomicScience"), "Atomic Science was not loaded");
            Class<?> worldType = type("world.World");
            Object server = type("server.MinecraftServer").getMethod("func_71276_C").invoke(null);
            Object world = server.getClass().getMethod("func_71218_a", int.class).invoke(server, 0);
            Object block = Class.forName("atomicscience.AtomicScience").getField("blockThermometer").get(null);
            int blockId = type("block.Block").getField("field_71990_ca").getInt(block);
            Object spawn = worldType.getMethod("func_72861_E").invoke(world);
            int x = coord(spawn, "field_71574_a"), y = coord(spawn, "field_71572_b") + 3, z = coord(spawn, "field_71573_c");
            Method place = worldType.getMethod("func_72832_d", int.class, int.class, int.class, int.class, int.class, int.class);
            require((Boolean) place.invoke(world, x, y, z, blockId, 0, 3), "Could not place the actual thermometer");
            Class<?> tile = Class.forName("atomicscience.process.sensor.TileThermometer");
            readSensor(world, worldType, block, tile, x, y, z);
        } catch (Throwable error) {
            System.err.println("MCMODUP_ATOMIC_FAIL " + error);
            error.printStackTrace();
        }
    }

    private static void readSensor(Object world, Class<?> worldType, Object block, Class<?> tile, int x, int y, int z) throws Exception {
        Class<?> vector = Class.forName("universalelectricity.api.vector.Vector3");
        Class<?> vectorWorld = Class.forName("universalelectricity.api.vector.VectorWorld");
        Object sensor = worldType.getMethod("func_72796_p", int.class, int.class, int.class).invoke(world, x, y, z);
        require(tile.isInstance(sensor), "Wrong thermometer block entity: " + sensor);
        Method get = tile.getMethod("getThershold"), set = tile.getMethod("setThreshold", int.class);
        Class<?> playerType = type("entity.player.EntityPlayer");
        Object player = Class.forName("net.minecraftforge.common.FakePlayerFactory")
            .getMethod("get", worldType, String.class).invoke(null, world, "[mcmodup thermometer]");
        Method crouch = type("entity.Entity").getMethod("func_70095_a", boolean.class);
        Method use = tile.getDeclaredMethod("use", playerType, int.class, vector);
        Method configure = tile.getDeclaredMethod("configure", playerType, int.class, vector);
        use.setAccessible(true);
        configure.setAccessible(true);
        Field sensed = tile.getField("detectedTemperature");
        require((Integer) get.invoke(sensor) == 1000 && sensed.getFloat(sensor) == 295F, "Different defaults");
        require((Boolean) use.invoke(sensor, player, 1, null) && (Integer) get.invoke(sensor) == 900, "Use should decrease by 100");
        crouch.invoke(player, true);
        require((Boolean) use.invoke(sensor, player, 1, null) && (Integer) get.invoke(sensor) == 1000, "Crouching use should increase by 100");
        require((Boolean) configure.invoke(sensor, player, 1, null) && (Integer) get.invoke(sensor) == 990, "Crouching configure should decrease by 10");
        crouch.invoke(player, false);
        require((Boolean) configure.invoke(sensor, player, 1, null) && (Integer) get.invoke(sensor) == 1000, "Configure should increase by 10");
        int[][] wrap = {{0, 5000}, {-1, 5000}, {5000, 5000}, {5001, 1}, {10_010, 10}};
        for (int[] pair : wrap) {
            set.invoke(sensor, pair[0]);
            require((Integer) get.invoke(sensor) == pair[1], "Unexpected threshold wrapping for " + pair[0]);
        }
        set.invoke(sensor, 1000);
        Class<?> gridType = Class.forName("calclavia.lib.thermal.ThermalGrid");
        Object grid = gridType.getMethod("instance").invoke(null);
        Object local = vectorWorld.getConstructor(worldType, double.class, double.class, double.class).newInstance(world, x, y, z);
        Object remote = vectorWorld.getConstructor(worldType, double.class, double.class, double.class).newInstance(world, x + 4, y, z);
        Method heat = gridType.getMethod("addTemperature", vectorWorld, float.class);
        Method ambient = gridType.getMethod("getDefaultTemperature", vectorWorld);
        // Calclavia updates heat on a separate thread. Use its storage lock to
        // hold both measured temperatures steady while explicitly ticking the sensor.
        Field source = gridType.getDeclaredField("thermalSource");
        source.setAccessible(true);
        synchronized (source.get(grid)) {
            readStableSamples(world, worldType, block, tile, x, y, z, sensor, vector, grid, local, remote, heat, ambient, get);
        }
    }

    private static void readStableSamples(Object world, Class<?> worldType, Object block, Class<?> tile, int x, int y, int z,
                                          Object sensor, Class<?> vector, Object grid, Object local, Object remote,
                                          Method heat, Method ambient, Method get) throws Exception {
        Field sensed = tile.getField("detectedTemperature");
        heat.invoke(grid, local, 1000F - (Float) ambient.invoke(grid, local));
        heat.invoke(grid, remote, 999F - (Float) ambient.invoke(grid, remote));
        Method tick = tile.getMethod("func_70316_g");
        Field ticks = tile.getSuperclass().getDeclaredField("ticks");
        ticks.setAccessible(true);
        ticks.setLong(sensor, 0L);
        for (int i = 0; i < 9; ++i) tick.invoke(sensor);
        require(sensed.getFloat(sensor) == 295F, "Thermometer sampled before the tenth tick");
        tick.invoke(sensor);
        require(sensed.getFloat(sensor) == 1000F, "No local sample on the tenth tick: " + sensed.getFloat(sensor));
        String weak = signal(block, sensor, world, tile, x, y, z, 15);
        Object target = vector.getConstructor(double.class, double.class, double.class).newInstance(x + 4, y, z);
        tile.getMethod("setTrack", vector).invoke(sensor, target);
        for (int i = 0; i < 10; ++i) tick.invoke(sensor);
        require(sensed.getFloat(sensor) == 999F, "The next sample did not use the tracked coordinate");
        signal(block, sensor, world, tile, x, y, z, 0);

        Class<?> nbt = type("nbt.NBTTagCompound");
        Object saved = nbt.newInstance();
        tile.getMethod("func_70310_b", nbt).invoke(sensor, saved);
        require((Integer) nbt.getMethod("func_74762_e", String.class).invoke(saved, "threshold") == 1000, "Threshold was not persisted");
        Object reloaded = tile.newInstance();
        tile.getMethod("func_70307_a", nbt).invoke(reloaded, saved);
        Object tracked = tile.getField("trackCoordinate").get(reloaded);
        require((Integer) get.invoke(reloaded) == 1000 && tracked != null &&
            (Integer) vector.getMethod("intX").invoke(tracked) == x + 4 &&
            (Integer) vector.getMethod("intY").invoke(tracked) == y &&
            (Integer) vector.getMethod("intZ").invoke(tracked) == z, "Did not restore tracked threshold and coordinate");
        System.out.println("MCMODUP_ATOMIC_PASS defaults=1000/295; use=−100/+100; configure=+10/−10; modulo=5000; " +
            "sample_interval=10; at_threshold=15; below=0; weak_at_threshold=" + weak + "; tracked coordinate and threshold persisted");
    }

    private static String signal(Object block, Object sensor, Object world, Class<?> tile, int x, int y, int z, int expected) throws Exception {
        Class<?> access = type("world.IBlockAccess");
        Method strong = tile.getMethod("getStrongRedstonePower", access, int.class);
        Method weak = block.getClass().getMethod("func_71865_a", access, int.class, int.class, int.class, int.class);
        StringBuilder out = new StringBuilder();
        for (int side = 0; side < 6; ++side) {
            require((Integer) strong.invoke(sensor, world, side) == expected, "Wrong original strong power at " + side);
            if (side > 0) out.append(',');
            out.append(weak.invoke(block, world, x, y, z, side));
        }
        return out.toString();
    }

    private static int coord(Object pos, String field) throws Exception { return pos.getClass().getField(field).getInt(pos); }
    private static Class<?> type(String name) throws Exception { return Class.forName("net.minecraft." + name); }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
