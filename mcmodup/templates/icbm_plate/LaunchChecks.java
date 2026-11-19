package @PACKAGE@;

import mcmodup_kernel.icbm_glass_plate.LaunchSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("@TEST_NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class LaunchChecks {
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("icbm_glass_plate", path); }

    @GameTest(template = "launch_room", timeoutTicks = 60)
    public static void savedLauncherAndSentry(GameTestHelper test) {
        var world = test.getLevel();
        var base = test.absolutePos(new BlockPos(2, 2, 3));
        var screen = base.north();
        var frame = base.south();
        var target = base.east(12);
        var turret = base.offset(4, 0, 3);
        var platform = turret.below();
        put(test, base, "launcher_base");
        put(test, screen, "launcher_screen");
        put(test, turret, "anti_air_turret");
        put(test, platform, "sentry_platform");
        world.setBlockAndUpdate(target, Blocks.GLASS.defaultBlockState());
        var launcher = (LaunchSystem.LauncherTile) world.getBlockEntity(screen);
        var ammo = world.getCapability(Capabilities.ItemHandler.BLOCK, base, Direction.UP);
        var power = world.getCapability(Capabilities.EnergyStorage.BLOCK, screen, Direction.NORTH);
        var rounds = world.getCapability(Capabilities.ItemHandler.BLOCK, turret, Direction.UP);
        var turretPower = world.getCapability(Capabilities.EnergyStorage.BLOCK, turret, Direction.DOWN);
        var bus = world.getCapability(Capabilities.EnergyStorage.BLOCK, platform, Direction.DOWN);
        var missile = new ItemStack(BuiltInRegistries.ITEM.get(id("training_missile")));
        test.assertTrue(ammo != null && power != null && rounds != null && turretPower != null && bus != null, "Missing launcher or Sentry capability");
        test.assertTrue(ammo.insertItem(0, missile, false).isEmpty(), "Base did not accept one missile");
        launcher.setTarget(target);
        test.assertTrue(!launcher.launch(world), "Uncharged launcher fired");
        for (int i = 0; i < 50; i++) test.assertTrue(power.receiveEnergy(10_000_000, false) == 10_000_000, "Unexpected screen FE limit");
        test.assertTrue(!launcher.launch(world), "No-frame preview launcher fired");
        put(test, frame, "launcher_frame");
        launcher.setTarget(base.east(9));
        test.assertTrue(!launcher.launch(world) && power.getEnergyStored() == LaunchSystem.LAUNCH_FE && !ammo.getStackInSlot(0).isEmpty(), "Too-close target spent the missile or charge");
        launcher.setTarget(target);
        var lookup = world.registryAccess();
        launcher.loadWithComponents(launcher.saveWithFullMetadata(lookup), lookup);
        world.setBlockAndUpdate(screen.north(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        LaunchSystem.LauncherTile.tick(world, screen, world.getBlockState(screen), launcher);
        var missiles = world.getEntitiesOfClass(LaunchSystem.Missile.class, new AABB(base).inflate(15));
        test.assertTrue(missiles.size() == 1 && ammo.getStackInSlot(0).isEmpty() && power.getEnergyStored() == 0, "A valid pulse did not launch exactly once and spend the resources");
        var flight = missiles.getFirst();
        for (int i = 0; i < 8; i++) flight.tick();
        test.assertTrue(flight.getTicksInAir() == 8 && flight.getX() > base.getX() + 1 && flight.getY() > base.getY() + 2, "Staged missile did not fly toward its target");
        test.assertTrue(rounds.insertItem(0, new ItemStack(Items.IRON_NUGGET, 2), false).isEmpty(), "Sentry rejected ammo");
        test.assertTrue(bus.receiveEnergy(LaunchSystem.AA_SHOT_FE, true) == LaunchSystem.AA_SHOT_FE && turretPower.getEnergyStored() == 0,
                "Simulated platform transfer charged the turret");
        test.assertTrue(bus.receiveEnergy(LaunchSystem.AA_SHOT_FE, false) == LaunchSystem.AA_SHOT_FE && bus.getEnergyStored() == 0,
                "Sentry platform did not forward power into the real turret");
        var sentry = (LaunchSystem.LauncherTile) world.getBlockEntity(turret);
        LaunchSystem.LauncherTile.tick(world, turret, world.getBlockState(turret), sentry);
        test.assertTrue(flight.isRemoved() && turretPower.getEnergyStored() == 0 && rounds.getStackInSlot(0).getCount() == 1,
                "Active Sentry did not spend one charge and one round to intercept");
        test.assertTrue(world.getBlockState(target).is(Blocks.GLASS), "Training missile destroyed its marker");
        test.succeed();
    }

    @GameTest(template = "launch_room", timeoutTicks = 60)
    public static void launcherTiersAndDefense(GameTestHelper test) {
        var world = test.getLevel();
        var origin = test.absolutePos(new BlockPos(3, 2, 3));
        int[] capacity = {500_000_000, 800_000_000, 1_000_000_000};
        int[] distance = {1_000, 2_000, 10_000};
        for (int i = 0; i < capacity.length; i++) {
            var pos = origin.east(i * 2);
            var suffix = i == 0 ? "" : "_mk" + (i + 1);
            put(test, pos, "launcher_screen" + suffix);
            var power = world.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.UP);
            test.assertTrue(power != null && power.getMaxEnergyStored() == capacity[i], "Wrong screen capacity " + suffix);
            test.assertTrue(!LaunchSystem.LauncherTile.inRange(pos, pos.east(9), i) &&
                    LaunchSystem.LauncherTile.inRange(pos, pos.east(distance[i] - 1).above(23), i) &&
                    !LaunchSystem.LauncherTile.inRange(pos, pos.east(distance[i]), i), "Wrong horizontal range " + suffix);
        }
        var gunPos = origin.south(4);
        put(test, gunPos, "gun_turret");
        var gun = (LaunchSystem.LauncherTile) world.getBlockEntity(gunPos);
        var power = world.getCapability(Capabilities.EnergyStorage.BLOCK, gunPos, Direction.DOWN);
        var ammo = world.getCapability(Capabilities.ItemHandler.BLOCK, gunPos, Direction.UP);
        test.assertTrue(ammo != null && power != null && ammo.insertItem(0, new ItemStack(Items.IRON_NUGGET, 2), false).isEmpty(), "Missing gun inputs");
        power.receiveEnergy(1_200, false);
        var cow = test.spawn(EntityType.COW, new BlockPos(4, 2, 7));
        var zombie = test.spawn(EntityType.ZOMBIE, new BlockPos(7, 2, 7));
        float start = zombie.getHealth();
        LaunchSystem.LauncherTile.tick(world, gunPos, world.getBlockState(gunPos), gun);
        LaunchSystem.LauncherTile.tick(world, gunPos, world.getBlockState(gunPos), gun);
        test.assertTrue(zombie.getHealth() < start && cow.getHealth() == cow.getMaxHealth() && power.getEnergyStored() == 600 &&
                ammo.getStackInSlot(0).getCount() == 1, "Gun did not filter hostile mobs or obey its paid cooldown");
        var laserPos = origin.south(7);
        put(test, laserPos, "laser_turret");
        var laser = (LaunchSystem.LauncherTile) world.getBlockEntity(laserPos);
        var supply = world.getCapability(Capabilities.EnergyStorage.BLOCK, laserPos, Direction.UP);
        test.assertTrue(supply != null && world.getCapability(Capabilities.ItemHandler.BLOCK, laserPos, Direction.UP) == null, "Laser should not expose an ammo slot");
        supply.receiveEnergy(8_000, false);
        float remaining = zombie.getHealth();
        // Only the gun used the target this tick; reset invulnerability to exercise the laser separately.
        zombie.invulnerableTime = 0;
        LaunchSystem.LauncherTile.tick(world, laserPos, world.getBlockState(laserPos), laser);
        test.assertTrue(zombie.getHealth() < remaining && supply.getEnergyStored() == 0, "Laser did not spend FE on its hostile target");
        test.succeed();
    }

    private static void put(GameTestHelper test, BlockPos pos, String name) {
        test.getLevel().setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.get(id(name)).defaultBlockState());
    }
}
