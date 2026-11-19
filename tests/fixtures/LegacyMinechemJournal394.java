package mcmodup.minechemoracle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/** Observe the registered microscope, journal item and synthesizer of .394 together. */
final class LegacyMinechemJournal394 {
    private final Object world, journalItem;
    private final Class<?> worldType = mc("world.World"), stack = mc("item.ItemStack"), tagType = mc("nbt.NBTTagCompound");
    private final Constructor<?> make = stack.getConstructor(mc("item.Item"), int.class, int.class);
    private final Class<?> blocks = Class.forName("minechem.MinechemBlocksGeneration");
    private final Method place = worldType.getMethod("func_147449_b", int.class, int.class, int.class, mc("block.Block"));
    private final Method entity = worldType.getMethod("func_147438_o", int.class, int.class, int.class);
    private final Method item = stack.getMethod("func_77973_b"), copy = stack.getMethod("func_77946_l");
    private final Method find = Class.forName("cpw.mods.fml.common.registry.GameRegistry").getMethod("findItem", String.class, String.class);
    private final Method known, active, select;
    private final int x, y, z;

    private LegacyMinechemJournal394(Object world, int x, int y, int z) throws Exception {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        journalItem = Class.forName("minechem.MinechemItemsRegistration").getField("journal").get(null);
        known = journalItem.getClass().getMethod("getItemList", stack);
        active = journalItem.getClass().getMethod("getActiveStack", stack);
        select = journalItem.getClass().getMethod("setActiveStack", stack, stack);
    }

    static String survey(Object world, int x, int y, int z) throws Exception {
        return new LegacyMinechemJournal394(world, x, y, z).measure();
    }

    private String measure() throws Exception {
        Object microscope = place("microscope", 2), microscopeReload = place("microscope", 4);
        Object synthesis = place("synthesis", 6), synthesisReload = place("synthesis", 8);
        Class<?> microscopeType = microscope.getClass(), tile = synthesis.getClass();
        Method get = microscopeType.getMethod("func_70301_a", int.class), set = microscopeType.getMethod("func_70299_a", int.class, stack);
        require((Integer) microscopeType.getMethod("func_70302_i_").invoke(microscope) == 11 &&
                (Integer) microscopeType.getMethod("func_70297_j_").invoke(microscope) == 1,
                "Unexpected microscope layout or stack limit");
        Object iron = vanilla("iron_ingot"), ore = vanilla("coal_ore"), unknown = vanilla("bedrock");
        Object book = make.newInstance(journalItem, 1, 0);
        set.invoke(microscope, 0, iron);
        require(get.invoke(microscope, 0) == iron && known.invoke(journalItem, book) == null, "Inspecting without a book taught a journal");
        set.invoke(microscope, 1, book);
        expectKnown(book, iron);
        set.invoke(microscope, 0, copy.invoke(iron));
        expectKnown(book, iron);
        set.invoke(microscope, 0, unknown);
        expectKnown(book, iron);
        set.invoke(microscope, 0, ore);
        expectKnown(book, iron, ore);
        require(item.invoke(get.invoke(microscope, 0)) == item.invoke(ore) && get.invoke(microscope, 1) == book &&
                active.invoke(journalItem, book) == null, "Learning consumed the sample/book or auto-selected a recipe");
        // Discoveries alone do not populate the synthesizer. Only an explicit active selection does.
        Method sset = tile.getMethod("func_70299_a", int.class, stack), sget = tile.getMethod("func_70301_a", int.class);
        Method tick = tile.getMethod("func_145845_h");
        Method take = microscopeType.getMethod("func_70298_a", int.class, int.class);
        sset.invoke(synthesis, 28, take.invoke(microscope, 1, 1));
        tick.invoke(synthesis);
        require(sget.invoke(synthesis, 0) == null, "Unselected journal populated a recipe");
        book = tile.getMethod("func_70298_a", int.class, int.class).invoke(synthesis, 28, 1);
        select.invoke(journalItem, iron, book);
        set.invoke(microscope, 1, book);
        roundTrip(microscope, microscopeReload);
        Object restoredBook = get.invoke(microscopeReload, 1);
        expectKnown(restoredBook, iron, ore);
        require(same(active.invoke(journalItem, restoredBook), iron) && same(get.invoke(microscopeReload, 0), ore),
                "Microscope did not restore its sample, journal and active selection");
        sset.invoke(synthesis, 28, restoredBook);
        tick.invoke(synthesis);
        assertIronTemplate(synthesis, sget, iron);
        require((Integer) tile.getMethod("getEnergyStored").invoke(synthesis) == 0 &&
                !(Boolean) tile.getMethod("canTakeOutputStack", boolean.class).invoke(synthesis, false),
                "A selected journal bypassed the cost or storage requirements");
        roundTrip(synthesis, synthesisReload);
        tick.invoke(synthesisReload);
        assertIronTemplate(synthesisReload, sget, iron);
        Object removed = tile.getMethod("func_70298_a", int.class, int.class).invoke(synthesisReload, 28, 1);
        expectKnown(removed, iron, ore);
        require(same(active.invoke(journalItem, removed), iron), "Removing the journal lost its active item");
        tick.invoke(synthesisReload);
        for (int i = 0; i <= 9; ++i) require(sget.invoke(synthesisReload, i) == null, "Removing the journal did not clear slot " + i);
        return "Microscope learns on either insertion order without consuming samples; suppresses duplicate and unsupported samples; " +
               "learns iron ingot and coal ore; original NBT restores both contents and selection; " +
               "selected journal in synthesizer slot 28 sets 16 iron tubes, no paid output; extraction clears ghost grid";
    }

    private void assertIronTemplate(Object tile, Method get, Object iron) throws Exception {
        Object element = Class.forName("minechem.MinechemItemsRegistration").getField("element").get(null);
        require(same(get.invoke(tile, 0), iron), "Selected journal did not preview iron ingot");
        int tubes = 0;
        for (int i = 1; i <= 9; ++i) {
            Object input = get.invoke(tile, i);
            if (input == null) continue;
            require(item.invoke(input) == element && (Integer) stack.getMethod("func_77960_j").invoke(input) == 26,
                    "Journal populated an unexpected input");
            tubes += stack.getField("field_77994_a").getInt(input);
        }
        require(tubes == 16, "Expected 16 iron tubes in the ghost grid, not " + tubes);
    }

    private void expectKnown(Object journal, Object... samples) throws Exception {
        List<?> values = (List<?>) known.invoke(journalItem, journal);
        require(values != null && values.size() == samples.length, "Unexpected learned list size: " + values);
        for (int i = 0; i < samples.length; ++i) require(same(values.get(i), samples[i]), "Unexpected discovery order: " + values);
    }

    private Object place(String field, int offset) throws Exception {
        require((Boolean) place.invoke(world, x + offset, y, z, blocks.getField(field).get(null)), "Couldn't place " + field);
        Object found = entity.invoke(world, x + offset, y, z);
        require(found != null, "No tile placed for " + field);
        return found;
    }

    private void roundTrip(Object from, Object to) throws Exception {
        Object tag = tagType.newInstance();
        from.getClass().getMethod("func_145841_b", tagType).invoke(from, tag);
        to.getClass().getMethod("func_145839_a", tagType).invoke(to, tag);
    }

    private boolean same(Object left, Object right) throws Exception {
        return left != null && right != null && (Boolean) stack.getMethod("func_77969_a", stack).invoke(left, right);
    }

    private Object vanilla(String name) throws Exception {
        Object registered = find.invoke(null, "minecraft", name);
        require(registered != null, "Missing " + name);
        return make.newInstance(registered, 1, 0);
    }

    private static Class<?> mc(String name) throws ClassNotFoundException { return Class.forName("net.minecraft." + name); }
    private static void require(boolean ok, String error) { if (!ok) throw new AssertionError(error); }
}
