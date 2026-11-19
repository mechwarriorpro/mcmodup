package mcmodup.minechemoracle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;

/** Real survival damage, slot eligibility and outbound chat on the pinned Forge servers. */
final class LegacyMinechemPlayerDecay {
    private final LegacyMinechemMatter matter;
    private final Object world;
    private final boolean old;
    private final int uranium;
    private final long life;
    private final Class<?> stack = mc("item.ItemStack"), item = mc("item.Item"), inv = mc("inventory.IInventory");
    private final Class<?> player = mc("entity.player.EntityPlayer"), mp = mc("entity.player.EntityPlayerMP");
    private final Class<?> w = mc("world.World"), server = mc("server.MinecraftServer");
    private final Class<?> playerInv = mc("entity.player.InventoryPlayer");
    private final Method get = inv.getMethod("func_70301_a", int.class), set = inv.getMethod("func_70299_a", int.class, stack);
    private final Method health = player.getMethod("func_110143_aJ");
    private final Field opened = player.getField("field_71070_bA");
    private final Class<?> radiation = Class.forName("minechem.radiation.RadiationHandler");
    private final Object handler = radiation.getMethod("getInstance").invoke(null);

    LegacyMinechemPlayerDecay(LegacyMinechemMatter matter, Object world, boolean old, int uranium, long life) throws Exception {
        this.matter = matter;
        this.world = world;
        this.old = old;
        this.uranium = uranium;
        this.life = life;
    }

    Map<String, String> survey() throws Exception {
        Map<String, String> rows = new TreeMap<String, String>();
        Class<?> shield = Class.forName(old ? "minechem.radiation.IRadiationShield" : "minechem.api.IRadiationShield");
        Iterable<?> items = old ? Arrays.asList((Object[]) item.getField("field_77698_e").get(null)) :
            (Iterable<?>) item.getField("field_150901_e").get(null);
        int registered = 0;
        for (Object value : items) if (shield.isInstance(value)) registered++;
        rows.put("registered_item_shields", String.valueOf(registered));

        Actor actor = new Actor();
        set.invoke(actor.inventory, 0, sample());
        rows.put("hotbar", scan(actor, actor.inventory, 0));
        rows.put("repeat", scan(actor, actor.inventory, 0));

        actor = new Actor();
        for (int id = 310; id <= 313; id++) {
            Object armor = old ? stack.getConstructor(int.class, int.class, int.class).newInstance(id, 1, 0) :
                stack.getConstructor(item, int.class, int.class).newInstance(item.getMethod("func_150899_d", int.class).invoke(null, id), 1, 0);
            set.invoke(actor.inventory, 39 - (id - 310), armor);
        }
        set.invoke(actor.inventory, 0, sample());
        rows.put("vanilla_armor", scan(actor, actor.inventory, 0));

        actor = new Actor();
        Object carried = sample();
        playerInv.getMethod("func_70437_b", stack).invoke(actor.inventory, carried);
        float prior = actor.health();
        update(actor);
        rows.put("cursor", actor.outcome(matter.key(carried), prior));
        playerInv.getMethod("func_70437_b", stack).invoke(actor.inventory, new Object[]{null});
        set.invoke(actor.inventory, 0, carried);
        rows.put("cursor_placed", scan(actor, actor.inventory, 0));

        actor = new Actor();
        Object chest = mc("inventory.InventoryBasic").getConstructor(String.class, boolean.class, int.class).newInstance("samples", false, 27);
        opened.set(actor.player, mc("inventory.ContainerChest").getConstructor(inv, inv).newInstance(actor.inventory, chest));
        set.invoke(chest, 0, sample());
        rows.put("vanilla_chest", scan(actor, chest, 0));

        machine(rows, "leaded", "leadedchest.LeadedChest", 0, 26, 27);
        machine(rows, "synthesis", "synthesis.Synthesis", 10, 15, 16);
        machine(rows, "fission", "multiblock.fission.Fission", 0, 9, 10);
        machine(rows, "fusion", "multiblock.fusion.Fusion", old ? 1 : 0, 9, 10);
        if (old) {
            machine(rows, "decomposer", "decomposer.Decomposer", 0, 25, 26);
            rows.put("scheduler_spacing", Class.forName("minechem.tickhandler.ScheduledTickHandler")
                .getMethod("nextTickSpacing").invoke(Class.forName("minechem.tickhandler.ScheduledTickHandler").newInstance()).toString());
        } else {
            Field option = Class.forName("minechem.Settings").getField("decaySafeMachines");
            boolean original = option.getBoolean(null);
            try {
                option.setBoolean(null, false);
                machine(rows, "decomposer_false", "decomposer.Decomposer", 0, 25, 26);
                option.setBoolean(null, true);
                machine(rows, "decomposer_true", "decomposer.Decomposer", 0, 25, 26);
            } finally { option.setBoolean(null, original); }

            actor = new Actor();
            set.invoke(actor.inventory, 0, sample());
            Class<?> fml = Class.forName("cpw.mods.fml.common.FMLCommonHandler");
            Object hooks = fml.getMethod("instance").invoke(null);
            prior = actor.health();
            fml.getMethod("onPlayerPostTick", player).invoke(hooks, actor.player);
            rows.put("tick_end", actor.outcome(matter.key(get.invoke(actor.inventory, 0)), prior));
            prior = actor.health();
            fml.getMethod("onPlayerPreTick", player).invoke(hooks, actor.player);
            rows.put("tick_start", actor.outcome(matter.key(get.invoke(actor.inventory, 0)), prior));

            bucket(rows, "element_bucket", "e" + uranium, life + 1201);
            moleculeBuckets(rows, "m109", 109, life / 2 + 1201);
            moleculeBuckets(rows, "m153", 153, life / 8 + 1201);
        }
        return rows;
    }

    private void machine(Map<String, String> rows, String label, String kind, int storage, int early, int late) throws Exception {
        Actor actor = new Actor();
        Object tile = open(actor, kind);
        set.invoke(tile, storage, sample());
        rows.put(label + "_storage", scan(actor, tile, storage));
        for (int slot : new int[]{early, late, 0}) {
            actor = new Actor();
            open(actor, kind);
            set.invoke(actor.inventory, slot, sample());
            rows.put(label + "_player_" + slot, scan(actor, actor.inventory, slot));
        }
    }

    private Object open(Actor actor, String kind) throws Exception {
        String prefix = "minechem.tileentity." + kind;
        Class<?> tile = Class.forName(prefix + "TileEntity");
        Object machine = tile.newInstance();
        matter.assignWorld(machine);
        opened.set(actor.player, Class.forName(prefix + "Container").getConstructor(playerInv, tile).newInstance(actor.inventory, machine));
        return machine;
    }

    private Object sample() throws Exception { return matter.aged(uranium, 3, life + 1201); }

    private void bucket(Map<String, String> rows, String label, String key, long age) throws Exception {
        Actor actor = new Actor();
        set.invoke(actor.inventory, 0, matter.aged(matter.filledBucket(key), age));
        rows.put(label, scan(actor, actor.inventory, 0));
        List<String> byproducts = new ArrayList<String>();
        for (int i = 1; i < 36; i++) {
            Object other = get.invoke(actor.inventory, i);
            if (other != null) byproducts.add(matter.key(other));
        }
        Collections.sort(byproducts);
        rows.put(label + "_byproducts", byproducts.toString());
    }

    private void moleculeBuckets(Map<String, String> rows, String key, int id, long age) throws Exception {
        Class<?> handler = Class.forName("minechem.radiation.RadiationMoleculeHandler");
        Field field = handler.getDeclaredField("decayedMoleculesCache");
        field.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) field.get(handler.getMethod("getInstance").invoke(null));
        Object molecule = Class.forName("minechem.item.molecule.MoleculeEnum").getMethod("getById", int.class).invoke(null, id);
        String label = key + "_bucket";
        Map<String, String> results = new TreeMap<String, String>();
        // HashSet identity order can change when the original recomputes its product cache.
        for (int i = 0; i < 64 && results.size() < 2; i++) {
            cache.remove(molecule);
            Map<String, String> sample = new TreeMap<String, String>();
            bucket(sample, label, key, age);
            results.put(sample.get(label), sample.get(label + "_byproducts"));
        }
        if (results.size() != 2) throw new AssertionError("Could not re-observe both " + key + " daughters: " + results);
        String suffix = "";
        for (Map.Entry<String, String> result : results.entrySet()) {
            rows.put(label + suffix, result.getKey());
            rows.put(label + suffix + "_byproducts", result.getValue());
            suffix = "_alternate";
        }
    }

    private void update(Actor actor) throws Exception { radiation.getMethod("update", player).invoke(handler, actor.player); }
    private String scan(Actor actor, Object inventory, int slot) throws Exception {
        float before = actor.health();
        update(actor);
        return actor.outcome(matter.key(get.invoke(inventory, slot)), before);
    }

    private final class Actor {
        final Object player, inventory, network;
        final List<Object> packets = new ArrayList<Object>();

        Actor() throws Exception {
            Object game = server.getMethod("func_71276_C").invoke(null);
            Class<?> manager = mc(old ? "item.ItemInWorldManager" : "server.management.ItemInWorldManager");
            Object interaction = manager.getConstructor(w).newInstance(world);
            if (old) {
                player = mp.getConstructor(server, w, String.class, manager).newInstance(game, world, "Probe", interaction);
                Class<?> connection = mc("network.INetworkManager");
                final Class<?> packet = mc("network.packet.Packet");
                network = Proxy.newProxyInstance(connection.getClassLoader(), new Class<?>[]{connection}, new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method method, Object[] args) {
                        if (args != null && args.length == 1 && packet.isInstance(args[0])) packets.add(args[0]);
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        return null;
                    }
                });
                mp.getField("field_71135_a").set(player, mc("network.NetServerHandler").getConstructor(server, connection, mp)
                    .newInstance(game, network, player));
            } else {
                Class<?> profile = Class.forName("com.mojang.authlib.GameProfile"), connection = mc("network.NetworkManager");
                player = mp.getConstructor(server, mc("world.WorldServer"), profile, manager).newInstance(game, world,
                    profile.getConstructor(java.util.UUID.class, String.class).newInstance(java.util.UUID.randomUUID(), "Probe"), interaction);
                network = connection.getConstructor(boolean.class).newInstance(true);
                mp.getField("field_71135_a").set(player, mc("network.NetHandlerPlayServer").getConstructor(server, connection, mp)
                    .newInstance(game, network, player));
            }
            // Use a real survival player, not Forge FakePlayer (which discards chat and may be invulnerable).
            Field immunity = mp.getDeclaredField(old ? "field_71145_cl" : "field_147101_bU");
            immunity.setAccessible(true);
            immunity.setInt(player, 0);
            inventory = LegacyMinechemPlayerDecay.this.player.getField("field_71071_by").get(player);
        }

        float health() throws Exception { return ((Number) health.invoke(player)).floatValue(); }

        String outcome(String product, float before) throws Exception {
            List<String> warnings = new ArrayList<String>();
            if (old) {
                for (Object packet : packets) if (packet.getClass().getSimpleName().equals("Packet3Chat")) {
                    Field message = packet.getClass().getDeclaredField("field_73476_b");
                    message.setAccessible(true);
                    warnings.add(message.get(packet).toString());
                }
                packets.clear();
            } else {
                Class<?> chat = mc("network.play.server.S02PacketChat"), text = mc("util.IChatComponent");
                Field component = null;
                for (Field field : chat.getDeclaredFields()) if (field.getType() == text) component = field;
                if (component == null) throw new AssertionError("No text component in the original chat packet");
                component.setAccessible(true);
                for (Field queue : network.getClass().getDeclaredFields()) if (Queue.class.isAssignableFrom(queue.getType())) {
                    queue.setAccessible(true);
                    Queue<?> pending = (Queue<?>) queue.get(network);
                    for (Object entry : pending) for (Field payload : entry.getClass().getDeclaredFields()) {
                        if (!payload.getType().isAssignableFrom(chat)) continue;
                        payload.setAccessible(true);
                        Object packet = payload.get(entry);
                        if (chat.isInstance(packet)) {
                            Object message = component.get(packet);
                            warnings.add((String) text.getMethod("func_150260_c").invoke(message));
                        }
                    }
                    pending.clear();
                }
            }
            return product + "|" + (before - health()) + "|" + warnings;
        }
    }

    private static Class<?> mc(String suffix) throws Exception { return Class.forName("net.minecraft." + suffix); }
}
