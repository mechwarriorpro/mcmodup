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
            if (Boolean.getBoolean("mcmodup.minechem.ore_disable")) {
                System.out.println("MCMODUP_MINECHEM_ORIGINAL_PASS " + LegacyMinechemOreInterop.survey(world, FINAL_164));
                return;
            }
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
            Object nextInput = FINAL_164 ? null : itemStack.getMethod("func_77946_l").invoke(input);
            set.invoke(machine, 0, input);
            tick.invoke(machine);
            require(get.invoke(machine, 0) != null && get.invoke(machine, 1) == null, "Unpowered decomposition began");
            long capacity = FINAL_164 ? 1 : 1000;
            if (FINAL_164) {
                fill(machine, capacity);
            } else {
                fill(machine, capacity - 1);
                tick.invoke(machine);
                require(get.invoke(machine, 0) != null && energy(machine) == 999L, ".394 started below the original RF threshold");
                fill(machine, 1);
            }
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
            String jam = "";
            if (FINAL_164) {
                Method receive = tile.getMethod("onReceiveEnergy", direction(), long.class, boolean.class);
                require((Long) receive.invoke(replica, unknown(), 100L, false) == 20L && energy(replica) == 0L,
                        "Wrong original simulated energy reception");
                fill(replica, 1);
                for (int slot = 1; slot < slots - 1; slot++) {
                    set.invoke(replica, slot, itemStack.getConstructor(int.class, int.class, int.class).newInstance(1, 64, 0));
                }
                set.invoke(replica, slots - 1, itemStack.getConstructor(type("item.Item"), int.class, int.class).newInstance(element, 63, 25));
                set.invoke(replica, 0, itemStack.getConstructor(int.class, int.class, int.class).newInstance(265, 1, 0));
                for (int i = 0; i < 3; i++) tick.invoke(replica);
                require(state(replica).equals("jammed") && energy(replica) == 0L, "Original did not jam after the final paid unit filled the output");
                set.invoke(replica, slots - 1, null);
                tick.invoke(replica);
                require(state(replica).equals("jammed") && get.invoke(replica, slots - 1) == null, "Original jam cleared without charge");
                Object occupied = itemStack.getConstructor(type("item.Item"), int.class, int.class).newInstance(element, 62, 25);
                set.invoke(replica, slots - 1, occupied);
                fill(replica, 1);
                tick.invoke(replica);
                require(state(replica).equals("jammed") && energy(replica) == 1L &&
                        itemStack.getField("field_77994_a").getInt(get.invoke(replica, slots - 1)) == 62,
                        "Matching occupied space must not clear FINAL's powered jam");
                set.invoke(replica, slots - 1, null);
                for (int i = 0; i < 15; i++) tick.invoke(replica);
                Object remaining = get.invoke(replica, slots - 1);
                require(remaining != null && itemStack.getField("field_77994_a").getInt(remaining) == 15 && energy(replica) == 0L,
                        "Original did not preserve the remaining buffer across a jam");
                jam = "; receive_limit=20; jam_clears_only_when_charged_and_slot_empty";
            } else {
                require((Integer) tile.getMethod("getMaxEnergyStored").invoke(replica) == 10_000, "Wrong original .394 energy capacity");
                Method receive = tile.getMethod("receiveEnergy", int.class, boolean.class);
                require((Integer) receive.invoke(replica, 1_000, true) == 100 && energy(replica) == 0L,
                        "Wrong original .394 simulated energy reception");
                Class<?> items = type("item.Item");
                Method item = itemStack.getMethod("func_77973_b");
                Object blocker = item.invoke(nextInput); // Use a known valid, non-tube item.
                Method copy = itemStack.getMethod("func_77946_l");
                java.lang.reflect.Field quantity = itemStack.getField("field_77994_a");
                // Use the actual previous batch's stack, including any item tag the binary attached.
                Object priorOutput = get.invoke(replica, 1);
                require(priorOutput != null, ".394 cleared stored output when returning to idle");
                Object matching = copy.invoke(priorOutput);
                Object partial = copy.invoke(matching), full = copy.invoke(matching);
                quantity.setInt(partial, 63);
                quantity.setInt(full, 64);
                set.invoke(replica, 1, (Object) null);
                set.invoke(replica, slots - 1, partial);
                require(get.invoke(replica, slots - 1) != null, "Writing via the setter didn't populate the last .394 slot");
                fill(replica, 1_000);
                set.invoke(replica, 0, nextInput);
                tick.invoke(replica);
                require(get.invoke(replica, 0) == null && energy(replica) == 0L, "The second .394 intake did not prepay");
                Object compacted = get.invoke(replica, 1);
                require(compacted != null && item.invoke(compacted) == element && quantity.getInt(compacted) == 63 &&
                        get.invoke(replica, slots - 1) == null, ".394 must compact its existing output during intake without releasing a tube");
                // Introduce a gap again after intake. This distinguishes compaction from normal output selection.
                set.invoke(replica, 1, (Object) null);
                set.invoke(replica, slots - 1, compacted);
                tick.invoke(replica);
                Object trailing = get.invoke(replica, slots - 1);
                require(get.invoke(replica, 1) == null && trailing != null && quantity.getInt(trailing) == 64,
                        ".394 did not merge into a matching stack ahead of the first empty slot while active");
                for (int slot = 1; slot < slots - 1; slot++) {
                    set.invoke(replica, slot, itemStack.getConstructor(items, int.class, int.class).newInstance(blocker, 64, 0));
                }
                set.invoke(replica, slots - 1, full);
                tick.invoke(replica);
                require(state(replica).equals("jammed") && energy(replica) == 0L, "Expected a prepaid but jammed .394 queue");
                partial = copy.invoke(matching);
                quantity.setInt(partial, 63);
                set.invoke(replica, slots - 1, partial);
                tick.invoke(replica);
                trailing = get.invoke(replica, slots - 1);
                require(trailing != null && quantity.getInt(trailing) == 64 && state(replica).equals("active") && energy(replica) == 0L,
                        ".394 did not unjam with room in a matching stack and no new charge");
                set.invoke(replica, 1, (Object) null);
                for (int i = 0; i < 14; i++) tick.invoke(replica);
                Object remainder = get.invoke(replica, 1);
                require(remainder != null && item.invoke(remainder) == element && quantity.getInt(remainder) == 14 && energy(replica) == 0L,
                        ".394 jam did not preserve its paid remainder");
                Object split = tile.newInstance();
                tile.getMethod("func_145834_a", worldType).invoke(split, world);
                Object front = copy.invoke(matching), back = copy.invoke(matching);
                quantity.setInt(front, 60);
                quantity.setInt(back, 10);
                set.invoke(split, 1, front);
                set.invoke(split, slots - 1, back);
                set.invoke(split, 0, itemStack.getConstructor(items, int.class, int.class).newInstance(blocker, 1, 0));
                fill(split, 1_000);
                tick.invoke(split);
                require(get.invoke(split, 0) == null && energy(split) == 0L && get.invoke(split, 2) == null &&
                        quantity.getInt(get.invoke(split, 1)) == 64 && quantity.getInt(get.invoke(split, slots - 1)) == 6,
                        "Intake should fill 60→64, leave six tubes in the trailing slot, and emit none");
                jam = "; receive_limit=100; compacts_at_intake_and_keeps_merge_remainder; merge_before_empty_when_active; paid_jam_resumes_without_more_charge";
            }
            String materials = FINAL_164 ? "; " + finalWater(tile, world) + " | " + LegacyMinechemSynthesisFinal.survey(world, x, y + 27, z) : "; " + LegacyMinechemMaterials.survey(machine, world) +
                    " | " + LegacyMinechemSynthesis394.survey(world) + " | " + LegacyMinechemJournal394.survey(world, x, y, z);
            if (Boolean.getBoolean("mcmodup.minechem.matter")) LegacyMinechemOre.survey(world, FINAL_164);
            LegacyMinechemMining.survey(world, FINAL_164, x - 24, y + 48, z);
            LegacyMinechemCombat.survey(world, FINAL_164, x + 48, y + 48, z);
            System.out.println("MCMODUP_MINECHEM_ORIGINAL_PASS slots=" + slots + "; start_charge=" + capacity + "; start_cost=" + paidAtStart
                    + "; input_consumed_on_start; saved_queue_emits_16_iron_tubes_one_per_tick; remaining_charge=0" + jam + materials + " | " + cloning(world) +
                    " | " + LegacyMinechemPolytool.survey(world, FINAL_164, x + 24, y + 8, z) +
                    " | " + LegacyMinechemReactors.survey(world, FINAL_164, x, y + 18, z) +
                    " | " + LegacyMinechemChemistry.survey(world, FINAL_164) +
                    (Boolean.getBoolean("mcmodup.minechem.matter") ? " | " + LegacyMinechemMatter.survey(world, FINAL_164, x + 24, y + 10, z) : "")
                    + " | " + LegacyMinechemOreInterop.survey(world, FINAL_164));
        } catch (Throwable failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            System.err.println("MCMODUP_MINECHEM_ORIGINAL_FAIL " + cause + " at " +
                    (cause.getStackTrace().length == 0 ? "unknown" : cause.getStackTrace()[0]) +
                    " (probe: " + fixtureFrame(failure) + ")");
            failure.printStackTrace();
        }
    }

    private static String finalWater(Class<?> tile, Object world) throws Exception {
        Class<?> fluid = Class.forName("net.minecraftforge.fluids.Fluid"), stack = Class.forName("net.minecraftforge.fluids.FluidStack");
        Object water = Class.forName("net.minecraftforge.fluids.FluidRegistry").getField("WATER").get(null);
        Method fillTank = tile.getMethod("fill", direction(), stack, boolean.class);
        Method info = tile.getMethod("getTankInfo", direction());
        Method get = tile.getMethod("func_70301_a", int.class), tick = tile.getMethod("func_70316_g");
        Method damage = type("item.ItemStack").getMethod("func_77960_j");
        java.lang.reflect.Field count = type("item.ItemStack").getField("field_77994_a");
        java.lang.reflect.Field amount = stack.getField("amount");
        java.lang.reflect.Field stored = Class.forName("net.minecraftforge.fluids.FluidTankInfo").getField("fluid");
        StringBuilder outcomes = new StringBuilder();
        for (int mb : new int[]{144, 145, 10000, 10001, 10145}) {
            Object machine = tile.newInstance();
            tile.getField("field_70331_k").set(machine, world);
            Object waterStack = stack.getConstructor(fluid, int.class).newInstance(water, mb);
            require((Integer) fillTank.invoke(machine, unknown(), waterStack, false) == mb && Array.getLength(info.invoke(machine, unknown())) == 1,
                    "FINAL water fill simulation added a fluid");
            require((Integer) fillTank.invoke(machine, unknown(), waterStack, true) == mb, "FINAL fluid intake refused water");
            tick.invoke(machine);
            Object contents = stored.get(Array.get(info.invoke(machine, unknown()), 0));
            int remaining = amount.getInt(contents);
            Object input = get.invoke(machine, 0);
            require(get.invoke(machine, 1) == null && (input == null || remaining < mb), "FINAL fluid conversion duplicated a charge or output");
            Object nbt = type("nbt.NBTTagCompound").newInstance();
            tile.getMethod("func_70310_b", nbt.getClass()).invoke(machine, nbt);
            Object restored = tile.newInstance();
            tile.getField("field_70331_k").set(restored, world);
            tile.getMethod("func_70307_a", nbt.getClass()).invoke(restored, nbt);
            require(Array.getLength(info.invoke(restored, unknown())) == 1 && (get.invoke(restored, 0) != null) == (input != null),
                    "FINAL fluid or converted input save behavior changed");
            fill(restored, 1);
            for (int i = 0; i < 6; i++) tick.invoke(restored);
            outcomes.append(mb).append("->").append(remaining).append("/input=").append(input != null).append("/out=");
            for (int i = 1; i <= 9; i++) {
                Object result = get.invoke(restored, i);
                if (result != null) outcomes.append(damage.invoke(result)).append('x').append(count.getInt(result)).append(',');
            }
            outcomes.append(';');
        }
        return "FINAL water probes (offered to retained after one unpowered tick / converted input / outputs after reload with 1 charge): " + outcomes;
    }

    private static String cloning(Object world) throws Exception {
        Class<?> stack = type("item.ItemStack"), item = type("item.Item"), worldType = type("world.World");
        Class<?> tagType = type("nbt.NBTTagCompound"), gridType = type("inventory.InventoryCrafting");
        Object journalItem = Class.forName(FINAL_164 ? "minechem.MinechemItemsGeneration" : "minechem.MinechemItemsRegistration").getField("journal").get(null);
        Object journal = stack.getConstructor(item, int.class, int.class).newInstance(journalItem, 1, 0);
        Object book = FINAL_164 ? stack.getConstructor(int.class, int.class, int.class).newInstance(340, 1, 0) :
            stack.getConstructor(item, int.class, int.class).newInstance(
                Class.forName("cpw.mods.fml.common.registry.GameRegistry").getMethod("findItem", String.class, String.class)
                    .invoke(null, "minecraft", "book"), 1, 0);
        Object tag = tagType.newInstance();
        tagType.getMethod("func_74778_a", String.class, String.class).invoke(tag, "owner", "Minechem oracle");
        stack.getMethod("func_77982_d", tagType).invoke(journal, tag);
        journalItem.getClass().getMethod("addItemStackToJournal", stack, stack, worldType).invoke(journalItem, book, journal, world);
        journalItem.getClass().getMethod("setActiveStack", stack, stack).invoke(journalItem, book, journal);
        Object grid = gridType.getConstructor(type("inventory.Container"), int.class, int.class).newInstance(null, 3, 3);
        java.lang.reflect.Field raw = gridType.getDeclaredField("field_70466_a");
        raw.setAccessible(true);
        Object[] slots = (Object[]) raw.get(grid);
        Class<?> managerType = type("item.crafting.CraftingManager");
        Object manager = managerType.getMethod("func_77594_a").invoke(null);
        Method craft = managerType.getMethod("func_82787_a", gridType, worldType);
        slots[0] = journal;
        slots[1] = book;
        Object result = craft.invoke(manager, grid, world);
        Method getItem = stack.getMethod("func_77973_b"), getTag = stack.getMethod("func_77978_p");
        java.lang.reflect.Field count = stack.getField("field_77994_a");
        Object copied = result == null ? null : getTag.invoke(result);
        require(result != null && getItem.invoke(result) == journalItem && count.getInt(result) == 2 && count.getInt(journal) == 1 &&
                (Integer) stack.getMethod("func_77976_d").invoke(result) == 64 && copied != null && copied != tag && copied.equals(tag),
                "Registered journal clone did not preserve the whole original tag and two-count output");
        slots[0] = book;
        slots[1] = journal;
        require(craft.invoke(manager, grid, world) == null, "Original cloning unexpectedly matched reversed books");
        slots[0] = null;
        slots[1] = journal;
        slots[2] = book;
        require(craft.invoke(manager, grid, world) == null, "Original cloning unexpectedly matched shifted books");
        slots[0] = journal;
        slots[1] = book;
        slots[2] = null;
        slots[6] = stack.getMethod("func_77946_l").invoke(book);
        require(craft.invoke(manager, grid, world) != null, "Original cloning no longer ignores other slots");
        return "registered journal clone: top-left journal then book yields 2, limit 64, independent tag copy including owner, discoveries and selection; rejects reverse/shift, ignores extras";
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
    private static String fixtureFrame(Throwable failure) {
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (frame.getClassName().startsWith("mcmodup.minechemoracle.")) return frame.toString();
        }
        return failure.getStackTrace()[0].toString();
    }
    private static void require(boolean ok, String failure) { if (!ok) throw new AssertionError(failure); }
}
