package mcmodup.minechemoracle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Runs registered blueprints and assembled reactors in the exact Forge world. */
final class LegacyMinechemReactors {
    private final boolean old;
    private final Object world, reactorBlock, projectorBlock, ghostBlock, element, blueprintItem;
    private final Class<?> block, stack, blueprints;
    private final Constructor<?> itemStack;
    private final Method place, getBlock, tile;

    private LegacyMinechemReactors(Object world, boolean old) throws Exception {
        this.old = old;
        this.world = world;
        Class<?> w = mc("world.World");
        block = mc("block.Block");
        stack = mc("item.ItemStack");
        itemStack = stack.getConstructor(mc("item.Item"), int.class, int.class);
        Class<?> blocks = Class.forName("minechem.MinechemBlocksGeneration");
        Class<?> items = Class.forName(old ? "minechem.MinechemItemsGeneration" : "minechem.MinechemItemsRegistration");
        reactorBlock = blocks.getField("fusion").get(null);
        projectorBlock = blocks.getField("blueprintProjector").get(null);
        ghostBlock = blocks.getField("ghostBlock").get(null);
        element = items.getField("element").get(null);
        blueprintItem = items.getField("blueprint").get(null);
        blueprints = Class.forName("minechem.item.blueprint.MinechemBlueprint");
        place = w.getMethod(old ? "func_72832_d" : "func_147465_d", int.class, int.class, int.class,
                old ? int.class : block, int.class, int.class);
        getBlock = w.getMethod(old ? "func_72798_a" : "func_147439_a", int.class, int.class, int.class);
        tile = w.getMethod(old ? "func_72796_p" : "func_147438_o", int.class, int.class, int.class);
    }

    static String survey(Object world, boolean old, int x, int y, int z) throws Exception {
        return new LegacyMinechemReactors(world, old).run(x, y, z);
    }

    static void surveyPower(Object world, int x, int y, int z, boolean paid, String profile, java.util.Map<String, String> rows) throws Exception {
        new LegacyMinechemReactors(world, false).runPower(x, y, z, paid, profile, rows);
    }

    private void runPower(int x, int y, int z, boolean paid, String profile, java.util.Map<String, String> rows) throws Exception {
        Integer[][][] split = layout(blueprints.getField("fission").get(null));
        Integer[][][] join = layout(blueprints.getField("fusion").get(null));
        // Build the actual shells and use the same facing as the default Forge replay.
        setBlock(x, y, z, projectorBlock, 1);
        Object projector = tile.invoke(world, x, y, z);
        for (int l = 0; l < 5; l++) for (int u = 0; u < 5; u++) for (int v = 0; v < 5; v++) {
            if (split[l][u][v] > 0) setBlock(x - 6 + u, y + l, z + 2 - v, reactorBlock, split[l][u][v] - 1);
        }
        set(projector, 0, itemStack.newInstance(blueprintItem, 1, 1));
        tick(projector);
        Object splitter = tile.invoke(world, x - 4, y + 2, z);
        require(splitter != null && splitter.getClass().getSimpleName().equals("FissionTileEntity"), "Configured fission core missing");
        rows.put(profile + "_fission_storage", LegacyMinechemPower394.storage(splitter));
        set(splitter, 0, chemical(9, 1));
        int cost = (Integer) splitter.getClass().getMethod("getEnergyNeeded").invoke(splitter);
        LegacyMinechemPower394.charge(splitter, paid ? cost - 1 : 0);
        tick(splitter);
        boolean splitBelow = get(splitter, 2) != null;
        if (paid) { LegacyMinechemPower394.charge(splitter, 1); tick(splitter); }
        Object splitResult = get(splitter, 2);
        if (splitResult != null) expect(splitResult, 4, 2, "Configured fission of F");
        rows.put(profile + "_fission_run", cost + "/" + splitBelow + "/" + (splitResult != null) + "/" + energy(splitter));

        for (int l = 0; l < join.length; l++) for (int u = 0; u < join[l].length; u++) for (int v = 0; v < join[l][u].length; v++) {
            if (join[l][u][v] > 0) setBlock(x + 4 + u, y + l, z - 6 + v, reactorBlock, join[l][u][v] - 1);
        }
        setBlock(x + 10, y + 1, z, reactorBlock, 2);
        Object combiner = tile.invoke(world, x + 10, y + 1, z);
        require(combiner != null && combiner.getClass().getSimpleName().equals("FusionTileEntity"), "Configured fusion core missing");
        rows.put(profile + "_fusion_storage", LegacyMinechemPower394.storage(combiner));
        set(combiner, 0, chemical(1, 1));
        set(combiner, 1, chemical(1, 1));
        cost = (Integer) combiner.getClass().getMethod("getEnergyNeeded").invoke(combiner);
        tick(combiner);
        boolean zero = get(combiner, 2) != null;
        LegacyMinechemPower394.charge(combiner, cost);
        tick(combiner);
        boolean equal = get(combiner, 2) != null;
        LegacyMinechemPower394.charge(combiner, 1);
        tick(combiner);
        Object fusionResult = get(combiner, 2);
        if (fusionResult != null) expect(fusionResult, 2, 1, "Configured fusion of H + H");
        rows.put(profile + "_fusion_run", cost + "/" + zero + "/" + equal + "/" + (fusionResult != null) + "/" + energy(combiner));
        surveyWalls(x, y, z, split, splitter, profile, rows);
    }

    private void surveyWalls(int x, int y, int z, Integer[][][] split, Object core, String profile,
            java.util.Map<String, String> rows) throws Exception {
        int packet = Class.forName("minechem.Settings").getField("energyPacketSize").getInt(null);
        setBlock(x - 9, y, z + 4, reactorBlock, 0);
        Object unlinked = tile.invoke(world, x - 9, y, z + 4);
        rows.put(profile + "_wall_storage", LegacyMinechemPower394.storage(unlinked));
        Method receive = unlinked.getClass().getMethod("receiveEnergy", int.class, boolean.class);
        Object accepted = receive.invoke(unlinked, packet, false);
        tick(unlinked);
        rows.put(profile + "_wall_unlinked", accepted + "/" + energy(unlinked) + "/" + receive.invoke(unlinked, packet, true));

        Object wall = tile.invoke(world, x - 6, y, z + 2);
        require(wall != null && wall.getClass().getField("manager").get(wall) == core, "Casing was not linked to the real .394 core");
        Object wallIntake = receive.invoke(wall, packet, false);
        tick(wall);
        String first = energy(wall) + "/" + energy(core);
        tick(wall);
        rows.put(profile + "_wall_forward", wallIntake + "/" + first + "/" + energy(wall) + "/" + energy(core));

        int capacity = (Integer) core.getClass().getMethod("getMaxEnergyStored").invoke(core);
        LegacyMinechemPower394.charge(core, capacity - 5 - energy(core));
        Object coil = null;
        for (int l = 0; l < split.length && coil == null; l++) for (int u = 0; u < split[l].length && coil == null; u++) {
            for (int v = 0; v < split[l][u].length && coil == null; v++) {
                if (split[l][u][v] == 2) coil = tile.invoke(world, x - 6 + u, y + l, z + 2 - v);
            }
        }
        require(coil != null && coil.getClass().getField("manager").get(coil) == core, "Coil was not linked to the .394 core");
        Object coilIntake = receive.invoke(coil, packet, false);
        tick(coil);
        String partly = energy(coil) + "/" + energy(core);
        tick(coil);
        rows.put(profile + "_wall_partial", coilIntake + "/" + partly + "/" + energy(coil) + "/" + energy(core));
    }

    private String run(int x, int y, int z) throws Exception {
        Object fission = blueprints.getField("fission").get(null), fusion = blueprints.getField("fusion").get(null);
        Integer[][][] split = layout(fission), join = layout(fusion);
        Files.write(Paths.get("minechem-blueprints.txt"), Arrays.asList(line("fusion", fusion, join), line("fission", fission, split)),
                Charset.forName("UTF-8"));
        // Facing west (metadata 1): world x = projectorX - 6 + localX, z = projectorZ + 2 - localZ.
        setBlock(x, y, z, projectorBlock, 1);
        Object projector = tile.invoke(world, x, y, z);
        require(projector != null, "Projector tile missing");
        set(projector, 0, itemStack.newInstance(blueprintItem, 1, 1));
        tick(projector);
        int first = ghosts(x - 6, y, z);
        require(first == 25, "Expected only the first fission slice (25), got " + first);
        for (int l = 0; l < 5; l++) for (int v = 0; v < 5; v++) setBlock(x - 6, y + l, z + 2 - v, reactorBlock, 1);
        tick(projector);
        int second = ghosts(x - 6, y, z);
        require(second == 16, "Expected the second fission slice (16), got " + second);
        Object removed = projector.getClass().getMethod("func_70298_a", int.class, int.class).invoke(projector, 0, 1);
        require(removed != null && metadata(removed) == 1 && ghosts(x - 6, y, z) == 0,
                "Removing the blueprint did not clear the projection");
        set(projector, 0, removed);
        for (int l = 0; l < 5; l++) for (int u = 1; u < 5; u++) for (int v = 0; v < 5; v++) {
            if (split[l][u][v] > 0) setBlock(x - 6 + u, y + l, z + 2 - v, reactorBlock, split[l][u][v] - 1);
        }
        tick(projector);
        Object splitter = tile.invoke(world, x - 4, y + 2, z);
        require(splitter != null && splitter.getClass().getSimpleName().equals("FissionTileEntity"), "Projector did not form a fission core");
        long now = ((Long) world.getClass().getMethod("func_82737_E").invoke(world));
        try {
            time(200);
            set(splitter, 0, chemical(9, 1));
            if (old) {
                set(splitter, 1, chemical(92, 1));
                time(249);
                tick(splitter);
                require(get(splitter, 2) == null, "FINAL fission ignored its world tick boundary");
                time(250);
            } else {
                fill(splitter, 899);
                tick(splitter);
                require(get(splitter, 2) == null && energy(splitter) == 899, ".394 fission ran below its cost");
                fill(splitter, 1);
            }
            tick(splitter);
            expect(get(splitter, 2), 4, 2, "Fission of F");
            require(get(splitter, 0) == null && get(splitter, 1) == null, "Fission did not consume the expected slots");
            if (!old) require(energy(splitter) == 0, ".394 fission did not pay 900 RF");
            set(splitter, 2, null);
            set(splitter, 0, chemical(3, 1));
            if (old) set(splitter, 1, chemical(92, 1)); else fill(splitter, 300);
            time(300);
            tick(splitter);
            if (old) require(get(splitter, 2) == null && get(splitter, 0) == null && get(splitter, 1) == null,
                    "FINAL light input no longer consumes fuel/input without output");
            else expect(get(splitter, 2), 1, 2, ".394 fission of Li");

            for (int l = 0; l < join.length; l++) for (int u = 0; u < join[l].length; u++) for (int v = 0; v < join[l][u].length; v++) {
                if (join[l][u][v] > 0) setBlock(x + 4 + u, y + l, z - 6 + v, reactorBlock, join[l][u][v] - 1);
            }
            setBlock(x + 10, y + 1, z, reactorBlock, 2);
            Object combiner = tile.invoke(world, x + 10, y + 1, z);
            require(combiner != null && combiner.getClass().getSimpleName().equals("FusionTileEntity"), "Fusion core missing");
            int left = combiner.getClass().getField("inputLeft").getInt(null);
            int right = combiner.getClass().getField("inputRight").getInt(null);
            int output = combiner.getClass().getField("output").getInt(null);
            require(left == (old ? 1 : 0) && right == left + 1 && output == right + 1, "Unexpected original fusion slot mapping");
            set(combiner, left, chemical(1, 2));
            set(combiner, right, chemical(1, 2));
            if (old) set(combiner, 0, stack.getConstructor(int.class, int.class, int.class).newInstance(399, 1, 0));
            else {
                int need = (Integer) combiner.getClass().getMethod("getEnergyNeeded").invoke(combiner);
                boolean valid = (Boolean) combiner.getClass().getMethod("inputsCanBeFused").invoke(combiner);
                require(need == 200 && valid, "The .394 fusion inputs (H + H) had need=" + need + ", valid=" + valid);
                fill(combiner, 200);
            }
            tick(combiner);
            if (!old) {
                require(get(combiner, output) == null && energy(combiner) == 200, ".394 fusion did not wait for strictly more than its cost");
                fill(combiner, 1);
                tick(combiner);
                boolean ready = (Boolean) combiner.getClass().getMethod("func_70300_a", mc("entity.player.EntityPlayer")).invoke(combiner, new Object[]{null});
                require(get(combiner, output) != null, ".394 fusion did not yield output; complete=" + ready +
                        ", energy=" + energy(combiner) + ", need=" + combiner.getClass().getMethod("getEnergyNeeded").invoke(combiner));
            }
            expect(get(combiner, output), 2, 1, "Fusion of H + H");
            if (old) {
                Object spent = get(combiner, 0);
                Object star = Class.forName("minechem.MinechemItemsGeneration").getField("fusionStar").get(null);
                require(spent != null && stack.getMethod("func_77973_b").invoke(spent) == star && metadata(spent) == 0,
                        "FINAL did not turn the Nether Star into an undamaged Fusion Star");
                tick(combiner);
                expect(get(combiner, output), 2, 2, "Second FINAL fusion");
                require(metadata(get(combiner, 0)) == 1, "FINAL did not wear the Fusion Star on repeat use");
            } else require(energy(combiner) == 1, ".394 fusion did not charge 200 RF");
        } finally {
            time(now);
        }
        return "Blueprints: original full shapes captured; fission projected " + first + "/" + second + " cells then removed and formed a core. "
                + (old ? "Fission tick 250, fuel metadata 91, light input consumed; fusion fuel converted and wore."
                       : "Fission paid 900 RF and permits H output; fusion waits above 200 RF and pays 200.");
    }

    private Integer[][][] layout(Object blueprint) throws Exception {
        return (Integer[][][]) blueprints.getMethod("getStructure").invoke(blueprint);
    }

    private String line(String name, Object blueprint, Integer[][][] layout) throws Exception {
        StringBuilder values = new StringBuilder();
        int a = 0, c = 0;
        for (Integer[][] plane : layout) for (Integer[] row : plane) for (int value : row) {
            values.append(value == -1 ? '.' : value == 0 ? '0' : value == 1 ? 'A' : 'C');
            if (value == 1) a++;
            if (value == 2) c++;
        }
        List<String> at = new ArrayList<String>();
        for (String axis : new String[]{"X", "Y", "Z"}) at.add(blueprints.getMethod("getManagerPos" + axis).invoke(blueprint).toString());
        return name + " " + layout[0].length + "," + layout.length + "," + layout[0][0].length + " "
                + at.get(0) + "," + at.get(1) + "," + at.get(2) + " " + a + "," + c + " " + values;
    }

    private int ghosts(int ox, int y, int z) throws Exception {
        Object ghost = worldBlock(ghostBlock);
        int count = 0;
        for (int u = 0; u < 5; u++) for (int l = 0; l < 5; l++) for (int v = 0; v < 5; v++) {
            if (ghost.equals(getBlock.invoke(world, ox + u, y + l, z + 2 - v))) count++;
        }
        return count;
    }

    private Object worldBlock(Object value) throws Exception { return old ? block.getField("field_71990_ca").getInt(value) : value; }
    private void setBlock(int x, int y, int z, Object block, int meta) throws Exception {
        place.invoke(world, x, y, z, worldBlock(block), meta, 3);
    }
    private Object chemical(int atomicNumber, int count) throws Exception {
        return itemStack.newInstance(element, count, old ? atomicNumber - 1 : atomicNumber);
    }
    private void expect(Object value, int number, int count, String context) throws Exception {
        require(value != null && stack.getMethod("func_77973_b").invoke(value) == element &&
                metadata(value) == (old ? number - 1 : number) && stack.getField("field_77994_a").getInt(value) == count,
                context + " returned " + value);
    }
    private int metadata(Object value) throws Exception { return (Integer) stack.getMethod("func_77960_j").invoke(value); }
    private Object get(Object machine, int slot) throws Exception { return machine.getClass().getMethod("func_70301_a", int.class).invoke(machine, slot); }
    private void set(Object machine, int slot, Object value) throws Exception { machine.getClass().getMethod("func_70299_a", int.class, stack).invoke(machine, slot, value); }
    private void tick(Object machine) throws Exception { machine.getClass().getMethod(old ? "func_70316_g" : "func_145845_h").invoke(machine); }
    private void time(long gameTime) throws Exception {
        Object info = mc("world.World").getMethod("func_72912_H").invoke(world);
        info.getClass().getMethod("func_82572_b", long.class).invoke(info, gameTime);
    }
    private int energy(Object machine) throws Exception { return (Integer) machine.getClass().getMethod("getEnergyStored").invoke(machine); }
    private void fill(Object machine, int amount) throws Exception {
        require((Integer) machine.getClass().getMethod("getMaxEnergyStored").invoke(machine) == 100_000, "Wrong original reactor capacity");
        Method receive = machine.getClass().getMethod("receiveEnergy", int.class, boolean.class);
        require((Integer) receive.invoke(machine, 9999, true) == 100, "Wrong original reactor packet limit");
        while (amount > 0) {
            int accepted = (Integer) receive.invoke(machine, amount, false);
            require(accepted > 0, "Original reactor refused charge");
            amount -= accepted;
        }
    }
    private static Class<?> mc(String suffix) throws Exception { return Class.forName("net.minecraft." + suffix); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
