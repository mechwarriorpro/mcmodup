package mcmodup_kernel.icbm_glass_plate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** Typed entry points used by the two imported platform methods from the exact Voltz JAR. */
public final class SentryBridge {
    private static final Direction[] ORDER = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    static int forward(ServerLevel level, BlockPos platform, int amount, boolean simulate) {
        Receiver[] adjacent = new Receiver[ORDER.length];
        for (int i = 0; i < ORDER.length; ++i) {
            Direction outward = ORDER[i];
            var pos = platform.relative(outward);
            if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos)) continue;
            var sink = level.getCapability(SentryPlatform.RECEIVER, pos, outward.getOpposite());
            if (sink != null && sink.canReceive()) adjacent[i] = new Receiver(new Consumer(new Power(sink)));
        }
        // The original method ignores the caller-side parameter. Forge's execute flag is inverse to NeoForge's simulate.
        return (int) SentryPlatformLogic.forward(new Access(adjacent), null, amount, !simulate);
    }

    static int extract(int amount, boolean simulate) {
        return (int) SentryPlatformLogic.extract(null, null, amount, !simulate);
    }

    public record Access(Receiver[] adjacent) {
        public static Receiver[] receivers(Access access) { return access.adjacent(); }
    }

    public record Receiver(Consumer consumer) {
        public static Consumer unwrap(Receiver receiver) { return receiver.consumer(); }
    }

    public record Consumer(Power power) {
        public static Power storage(Consumer consumer) { return consumer.power(); }
    }

    public record Power(IEnergyStorage sink) {
        public static long receive(Power adapter, long amount, boolean execute) {
            if (amount <= 0) return 0;
            int offer = (int) Math.min(amount, Integer.MAX_VALUE);
            return Math.clamp(adapter.sink().receiveEnergy(offer, !execute), 0, offer);
        }
    }

    public static final class Side {
        private Side() {}
    }

    private SentryBridge() {}
}
