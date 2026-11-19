package @PACKAGE@;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Release-local .394 machine settings. Legacy RF amounts are used as FE. */
final class PowerConfig394 {
    private static final int MAX_STORAGE = 1_000_000;
    private static final int MAX_MULTIPLIER = 1_000;
    static final int DEFAULT_SYNTHESIS_MULTIPLIER = 10;
    private static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue ENABLED;
    private static final ModConfigSpec.IntValue DECOMPOSER_COST, SYNTHESIS_MULTIPLIER, FISSION_MULTIPLIER, FUSION_MULTIPLIER;
    private static final ModConfigSpec.IntValue PACKET, DECOMPOSER_STORAGE, SYNTHESIZER_STORAGE, FISSION_STORAGE, FUSION_STORAGE;

    static {
        var b = new ModConfigSpec.Builder();
        ENABLED = b.comment("Require payment for .394 machines. Fusion always needs more than its configured cost in storage, even when payment is disabled.")
            .worldRestart().define("enable", true);
        DECOMPOSER_COST = b.comment("FE paid when an item or fluid batch enters the decomposition buffer.")
            .worldRestart().defineInRange("costDecomposition", 1_000, 0, MAX_STORAGE);
        SYNTHESIS_MULTIPLIER = b.comment("Scale the base synthesis costs. The original key was spelled costSythesisMultiplier.")
            .worldRestart().defineInRange("costSythesisMultiplier", DEFAULT_SYNTHESIS_MULTIPLIER, 0, MAX_MULTIPLIER);
        FISSION_MULTIPLIER = b.worldRestart().defineInRange("costFissionMultiplier", 100, 0, MAX_MULTIPLIER);
        FUSION_MULTIPLIER = b.worldRestart().defineInRange("costFusionMultiplier", 100, 0, MAX_MULTIPLIER);
        PACKET = b.comment("Maximum FE accepted per call. Also the capacity of an unlinked reactor wall.")
            .worldRestart().defineInRange("energyPacketSize", 100, 0, MAX_STORAGE);
        DECOMPOSER_STORAGE = b.worldRestart().defineInRange("maxDecomposerStorage", 10_000, 1, MAX_STORAGE);
        SYNTHESIZER_STORAGE = b.worldRestart().defineInRange("maxSynthesizerStorage", 100_000, 1, MAX_STORAGE);
        FISSION_STORAGE = b.worldRestart().defineInRange("maxFissionStorage", 100_000, 1, MAX_STORAGE);
        FUSION_STORAGE = b.worldRestart().defineInRange("maxFusionStorage", 100_000, 1, MAX_STORAGE);
        SPEC = b.build();
    }

    private PowerConfig394() {}
    static void register(ModContainer mod) { mod.registerConfig(ModConfig.Type.SERVER, SPEC, CatalogPort.MOD_ID + "-power.toml"); }
    static boolean enabled() { return ENABLED.get(); }
    static int payment(int nominal) { return enabled() ? nominal : 0; }
    static int decompositionCost() { return payment(DECOMPOSER_COST.get()); }
    static int synthesisCost(int defaultCost) {
        // All pinned registry rows are exact multiples of the original default multiplier.
        return payment((defaultCost / DEFAULT_SYNTHESIS_MULTIPLIER) * SYNTHESIS_MULTIPLIER.get());
    }
    static int fissionCost(int atomic) { return payment(atomic * FISSION_MULTIPLIER.get()); }
    static int fusionGate(int combined) { return combined * FUSION_MULTIPLIER.get(); }
    static int packet() { return PACKET.get(); }
    static int decomposerStorage() { return DECOMPOSER_STORAGE.get(); }
    static int synthesizerStorage() { return SYNTHESIZER_STORAGE.get(); }
    static int fissionStorage() { return FISSION_STORAGE.get(); }
    static int fusionStorage() { return FUSION_STORAGE.get(); }
}
