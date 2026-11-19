package mcmodup.minechemoracle;

import java.io.PrintWriter;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/** Test the exact .394 registry and callbacks; none of this code is used by the port. */
final class LegacyMinechemFluidAutomation {
    private final Object world, element, molecule, bucket, waterBucket;
    private final Class<?> w = mc("world.World"), block = mc("block.Block"), stack = mc("item.ItemStack"), item = mc("item.Item");
    private final Class<?> dropped = mc("entity.item.EntityItem"), entity = mc("entity.Entity");
    private final Class<?> inv = mc("inventory.IInventory"), type = Class.forName("minechem.item.MinechemChemicalType");
    private final Class<?> ec = Class.forName("minechem.item.element.ElementEnum"), mol = Class.forName("minechem.item.molecule.MoleculeEnum");
    private final Class<?> bSource = mc("dispenser.IBlockSource"), dispenser = mc("block.BlockDispenser");
    private final Class<?> chemicalBucket = Class.forName("minechem.item.bucket.MinechemBucketItem");
    private final Class<?> bucketHandler = Class.forName("minechem.item.bucket.MinechemBucketHandler");
    private final Class<?> rules = Class.forName("minechem.fluid.reaction.ChemicalFluidReactionHandler");
    private final Class<?> registry = Class.forName("cpw.mods.fml.common.registry.GameRegistry");
    private final Constructor<?> makeStack = stack.getConstructor(item, int.class, int.class);
    private final Method getBlock = w.getMethod("func_147439_a", int.class, int.class, int.class);
    private final Method meta = w.getMethod("func_72805_g", int.class, int.class, int.class);
    private final Method setBlock = w.getMethod("func_147465_d", int.class, int.class, int.class, block, int.class, int.class);
    private final Method itemOf = stack.getMethod("func_77973_b"), damage = stack.getMethod("func_77960_j");
    private final Class<?> fluidBlock = Class.forName("net.minecraftforge.fluids.IFluidBlock");
    private final Class<?> minechemFluid = Class.forName("minechem.fluid.MinechemFluid");
    private final Field count = stack.getField("field_77994_a");
    private final Object iron, lithium, exampleMolecule;
    private final Map<String, String> rows = new TreeMap<String, String>();

    private LegacyMinechemFluidAutomation(Object world) throws Exception {
        this.world = world;
        Class<?> items = Class.forName("minechem.MinechemItemsRegistration");
        element = items.getField("element").get(null);
        molecule = items.getField("molecule").get(null);
        Method findItem = registry.getMethod("findItem", String.class, String.class);
        bucket = findItem.invoke(null, "minecraft", "bucket");
        waterBucket = findItem.invoke(null, "minecraft", "water_bucket");
        iron = ec.getField("Fe").get(null);
        lithium = ec.getField("Li").get(null);
        exampleMolecule = mol.getField("cellulose").get(null);
    }

    static void survey(Object world, int x, int y, int z) throws Exception {
        LegacyMinechemFluidAutomation probe = new LegacyMinechemFluidAutomation(world);
        probe.registries();
        probe.dispensers(x, y, z);
        probe.reactions(x, y + 12, z);
        probe.propagation(x, y + 24, z);
        try (PrintWriter out = new PrintWriter("minechem-fluid-automation.txt", "UTF-8")) {
            for (Map.Entry<String, String> row : probe.rows.entrySet()) out.println(row.getKey() + "=" + row.getValue());
            if (out.checkError()) throw new AssertionError("Could not save the original fluid automation trace");
        }
    }

    private void registries() throws Exception {
        Class<?> settings = Class.forName("minechem.Settings");
        rows.put("config", settings.getField("reactionItemMeetFluid").getBoolean(null) + "/" + settings.getField("reactionFluidMeetFluid").getBoolean(null));
        Map<?, ?> reactionRules = (Map<?, ?>) rules.getField("reactionRules").get(null);
        for (Map.Entry<?, ?> pair : reactionRules.entrySet()) {
            Object key = pair.getKey(), out = pair.getValue();
            String a = chemical(key.getClass().getField("chemicalA").get(key));
            String b = chemical(key.getClass().getField("chemicalB").get(key));
            String products = "";
            for (Object product : (List<?>) out.getClass().getField("outputs").get(out)) {
                products += (products.isEmpty() ? "" : ",") + chemical(product);
            }
            rows.put("rule_" + (a.compareTo(b) < 0 ? a + "+" + b : b + "+" + a),
                out.getClass().getField("explosionLevel").getFloat(out) + ";" + products);
        }
        Class<?> ignition = Class.forName("minechem.fluid.ExplosiveFluidHandler");
        Object ign = ignition.getMethod("getInstance").invoke(null);
        Class<?> fuel = Class.forName("minechem.utils.MinechemFuelHandler");
        Object handler = fuel.newInstance();
        Method burn = fuel.getMethod("getBurnTime", stack);
        Map<?, ?> elements = (Map<?, ?>) ec.getField("elements").get(null);
        Map<?, ?> molecules = (Map<?, ?>) mol.getField("molecules").get(null);
        List<Object> chemicals = new ArrayList<Object>(elements.values());
        chemicals.addAll(molecules.values());
        for (Object value : chemicals) {
            String key = chemical(value);
            Object held = tube(value, 1);
            int ticks = (Integer) burn.invoke(handler, held);
            if (ticks > 0) rows.put("fuel_" + key, Integer.toString(ticks));
            Object full = fullBucket(value);
            if (full != null) {
                ticks = (Integer) burn.invoke(handler, full);
                if (ticks > 0) rows.put("fuel_b" + key, Integer.toString(ticks));
            }
            float power = (Float) ignition.getMethod("getExplosiveFluid", type).invoke(ign, value);
            if (!Float.isNaN(power)) rows.put("ignite_" + key, Float.toString(power));
        }
        String sources = "";
        for (String name : new String[]{"fire", "flowing_lava", "lava", "torch", "water"}) {
            if ((Boolean) ignition.getMethod("existingFireSource", block).invoke(ign, vanilla(name))) {
                sources += (sources.isEmpty() ? "" : ",") + name;
            }
        }
        rows.put("fire_sources", sources);
    }

    private void dispensers(int x, int y, int z) throws Exception {
        Object air = vanilla("air"), stone = vanilla("stone"), water = vanilla("water");
        Object ferrous = fluidBlock(iron);
        rows.put("dispense_bucket", dispense(x, y, z, fullBucket(iron), null, air, 0));
        rows.put("dispense_blocked_bucket", dispense(x + 4, y, z, fullBucket(iron), null, stone, 0));
        rows.put("dispense_pickup_source", dispense(x + 8, y, z, makeStack.newInstance(bucket, 1, 0), null, ferrous, 0));
        rows.put("dispense_pickup_flow", dispense(x + 12, y, z, makeStack.newInstance(bucket, 1, 0), null, ferrous, 1));
        rows.put("dispense_water_fallback", dispense(x + 16, y, z, makeStack.newInstance(bucket, 1, 0), null, water, 0));
        rows.put("dispense_element_air", dispense(x + 20, y, z, tube(iron, 8), null, air, 0));
        rows.put("dispense_element_source", dispense(x + 24, y, z, tube(iron, 8), null, water, 0));
        rows.put("dispense_empty_tube", dispense(x + 28, y, z, makeStack.newInstance(element, 8, 0), null, water, 0));
        rows.put("dispense_molecule_split", dispense(x + 32, y, z, tube(exampleMolecule, 3), tube(exampleMolecule, 5), air, 0));
        rows.put("dispense_molecule_short", dispense(x + 36, y, z, tube(exampleMolecule, 3), tube(exampleMolecule, 4), vanilla("flowing_water"), 1));
        rows.put("dispense_element_split", dispense(x + 40, y, z, tube(iron, 3), makeStack.newInstance(element, 5, 0), water, 0));
    }

    private String dispense(int x, int y, int z, Object selected, Object extra, Object front, int level) throws Exception {
        // The real registered behavior, with deterministic slot zero rather than the dispenser's random slot choice.
        put(x + 1, y, z, front, level);
        put(x, y, z, vanilla("dispenser"), 5);
        w.getMethod("func_72921_c", int.class, int.class, int.class, int.class, int.class).invoke(world, x, y, z, 5, 2);
        Object inventory = w.getMethod("func_147438_o", int.class, int.class, int.class).invoke(world, x, y, z);
        Method set = inv.getMethod("func_70299_a", int.class, stack), get = inv.getMethod("func_70301_a", int.class);
        set.invoke(inventory, 0, selected);
        if (extra != null) set.invoke(inventory, 1, extra);
        Object behaviorRegistry = dispenser.getField("field_149943_a").get(null);
        Object behavior = behaviorRegistry.getClass().getMethod("func_82594_a", Object.class).invoke(behaviorRegistry, itemOf.invoke(selected));
        Object source = mc("block.BlockSourceImpl").getConstructor(w, int.class, int.class, int.class).newInstance(world, x, y, z);
        List<Object> before = new ArrayList<Object>(entities());
        String status = "ok";
        try {
            Object result = mc("dispenser.IBehaviorDispenseItem").getMethod("func_82482_a", bSource, stack).invoke(behavior, source, selected);
            set.invoke(inventory, 0, result != null && count.getInt(result) > 0 ? result : null);
        } catch (InvocationTargetException broken) {
            status = broken.getCause().getClass().getSimpleName();
        }
        Map<String, Integer> contents = new TreeMap<String, Integer>();
        for (int i = 0; i < 9; i++) {
            Object stored = get.invoke(inventory, i);
            if (stored != null && count.getInt(stored) > 0) {
                tally(stored, contents);
            }
        }
        return status + "/" + position(x + 1, y, z) + "/" + contents + "/" + dropsSince(before);
    }

    private List<?> entities() throws Exception { return (List<?>) w.getField("field_72996_f").get(world); }

    private Map<String, Integer> dropsSince(List<Object> before) throws Exception {
        Map<String, Integer> drops = new TreeMap<String, Integer>();
        for (Object found : entities()) {
            if (dropped.isInstance(found) && !before.contains(found)) tally(dropped.getMethod("func_92059_d").invoke(found), drops);
        }
        return drops;
    }

    private void tally(Object stored, Map<String, Integer> contents) throws Exception {
        String key = itemKey(stored);
        contents.put(key, (contents.containsKey(key) ? contents.get(key) : 0) + count.getInt(stored));
    }

    private void reactions(int x, int y, int z) throws Exception {
        Object water = vanilla("water"), flow = vanilla("flowing_water"), lithiumBlock = fluidBlock(lithium);
        put(x, y, z, water, 0);
        put(x + 1, y, z, lithiumBlock, 0);
        rows.put("react_source", products(x, y, z));
        x += 8;
        put(x, y, z, flow, 1);
        put(x + 1, y, z, lithiumBlock, 0);
        rows.put("react_flow", products(x, y, z));
        x += 8;
        rows.put("react_item", itemContact(x, y, z));
        x += 8;
        Object acid = fluidBlock(mol.getField("hcl").get(null));
        Object alkaline = mol.getField("sodiumHydroxide").get(null);
        put(x, y, z, acid, 0);
        rows.put("react_item_chemical_only", itemContact(x, y, z, 0.5, alkaline));
        x += 8;
        put(x, y, z, acid, 0);
        put(x + 1, y, z, water, 0);
        rows.put("react_item_water_edge", itemContact(x, y, z, 0.95, alkaline));
        x += 8;
        Class<?> settings = Class.forName("minechem.Settings");
        Field blockToggle = settings.getField("reactionFluidMeetFluid"), itemToggle = settings.getField("reactionItemMeetFluid");
        boolean originalBlocks = blockToggle.getBoolean(null), originalItems = itemToggle.getBoolean(null);
        try {
            blockToggle.setBoolean(null, false);
            itemToggle.setBoolean(null, false);
            put(x, y, z, water, 0);
            put(x + 1, y, z, lithiumBlock, 0);
            rows.put("react_source_disabled", products(x, y, z));
            x += 8;
            rows.put("react_item_disabled", itemContact(x, y, z));
            x += 8;
            put(x, y, z, vanilla("lava"), 0);
            put(x + 1, y, z, fluidBlock(mol.getField("tnt").get(null)), 0);
            rows.put("ignite_disabled_reactions", position(x + 1, y, z));
        } finally {
            blockToggle.setBoolean(null, originalBlocks);
            itemToggle.setBoolean(null, originalItems);
        }
        x += 8;
        put(x, y, z, vanilla("lava"), 0);
        put(x + 1, y, z, fluidBlock(mol.getField("tnt").get(null)), 0);
        rows.put("ignite_lava", position(x + 1, y, z));
        x += 8;
        put(x, y, z, fluidBlock(mol.getField("hcl").get(null)), 0);
        put(x + 1, y, z, fluidBlock(mol.getField("sodiumHydroxide").get(null)), 0);
        rows.put("react_water_product", products(x, y, z));
    }

    private void propagation(int x, int y, int z) throws Exception {
        Object gas = fluidBlock(ec.getField("H").get(null));
        Object ethanol = fluidBlock(mol.getField("ethanol").get(null));
        Object solid = fluidBlock(iron), air = vanilla("air");
        Object[] types = {gas, ethanol, solid};
        String[] names = {"gas", "liquid", "solid"};
        for (int i = 0; i < types.length; i++, x += 8) {
            put(x, y, z, types[i], 0);
            updateFluid(types[i], x, y, z);
            rows.put("propagate_" + names[i], column(x, y, z));
        }
        put(x, y + 1, z, vanilla("stone"), 0);
        put(x, y, z, gas, 0);
        updateFluid(gas, x, y, z);
        updateFluid(gas, x + 1, y, z);
        rows.put("propagate_capped_gas", column(x, y, z) + "/" + column(x + 1, y, z));
        put(x, y, z, air, 0);
        updateFluid(gas, x + 1, y, z);
        rows.put("propagate_unsupported_gas", column(x + 1, y, z));
    }

    private void updateFluid(Object value, int x, int y, int z) throws Exception {
        try {
            MethodHandles.publicLookup().findVirtual(block, "func_149674_a", MethodType.methodType(void.class,
                w, int.class, int.class, int.class, Random.class)).invokeWithArguments(value, world, x, y, z, new Random(0));
        } catch (Throwable error) {
            throw new AssertionError("Could not tick the original fluid block", error);
        }
    }

    private String column(int x, int y, int z) throws Exception {
        return position(x, y - 1, z) + "/" + position(x, y, z) + "/" + position(x, y + 1, z);
    }

    private String itemContact(int x, int y, int z) throws Exception {
        put(x, y, z, vanilla("water"), 0);
        return itemContact(x, y, z, 0.5, lithium);
    }

    private String itemContact(int x, int y, int z, double offset, Object chemical) throws Exception {
        Object itemEntity = dropped.getConstructor(w, double.class, double.class, double.class, stack)
            .newInstance(world, x + offset, y + 0.1, z + 0.5, fullBucket(chemical));
        w.getMethod("func_72838_d", entity).invoke(world, itemEntity);
        List<Object> before = new ArrayList<Object>(entities());
        Class<?> event = Class.forName("cpw.mods.fml.common.gameevent.TickEvent$WorldTickEvent");
        Class<?> side = Class.forName("cpw.mods.fml.relauncher.Side"), phase = Class.forName("cpw.mods.fml.common.gameevent.TickEvent$Phase");
        Object tick = event.getConstructor(side, phase, w).newInstance(side.getField("SERVER").get(null), phase.getField("END").get(null), world);
        rules.getMethod("tick", event).invoke(rules.newInstance(), tick);
        return entity.getField("field_70128_L").getBoolean(itemEntity) + "/" + products(x, y, z) + "/" + dropsSince(before);
    }

    private String products(int x, int y, int z) throws Exception {
        List<String> found = new ArrayList<String>();
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 2; dx++) for (int dz = -1; dz <= 1; dz++) {
            String value = position(x + dx, y + dy, z + dz);
            if (value.startsWith("e") || value.startsWith("m")) found.add(value + "@" + dy);
        }
        Collections.sort(found);
        return found.toString();
    }
    private String position(int x, int y, int z) throws Exception {
        Object value = getBlock.invoke(world, x, y, z);
        if (value == vanilla("water") || value == vanilla("flowing_water")) return "m1:" + meta.invoke(world, x, y, z);
        if (fluidBlock.isInstance(value)) {
            Object fluid = fluidBlock.getMethod("getFluid").invoke(value);
            if (minechemFluid.isInstance(fluid)) return chemical(minechemFluid.getMethod("getChemical").invoke(fluid)) + ":" + meta.invoke(world, x, y, z);
        }
        for (String name : new String[]{"air", "stone", "fire", "lava", "flowing_lava"}) if (value == vanilla(name)) return name;
        return "other";
    }
    private String itemKey(Object value) throws Exception {
        Object held = itemOf.invoke(value);
        if (held == element) return "e" + damage.invoke(value);
        if (held == molecule) return "m" + damage.invoke(value);
        if (held == bucket) return "bucket";
        if (held == waterBucket) return "water_bucket";
        if (chemicalBucket.isInstance(held)) return "b" + chemical(chemicalBucket.getField("chemical").get(held));
        return "vanilla";
    }
    private Object tube(Object value, int n) throws Exception {
        String key = chemical(value);
        return makeStack.newInstance(ec.isInstance(value) ? element : molecule, n, Integer.valueOf(key.substring(1)));
    }
    private Object fullBucket(Object value) throws Exception {
        Object found = bucketHandler.getMethod("getBucket", type).invoke(bucketHandler.getMethod("getInstance").invoke(null), value);
        return found == null ? null : makeStack.newInstance(found, 1, 0);
    }
    private Object fluidBlock(Object value) throws Exception {
        Object filled = fullBucket(value);
        return chemicalBucket.getField("block").get(itemOf.invoke(filled));
    }
    private String chemical(Object value) throws Exception {
        return ec.isInstance(value) ? "e" + ec.getMethod("atomicNumber").invoke(value) : "m" + mol.getMethod("id").invoke(value);
    }
    private Object vanilla(String name) throws Exception {
        return registry.getMethod("findBlock", String.class, String.class).invoke(null, "minecraft", name);
    }
    private void put(int x, int y, int z, Object value, int meta) throws Exception {
        setBlock.invoke(world, x, y, z, value, meta, 3);
    }
    private static Class<?> mc(String suffix) throws Exception {
        try {
            return Class.forName("net.minecraft." + suffix);
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("Missing legacy class " + suffix + ": " + missing);
        }
    }
}
