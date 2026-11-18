package mcmodup.minechemoracle;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import java.lang.reflect.Array;
import java.lang.reflect.Method;

/** Runs inventory operations and ticks on each exact Minechem release's registered decomposer. */
@Mod(modid = "mcmodup_minechemoracle", name = "Minechem decomposer parity oracle", version = "1.0", dependencies = "required-after:minechem")
public final class LegacyMinechemDecomposer {
    private static final boolean FINAL_164 = "1.6.4".equals(System.getProperty("mcmodup.minechem.version"));

    @Mod.EventHandler
    public void check(FMLServerStartedEvent event) {
        try {
            require(Loader.isModLoaded("minechem"), "The original Minechem did not load");
            Class<?> serverType = type("server.MinecraftServer"), worldType = type("world.World");
            Object server = serverType.getMethod("func_71276_C").invoke(null);
            Object world = serverType.getMethod("func_71218_a", int.class).invoke(server, 0);
            Object spawn = worldType.getMethod("func_72861_E").invoke(world);
            int x = coordinate(spawn, "field_71574_a"), y = coordinate(spawn, "field_71572_b") + 4, z = coordinate(spawn, "field_71573_c");
            Object block = Class.forName("minechem.MinechemBlocksGeneration").getField("decomposer").get(null);
            Object machine;
            if (FINAL_164) {
                int blockId = type("block.Block").getField("field_71990_ca").getInt(block);
                require((Boolean) worldType.getMethod("func_94575_c", int.class, int.class, int.class, int.class).invoke(world, x, y, z, blockId), "Couldn't place 1.6.4 decomposer");
                machine = worldType.getMethod("func_72796_p", int.class, int.class, int.class).invoke(world, x, y, z);
            } else {
                require((Boolean) worldType.getMethod("func_147449_b", int.class, int.class, int.class, type("block.Block")).invoke(world, x, y, z, block), "Couldn't place 1.7.10 decomposer");
                machine = worldType.getMethod("func_147438_o", int.class, int.class, int.class).invoke(world, x, y, z);
            }
            require(machine != null && machine.getClass().getName().equals("minechem.tileentity.decomposer.DecomposerTileEntity"), "Didn't find a real decomposer tile");
            Class<?> tile = machine.getClass();
            Class<?> itemStack = type("item.ItemStack");
            int slots = (Integer) tile.getMethod("func_70302_i_").invoke(machine);
            require(slots == (FINAL_164 ? 10 : 19), "Wrong machine slot count " + slots);
            Method sided = tile.getMethod("func_94128_d", int.class);
            require(Array.getLength(sided.invoke(machine, 1)) == 1 && Array.getInt(sided.invoke(machine, 1), 0) == 0, "Top no longer exposes the original input");
            require(Array.getLength(sided.invoke(machine, 2)) == slots - 1, "North no longer exposes the original outputs");
            Method set = tile.getMethod("func_70299_a", int.class, itemStack);
            Method get = tile.getMethod("func_70301_a", int.class);
            Method tick = tile.getMethod(FINAL_164 ? "func_70316_g" : "func_145845_h");
            Object input;
            if (FINAL_164) input = itemStack.getConstructor(int.class, int.class, int.class).newInstance(265, 1, 0);
            else {
                Object iron = Class.forName("cpw.mods.fml.common.registry.GameRegistry").getMethod("findItem", String.class, String.class)
                        .invoke(null, "minecraft", "iron_ingot");
                input = itemStack.getConstructor(type("item.Item"), int.class, int.class).newInstance(iron, 1, 0);
            }
            set.invoke(machine, 0, input);
            tick.invoke(machine);
            require(get.invoke(machine, 0) != null && get.invoke(machine, 1) == null, "Unpowered decomposition began");
            long capacity = FINAL_164 ? 1 : 1000;
            fill(machine, capacity);
            require(energy(machine) == capacity, "Couldn't charge original machine");
            tick.invoke(machine);
            require(get.invoke(machine, 0) == null && get.invoke(machine, 1) == null && state(machine).equals("active"), "Original did not consume the input into its buffer before producing output");
            long paidAtStart = capacity - energy(machine);
            require(paidAtStart == (FINAL_164 ? 0 : 1000), "Wrong start cost " + paidAtStart);
            Class<?> nbt = type("nbt.NBTTagCompound");
            Object tag = nbt.newInstance();
            tile.getMethod(FINAL_164 ? "func_70310_b" : "func_145841_b", nbt).invoke(machine, tag);
            Object replica = tile.newInstance();
            if (FINAL_164) tile.getField("field_70331_k").set(replica, world);
            else tile.getMethod("func_145834_a", worldType).invoke(replica, world);
            tile.getMethod(FINAL_164 ? "func_70307_a" : "func_145839_a", nbt).invoke(replica, tag);
            require(state(replica).equals("active") && energy(replica) == energy(machine), "Original buffer state or charge did not round trip");
            Object element = Class.forName(FINAL_164 ? "minechem.MinechemItemsGeneration" : "minechem.MinechemItemsRegistration").getField("element").get(null);
            for (int i = 1; i <= 16; i++) {
                tick.invoke(replica);
                Object output = get.invoke(replica, 1);
                require(output != null && itemStack.getField("field_77994_a").getInt(output) == i,
                        "Original did not release exactly one item during output tick " + i);
                require(itemStack.getMethod("func_77973_b").invoke(output) == element &&
                        (Integer) itemStack.getMethod("func_77960_j").invoke(output) == (FINAL_164 ? 25 : 26), "Wrong output item or iron element metadata");
                require(energy(replica) == 0L, "Wrong original charge on output tick " + i);
            }
            tick.invoke(replica);
            require(state(replica).equals("idle"), "Original did not return to idle after emptying its buffer");
            System.out.println("MCMODUP_MINECHEM_ORIGINAL_PASS slots=" + slots + "; start_charge=" + capacity + "; start_cost=" + paidAtStart
                    + "; input_consumed_on_start; saved_queue_emits_16_iron_tubes_one_per_tick; remaining_charge=0");
        } catch (Throwable failure) {
            System.err.println("MCMODUP_MINECHEM_ORIGINAL_FAIL " + failure);
            failure.printStackTrace();
        }
    }

    private static void fill(Object tile, long energy) throws Exception {
        if (FINAL_164) tile.getClass().getMethod("setEnergy", direction(), long.class).invoke(tile, unknown(), energy);
        else {
            Method receive = tile.getClass().getMethod("receiveEnergy", int.class, boolean.class);
            for (long left = energy; left > 0;) {
                int added = (Integer) receive.invoke(tile, (int) left, false);
                require(added > 0 && added <= left, "Unexpected RF reception " + added);
                left -= added;
            }
        }
    }

    private static long energy(Object tile) throws Exception {
        if (FINAL_164) return (Long) tile.getClass().getMethod("getEnergy", direction()).invoke(tile, unknown());
        return (Integer) tile.getClass().getMethod("getEnergyStored").invoke(tile);
    }

    private static Class<?> direction() throws Exception { return Class.forName("net.minecraftforge.common.ForgeDirection"); }
    private static Object unknown() throws Exception { return direction().getField("UNKNOWN").get(null); }
    private static String state(Object tile) throws Exception { return tile.getClass().getField("state").get(tile).toString(); }
    private static int coordinate(Object object, String field) throws Exception { return object.getClass().getField(field).getInt(object); }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName("net.minecraft." + name); }
    private static void require(boolean ok, String failure) { if (!ok) throw new AssertionError(failure); }
}
