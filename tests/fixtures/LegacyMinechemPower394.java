package mcmodup.minechemoracle;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;

/** Boot .394 with an actual non-default config; measure the same machines on both sides of the power gate. */
final class LegacyMinechemPower394 {
    private LegacyMinechemPower394() {}

    static String survey(Object world, int x, int y, int z) throws Exception {
        Class<?> settings = Class.forName("minechem.Settings");
        Field enabled = settings.getField("powerUseEnabled");
        String[] keys = {"costDecomposition", "synthesisMultiplier", "fissionMultiplier", "fusionMultiplier", "energyPacketSize",
                "maxDecomposerStorage", "maxSynthesizerStorage", "maxFissionStorage", "maxFusionStorage"};
        Map<String, String> rows = new TreeMap<String, String>();
        rows.put("boot_enabled", Boolean.toString(enabled.getBoolean(null)));
        for (String key : keys) rows.put(key, Integer.toString(settings.getField(key).getInt(null)));
        try {
            for (boolean paid : new boolean[]{false, true}) {
                enabled.setBoolean(null, paid);
                String profile = paid ? "paid" : "free";
                decomposer(world, x + (paid ? 1 : 0), y, z, paid, settings.getField("costDecomposition").getInt(null), profile, rows);
                LegacyMinechemSynthesis394.surveyPower(world, paid, profile, rows);
                LegacyMinechemReactors.surveyPower(world, x, y, z + (paid ? 48 : 16), paid, profile, rows);
            }
        } finally {
            enabled.setBoolean(null, Boolean.parseBoolean(rows.get("boot_enabled")));
        }
        try (PrintWriter out = new PrintWriter("minechem-power-config.txt", "UTF-8")) {
            for (Map.Entry<String, String> row : rows.entrySet()) out.println(row.getKey() + "=" + row.getValue());
            if (out.checkError()) throw new AssertionError("Could not write original power measurements");
        }
        return rows.size() + " .394 config, FE/RF intake and payment controls";
    }

    private static void decomposer(Object world, int x, int y, int z, boolean paid, int cost, String profile, Map<String, String> rows) throws Exception {
        Object block = Class.forName("minechem.MinechemBlocksGeneration").getField("decomposer").get(null);
        Class<?> w = Class.forName("net.minecraft.world.World"), stack = Class.forName("net.minecraft.item.ItemStack");
        w.getMethod("func_147449_b", int.class, int.class, int.class, Class.forName("net.minecraft.block.Block")).invoke(world, x, y, z, block);
        Object machine = w.getMethod("func_147438_o", int.class, int.class, int.class).invoke(world, x, y, z);
        rows.put(profile + "_decomposer_storage", storage(machine));
        Class<?> tile = machine.getClass();
        Object iron = Class.forName("cpw.mods.fml.common.registry.GameRegistry").getMethod("findItem", String.class, String.class)
            .invoke(null, "minecraft", "iron_ingot");
        Object input = stack.getConstructor(Class.forName("net.minecraft.item.Item"), int.class, int.class).newInstance(iron, 1, 0);
        Method get = tile.getMethod("func_70301_a", int.class), tick = tile.getMethod("func_145845_h");
        tile.getMethod("func_70299_a", int.class, stack).invoke(machine, 0, input);
        charge(machine, paid ? cost - 1 : 17);
        tick.invoke(machine);
        String below = (get.invoke(machine, 0) != null) + "/" + energy(machine);
        if (paid) { charge(machine, 1); tick.invoke(machine); }
        String started = (get.invoke(machine, 0) == null) + "/" + energy(machine);
        for (int i = 0; i < 16; ++i) tick.invoke(machine);
        Object result = get.invoke(machine, 1);
        int count = result == null ? 0 : stack.getField("field_77994_a").getInt(result);
        int number = result == null ? 0 : (Integer) stack.getMethod("func_77960_j").invoke(result);
        rows.put(profile + "_decomposer_run", below + "/" + started + "/" + number + "/" + count + "/" + energy(machine));
    }

    static int energy(Object machine) throws Exception { return (Integer) machine.getClass().getMethod("getEnergyStored").invoke(machine); }

    static String storage(Object machine) throws Exception {
        Class<?> tile = machine.getClass();
        return tile.getMethod("getMaxEnergyStored").invoke(machine) + "/" +
            tile.getMethod("receiveEnergy", int.class, boolean.class).invoke(machine, Integer.MAX_VALUE, true) + "/" + energy(machine);
    }

    static void charge(Object machine, int amount) throws Exception {
        Method receive = machine.getClass().getMethod("receiveEnergy", int.class, boolean.class);
        while (amount > 0) {
            int got = (Integer) receive.invoke(machine, amount, false);
            if (got <= 0 || got > amount) throw new AssertionError("Configured machine did not accept its test charge");
            amount -= got;
        }
    }
}
