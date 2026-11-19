package mcmodup.voltzcheck;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLServerStartedEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/** Observe utility behavior: heat, steam, empty assembler state and Sentry power-bus forwarding. */
@Mod(modid = "mcmodup_voltz_infrastructure", name = "Voltz infrastructure parity probe", version = "1.0.0",
     dependencies = "required-after:AtomicScience;required-after:ICBM|Explosion;required-after:ICBM|Sentry")
public final class LegacyVoltzInfrastructure {
    private static Class<?> worldType;
    private static Object world;
    private static int x, y, z;

    @Mod.EventHandler
    public void check(FMLServerStartedEvent event) {
        try {
            require(Loader.isModLoaded("AtomicScience") && Loader.isModLoaded("ICBM|Explosion") && Loader.isModLoaded("ICBM|Sentry"),
                    "Required exact Voltz modules did not load");
            worldType = mc("world.World");
            Object server = mc("server.MinecraftServer").getMethod("func_71276_C").invoke(null);
            world = server.getClass().getMethod("func_71218_a", int.class).invoke(server, 0);
            Object spawn = worldType.getMethod("func_72861_E").invoke(world);
            x = coord(spawn, "field_71574_a") + 1;
            y = Math.min(220, coord(spawn, "field_71572_b") + 8);
            z = coord(spawn, "field_71573_c") + 1;
            String grid = thermal();
            String turbine = turbine();
            String steam = steamProduction();
            String assembler = assembler();
            String platform = platform();
            String inventory = platformInventory();
            System.out.println("MCMODUP_INFRA_PASS " + grid + " | " + turbine + " | " + steam + " | " + assembler + " | " + platform + " | " + inventory);
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            System.err.println("MCMODUP_INFRA_FAIL " + cause + " (probe: " + error.getStackTrace()[0] + ")");
            error.printStackTrace();
        }
    }

    private static String thermal() throws Exception {
        Class<?> gridType = Class.forName("calclavia.lib.thermal.ThermalGrid");
        Class<?> vector = Class.forName("universalelectricity.api.vector.VectorWorld");
        Object grid = gridType.getConstructor().newInstance();
        Object origin = vector.getConstructor(worldType, double.class, double.class, double.class).newInstance(world, x, y, z);
        Object east = vector.getConstructor(worldType, double.class, double.class, double.class).newInstance(world, x + 1, y, z);
        Object north = vector.getConstructor(worldType, double.class, double.class, double.class).newInstance(world, x, y, z - 1);
        place(mc("block.Block").getField("field_71981_t").get(null), x + 1, y, z, 0);
        Method ambient = gridType.getMethod("getDefaultTemperature", vector);
        Method temperature = gridType.getMethod("getTemperature", vector);
        Method heat = gridType.getMethod("addTemperature", vector, float.class);
        float base = (Float) ambient.invoke(grid, origin);
        float baseEast = (Float) ambient.invoke(grid, east);
        float baseNorth = (Float) ambient.invoke(grid, north);
        require((Float) temperature.invoke(grid, origin) == base, "Unheated position did not start at its ambient value");
        heat.invoke(grid, origin, 140F);
        require((Float) temperature.invoke(grid, origin) == base + 140F, "Original heat source did not store a temperature delta");
        gridType.getMethod("update").invoke(grid);
        float originDelta = (Float) temperature.invoke(grid, origin) - base;
        float eastDelta = (Float) temperature.invoke(grid, east) - baseEast;
        float northDelta = (Float) temperature.invoke(grid, north) - baseNorth;
        require(originDelta > 0F && originDelta < 140F && eastDelta > northDelta && northDelta > 0F, "No solid/air heat transfer");
        return "thermal given +140 K; one update ΔK=origin " + originDelta + ", solid east " + eastDelta + ", air north " + northDelta;
    }

    private static String turbine() throws Exception {
        Class<?> atomic = Class.forName("atomicscience.AtomicScience");
        Class<?> fluid = Class.forName("net.minecraftforge.fluids.Fluid");
        Class<?> stackType = Class.forName("net.minecraftforge.fluids.FluidStack");
        Class<?> direction = Class.forName("net.minecraftforge.common.ForgeDirection");
        Object tile = place(atomic.getField("blockElectricTurbine").get(null), x + 3, y, z, 0);
        Object steam = atomic.getField("FLUID_STEAM").get(null);
        Object water = Class.forName("net.minecraftforge.fluids.FluidRegistry").getField("WATER").get(null);
        Object bottom = direction.getField("DOWN").get(null);
        Object top = direction.getField("UP").get(null);
        Method canFill = tile.getClass().getMethod("canFill", direction, fluid);
        require((Boolean) canFill.invoke(tile, bottom, steam) && !(Boolean) canFill.invoke(tile, top, steam) &&
                !(Boolean) canFill.invoke(tile, bottom, water), "Turbine fluid or side acceptance changed");
        Method fill = tile.getClass().getMethod("fill", direction, stackType, boolean.class);
        Object tank = field(tile.getClass(), "tank").get(tile);
        Method amount = tank.getClass().getMethod("getFluidAmount");
        Method capacity = tank.getClass().getMethod("getCapacity");
        Object offered = stackType.getConstructor(fluid, int.class).newInstance(steam, 1000);
        require((Integer) fill.invoke(tile, bottom, offered, false) == 1000 && (Integer) amount.invoke(tank) == 0,
                "Steam simulation mutated the tank");
        require((Integer) fill.invoke(tile, bottom, offered, true) == 1000, "Steam fill did not insert one bucket");
        Method tick = tile.getClass().getMethod("func_70316_g");
        StringBuilder stages = new StringBuilder();
        for (int number = 0; number < 5; ++number) {
            tick.invoke(tile);
            if (number > 0) stages.append(',');
            stages.append(amount.invoke(tank)).append('/').append(field(tile.getClass(), "power").getLong(tile)).append('/')
                  .append(field(tile.getClass(), "angularVelocity").getFloat(tile));
        }
        Object energy = field(tile.getClass(), "energy").get(tile);
        Method stored = energy.getClass().getMethod("getEnergy");
        require((Integer) amount.invoke(tank) < 1000 && (Long) stored.invoke(energy) > 0L, "Steam produced no energy");
        Class<?> nbt = mc("nbt.NBTTagCompound");
        Object saved = nbt.newInstance();
        tile.getClass().getMethod("func_70310_b", nbt).invoke(tile, saved);
        Object restored = tile.getClass().newInstance();
        restored.getClass().getMethod("func_70307_a", nbt).invoke(restored, saved);
        int restoredAmount = (Integer) amount.invoke(field(tile.getClass(), "tank").get(restored));
        require(restoredAmount == (Integer) amount.invoke(tank), "Steam did not round-trip through original NBT");
        long restoredEnergy = (Long) stored.invoke(field(tile.getClass(), "energy").get(restored));
        return "turbine bottom steam only; capacity=" + capacity.invoke(tank) + "; steam/power/spin=" + stages +
                "; energy=" + stored.invoke(energy) + "; restored=" + restoredAmount + "/" + restoredEnergy;
    }

    private static String steamProduction() throws Exception {
        Class<?> atomic = Class.forName("atomicscience.AtomicScience"), block = mc("block.Block");
        Class<?> gridType = Class.forName("calclavia.lib.thermal.ThermalGrid"), vector = Class.forName("universalelectricity.api.vector.VectorWorld");
        Class<?> fluid = Class.forName("net.minecraftforge.fluids.Fluid");
        Class<?> direction = Class.forName("net.minecraftforge.common.ForgeDirection");
        Object down = direction.getField("DOWN").get(null), up = direction.getField("UP").get(null), east = direction.getField("EAST").get(null);
        Object water = Class.forName("net.minecraftforge.fluids.FluidRegistry").getField("WATER").get(null);
        Object steam = atomic.getField("FLUID_STEAM").get(null);
        Object collector = atomic.getField("blockSteamFunnel").get(null);
        int px = x + 23;
        Object lower = place(collector, px, y + 1, z, 0), higher = place(collector, px, y + 2, z, 0);
        Class<?> funnel = lower.getClass();
        Object lowerTank = field(funnel, "tank").get(lower), upperTank = field(funnel, "tank").get(higher);
        Method amount = lowerTank.getClass().getMethod("getFluidAmount");
        Method canFill = funnel.getMethod("canFill", direction, fluid), canDrain = funnel.getMethod("canDrain", direction, fluid);
        require((Integer) lowerTank.getClass().getMethod("getCapacity").invoke(lowerTank) == 16000 &&
                (Boolean) canFill.invoke(lower, down, steam) && !(Boolean) canFill.invoke(lower, east, steam) &&
                !(Boolean) canFill.invoke(lower, down, water) && (Boolean) canDrain.invoke(lower, up, steam) &&
                !(Boolean) canDrain.invoke(lower, down, steam), "Funnel capacity or sided rules changed");
        Object still = block.getField("field_71943_B").get(null);
        Method at = worldType.getMethod("func_72798_a", int.class, int.class, int.class);
        int stillId = block.getField("field_71990_ca").getInt(still);
        for (float threshold : new float[]{372F, 373F}) {
            Object grid = gridType.newInstance();
            Object origin = vector.getConstructor(worldType, double.class, double.class, double.class).newInstance(world, px, y, z);
            place(still, px, y, z, 0);
            float ambient = (Float) gridType.getMethod("getDefaultTemperature", vector).invoke(grid, origin);
            gridType.getMethod("addTemperature", vector, float.class).invoke(grid, origin, threshold - ambient);
            gridType.getMethod("update").invoke(grid);
            boolean boiling = threshold == 373F;
            require((Integer) amount.invoke(lowerTank) == (boiling ? 500 : 0) && (Integer) amount.invoke(upperTank) == (boiling ? 1000 : 0) &&
                    (Integer) at.invoke(world, px, y, z) == (boiling ? 0 : stillId), "Wrong steam uptake or water at " + threshold + " K: lower=" +
                    amount.invoke(lowerTank) + ", upper=" + amount.invoke(upperTank) + ", water=" + at.invoke(world, px, y, z));
            if (!boiling) worldType.getMethod("func_72832_d", int.class, int.class, int.class, int.class, int.class, int.class).invoke(world, px, y, z, 0, 0, 2);
        }
        Object turbine = place(atomic.getField("blockElectricTurbine").get(null), px, y + 3, z, 0);
        Method tick = funnel.getMethod("func_70316_g");
        tick.invoke(lower);
        require((Integer) amount.invoke(lowerTank) == 0 && (Integer) amount.invoke(upperTank) == 1500, "Original funnel did not push only its accepted amount upward");
        tick.invoke(higher);
        require((Integer) amount.invoke(upperTank) == 0 && (Integer) amount.invoke(field(turbine.getClass(), "tank").get(turbine)) == 1500,
                "Original funnel did not supply the original turbine");
        Object tag = mc("nbt.NBTTagCompound").newInstance(), restored = funnel.newInstance();
        Class<?> stack = Class.forName("net.minecraftforge.fluids.FluidStack");
        Object fill = stack.getConstructor(fluid, int.class).newInstance(steam, 2000);
        Method insert = funnel.getMethod("fill", direction, stack, boolean.class);
        require((Integer) insert.invoke(higher, down, fill, true) == 2000, "Funnel refused bottom gas");
        // Observe regular world-save callbacks. This particular Calclavia tile calls
        // the tank read method from the world writer and vice versa.
        funnel.getMethod("func_70310_b", tag.getClass()).invoke(higher, tag);
        funnel.getMethod("func_70307_a", tag.getClass()).invoke(restored, tag);
        require((Integer) amount.invoke(field(funnel, "tank").get(restored)) == 0, "Original funnel NBT behavior changed");
        int funnelCount = funnelCraft(collector);
        require(funnelCount == 2, "Original U-shaped iron funnel recipe did not yield two funnels: " + funnelCount);
        return "372 K does not boil; 373 K source disappears and offers 500/1000 mB at 1/2 blocks above; 16000 mB each; " +
               "gas-only bottom fill/top drain advice; upward push supplies turbine 1500 mB; normal original NBT reload loses stored gas; U-shaped iron craft yields two";
    }

    private static int funnelCraft(Object block) throws Exception {
        Class<?> stack = mc("item.ItemStack"), gridType = mc("inventory.InventoryCrafting"), manager = mc("item.crafting.CraftingManager");
        Object grid = gridType.getConstructor(mc("inventory.Container"), int.class, int.class).newInstance(null, 3, 3);
        Object[] gridSlots = (Object[]) field(gridType, "field_70466_a").get(grid);
        for (int i : new int[]{1, 3, 5, 6, 8}) gridSlots[i] = stack.getConstructor(int.class, int.class, int.class).newInstance(265, 1, 0);
        Object result = manager.getMethod("func_82787_a", gridType, worldType).invoke(manager.getMethod("func_77594_a").invoke(null), grid, world);
        if (result == null || stack.getField("field_77993_c").getInt(result) != mc("block.Block").getField("field_71990_ca").getInt(block)) return 0;
        return stack.getField("field_77994_a").getInt(result);
    }

    private static String assembler() throws Exception {
        // Keep the test isolated from all launchers, projectiles and detonation behavior.
        Class<?> type = Class.forName("icbm.explosion.missile.modular.TileMissileAssembler");
        Class<?> explosion = Class.forName("icbm.explosion.ICBMExplosion");
        Object assembler = place(explosion.getField("blockMissileAssembler").get(null), x + 6, y, z, 0);
        require(type.isInstance(assembler), "Missing original assembler tile");
        Method slots = type.getMethod("func_70302_i_");
        Method direction = type.getMethod("getDirection");
        Method rotation = type.getMethod("setRotation", byte.class);
        Method setTier = type.getMethod("setTier", int.class);
        Method tier = type.getMethod("getTier");
        require((Integer) slots.invoke(assembler) == 1 && type.getMethod("func_70301_a", int.class).invoke(assembler, 0) == null,
                "Expected one empty assembler slot");
        Class<?> nbt = mc("nbt.NBTTagCompound");
        StringBuilder facings = new StringBuilder();
        for (byte number = 0; number < 4; ++number) {
            rotation.invoke(assembler, number);
            setTier.invoke(assembler, 4 + number);
            require((Integer) tier.invoke(assembler) == number, "Unexpected assembler tier mask");
            Object saved = nbt.newInstance();
            type.getMethod("func_70310_b", nbt).invoke(assembler, saved);
            Object restored = type.newInstance();
            type.getMethod("func_70307_a", nbt).invoke(restored, saved);
            require(direction.invoke(restored) == direction.invoke(assembler), "Empty assembler rotation not saved");
            if (number > 0) facings.append(',');
            facings.append(direction.invoke(assembler));
        }
        return "empty assembler slots=1; tiers modulo 4; rotations=" + facings;
    }

    private static String platform() throws Exception {
        Class<?> platformType = Class.forName("icbm.sentry.platform.TileTurretPlatform");
        Class<?> turretType = Class.forName("icbm.sentry.turret.block.TileTurret");
        Class<?> registry = Class.forName("icbm.sentry.turret.TurretRegistry");
        Class<?> direction = Class.forName("net.minecraftforge.common.ForgeDirection");
        Object platform = platformType.newInstance();
        Method receive = platformType.getMethod("onReceiveEnergy", direction, long.class, boolean.class);
        Method extract = platformType.getMethod("onExtractEnergy", direction, long.class, boolean.class);
        Object north = direction.getField("NORTH").get(null);
        Object south = direction.getField("SOUTH").get(null);
        require((Long) receive.invoke(platform, north, 100L, true) == 0L, "Empty platform accepted energy");
        @SuppressWarnings("unchecked") Map<String, Class<?>> types = (Map<String, Class<?>>) registry.getMethod("getSentryMap").invoke(null);
        require(!types.isEmpty(), "Sentry registry is empty");
        Class<?> turretClass = types.get(new java.util.TreeSet<String>(types.keySet()).first());
        Field turretField = field(turretType, "turret");
        Field energyField = field(turretClass, "energy");
        Object[] neighbors = (Object[]) field(platformType, "turrets").get(platform);
        for (int index : new int[]{2, 3}) {
            Object host = turretType.newInstance();
            turretField.set(host, turretClass.getConstructor(turretType).newInstance(host));
            require(energyField.get(turretField.get(host)) != null, "No neighbor storage");
            neighbors[index] = host;
        }
        Object first = energyField.get(turretField.get(neighbors[2]));
        Object second = energyField.get(turretField.get(neighbors[3]));
        Method capacity = first.getClass().getMethod("getEnergyCapacity");
        Method stored = first.getClass().getMethod("getEnergy");
        Method set = first.getClass().getMethod("setEnergy", long.class);
        long firstCapacity = (Long) capacity.invoke(first);
        set.invoke(first, firstCapacity - 5L);
        set.invoke(second, 0L);
        require((Long) receive.invoke(platform, south, 17L, false) == 17L && (Long) stored.invoke(first) == firstCapacity - 5L &&
                (Long) stored.invoke(second) == 0L, "Platform simulation mutated a neighbor");
        long accepted = (Long) receive.invoke(platform, south, 17L, true);
        require(accepted == 17L && (Long) stored.invoke(first) == firstCapacity && (Long) stored.invoke(second) == 12L,
                "Platform did not forward the remaining input after the first neighbor saturated");
        require((Long) extract.invoke(platform, north, 17L, true) == 0L, "Platform extracted power");
        return "Sentry platform forwards first 5 then 12, simulates without mutation, no extraction; slots=" +
                platformType.getMethod("func_70302_i_").invoke(platform) + "; registry=" + new java.util.TreeSet<String>(types.keySet());
    }

    private static String platformInventory() throws Exception {
        Class<?> sentry = Class.forName("icbm.sentry.ICBMSentry");
        Object block = sentry.getField("blockPlatform").get(null);
        int px = x + 12;
        Object platform = place(block, px, y, z, 0);
        Object restored = place(block, px + 2, y, z, 0);
        Class<?> tile = platform.getClass(), stack = mc("item.ItemStack");
        int slots = (Integer) tile.getMethod("func_70302_i_").invoke(platform);
        int stackLimit = (Integer) tile.getMethod("func_70297_j_").invoke(platform);
        require(slots == 20 && stackLimit == 64, "Unexpected platform inventory dimensions");
        Method available = tile.getMethod("func_94128_d", int.class);
        Method insertable = tile.getMethod("func_102007_a", int.class, stack, int.class);
        Method extractable = tile.getMethod("func_102008_b", int.class, stack, int.class);
        // Use only inert vanilla blocks; the test never loads or ticks any armament.
        Object payload = stack.getConstructor(int.class, int.class, int.class).newInstance(4, 37, 0);
        for (int side = 0; side < 6; ++side) {
            int[] seen = (int[]) available.invoke(platform, side);
            require(seen.length == slots, "Platform does not expose all slots to side " + side);
            for (int slot = 0; slot < slots; ++slot) {
                require(seen[slot] == slot && !(Boolean) insertable.invoke(platform, slot, payload, side) &&
                        (Boolean) extractable.invoke(platform, slot, payload, side), "Unexpected sided storage rule at " + side + "/" + slot);
            }
        }
        Method set = tile.getMethod("func_70299_a", int.class, stack);
        set.invoke(platform, 19, payload);
        Class<?> nbt = mc("nbt.NBTTagCompound");
        Object saved = nbt.newInstance();
        tile.getMethod("func_70310_b", nbt).invoke(platform, saved);
        tile.getMethod("func_70307_a", nbt).invoke(restored, saved);
        Object result = tile.getMethod("func_70301_a", int.class).invoke(restored, 19);
        require(result != null && stack.getField("field_77993_c").getInt(result) == 4 &&
                stack.getField("field_77994_a").getInt(result) == 37, "Platform lost inert inventory on NBT reload");
        Class<?> blockType = mc("block.Block");
        float hardness = (Float) blockType.getMethod("func_71934_m", worldType, int.class, int.class, int.class).invoke(block, world, px, y, z);
        float blast = (Float) blockType.getMethod("getExplosionResistance", mc("entity.Entity"), worldType, int.class, int.class, int.class,
                double.class, double.class, double.class).invoke(block, null, world, px, y, z, 0D, 0D, 0D);
        return "Sentry placed inventory=" + slots + "×" + stackLimit + "; extraction only on all six sides; saved cobblestone x37 at slot 19; hardness=" + hardness +
                "; resistance=" + blast;
    }

    private static Object place(Object block, int px, int py, int pz, int meta) throws Exception {
        int id = mc("block.Block").getField("field_71990_ca").getInt(block);
        require((Boolean) worldType.getMethod("func_72832_d", int.class, int.class, int.class, int.class, int.class, int.class)
                .invoke(world, px, py, pz, id, meta, 3), "Could not place original block " + id);
        return worldType.getMethod("func_72796_p", int.class, int.class, int.class).invoke(world, px, py, pz);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field field = current.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static int coord(Object pos, String field) throws Exception { return pos.getClass().getField(field).getInt(pos); }
    private static Class<?> mc(String name) throws Exception { return Class.forName("net.minecraft." + name); }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
