package mcmodup.minechemoracle;

import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Observe the real slot clicks and the registered tool, not a reimplementation of the alloy rules. */
final class LegacyMinechemPolytool {
    private static final String[] STATS = {"Sword", "Pickaxe", "Stone", "Axe", "Shovel"};
    private static final String[] DELTAS = {"Sword", "Ore", "Stone", "Axe", "Shovel"};
    private static final String[] SAMPLE = {"web", "coal_ore", "stone", "log", "dirt", "cobblestone", "diamond_ore",
                                           "planks", "sand", "obsidian", "leaves", "glass"};
    private static final int[] BLOCK_IDS = {30, 16, 1, 17, 3, 4, 56, 5, 12, 49, 18, 20};
    private final boolean old;
    private final Object world, element, molecule, tool, player, inventory, container, slot, coal;
    private final Class<?> stack = mc("item.ItemStack"), block = mc("block.Block"), worldType = mc("world.World");
    private final Class<?> playerType = mc("entity.player.EntityPlayer"), living = mc("entity.EntityLivingBase");
    private final Class<?> nbt = mc("nbt.NBTTagCompound"), elementEnum = Class.forName("minechem.item.element.ElementEnum");
    private final Object[] samples = new Object[SAMPLE.length];
    private final Constructor<?> make = stack.getConstructor(mc("item.Item"), int.class, int.class);
    private final Method setInventory, setCursor, cursor, click, accepts, inSlot, power, dig, wear, place, mine;
    private final Field count = stack.getField("field_77994_a");

    private LegacyMinechemPolytool(Object world, boolean old) throws Exception {
        this.world = world;
        this.old = old;
        Class<?> items = Class.forName(old ? "minechem.MinechemItemsGeneration" : "minechem.MinechemItemsRegistration");
        element = items.getField("element").get(null);
        molecule = items.getField("molecule").get(null);
        tool = items.getField("polytool").get(null);
        player = LegacyMinechemChemistry.fakePlayer(world, old, "[Polytool]");
        inventory = playerType.getField("field_71071_by").get(player);
        Class<?> inv = inventory.getClass();
        setInventory = inv.getMethod("func_70299_a", int.class, stack);
        setCursor = inv.getMethod("func_70437_b", stack);
        cursor = inv.getMethod("func_70445_o");
        container = Class.forName("minechem.item.polytool.PolytoolContainer").getConstructor(playerType).newInstance(player);
        Class<?> menu = container.getClass();
        slot = menu.getMethod("func_75139_a", int.class).invoke(container, 0);
        click = menu.getMethod("func_75144_a", int.class, int.class, int.class, playerType);
        accepts = slot.getClass().getMethod("func_75214_a", stack);
        inSlot = slot.getClass().getMethod("func_75211_c");
        power = tool.getClass().getMethod("getPowerOfType", stack, elementEnum);
        dig = old ? tool.getClass().getMethod("func_77638_a", stack, block) : tool.getClass().getMethod("getDigSpeed", stack, block, int.class);
        wear = stack.getMethod("func_77952_i");
        place = worldType.getMethod(old ? "func_72832_d" : "func_147465_d", int.class, int.class, int.class,
                                   old ? int.class : block, int.class, int.class);
        mine = tool.getClass().getMethod(old ? "func_77660_a" : "func_150894_a", stack, worldType,
                                        old ? int.class : block, int.class, int.class, int.class, living);
        Method getBlock = old ? null : Class.forName("cpw.mods.fml.common.registry.GameRegistry").getMethod("findBlock", String.class, String.class);
        for (int i = 0; i < SAMPLE.length; i++) {
            samples[i] = old ? Array.get(block.getField("field_71973_m").get(null), BLOCK_IDS[i]) : getBlock.invoke(null, "minecraft", SAMPLE[i]);
            require(samples[i] != null, "Missing sample block " + SAMPLE[i]);
        }
        coal = samples[1];
    }

    static String survey(Object world, boolean old, int x, int y, int z) throws Exception {
        new LegacyMinechemPolytool(world, old).measure(x, y, z);
        return "Polytool: original slot clicks, upgrade NBT, alloy stats, mining/attack hooks and energy measured";
    }

    private void measure(int x, int y, int z) throws Exception {
        Object plain = fresh();
        try (PrintWriter out = new PrintWriter("minechem-polytool.txt", "UTF-8")) {
            out.println("base=" + stats(plain) + ";" + speeds(plain) + ";" + attackModifier(plain));
            Object shortStack = tubes(13, 63), unsupported = tubes(29, 64), fullMolecule = make.newInstance(molecule, 64, old ? 0 : 1);
            out.println("invalid=" + insert(shortStack, 0) + ";" + insert(unsupported, 0) + ";" + insert(fullMolecule, 0) +
                        ";right=" + insert(tubes(13, 64), 1) + ";rank=" + rank(plain, 13));
            clearCursorAndSlot();
            Class<?> helper = Class.forName("minechem.item.polytool.PolytoolHelper");
            Method upgrade = helper.getMethod("getTypeFromElement", elementEnum, float.class);
            Method atomic = elementEnum.getMethod("atomicNumber");
            Method lookup = old ? null : elementEnum.getMethod("getByID", int.class);
            for (int id = 1; id <= 118; id++) {
                Object chem = old ? (id <= elementEnum.getEnumConstants().length ? elementEnum.getEnumConstants()[id - 1] : null)
                                  : lookup.invoke(null, id);
                if (chem == null || (Integer) atomic.invoke(chem) != id) continue;
                Object full = tubes(id, 64), type = upgrade.invoke(null, chem, 1F);
                boolean fitted = (Boolean) accepts.invoke(slot, full);
                require(fitted == (type != null), "Slot vs upgrade registry mismatch for " + id);
                if (!fitted) continue;
                Object sample = fresh();
                require(insert(full, 0).equals("0/0") && rank(sample, id) == 1, "Did not consume one full stack of element " + id);
                if (type.getClass().getSimpleName().equals("PolytoolTypeAlloy")) {
                    int[] delta = new int[5];
                    for (int i = 0; i < delta.length; i++) delta[i] = integer(type.getClass().getMethod("getStr" + DELTAS[i]).invoke(type));
                    out.println("e" + id + "=alloy;" + csv(delta) + ";" + stats(sample) + ";" + speeds(sample));
                } else out.println("e" + id + "=module");
            }
            Object alloyed = fresh();
            String strokes = insert(tubes(13, 64), 0) + ";" + insert(tubes(13, 64), 0);
            Object reloaded = reload(alloyed);
            out.println("aluminium=" + strokes + ";rank=" + rank(reloaded, 13) + ";stats=" + stats(reloaded) +
                        ";stored=" + storedId(reloaded) + ";" + attackModifier(reloaded));
            clearCursorAndSlot();
            Object mixed = fresh();
            String sequence = insert(tubes(13, 64), 0) + ";" + insert(tubes(31, 64), 0);
            out.println("mixed=" + sequence + ";rank=" + rank(mixed, 13) + "," + rank(mixed, 31) + ";stats=" + stats(mixed));
            clearCursorAndSlot();
            Object repeated = fresh();
            StringBuilder sequenceV = new StringBuilder();
            for (int i = 1; i <= 4; i++) sequenceV.append(insert(tubes(23, 64), 0)).append(';');
            out.println("vanadium=" + sequenceV + "rank=" + rank(repeated, 23) + ";stats=" + stats(repeated));
            clearCursorAndSlot();
            Object shifted = fresh();
            setInventory.invoke(inventory, 1, tubes(8, 64)); // container index 2 is hotbar index 1
            click.invoke(container, 2, 0, 1, player);
            varNoop();
            Object remains = inventory.getClass().getMethod("func_70301_a", int.class).invoke(inventory, 1);
            out.println("shift=" + rank(shifted, 8) + "," + amount(remains) + "," + amount(inSlot.invoke(slot)));
            clearCursorAndSlot();
            setInventory.invoke(inventory, 1, null);
            miningAndCombat(out, x, y, z);
            energy(out);
            require(!out.checkError(), "Couldn't write Polytool evidence");
        }
    }

    private void miningAndCombat(PrintWriter out, int x, int y, int z) throws Exception {
        Object upgraded = fresh();
        for (int id : new int[]{8, 8, 26, 16}) require(insert(tubes(id, 64), 0).equals("0/0"), "Could not install mining/combat module " + id);
        mc("entity.Entity").getMethod("func_70050_g", int.class).invoke(player, 100);
        for (int i = 0; i < 3; ++i) place.invoke(world, x + i, y, z, old ? 16 : coal, 0, 3);
        mine.invoke(tool, upgraded, world, old ? 16 : coal, x, y, z, player);
        Method airBlock = worldType.getMethod(old ? "func_72799_c" : "func_147437_c", int.class, int.class, int.class);
        int air = (Integer) mc("entity.Entity").getMethod("func_70086_ai").invoke(player);
        out.println("mine=" + air + ";neighbors=" + airBlock.invoke(world, x + 1, y, z) + "," + airBlock.invoke(world, x + 2, y, z) +
                    ";wear=" + wear.invoke(upgraded));
        Object pig = mc("entity.passive.EntityPig").getConstructor(worldType).newInstance(world);
        Method health = living.getMethod("func_110143_aJ");
        int before = integer(health.invoke(pig));
        tool.getClass().getMethod("func_77644_a", stack, living, living).invoke(tool, upgraded, pig, player);
        out.println("hit=" + (before - integer(health.invoke(pig))) + ";ignites=" + mc("entity.Entity").getMethod("func_70027_ad").invoke(pig) +
                    ";wear=" + wear.invoke(upgraded));
    }

    private void energy(PrintWriter out) throws Exception {
        Object lithium = fresh();
        Class<?> type = tool.getClass();
        boolean hasEnergy = false;
        for (Method method : type.getMethods()) if (method.getName().equals("recharge") || method.getName().equals("receiveEnergy")) hasEnergy = true;
        if (!old) {
            out.println("lithium=" + insert(tubes(3, 64), 0) + ";energy=" + hasEnergy);
            return;
        }
        require(hasEnergy, "FINAL Polytool lost its original UE interface");
        Method charge = type.getMethod("recharge", stack, long.class, boolean.class);
        Method stored = type.getMethod("getEnergy", stack);
        Object accepted = charge.invoke(tool, lithium, 100L, false);
        Object without = stored.invoke(tool, lithium);
        String clicks = insert(tubes(3, 64), 0) + ";" + insert(tubes(3, 64), 0);
        Object simulated = charge.invoke(tool, lithium, 100L, true);
        Object before = stored.invoke(tool, lithium);
        Object charged = charge.invoke(tool, lithium, 100L, false);
        Object after = stored.invoke(tool, reload(lithium));
        Object discharge = type.getMethod("discharge", stack, long.class, boolean.class).invoke(tool, lithium, 50L, true);
        type.getMethod("setEnergy", stack, long.class).invoke(tool, lithium, 1000L);
        Object cap = type.getMethod("getEnergyCapacity", stack).invoke(tool, lithium);
        out.println("lithium=" + clicks + ";rank=" + rank(lithium, 3) + ";absent=" + accepted + "," + without + ";true=" + simulated + "," + before +
                    ";false=" + charged + "," + after + ";drain=" + discharge + ";set=" + stored.invoke(tool, lithium) + ";cap=" + cap);
    }

    private Object fresh() throws Exception {
        Object item = make.newInstance(tool, 1, 0);
        setInventory.invoke(inventory, 0, item);
        return item;
    }

    private Object tubes(int atomic, int amount) throws Exception { return make.newInstance(element, amount, old ? atomic - 1 : atomic); }
    private int amount(Object found) throws Exception { return found == null ? 0 : count.getInt(found); }

    private String insert(Object item, int button) throws Exception {
        setCursor.invoke(inventory, item);
        click.invoke(container, 0, button, 0, player);
        return amount(cursor.invoke(inventory)) + "/" + amount(inSlot.invoke(slot));
    }

    private void clearCursorAndSlot() throws Exception {
        setCursor.invoke(inventory, new Object[]{null});
        click.invoke(container, 0, 0, 0, player);
        setCursor.invoke(inventory, new Object[]{null});
        require(inSlot.invoke(slot) == null, "Could not clear original infusion slot");
    }

    private int rank(Object item, int id) throws Exception {
        Object e = old ? elementEnum.getEnumConstants()[id - 1] : elementEnum.getMethod("getByID", int.class).invoke(null, id);
        return integer(power.invoke(null, item, e));
    }

    private String stats(Object item) throws Exception {
        int[] values = new int[STATS.length];
        for (int i = 0; i < values.length; i++) values[i] = integer(tool.getClass().getMethod("get" + STATS[i] + "Str", stack).invoke(tool, item));
        return csv(values);
    }

    private String speeds(Object item) throws Exception {
        int[] values = new int[samples.length];
        for (int i = 0; i < values.length; i++) values[i] = integer(old ? dig.invoke(tool, item, samples[i]) : dig.invoke(tool, item, samples[i], 0));
        return csv(values);
    }

    private Object reload(Object item) throws Exception {
        Object tag = nbt.newInstance();
        stack.getMethod("func_77955_b", nbt).invoke(item, tag);
        return stack.getMethod("func_77949_a", nbt).invoke(null, tag);
    }

    private int storedId(Object item) throws Exception {
        Object tag = stack.getMethod("func_77978_p").invoke(item);
        Method list = old ? nbt.getMethod("func_74761_m", String.class) : nbt.getMethod("func_150295_c", String.class, int.class);
        Object ranks = old ? list.invoke(tag, "Upgrades") : list.invoke(tag, "Upgrades", 10);
        Object first = ranks.getClass().getMethod(old ? "func_74743_b" : "func_150305_b", int.class).invoke(ranks, 0);
        return (Integer) nbt.getMethod("func_74762_e", String.class).invoke(first, "Element");
    }

    private String attackModifier(Object item) throws Exception {
        Object attributes;
        try {
            attributes = tool.getClass().getMethod("getAttributeModifiers", stack).invoke(tool, item);
        } catch (NoSuchMethodException e) {
            if (!old) throw e;
            attributes = tool.getClass().getMethod("func_111205_h").invoke(tool);
        }
        Class<?> multi = Class.forName("com.google.common.collect.Multimap");
        Iterable<?> attack = (Iterable<?>) multi.getMethod("get", Object.class).invoke(attributes, "generic.attackDamage");
        double sum = 0;
        for (Object attr : attack) sum += ((Number) attr.getClass().getMethod("func_111164_d").invoke(attr)).doubleValue();
        return "attack_modifier=" + sum;
    }

    private static String csv(int[] values) { return Arrays.toString(values).replace("[", "").replace("]", "").replace(" ", ""); }
    private static int integer(Object value) {
        float f = ((Number) value).floatValue();
        require(f == (int) f, "Expected integral observation: " + value);
        return (int) f;
    }
    private static Class<?> mc(String name) throws ClassNotFoundException { return Class.forName("net.minecraft." + name); }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
