package mcmodup.minechemoracle;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Survey the registered generator and its vanilla WorldGenMinable against a controlled stone volume. */
final class LegacyMinechemOre {
    private final boolean old;
    private final Object world, generator, chunks;
    private final Class<?> worldType, chunkProvider;
    private final Method getBlock, setBlock, blockId, generate, getMeta, loadChunk;
    private final Field enabled, density, cluster, provider;
    private final Map<Integer, Object> blocks = new LinkedHashMap<Integer, Object>();
    private final int uranium;
    private final Map<String, String> rows = new LinkedHashMap<String, String>();

    private LegacyMinechemOre(Object world, boolean old) throws Exception {
        this.world = world;
        this.old = old;
        worldType = mc("world.World");
        chunkProvider = mc("world.chunk.IChunkProvider");
        Class<?> blockType = mc("block.Block"), settings = Class.forName("minechem.Settings");
        getBlock = worldType.getMethod(old ? "func_72798_a" : "func_147439_a", int.class, int.class, int.class);
        setBlock = worldType.getMethod(old ? "func_72832_d" : "func_147465_d", int.class, int.class, int.class,
            old ? int.class : blockType, int.class, int.class);
        getMeta = worldType.getMethod("func_72805_g", int.class, int.class, int.class);
        loadChunk = worldType.getMethod("func_72964_e", int.class, int.class);
        provider = worldType.getField("field_73011_w");
        blockId = old ? null : blockType.getMethod("func_149682_b", blockType);
        Object ore = Class.forName("minechem.MinechemBlocksGeneration").getField("uranium").get(null);
        uranium = old ? blockType.getField("field_71990_ca").getInt(ore) : (Integer) blockId.invoke(null, ore);
        for (int id : new int[]{0, 1, 3, 13, 56}) {
            blocks.put(id, old ? id : blockType.getMethod("func_149729_e", int.class).invoke(null, id));
        }
        enabled = settings.getField(old ? "WorldGenOre" : "generateOre");
        density = settings.getField("UraniumOreDensity");
        cluster = settings.getField("UraniumOreClusterSize");
        Class<?> generation = Class.forName("minechem.MinechemGeneration");
        generator = generation.newInstance();
        generate = generation.getMethod("generate", Random.class, int.class, int.class, worldType, chunkProvider, chunkProvider);
        chunks = worldType.getMethod("func_72863_F").invoke(world);
    }

    static void survey(Object world, boolean old) throws Exception {
        new LegacyMinechemOre(world, old).survey();
    }

    private void survey() throws Exception {
        boolean defaultEnabled = enabled.getBoolean(null);
        int defaultDensity = density.getInt(null), defaultCluster = cluster.getInt(null);
        long seed = (Long) worldType.getMethod("func_72905_C").invoke(world);
        require(seed == 176394211L, "Wrong original ore survey world seed: " + seed);
        rows.put("seed", Long.toString(seed));
        rows.put("defaults", defaultEnabled + "/" + defaultDensity + "/" + defaultCluster);
        try {
            String full = replay("default", seed, 14, -11, defaultEnabled, defaultDensity, defaultCluster, false, true);
            String masked = replay("masked", seed, 14, -11, true, defaultDensity, defaultCluster, true, false);
            require(!full.isEmpty() && !masked.isEmpty() && !full.equals(masked), "Ore replacement mask did not exercise a vein");
            replay("tuned", seed, -15, 17, true, 2, 10, false, false);
            replay("empty_roll", seed, -33, 20, true, 0, defaultCluster, false, false);
            replay("floor", seed, -30, 60, true, 0, defaultCluster, false, false);
            replay("ceiling", seed, -33, 46, true, 0, defaultCluster, false, false);
            replay("disabled", seed, 14, -11, false, defaultDensity, defaultCluster, false, false);
            replay("negative_density", seed, 14, -11, true, -1, defaultCluster, false, false);
            enabled.setBoolean(null, true);
            density.setInt(null, defaultDensity);
            surfaceWorlds();
        } finally {
            enabled.setBoolean(null, defaultEnabled);
            density.setInt(null, defaultDensity);
            cluster.setInt(null, defaultCluster);
        }
        try (PrintWriter out = new PrintWriter("minechem-uranium-worldgen.txt", "UTF-8")) {
            for (Map.Entry<String, String> row : rows.entrySet()) out.println(row.getKey() + "=" + row.getValue());
            require(!out.checkError(), "Could not save the original ore trace");
        }
    }

    private void surfaceWorlds() throws Exception {
        Class<?> serverType = mc("server.MinecraftServer");
        Object server = serverType.getMethod("func_71276_C").invoke(null);
        Method dimension = serverType.getMethod("func_71218_a", int.class);
        StringBuilder actual = new StringBuilder();
        for (int dim : new int[]{0, -1, 1}) {
            Object level = dimension.invoke(server, dim), levelProvider = provider.get(level);
            boolean surface = (Boolean) levelProvider.getClass().getMethod("func_76569_d").invoke(levelProvider);
            if (actual.length() > 0) actual.append(',');
            actual.append(dim).append(':').append(surface);
            if (dim != 0) {
                TracedRandom random = new TracedRandom(0L);
                generate.invoke(generator, random, 14, -11, level, null, null);
                require(random.calls.isEmpty(), "Non-surface dimension tried to generate uranium: " + dim);
            }
        }
        rows.put("surface", actual.toString());
    }

    private String replay(String name, long seed, int cx, int cz, boolean on, int count, int size,
                          boolean mask, boolean checkRegistration) throws Exception {
        enabled.setBoolean(null, on);
        density.setInt(null, count);
        cluster.setInt(null, size);
        // Load and populate the halo before resetting the sample, so no neighbouring population
        // can contribute extra ore during the isolated call.
        for (int z = cz - 2; z <= cz + 2; z++) for (int x = cx - 2; x <= cx + 2; x++) loadChunk.invoke(world, x, z);
        int bx = cx * 16, bz = cz * 16;
        fill(bx, bz, mask);
        Random seeder = new Random(seed);
        long chunkSeed = (((seeder.nextLong() >> 3) * cx) + ((seeder.nextLong() >> 3) * cz)) ^ seed;
        TracedRandom random = new TracedRandom(chunkSeed);
        generate.invoke(generator, random, cx, cz, world, chunks, chunks);
        String positions = read(bx, bz, mask);
        String rolls = random.attempts(size + 10, on ? Math.max(0, count + 1) : 0);
        rows.put(name, cx + "," + cz + "|" + on + "," + count + "," + size + "," + mask + "|" + rolls + "|" +
            random.nextLong() + "|" + positions);
        if (checkRegistration) {
            fill(bx, bz, mask);
            Class.forName("cpw.mods.fml.common.registry.GameRegistry")
                .getMethod("generateWorld", int.class, int.class, worldType, chunkProvider, chunkProvider)
                .invoke(null, cx, cz, world, chunks, chunks);
            require(read(bx, bz, mask).equals(positions), "Forge's registered generator did not match the chunk seed replay");
            rows.put("forge_registry", "true");
        }
        return positions;
    }

    // Coordinates are relative to the chunk, including the +8 legacy vein center offset.
    private void fill(int bx, int bz, boolean mask) throws Exception {
        for (int x = 0; x < 32; x++) for (int y = 0; y < 54; y++) for (int z = 0; z < 32; z++) {
            setBlock.invoke(world, bx + x, y, bz + z, blocks.get(base(x, y, z, mask)), 0, 2);
        }
    }

    private String read(int bx, int bz, boolean mask) throws Exception {
        StringBuilder ore = new StringBuilder();
        for (int x = 0; x < 32; x++) for (int y = 0; y < 54; y++) for (int z = 0; z < 32; z++) {
            Object state = getBlock.invoke(world, bx + x, y, bz + z);
            int id = old ? (Integer) state : (Integer) blockId.invoke(null, state);
            int original = base(x, y, z, mask);
            if (id == uranium) {
                require(original == 1 && ((Integer) getMeta.invoke(world, bx + x, y, bz + z)) == 0,
                    "Uranium replaced a nonstone block or has unexpected metadata");
                if (ore.length() > 0) ore.append(';');
                ore.append(x).append(',').append(y).append(',').append(z);
            } else require(id == original, "Generation changed a non-uranium block: " + id + " instead of " + original);
        }
        return ore.toString();
    }

    private static int base(int x, int y, int z, boolean mask) {
        if (!mask) return 1;
        switch ((x + 3 * y + 5 * z) & 7) {
            case 0: case 1: return 56; // Don't overwrite ores.
            case 2: return 3;
            case 3: return 0;
            case 4: return 13;
            default: return 1;
        }
    }

    private static final class TracedRandom extends Random {
        final List<String> calls = new ArrayList<String>();
        TracedRandom(long seed) { super(seed); }
        @Override public int nextInt(int bound) {
            int result = super.nextInt(bound);
            calls.add(bound + ":" + result);
            return result;
        }
        @Override public float nextFloat() {
            calls.add("float");
            return super.nextFloat();
        }
        @Override public double nextDouble() {
            calls.add("double");
            return super.nextDouble();
        }

        String attempts(int sizeBound, int expectedCount) {
            StringBuilder out = new StringBuilder();
            int i = 0, count = 0;
            while (i < calls.size()) {
                if (count++ > 0) out.append(';');
                for (int bound : new int[]{16, 50, 16, sizeBound}) out.append(integer(i++, bound)).append(',');
                require(calls.get(i++).equals("float"), "Vein did not roll an angle first: " + calls);
                out.append(integer(i++, 3)).append(',').append(integer(i++, 3)).append(',');
                int diameters = 0;
                while (i < calls.size() && calls.get(i).equals("double")) {
                    diameters++;
                    i++;
                }
                out.append(diameters);
            }
            require(count == expectedCount, "Wrong number of uranium attempts: " + count + " expected " + expectedCount);
            return out.toString();
        }

        private String integer(int index, int bound) {
            String value = calls.get(index), prefix = bound + ":";
            require(value.startsWith(prefix), "Unexpected original random bounds: " + calls);
            return value.substring(prefix.length());
        }
    }

    private Class<?> mc(String name) throws ClassNotFoundException { return Class.forName("net.minecraft." + name); }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
