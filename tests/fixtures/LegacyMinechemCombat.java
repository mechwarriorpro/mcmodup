package mcmodup.minechemoracle;

import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

/** Reference for real melee damage, the original loot subscriber, and carried Polytool updates. */
final class LegacyMinechemCombat {
    private final boolean old;
    private final Object world, actor, inventory, tool, attrMap, maxHealth;
    private final Object elementItem, moleculeItem;
    private final int x, y, z;
    private Object modifiers;
    private final Class<?> w = mc("world.World"), living = mc("entity.EntityLivingBase"), entity = mc("entity.Entity");
    private final Class<?> item = mc("item.Item"), stack = mc("item.ItemStack"), player = mc("entity.player.EntityPlayer");
    private final Class<?> potion = mc("potion.Potion"), potionEffect = mc("potion.PotionEffect"), itemEntity = mc("entity.item.EntityItem");
    private final Class<?> multimap = Class.forName("com.google.common.collect.Multimap"), box = mc("util.AxisAlignedBB");
    private final Class<?> element = Class.forName("minechem.item.element.ElementEnum");
    private final Class<?> source = mc("util.DamageSource"), xp = mc("entity.item.EntityXPOrb");
    private final Method attack = player.getMethod("func_71059_n", entity), health = living.getMethod("func_110143_aJ");
    private final Method at = entity.getMethod("func_70107_b", double.class, double.class, double.class);
    private final Method spawn = w.getMethod("func_72838_d", entity), discard = entity.getMethod("func_70106_y");
    private final Method getAttribute, setBase;
    private final Method update = stack.getMethod("func_77945_a", w, entity, int.class, boolean.class);
    private final Method getStack = itemEntity.getMethod("func_92059_d"), getItem = stack.getMethod("func_77973_b");
    private final Method meta = stack.getMethod("func_77960_j"), entities = w.getMethod("func_72872_a", Class.class, box);
    private final Method bounds = box.getMethod("func_72330_a", double.class, double.class, double.class, double.class, double.class, double.class);
    private final Field count = stack.getField("field_77994_a"), motionY = entity.getField("field_70181_x"), dead = entity.getField("field_70128_L");
    private final Map<String, String> rows = new TreeMap<String, String>();
    private final Random worldRandom;

    private LegacyMinechemCombat(Object world, boolean old, int x, int y, int z) throws Exception {
        this.old = old;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        LegacyMinechemPlayerDecay.Actor real = new LegacyMinechemPlayerDecay.Actor(world, old);
        actor = real.player;
        inventory = real.inventory;
        at.invoke(actor, x + .5, (double)y, z - .5);
        Class<?> items = Class.forName(old ? "minechem.MinechemItemsGeneration" : "minechem.MinechemItemsRegistration");
        tool = items.getField("polytool").get(null);
        elementItem = items.getField("element").get(null);
        moleculeItem = items.getField("molecule").get(null);
        getAttribute = living.getMethod("func_110148_a", mc("entity.ai.attributes." + (old ? "Attribute" : "IAttribute")));
        setBase = getAttribute.getReturnType().getMethod("func_111128_a", double.class);
        attrMap = living.getMethod("func_110140_aT").invoke(actor);
        maxHealth = mc("entity.SharedMonsterAttributes").getField("field_111267_a").get(null);
        worldRandom = (Random)w.getField("field_73012_v").get(world);
    }

    static void survey(Object world, boolean old, int x, int y, int z) throws Exception {
        LegacyMinechemCombat test = new LegacyMinechemCombat(world, old, x, y, z);
        test.measure();
    }

    private void measure() throws Exception {
        damage("plain_pig", "passive.EntityPig");
        damage("boron1_spider", "monster.EntitySpider", 5, 1);
        damage("boron16_spider", "monster.EntitySpider", 5, 16);
        damage("boron16_cave_spider", "monster.EntityCaveSpider", 5, 16);
        damage("boron16_pig", "passive.EntityPig", 5, 16);
        damage("silver1_enderman", "monster.EntityEnderman", 47, 1);
        damage("silver16_enderman", "monster.EntityEnderman", 47, 16);
        damage("argon16_open", "passive.EntityPig", 18, 16);
        put(x, y + 3, z, 1);
        damage("argon1_covered", "passive.EntityPig", 18, 1);
        damage("argon16_covered", "passive.EntityPig", 18, 16);
        put(x, y + 3, z, 0);
        krypton();
        platinum();
        deathLoot();
        carried();
        combatEffects();
        try (PrintWriter out = new PrintWriter("minechem-polytool-combat.txt", "UTF-8")) {
            for (Map.Entry<String, String> row : rows.entrySet()) out.println(row.getKey() + "=" + row.getValue());
            if (out.checkError()) throw new AssertionError("Could not write combat trace");
        }
    }

    private void damage(String key, String type, int... ranks) throws Exception {
        equip(ranks);
        Object victim = target(type, .5, .5, false);
        rows.put(key, rounded(hit(victim)));
    }

    private void krypton() throws Exception {
        Field reduction = mc("item.ItemArmor").getField("field_77879_b");
        Object chest = getItem.invoke(vanilla(311, 1, 0));
        int original = reduction.getInt(chest);
        try {
            for (int armor = 23; armor <= 24; armor++) {
                reduction.setInt(chest, original + armor - 20);
                for (int rank : new int[]{0, 1, 16}) {
                    LegacyMinechemPlayerDecay.Actor victim = new LegacyMinechemPlayerDecay.Actor(world, old);
                    at.invoke(victim.player, x + .5, (double)y, z + .5);
                    for (int slot = 0; slot < 4; slot++) set(victim.inventory, 39 - slot, vanilla(310 + slot, 1, 0));
                    require((Integer) player.getMethod("func_70658_aO").invoke(victim.player) == armor, "Original armor value did not reach " + armor);
                    beefy(victim.player);
                    if (rank == 0) equip(); else equip(36, rank);
                    rows.put("krypton" + rank + "_armor" + armor, rounded(hit(victim.player)));
                }
            }
        } finally { reduction.setInt(chest, original); }
    }

    private void combatEffects() throws Exception {
        Object mob = target("monster.EntityZombie", .5, .5, false);
        Field chances = mc("entity.EntityLiving").getDeclaredField("field_82174_bp");
        chances.setAccessible(true);
        float before = ((float[])chances.get(mob))[0];
        equip(22, 2);
        hit(mob);
        rows.put("titanium_hit", rounded(before) + "/" + rounded(((float[])chances.get(mob))[0]));
        equip(12, 2, 17, 2, 92, 2, 10, 3, 16, 3, 82, 1);
        Object victim = target("passive.EntityPig", .5, .5, true);
        Object near = target("passive.EntityPig", 2., 2., true);
        Object far = target("passive.EntityPig", 4.5, .5, true);
        Object spider = target("monster.EntitySpider", 1.5, .5, true);
        boolean present = (Boolean)spawn.invoke(world, actor);
        require(present, "Original attacker could not join the area-effect test");
        try {
            hit(victim);
            rows.put("potion_target", effects(victim));
            rows.put("potion_diagonal", effects(near));
            rows.put("potion_far", effects(far));
            rows.put("potion_spider", effects(spider));
            rows.put("potion_attacker", effects(actor));
            rows.put("lead_vertical", rounded(motionY.getDouble(victim)) + "/" + rounded(motionY.getDouble(near)) + "/" +
                rounded(motionY.getDouble(far)) + "/" + rounded(motionY.getDouble(actor)));
            Field fire = entity.getDeclaredField("field_70151_c");
            fire.setAccessible(true);
            rows.put("sulfur3_ticks", Integer.toString(fire.getInt(victim)));
        } finally {
            for (Object value : new Object[]{victim, near, far, spider}) discard.invoke(value);
            w.getMethod("func_72900_e", entity).invoke(world, actor);
        }

    }

    private String effects(Object mob) throws Exception {
        List<String> effects = new ArrayList<String>();
        Object potions = potion.getField("field_76425_a").get(null);
        Method lookup = living.getMethod("func_70660_b", potion);
        for (int id : new int[]{15, 16, 19, 20}) {
            Object effect = lookup.invoke(mob, Array.get(potions, id));
            if (effect != null) effects.add(id + ":" + potionEffect.getMethod("func_76459_b").invoke(effect) + ":" +
                potionEffect.getMethod("func_76458_c").invoke(effect));
        }
        return effects.toString();
    }

    private void platinum() throws Exception {
        // Invoke a real hit with rank 49 (guaranteed) and measure the total spawned XP, not orb splitting.
        equip(78, 49);
        TreeSet<Integer> amounts = new TreeSet<Integer>();
        for (int i = 0; i < 384; i++) {
            hit(target("passive.EntityPig", .5, .5, false));
            amounts.add(xpSince());
        }
        rows.put("platinum49", amounts.toString());
        equip();
        hit(target("passive.EntityPig", .5, .5, false));
        rows.put("platinum_absent", Integer.toString(xpSince()));
    }

    private int xpSince() throws Exception {
        int sum = 0;
        Object area = bounds.invoke(null, x - 2., y - 2., z - 3., x + 3., y + 3., z + 3.);
        for (Object orb : (List<?>)entities.invoke(world, xp, area)) {
            if (dead.getBoolean(orb)) continue;
            sum += (Integer)xp.getMethod("func_70526_d").invoke(orb);
            discard.invoke(orb);
        }
        return sum;
    }

    private void deathLoot() throws Exception {
        drops("nitrogen1", "monster.EntityZombie", 7, 1, 367, 2, true);
        drops("calcium1", "monster.EntitySkeleton", 20, 1, 352, 2, true);
        drops("calcium_without_bone", "monster.EntitySkeleton", 20, 1, 262, 2, true);
        drops("be5_skeleton", "monster.EntitySkeleton", 4, 5, 352, 1, true);
        drops("be7_zombie", "monster.EntityZombie", 4, 7, 367, 1, true);
        drops("be7_pig", "passive.EntityPig", 4, 7, 319, 1, true);
        drops("silicon_bones", "monster.EntitySkeleton", 14, 15, 352, 2, true);
        drops("silicon_not_melee", "monster.EntitySkeleton", 14, 15, 352, 2, false);
        Class<?> chance = Class.forName("minechem.tileentity.decomposer.DecomposerRecipeChance");
        Field rng = chance.getDeclaredField("random");
        rng.setAccessible(true);
        Random decomposition = (Random)rng.get(null);
        for (boolean pass : new boolean[]{false, true}) {
            long seed = 0;
            while ((new Random(seed).nextFloat() < .05F) != pass) seed++;
            decomposition.setSeed(seed);
            drops("silicon_flesh_" + (pass ? "pass" : "miss"), "monster.EntityZombie", 14, 15, 367, 1, true);
        }
        drops("silicon_no_recipe", "monster.EntitySkeleton", 14, 15, 7, 1, true);
        // Actual lethal player attack; check the subscriber is reached from vanilla death too.
        equip(7, 1, 4, 6);
        Object victim = target("monster.EntityZombie", .5, .5, true);
        living.getMethod("func_70606_j", float.class).invoke(victim, 1F);
        hit(victim);
        Object area = bounds.invoke(null, x - 2., y - 2., z - 2., x + 3., y + 3., z + 3.);
        rows.put("kill_zombie", itemCounts((List<?>)entities.invoke(world, itemEntity, area), true));
        discard.invoke(victim);
    }

    private void drops(String key, String kind, int module, int rank, int token, int amount, boolean melee) throws Exception {
        equip(module, rank);
        Object victim = target(kind, .5, .5, false);
        ArrayList<Object> sample = new ArrayList<Object>();
        sample.add(itemEntity.getConstructor(w, double.class, double.class, double.class, stack).newInstance(world,
            x + .5, (double)y, z + .5, vanilla(token, amount, 0)));
        Object by = melee ? source.getMethod("func_76365_a", player).invoke(null, actor) : source.getField("field_76376_m").get(null);
        Class<?> type = Class.forName("net.minecraftforge.event.entity.living.LivingDropsEvent");
        try {
            Object event = type.getConstructor(living, source, ArrayList.class, int.class, boolean.class, int.class)
                .newInstance(victim, by, sample, 0, true, 0);
            Object bus = Class.forName("net.minecraftforge.common.MinecraftForge").getField("EVENT_BUS").get(null);
            Class<?> eventType = Class.forName(old ? "net.minecraftforge.event.Event" : "cpw.mods.fml.common.eventhandler.Event");
            bus.getClass().getMethod("post", eventType).invoke(bus, event);
            rows.put(key, itemCounts(sample, false));
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (!key.equals("silicon_no_recipe") || !(cause instanceof NullPointerException)) throw failure;
            rows.put(key, "no_recipe_exception");
        }
    }

    private String itemCounts(List<?> drops, boolean cleanup) throws Exception {
        Map<String, Integer> result = new TreeMap<String, Integer>();
        for (Object drop : drops) {
            if (dead.getBoolean(drop)) continue;
            Object payload = getStack.invoke(drop), kind = getItem.invoke(payload);
            int data = (Integer)meta.invoke(payload);
            String key;
            if (kind == elementItem) key = "e" + data;
            else if (kind == moleculeItem) key = "m" + data;
            else key = "v" + (old ? item.getField("field_77779_bT").getInt(kind) : item.getMethod("func_150891_b", item).invoke(null, kind)) + ":" + data;
            int units = count.getInt(payload);
            if (units > 0) result.put(key, units + (result.containsKey(key) ? result.get(key) : 0));
            if (cleanup) discard.invoke(drop);
        }
        return result.toString();
    }

    private void carried() throws Exception {
        Object held = equip(2, 1);
        Object first = target("passive.EntityPig", .5, .5, true);
        Object last = target("passive.EntityPig", 2.5, .5, true);
        try {
            motionY.setDouble(first, 0.);
            update.invoke(held, world, actor, 10, false);
            rows.put("helium_unmarked", rounded(motionY.getDouble(first)));
            hit(first);
            motionY.setDouble(first, .20);
            update.invoke(held, world, actor, 10, false);
            rows.put("helium_unselected", rounded(motionY.getDouble(first)));
            hit(last);
            motionY.setDouble(first, 0.);
            motionY.setDouble(last, .95);
            update.invoke(held, world, actor, 10, false);
            rows.put("helium_retarget", rounded(motionY.getDouble(first)) + "/" + rounded(motionY.getDouble(last)));
            // Each test restarts the mark, so the random one-tick stop is observable without a flaky long wait.
            TreeSet<Integer> stopped = new TreeSet<Integer>();
            Method callback = tool.getClass().getMethod("func_77644_a", stack, living, living);
            for (int i = 0; i < 256; i++) {
                callback.invoke(tool, held, last, actor);
                update.invoke(held, world, actor, 10, false);
                Object tag = stack.getMethod("func_77978_p").invoke(held);
                int id = (Integer) tag.getClass().getMethod("func_74762_e", String.class).invoke(tag, "HeliumHitEntity");
                stopped.add(id == 0 ? 1 : 0);
            }
            rows.put("helium_clears", stopped.toString());
        } finally { discard.invoke(first); discard.invoke(last); }
        held = equip(79, 1);
        Field weather = w.getField("field_73007_j");
        List<?> effects = (List<?>)weather.get(world);
        for (int roll = 0; roll <= 1; roll++) {
            int before = effects.size();
            long seed = 0;
            while (new Random(seed).nextInt(35000) != roll) seed++;
            worldRandom.setSeed(seed);
            update.invoke(held, world, actor, 10, false);
            rows.put("gold_rank1_roll" + roll, Integer.toString(effects.size() - before));
            while (effects.size() > before) effects.remove(effects.size() - 1);
        }
    }

    private Object equip(int... ranks) throws Exception {
        Object held = stack.getConstructor(item, int.class, int.class).newInstance(tool, 1, 0);
        Class<?> upgrade = Class.forName("minechem.item.polytool.PolytoolUpgradeType");
        Method lookup = Class.forName("minechem.item.polytool.PolytoolHelper").getMethod("getTypeFromElement", element, float.class);
        Method add = tool.getClass().getMethod("addTypeToNBT", stack, upgrade);
        for (int i = 0; i < ranks.length; i += 2) {
            Object chem = old ? element.getEnumConstants()[ranks[i] - 1] : element.getMethod("getByID", int.class).invoke(null, ranks[i]);
            add.invoke(null, held, lookup.invoke(null, chem, (float)ranks[i + 1]));
        }
        set(inventory, 0, held);
        if (modifiers != null) attrMap.getClass().getMethod("func_111148_a", multimap).invoke(attrMap, modifiers);
        // Normally updated by the next vanilla equipment tick; apply those exact modifiers without world ticks.
        modifiers = stack.getMethod("func_111283_C").invoke(held);
        attrMap.getClass().getMethod("func_111147_b", multimap).invoke(attrMap, modifiers);
        return held;
    }

    private Object target(String type, double dx, double dz, boolean join) throws Exception {
        Object target = mc("entity." + type).getConstructor(w).newInstance(world);
        at.invoke(target, x + dx, (double)y, z + dz);
        beefy(target);
        if (join) require((Boolean)spawn.invoke(world, target), "Original target did not spawn: " + type);
        return target;
    }

    private void beefy(Object value) throws Exception {
        setBase.invoke(getAttribute.invoke(value, maxHealth), 100.);
        living.getMethod("func_70606_j", float.class).invoke(value, 100F);
    }

    private float hit(Object victim) throws Exception {
        float before = (Float)health.invoke(victim);
        attack.invoke(actor, victim);
        return before - (Float)health.invoke(victim);
    }

    private void set(Object inventory, int index, Object value) throws Exception {
        inventory.getClass().getMethod("func_70299_a", int.class, stack).invoke(inventory, index, value);
    }

    private Object vanilla(int id, int units, int data) throws Exception {
        return old ? stack.getConstructor(int.class, int.class, int.class).newInstance(id, units, data) :
            stack.getConstructor(item, int.class, int.class).newInstance(item.getMethod("func_150899_d", int.class).invoke(null, id), units, data);
    }

    private void put(int x, int y, int z, int id) throws Exception {
        Class<?> block = mc("block.Block");
        w.getMethod(old ? "func_72832_d" : "func_147465_d", int.class, int.class, int.class, old ? int.class : block, int.class, int.class)
            .invoke(world, x, y, z, old ? id : block.getMethod("func_149729_e", int.class).invoke(null, id), 0, 3);
    }

    private static String rounded(double n) { return String.format(Locale.ROOT, "%.2f", n); }
    private static Class<?> mc(String name) throws ClassNotFoundException {
        try { return Class.forName("net.minecraft." + name); }
        catch (ClassNotFoundException e) { throw new AssertionError("Missing legacy class: " + name, e); }
    }
    private static void require(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
}
