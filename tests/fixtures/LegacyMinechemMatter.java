package mcmodup.minechemoracle;

import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Registry survey and real tank/decay/shielding boundaries on the two pinned binaries. */
final class LegacyMinechemMatter {
    private final boolean old;
    private final Object world, element, molecule;
    private final Class<?> w = mc("world.World"), stack = mc("item.ItemStack"), item = mc("item.Item"), nbt = mc("nbt.NBTTagCompound");
    private final Class<?> block = mc("block.Block"), player = mc("entity.player.EntityPlayer"), inventory = mc("inventory.IInventory");
    private final Class<?> ec = Class.forName("minechem.item.element.ElementEnum"), mc = Class.forName("minechem.item.molecule.MoleculeEnum");
    private final Class<?> fluid = Class.forName("net.minecraftforge.fluids.Fluid"), fluidStack = Class.forName("net.minecraftforge.fluids.FluidStack");
    private final Class<?> direction, handler = Class.forName("minechem.radiation.RadiationHandler");
    private final Constructor<?> make = stack.getConstructor(item, int.class, int.class), makeFluid = fluidStack.getConstructor(fluid, int.class);
    private final Method meta = stack.getMethod("func_77960_j"), itemOf = stack.getMethod("func_77973_b");
    private final Field count = stack.getField("field_77994_a"), mb = fluidStack.getField("amount");
    private final Object side;
    private final long now;

    private LegacyMinechemMatter(Object world, boolean old) throws Exception {
        this.old = old;
        this.world = world;
        Class<?> items = Class.forName(old ? "minechem.MinechemItemsGeneration" : "minechem.MinechemItemsRegistration");
        element = items.getField("element").get(null);
        molecule = items.getField("molecule").get(null);
        now = (Long) w.getMethod("func_82737_E").invoke(world);
        direction = Class.forName(old ? "net.minecraftforge.common.ForgeDirection" : "net.minecraftforge.common.util.ForgeDirection");
        side = direction.getField("NORTH").get(null);
    }

    static String survey(Object world, boolean old, int x, int y, int z) throws Exception {
        LegacyMinechemMatter survey = new LegacyMinechemMatter(world, old);
        survey.fluids();
        survey.decayAndStorage(x, y, z);
        return "Measured chemical fluid registry, tube/bucket transactions, decay thresholds and shielded inventories";
    }

    private void fluids() throws Exception {
        Class<?> helpers = Class.forName("minechem.fluid.FluidHelper");
        Class<?> containers = Class.forName("net.minecraftforge.fluids.FluidContainerRegistry");
        Method container = containers.getMethod("getFluidForFilledItem", stack);
        Method name = fluid.getMethod("getName"), gas = fluid.getMethod("isGaseous"), density = fluid.getMethod("getDensity");
        Method viscosity = fluid.getMethod("getViscosity"), tint = fluid.getMethod("getColor");
        Method placed = fluid.getMethod(old ? "getBlockID" : "getBlock");
        Map<String, String> rows = new TreeMap<String, String>();
        Map<?, ?> e = (Map<?, ?>) helpers.getField("elements").get(null), m = (Map<?, ?>) helpers.getField(old ? "molecule" : "molecules").get(null);
        Object iron = null, waterChemical = null;
        for (Map<?, ?> source : Arrays.asList(e, m)) {
            boolean elements = source == e;
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                Object chemical = entry.getKey(), value = entry.getValue();
                int id = (Integer) (elements ? ec.getMethod(old ? "ordinal" : "atomicNumber") : mc.getMethod("id")).invoke(chemical);
                String key = (elements ? "e" : "m") + id;
                Object bound = placed.invoke(value);
                boolean placeable = old ? ((Integer) bound) > 0 : bound != null;
                Object typed = container.invoke(null, make.newInstance(elements ? element : molecule, 1, id));
                Object type = typed == null ? null : fluidStack.getMethod("getFluid").invoke(typed);
                String tube = typed == null ? "-" : name.invoke(type) + ":" + mb.getInt(typed);
                rows.put(key, name.invoke(value) + "|" + gas.invoke(value) + "|" + density.invoke(value) + "|" + viscosity.invoke(value) +
                         "|" + tint.invoke(value) + "|" + placeable + "|" + tube);
                if (elements && id == (old ? 25 : 26)) iron = value;
                if (!elements && id == 1) waterChemical = value;
            }
        }
        if (!old && waterChemical == null) waterChemical = Class.forName("net.minecraftforge.fluids.FluidRegistry").getField("WATER").get(null);
        require(iron != null && waterChemical != null, "Could not find original iron and water fluids; e26=" + rows.get("e26") + ", m1=" + rows.get("m1"));
        Class<?> tile = Class.forName("minechem.tileentity.decomposer.DecomposerTileEntity");
        Method fill = tile.getMethod("fill", direction, fluidStack, boolean.class), info = tile.getMethod("getTankInfo", direction);
        Method tick = tile.getMethod(old ? "func_70316_g" : "func_145845_h");
        Method drain = tile.getMethod("drain", direction, int.class, boolean.class);
        Method get = tile.getMethod("func_70301_a", int.class);
        Object machine = tile.newInstance();
        assignWorld(machine);
        if (old) {
            Object before = info.invoke(machine, side);
            int first = (Integer) fill.invoke(machine, side, makeFluid.newInstance(iron, 500), true);
            int second = (Integer) fill.invoke(machine, side, makeFluid.newInstance(waterChemical, 500), true);
            tick.invoke(machine);
            Object after = info.invoke(machine, side);
            rows.put("tank", Array.getLength(before) + "/" + first + "/" + second + "/" + Array.getLength(after) +
                     "/unconverted=" + (get.invoke(machine, 0) == null && get.invoke(machine, 1) == null) + "/drain=" + (drain.invoke(machine, side, 125, false) != null));
        } else {
            Method receive = tile.getMethod("receiveEnergy", int.class, boolean.class), energy = tile.getMethod("getEnergyStored");
            for (int i = 0; i < 10; i++) receive.invoke(machine, 100, false);
            int first = (Integer) fill.invoke(machine, side, makeFluid.newInstance(iron, 124), true);
            tick.invoke(machine);
            Object before = energy.invoke(machine);
            int second = (Integer) fill.invoke(machine, side, makeFluid.newInstance(iron, 2), true);
            tick.invoke(machine);
            Object paid = energy.invoke(machine);
            Object remainder = drain.invoke(machine, side, 125, false);
            for (int i = 0; i < 4; i++) tick.invoke(machine);
            Object product = get.invoke(machine, 1);
            rows.put("tank", first + "/" + before + "/" + second + "/" + paid + "/" + mb.getInt(remainder) + "/" + key(product));
            Object saved = nbt.newInstance();
            tile.getMethod("func_145841_b", nbt).invoke(machine, saved);
            Object reload = tile.newInstance();
            assignWorld(reload);
            tile.getMethod("func_145839_a", nbt).invoke(reload, saved);
            rows.put("reload", name.invoke(fluidStack.getMethod("getFluid").invoke(drain.invoke(reload, side, 125, false))) + ":" +
                     mb.getInt(drain.invoke(reload, side, 125, false)));
            rows.put("bucket", bucket(container, false));
            rows.put("water", bucket(container, true));
        }
        write("fluids", rows);
    }

    private String bucket(Method getFilled, boolean water) throws Exception {
        Class<?> gridType = mc("inventory.InventoryCrafting"), container = mc("inventory.Container");
        Object actor = LegacyMinechemChemistry.fakePlayer(world, false, "[Vials]");
        Object grid = gridType.getConstructor(container, int.class, int.class).newInstance(player.getField("field_71069_bz").get(actor), 3, 3);
        Method set = gridType.getMethod("func_70299_a", int.class, stack);
        Class<?> recipe = Class.forName("minechem.item.bucket.MinechemBucketRecipe"), reverse = Class.forName("minechem.item.bucket.MinechemBucketReverseRecipe");
        Object craft = recipe.newInstance(), uncraft = reverse.newInstance();
        Object vanilla = Class.forName("cpw.mods.fml.common.registry.GameRegistry").getMethod("findItem", String.class, String.class).invoke(null, "minecraft", "bucket");
        set.invoke(grid, 0, make.newInstance(vanilla, 1, 0));
        Object tube = make.newInstance(water ? molecule : element, 1, water ? 1 : 26);
        for (int i = 1; i < 9; i++) set.invoke(grid, i, tube);
        boolean matches = (Boolean) recipe.getMethod("func_77569_a", gridType, w).invoke(craft, grid, world);
        require(water || matches, "Original iron bucket recipe didn't match");
        Object bucket = matches ? recipe.getMethod("func_77572_b", gridType).invoke(craft, grid) : make.newInstance(
            Class.forName("cpw.mods.fml.common.registry.GameRegistry").getMethod("findItem", String.class, String.class).invoke(null, "minecraft", "water_bucket"), 1, 0);
        Object held = getFilled.invoke(null, bucket);
        for (int i = 1; i < 9; i++) set.invoke(grid, i, null);
        set.invoke(grid, 0, bucket);
        require((Boolean) reverse.getMethod("func_77569_a", gridType, w).invoke(uncraft, grid, world), "Original bucket reverse recipe didn't match");
        String output = mb.getInt(held) + "/" + key(reverse.getMethod("func_77572_b", gridType).invoke(uncraft, grid));
        if (!water) return output;
        Object filled = getFilled.invoke(null, tube);
        return matches + "/" + (filled == null ? "-" : mb.getInt(filled)) + "/" + output;
    }

    private void decayAndStorage(int x, int y, int z) throws Exception {
        Map<String, String> rows = new TreeMap<String, String>();
        Class<?> classes = Class.forName("minechem.radiation.RadiationEnum");
        for (Object kind : classes.getEnumConstants()) {
            int ordinal = ((Enum<?>) kind).ordinal();
            rows.put("tier" + ordinal, classes.getMethod("getLife").invoke(kind) + "/" + classes.getMethod("getDamage").invoke(kind));
        }
        if (!old) {
            Class<?> settings = Class.forName("minechem.Settings");
            rows.put("config", settings.getField("halfLifeMultiplier").getInt(null) + "/" + settings.getField("decaySafeMachines").getBoolean(null) +
                     "/" + settings.getField("fluidEffects").getBoolean(null));
        }
        Map<String, Integer> radioactive = new TreeMap<String, Integer>();
        Method byID = old ? null : ec.getMethod("getByID", int.class);
        for (int id = 1; id <= 113; id++) {
            Object type = old ? ec.getEnumConstants()[id - 1] : byID.invoke(null, id);
            int power = ((Enum<?>) ec.getMethod("radioactivity").invoke(type)).ordinal();
            if (power > 0) radioactive.put("e" + (old ? id - 1 : id), power);
        }
        for (Map.Entry<String, Integer> tag : radioactive.entrySet()) rows.put(tag.getKey(), tag.getValue().toString());
        if (!old) {
            Class<?> decay = Class.forName("minechem.radiation.RadiationMoleculeHandler");
            Method products = decay.getDeclaredMethod("getDecayedMolecule", mc);
            products.setAccessible(true);
            Object factory = decay.getMethod("getInstance").invoke(null);
            for (Object type : ((Map<?, ?>) mc.getField("molecules").get(null)).values()) {
                int power = ((Enum<?>) mc.getMethod("radioactivity").invoke(type)).ordinal();
                if (power == 0) continue;
                String id = "m" + mc.getMethod("id").invoke(type);
                rows.put(id, power + "/" + chemicalProducts(products.invoke(factory, type)));
            }
        }
        int uranium = old ? 91 : 92;
        long life = ((Number) classes.getMethod("getLife").invoke(classes.getEnumConstants()[1])).longValue();
        rows.put("boundary", step(uranium, 3, life - 1) + "/" + step(uranium, 3, life) + "/" + step(uranium, 3, life + 1));
        rows.put("catchup", step(uranium, 3, life * 2 + 1));
        rows.put("storage", storage(x, y, z, uranium, life));
        rows.put("ore", ore());
        Class<?> settings = Class.forName("minechem.Settings");
        Field generate = settings.getDeclaredField(old ? "WorldGenOre" : "generateOre");
        Field density = settings.getDeclaredField("UraniumOreDensity"), size = settings.getDeclaredField("UraniumOreClusterSize");
        for (Field field : Arrays.asList(generate, density, size)) field.setAccessible(true);
        rows.put("worldgen", generate.getBoolean(null) + "/" + density.getInt(null) + "/" + size.getInt(null));
        write("radiation", rows);
    }

    private String chemicalProducts(Object result) throws Exception {
        Class<?> elementPart = Class.forName("minechem.item.element.Element"), chemical = Class.forName("minechem.potion.PotionChemical");
        Map<String, Integer> counts = new TreeMap<String, Integer>();
        int size = result instanceof List ? ((List<?>) result).size() : Array.getLength(result);
        for (int i = 0; i < size; i++) {
            Object part = result instanceof List ? ((List<?>) result).get(i) : Array.get(result, i);
            boolean e = elementPart.isInstance(part);
            Object kind = part.getClass().getField(e ? "element" : "molecule").get(part);
            String key = (e ? "e" : "m") + (e ? ec.getMethod(old ? "ordinal" : "atomicNumber") : mc.getMethod("id")).invoke(kind);
            int amount = chemical.getField("amount").getInt(part);
            counts.put(key, (counts.containsKey(key) ? counts.get(key) : 0) + amount);
        }
        StringBuilder line = new StringBuilder();
        for (Map.Entry<String, Integer> part : counts.entrySet()) {
            if (line.length() > 0) line.append(',');
            line.append(part.getKey()).append('x').append(part.getValue());
        }
        return line.toString();
    }

    private String step(int id, int size, long age) throws Exception {
        Object tube = aged(id, size, age);
        Method update = old ? handler.getDeclaredMethod("updateRadiation", w, stack) :
                        handler.getDeclaredMethod("updateRadiation", w, stack, inventory, double.class, double.class, double.class);
        update.setAccessible(true);
        Object h = handler.getMethod("getInstance").invoke(null);
        Object damage = old ? update.invoke(h, world, tube) : update.invoke(h, world, tube, null, 0.0, 80.0, 0.0);
        return key(tube) + "@" + damage;
    }

    private Object aged(int id, int size, long age) throws Exception {
        Object tube = make.newInstance(element, size, id);
        Object tag = nbt.newInstance();
        Method put = nbt.getMethod("func_74772_a", String.class, long.class);
        put.invoke(tag, "lastUpdate", now - age);
        if (old) {
            Object type = ec.getEnumConstants()[id];
            Object radiation = ec.getMethod("radioactivity").invoke(type);
            put.invoke(tag, "life", ((Number) radiation.getClass().getMethod("getLife").invoke(radiation)).longValue());
        } else put.invoke(tag, "decayStart", now - age);
        stack.getMethod("func_77982_d", nbt).invoke(tube, tag);
        return tube;
    }

    private String storage(int x, int y, int z, int uranium, long life) throws Exception {
        Class<?> registered = Class.forName("minechem.MinechemBlocksGeneration");
        Object placed = registered.getField(old ? "leadedChest" : "leadChest").get(null);
        Object chest = place(placed, x, y, z);
        Object actor = LegacyMinechemChemistry.fakePlayer(world, old, "[Shield]");
        Object inv = player.getField("field_71071_by").get(actor);
        Class<?> menu = Class.forName("minechem.tileentity.leadedchest.LeadedChestContainer");
        Object screen = menu.getConstructor(mc("entity.player.InventoryPlayer"), chest.getClass()).newInstance(inv, chest);
        Field opened = player.getField("field_71070_bA");
        Object original = opened.get(actor);
        opened.set(actor, screen);
        Object sample = aged(uranium, 3, life + 60);
        Method set = inventory.getMethod("func_70299_a", int.class, stack);
        set.invoke(chest, 0, sample);
        Object h = handler.getMethod("getInstance").invoke(null);
        handler.getMethod("update", player).invoke(h, actor);
        opened.set(actor, original);
        Object stored = inventory.getMethod("func_70301_a", int.class).invoke(chest, 0);
        Object dirty = old ? stack.getConstructor(int.class, int.class, int.class).newInstance(3, 1, 0) :
                       make.newInstance(Class.forName("cpw.mods.fml.common.registry.GameRegistry").getMethod("findItem", String.class, String.class)
                           .invoke(null, "minecraft", "dirt"), 1, 0);
        String plain = inventory.getMethod("func_70302_i_").invoke(chest) + "/" + inventory.getMethod("func_70297_j_").invoke(chest) +
                       "/" + ((List<?>) mc("inventory.Container").getField("field_75151_b").get(screen)).size() +
                       "/" + inventory.getMethod("func_94041_b", int.class, stack).invoke(chest, 0, dirty) + "/" + key(stored);
        if (!old) return plain;
        Object cabinet = place(registered.getField("chemicalStorage").get(null), x + 1, y, z);
        return plain + "/cabinet=" + inventory.getMethod("func_70302_i_").invoke(cabinet);
    }

    private Object place(Object value, int x, int y, int z) throws Exception {
        require((Boolean) w.getMethod(old ? "func_94575_c" : "func_147449_b", int.class, int.class, int.class, old ? int.class : block)
                      .invoke(world, x, y, z, old ? block.getField("field_71990_ca").getInt(value) : value), "Could not place original storage");
        return w.getMethod(old ? "func_72796_p" : "func_147438_o", int.class, int.class, int.class).invoke(world, x, y, z);
    }

    private String ore() throws Exception {
        Object ore = Class.forName("minechem.MinechemBlocksGeneration").getField("uranium").get(null);
        Object input = stack.getConstructor(block, int.class, int.class).newInstance(ore, 1, 0);
        Class<?> type = Class.forName("minechem.tileentity.decomposer.DecomposerRecipeHandler");
        Object recipe = type.getMethod("getRecipe", stack).invoke(type.getField("instance").get(null), input);
        return stack.getMethod("func_77977_a").invoke(input) + "/" + chemicalProducts(recipe.getClass().getMethod("getOutput").invoke(recipe));
    }

    private void assignWorld(Object tile) throws Exception {
        if (old) tile.getClass().getField("field_70331_k").set(tile, world);
        else tile.getClass().getMethod("func_145834_a", w).invoke(tile, world);
    }

    private String key(Object value) throws Exception {
        if (value == null) return "-";
        Object type = itemOf.invoke(value);
        return (type == element ? "e" : type == molecule ? "m" : "item") + meta.invoke(value) + "x" + count.getInt(value);
    }

    private static void write(String name, Map<String, String> rows) throws Exception {
        try (PrintWriter out = new PrintWriter("minechem-" + name + ".txt", "UTF-8")) {
            for (Map.Entry<String, String> row : rows.entrySet()) out.println(row.getKey() + "=" + row.getValue());
            require(!out.checkError(), "Could not write " + name);
        }
    }

    private static Class<?> mc(String suffix) throws Exception { return Class.forName("net.minecraft." + suffix); }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
