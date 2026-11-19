package @PACKAGE@;

import com.mojang.serialization.MapCodec;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** FINAL's OreDictionary recipe was contingent on a startup setting. */
final class UraniumOreCraft implements ICondition {
    private static final MapCodec<UraniumOreCraft> CODEC = MapCodec.unit(new UraniumOreCraft());
    private static final ModConfigSpec CONFIG;
    private static final ModConfigSpec.BooleanValue CRAFTABLE;

    static {
        var builder = new ModConfigSpec.Builder();
        CRAFTABLE = builder.comment("Allow conversion from the uranium ore item tag to FINAL Minechem uranium ore.")
            .define("uraniumOreCraftable", @CRAFTABLE@);
        CONFIG = builder.build();
    }

    static void register(IEventBus bus, ModContainer mod) {
        var conditions = DeferredRegister.create(NeoForgeRegistries.Keys.CONDITION_CODECS, CatalogPort.MOD_ID);
        conditions.register("uranium_ore_craftable", () -> CODEC);
        conditions.register(bus);
        // This only controls server recipe decoding, never the registry or the client handshake.
        mod.registerConfig(ModConfig.Type.STARTUP, CONFIG, CatalogPort.MOD_ID + "-crafting.toml");
    }

    @Override public boolean test(IContext context) { return CRAFTABLE.get(); }
    @Override public MapCodec<? extends ICondition> codec() { return CODEC; }
}
