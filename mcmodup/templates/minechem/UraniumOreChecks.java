package @PACKAGE@;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("@TEST_NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class @CLASS@ {
    private static final ResourceLocation ORE = ResourceLocation.fromNamespaceAndPath("@NAMESPACE@", "uranium_ore");
    private static final long ORIGINAL_SEED = @SEED@L;
    private record Sample(String name, int cx, int cz, boolean enabled, int density, int cluster, boolean mask, String coords) {
        Set<BlockPos> expected() {
            var positions = new HashSet<BlockPos>();
            if (!coords.isEmpty()) for (String coord : coords.split(";")) {
                var parts = coord.split(",");
                positions.add(new BlockPos(cx * 16 + Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), cz * 16 + Integer.parseInt(parts[2])));
            }
            return positions;
        }
    }
    private static final Sample[] SAMPLES = {
@SAMPLES@
    };

    @GameTest(template = "test_room")
    public static void @PREFIX@UraniumVeins(GameTestHelper test) throws ReflectiveOperationException {
        var level = test.getLevel();
        var registries = level.registryAccess();
        var configured = registries.registryOrThrow(Registries.CONFIGURED_FEATURE).get(ORE);
        var placed = registries.registryOrThrow(Registries.PLACED_FEATURE).get(ORE);
        test.assertTrue(configured != null && placed != null && placed.placement().isEmpty() && placed.feature().value() == configured,
            "The uranium feature must own the entire chunk attempt stream");
        var biomes = registries.registryOrThrow(Registries.BIOME);
        for (var key : List.of(Biomes.PLAINS, Biomes.DESERT, Biomes.DEEP_DARK, Biomes.NETHER_WASTES, Biomes.THE_END)) {
            var steps = biomes.get(key.location()).getGenerationSettings().features();
            int ores = GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();
            boolean hooked = steps.size() > ores && steps.get(ores).stream().anyMatch(holder -> holder.value() == placed);
            test.assertTrue(hooked == !(key.equals(Biomes.NETHER_WASTES) || key.equals(Biomes.THE_END)), "Wrong uranium biome hook for " + key);
        }
        var ore = BuiltInRegistries.BLOCK.get(ORE).defaultBlockState();
        var feature = configured.feature().getClass();
        ModConfigSpec.ConfigValue<Boolean> enabled = option(feature, "GENERATE_ORE");
        ModConfigSpec.ConfigValue<Integer> density = option(feature, "DENSITY"), cluster = option(feature, "CLUSTER_SIZE");
        var defaults = SAMPLES[0];
        test.assertTrue(enabled.get() == defaults.enabled() && density.get() == defaults.density() && cluster.get() == defaults.cluster(),
            "Native uranium defaults differ from the pinned original");
        // .394's paired-world salt is deliberately absent in the original. Feed the inverse seed
        // for exact shape comparison, then confirm the paired world really uses a separate stream.
        boolean paired = @RELEASE_394@ && ModList.get().isLoaded("minechem_chemistry");
        long seed = paired ? ORIGINAL_SEED ^ 0x394L : ORIGINAL_SEED;
        var stone = Blocks.STONE.defaultBlockState();
        try {
            for (var sample : SAMPLES) {
                enabled.set(sample.enabled());
                density.set(sample.density());
                cluster.set(sample.cluster());
                var observed = run(test, level, configured, sample, seed, stone, ore);
                test.assertTrue(observed.equals(sample.expected()), "Original uranium placement mismatch for " + sample.name() + ": " + observed);
            }
            enabled.set(defaults.enabled());
            density.set(defaults.density());
            cluster.set(defaults.cluster());
            for (var rock : new BlockState[]{Blocks.GRANITE.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState()})
                test.assertTrue(run(test, level, configured, defaults, seed, rock, ore).isEmpty(), "Uranium replaced " + rock);
            for (var key : List.of(Level.NETHER, Level.END)) {
                var other = level.getServer().getLevel(key);
                test.assertTrue(other != null && run(test, other, configured, defaults, seed, stone, ore).isEmpty(),
                    "A non-surface dimension accepted the uranium feature: " + key.location());
            }
            if (paired) test.assertTrue(!run(test, level, configured, defaults, ORIGINAL_SEED, stone, ore).equals(defaults.expected()),
                "The paired .394 stream duplicated FINAL's placements");
        } finally {
            enabled.set(defaults.enabled());
            density.set(defaults.density());
            cluster.set(defaults.cluster());
        }
        test.succeed();
    }

    private static Set<BlockPos> run(GameTestHelper test, ServerLevel level, ConfiguredFeature<?, ?> feature,
                                     Sample sample, long seed, BlockState rock, BlockState ore) {
        // Only intercept the terrain and seed; use the packaged feature, registries and real dimension type.
        var changed = new HashSet<BlockPos>();
        var access = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getSeed": return seed;
                    case "ensureCanWrite": return true;
                    case "getBlockState": {
                        var pos = (BlockPos) args[0];
                        return changed.contains(pos) ? ore : base(pos, sample, rock);
                    }
                    case "setBlock": {
                        var pos = (BlockPos) args[0];
                        test.assertTrue(base(pos, sample, rock).is(Blocks.STONE) && args[1] == ore && changed.add(pos.immutable()),
                            "Invalid uranium write for " + sample.name() + " to " + pos);
                        return true;
                    }
                    default: return method.invoke(level, args);
                }
            });
        var origin = new BlockPos(sample.cx() * 16 + 3, level.getMinBuildHeight(), sample.cz() * 16 + 5);
        boolean placed = feature.place(access, level.getChunkSource().getGenerator(), RandomSource.create(-3), origin);
        test.assertTrue(placed == !changed.isEmpty(), "Wrong uranium feature result for " + sample.name());
        return changed;
    }

    private static BlockState base(BlockPos pos, Sample sample, BlockState rock) {
        if (!sample.mask()) return rock;
        int mask = (pos.getX() - 16 * sample.cx() + 3 * pos.getY() + 5 * (pos.getZ() - 16 * sample.cz())) & 7;
        return switch (mask) {
            case 0, 1 -> Blocks.DIAMOND_ORE.defaultBlockState();
            case 2 -> Blocks.DIRT.defaultBlockState();
            case 3 -> Blocks.AIR.defaultBlockState();
            case 4 -> Blocks.GRAVEL.defaultBlockState();
            default -> rock;
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> ModConfigSpec.ConfigValue<T> option(Class<?> feature, String name) throws ReflectiveOperationException {
        var field = feature.getDeclaredField(name);
        field.setAccessible(true);
        return (ModConfigSpec.ConfigValue<T>) field.get(null);
    }

}
