package mcmodup.minechemoracle;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

/** Observe original Polytool effects, including real survival harvest drops and outbound chat. */
final class LegacyMinechemMining {
    private final boolean old;
    private final Object world, tool;
    private final LegacyMinechemPlayerDecay.Actor actor;
    private final Class<?> w = mc("world.World"), block = mc("block.Block"), item = mc("item.Item"), stack = mc("item.ItemStack");
    private final Class<?> entity = mc("entity.Entity"), dropped = mc("entity.item.EntityItem"), box = mc("util.AxisAlignedBB");
    private final Class<?> element = Class.forName("minechem.item.element.ElementEnum");
    private final Class<?> upgrade = Class.forName("minechem.item.polytool.PolytoolUpgradeType");
    private final Method getBlock, place, harvest, mine, makeUpgrade, addUpgrade, setInventory, itemId, blockId;
    private final Method setPosition = entity.getMethod("func_70107_b", double.class, double.class, double.class);
    private final Method getEntities = w.getMethod("func_72872_a", Class.class, box);
    private final Method getBox = box.getMethod("func_72330_a", double.class, double.class, double.class, double.class, double.class, double.class);
    private final Method getStack = dropped.getMethod("func_92059_d"), getItem = stack.getMethod("func_77973_b");
    private final Method meta = stack.getMethod("func_77960_j"), kill = entity.getMethod("func_70106_y");
    private final Field count = stack.getField("field_77994_a"), itemX = entity.getField("field_70165_t"), dead = entity.getField("field_70128_L");
    private final Field motionX = entity.getField("field_70159_w"), motionY = entity.getField("field_70181_x"), motionZ = entity.getField("field_70179_y");
    private final Map<Integer, Object> blocks = new HashMap<Integer, Object>();
    private final Map<String, String> rows = new TreeMap<String, String>();
    private final Random worldRandom;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private LegacyMinechemMining(Object world, boolean old) throws Exception {
        this.world = world;
        this.old = old;
        actor = new LegacyMinechemPlayerDecay.Actor(world, old);
        Class<?> manager = actor.interaction.getClass(), gameType = mc(old ? "world.EnumGameType" : "world.WorldSettings$GameType");
        manager.getMethod("func_73076_a", gameType).invoke(actor.interaction, Enum.valueOf((Class) gameType, "SURVIVAL"));
        harvest = manager.getMethod("func_73084_b", int.class, int.class, int.class);
        setInventory = actor.inventory.getClass().getMethod("func_70299_a", int.class, stack);
        tool = Class.forName(old ? "minechem.MinechemItemsGeneration" : "minechem.MinechemItemsRegistration").getField("polytool").get(null);
        makeUpgrade = Class.forName("minechem.item.polytool.PolytoolHelper").getMethod("getTypeFromElement", element, float.class);
        addUpgrade = tool.getClass().getMethod("addTypeToNBT", stack, upgrade);
        mine = tool.getClass().getMethod(old ? "func_77660_a" : "func_150894_a", stack, w, old ? int.class : block,
            int.class, int.class, int.class, mc("entity.EntityLivingBase"));
        getBlock = w.getMethod(old ? "func_72798_a" : "func_147439_a", int.class, int.class, int.class);
        place = w.getMethod(old ? "func_72832_d" : "func_147465_d", int.class, int.class, int.class, old ? int.class : block, int.class, int.class);
        blockId = old ? null : block.getMethod("func_149682_b", block);
        itemId = old ? null : item.getMethod("func_150891_b", item);
        worldRandom = (Random) w.getField("field_73012_v").get(world);
    }

    static void survey(Object world, boolean old, int x, int y, int z) throws Exception {
        new LegacyMinechemMining(world, old).measure(x, y, z);
    }

    private void measure(int x, int y, int z) throws Exception {
        setPosition.invoke(actor.player, x + .5, y + 1., z - 1.5);
        // Read the drops from tryHarvestBlock, not the tooltip or the hook's intermediate entities.
        loot("plain_coal", boosted(), 16, 0, 1, x, y, z);
        loot("plain_diamond", boosted(), 56, 0, 1, x, y, z);
        loot("plain_gold", boosted(), 14, 0, 1, x, y, z);
        loot("carbon2_coal", boosted(6, 2), 16, 0, 1, x, y, z);
        loot("carbon64_coal", boosted(6, 64), 16, 0, 512, x, y, z);
        loot("carbon8_diamond", boosted(6, 8), 56, 0, 512, x, y, z);
        loot("carbon64_stone", boosted(6, 64), 1, 0, 4, x, y, z);
        loot("bromine1_gold", boosted(35, 1), 14, 0, 1, x, y, z);
        loot("bromine64_gold", boosted(35, 64), 14, 0, 512, x, y, z);
        loot("bromine_iron", boosted(35, 1), 15, 0, 1, x, y, z);
        loot("wool_plain", boosted(), 35, 15, 1, x, y, z);
        loot("wool_chromium10", boosted(24, 10), 35, 15, 512, x, y, z);
        lowerChromium(x, y, z);
        loot("silk_plain_coal", silk(boosted()), 16, 0, 1, x, y, z);
        loot("silk_bromine_gold", silk(boosted(35, 1)), 14, 0, 1, x, y, z);
        vein("silk_iron_carbon64", silk(boosted(26, 1, 6, 64)), 16, 512, x, y, z);
        vein("iron_only_diamond", boosted(26, 1), 56, 4, x, y, z);
        vein("iron_carbon1", boosted(26, 1, 6, 1), 16, 4, x, y, z);
        vein("iron_only", boosted(26, 1), 16, 8, x, y, z);
        vein("iron_carbon64", boosted(26, 1, 6, 64), 16, 512, x, y, z);
        vein("iron_bromine1", boosted(26, 1, 35, 1), 14, 1, x, y, z);
        hunger(x, y, z);
        torch(x + 8, y, z);
        magnet(x, y, z);
        warning(x, y, z);
        try (PrintWriter out = new PrintWriter("minechem-polytool-mining.txt", "UTF-8")) {
            for (Map.Entry<String, String> row : rows.entrySet()) out.println(row.getKey() + "=" + row.getValue());
            if (out.checkError()) throw new AssertionError("Could not write mining trace");
        }
    }

    private void loot(String key, Object held, int blockId, int data, int samples, int x, int y, int z) throws Exception {
        setInventory.invoke(actor.inventory, 0, held);
        TreeSet<String> outputs = new TreeSet<String>();
        for (int i = 0; i < samples; i++) {
            put(x, y, z, blockId, data);
            harvest.invoke(actor.interaction, x, y, z);
            require(at(x, y, z) == 0, "Original harvest did not remove " + key);
            outputs.add(harvested(x, y, z, false)[0]);
        }
        rows.put(key, outputs.toString());
    }

    private void lowerChromium(int x, int y, int z) throws Exception {
        boosted(24, 1);
        TreeSet<String> kinds = new TreeSet<String>();
        for (int i = 0; i < 512; i++) {
            put(x, y, z, 35, 15);
            harvest.invoke(actor.interaction, x, y, z);
            String result = harvested(x, y, z, false)[0];
            require(result.matches("\\{35:(?:[0-9]|1[0-5])=1}"), "Expected just one wool, got " + result);
            kinds.add(result.equals("{35:15=1}") ? "unchanged" : "recolored");
        }
        rows.put("wool_chromium1", kinds.toString());
    }

    private void vein(String key, Object held, int oreId, int samples, int x, int y, int z) throws Exception {
        setInventory.invoke(actor.inventory, 0, held);
        TreeSet<String> origin = new TreeSet<String>(), extra = new TreeSet<String>();
        for (int i = 0; i < samples; i++) {
            for (int dx = 0; dx < 3; dx++) put(x + dx, y, z, oreId, 0);
            harvest.invoke(actor.interaction, x, y, z);
            require(at(x, y, z) == 0 && at(x + 1, y, z) == 0 && at(x + 2, y, z) == oreId,
                "Rank one iron did not remove exactly one extra ore for " + key);
            String[] result = harvested(x, y, z, true);
            origin.add(result[0]);
            extra.add(result[1]);
        }
        put(x + 2, y, z, 0, 0);
        rows.put(key, origin + "/" + extra);
    }

    private void hunger(int x, int y, int z) throws Exception {
        Object held = boosted(9, 1), food = actor.player.getClass().getMethod("func_71024_bL").invoke(actor.player);
        // The old public setters only exist in the client JARs.
        Field level = food.getClass().getDeclaredField("field_75127_a"), saturation = food.getClass().getDeclaredField("field_75125_b");
        level.setAccessible(true);
        saturation.setAccessible(true);
        for (int roll = 0; roll <= 1; roll++) {
            level.setInt(food, 10);
            saturation.setFloat(food, 0F);
            seedRoll(10, roll);
            callback(held, 1, x, y, z);
            rows.put("fluorine_rank1_roll" + roll, String.format(Locale.ROOT, "%d/%.2f", level.getInt(food), saturation.getFloat(food)));
        }
        level.setInt(food, 19);
        saturation.setFloat(food, 19F);
        callback(boosted(9, 10), 1, x, y, z);
        rows.put("fluorine_cap", String.format(Locale.ROOT, "%d/%.2f", level.getInt(food), saturation.getFloat(food)));
    }

    private void torch(int x, int y, int z) throws Exception {
        setPosition.invoke(actor.player, x + .5, y + .25, z + .5);
        Object held = boosted(11, 35);
        Method light = w.getMethod("func_72957_l", int.class, int.class, int.class);
        put(x, y - 1, z, 1, 0);
        put(x, y, z, 0, 0);
        Object sky = light.invoke(world, x, y, z);
        callback(held, 1, x + 4, y, z);
        rows.put("sodium_sky", sky + "/" + at(x, y, z));
        // The enclosed cavity is dark; put glowstone four and then three blocks away
        // for block light 11 and 12 at the actor's feet.
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) for (int dy = -1; dy <= 3; dy++) {
            boolean wall = Math.abs(dx) == 5 || Math.abs(dz) == 5 || dy == -1 || dy == 3;
            put(x + dx, y + dy, z + dz, wall ? 1 : 0, 0);
        }
        for (int distance = 4; distance >= 3; distance--) {
            put(x + distance, y, z, 89, 0);
            Object brightness = light.invoke(world, x, y, z);
            callback(held, 1, x + 4, y, z);
            rows.put("sodium_glowstone_" + distance, brightness + "/" + at(x, y, z));
            put(x + distance, y, z, 0, 0);
            put(x, y, z, 0, 0);
        }
        Object rankOne = boosted(11, 1);
        for (int roll = 0; roll <= 1; roll++) {
            put(x, y, z, 0, 0);
            seedRoll(35, roll);
            callback(rankOne, 1, x + 4, y, z);
            rows.put("sodium_rank1_roll" + roll, Integer.toString(at(x, y, z)));
        }
        put(x, y, z, 1, 0);
        callback(held, 1, x + 4, y, z);
        rows.put("sodium_occupied", Integer.toString(at(x, y, z)));
        put(x, y, z, 0, 0);
        put(x, y - 1, z, 0, 0);
        callback(held, 1, x + 4, y, z);
        rows.put("sodium_unsupported", Integer.toString(at(x, y, z)));
    }

    private void magnet(int x, int y, int z) throws Exception {
        setPosition.invoke(actor.player, x + .5, y + 1., z + .5);
        Object coal = old ? stack.getConstructor(int.class, int.class, int.class).newInstance(263, 1, 0) :
            stack.getConstructor(item, int.class, int.class).newInstance(item.getMethod("func_150899_d", int.class).invoke(null, 263), 1, 0);
        Object near = makeDrop(x + 1.25, y + 1.5, z + .25, coal), far = makeDrop(x + 3.5, y + 1., z + .5, coal);
        callback(boosted(28, 2), 1, x, y, z);
        rows.put("nickel", motion(near) + "/" + motion(far));
        kill.invoke(near);
        kill.invoke(far);
    }

    private void warning(int x, int y, int z) throws Exception {
        Object held = boosted(80, 1);
        for (int dy = 1; dy <= 6; dy++) put(x, y - dy, z, 0, 0);
        actor.chat();
        put(x, y - 5, z, 11, 0);
        callback(held, 1, x, y, z);
        rows.put("mercury_beyond", actor.chat().toString());
        put(x, y - 4, z, 11, 0);
        callback(held, 1, x, y, z);
        rows.put("mercury_source", actor.chat().toString());
        put(x, y - 4, z, 10, 2);
        put(x, y - 2, z, 11, 0);
        callback(held, 1, x, y, z);
        rows.put("mercury_two_levels", actor.chat().toString());
        put(x, y - 2, z, 0, 0);
        callback(held, 1, x, y, z);
        rows.put("mercury_flow", actor.chat().toString());
        for (int dy = 1; dy <= 6; dy++) put(x, y - dy, z, 0, 0);
    }

    private Object makeDrop(double x, double y, double z, Object contents) throws Exception {
        Object result = dropped.getConstructor(w, double.class, double.class, double.class, stack)
            .newInstance(world, x, y, z, stack.getMethod("func_77946_l").invoke(contents));
        for (Field axis : new Field[]{motionX, motionY, motionZ}) axis.setDouble(result, 0.);
        require((Boolean) w.getMethod("func_72838_d", entity).invoke(world, result), "Couldn't spawn the original test drop");
        return result;
    }

    private String motion(Object value) throws Exception {
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f", motionX.getDouble(value), motionY.getDouble(value), motionZ.getDouble(value));
    }

    private String[] harvested(int x, int y, int z, boolean vein) throws Exception {
        Object bounds = getBox.invoke(null, x - 1., y - 1., z - 1., x + 3., y + 2., z + 2.);
        Map<String, Integer> primary = new TreeMap<String, Integer>(), extra = new TreeMap<String, Integer>();
        for (Object drop : (List<?>) getEntities.invoke(world, dropped, bounds)) {
            if (dead.getBoolean(drop)) continue;
            Object payload = getStack.invoke(drop);
            if (count.getInt(payload) > 0) {
                Map<String, Integer> loot = vein && (int) Math.floor(itemX.getDouble(drop)) == x + 1 ? extra : primary;
                Object kind = getItem.invoke(payload);
                int id = old ? item.getField("field_77779_bT").getInt(kind) : (Integer) itemId.invoke(null, kind);
                String key = id + ":" + meta.invoke(payload);
                loot.put(key, count.getInt(payload) + (loot.containsKey(key) ? loot.get(key) : 0));
            }
            kill.invoke(drop);
        }
        return new String[]{primary.toString(), extra.toString()};
    }

    private void seedRoll(int bound, int roll) {
        long seed = 0;
        while (new Random(seed).nextInt(bound) != roll) seed++;
        worldRandom.setSeed(seed);
    }

    private Object silk(Object held) throws Exception {
        Class<?> enchant = mc("enchantment.Enchantment");
        stack.getMethod("func_77966_a", enchant, int.class).invoke(held, enchant.getField("field_77348_q").get(null), 1);
        return held;
    }

    private Object boosted(int... ranks) throws Exception {
        Object held = stack.getConstructor(item, int.class, int.class).newInstance(tool, 1, 0);
        for (int i = 0; i < ranks.length; i += 2) {
            int id = ranks[i];
            Object chemical = old ? element.getEnumConstants()[id - 1] : element.getMethod("getByID", int.class).invoke(null, id);
            addUpgrade.invoke(null, held, makeUpgrade.invoke(null, chemical, (float) ranks[i + 1]));
        }
        setInventory.invoke(actor.inventory, 0, held);
        return held;
    }

    private void callback(Object held, int id, int x, int y, int z) throws Exception {
        mine.invoke(tool, held, world, block(id), x, y, z, actor.player);
    }

    private void put(int x, int y, int z, int id, int data) throws Exception {
        place.invoke(world, x, y, z, block(id), data, 3);
    }

    private int at(int x, int y, int z) throws Exception {
        Object state = getBlock.invoke(world, x, y, z);
        return old ? (Integer) state : (Integer) blockId.invoke(null, state);
    }

    private Object block(int id) throws Exception {
        if (!blocks.containsKey(id)) blocks.put(id, old ? id : block.getMethod("func_149729_e", int.class).invoke(null, id));
        return blocks.get(id);
    }

    private static Class<?> mc(String name) throws ClassNotFoundException {
        try {
            return Class.forName("net.minecraft." + name);
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("Missing legacy class " + name, missing);
        }
    }
    private static void require(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
}
