package mcmodup.geoclient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.NeoForge;

@Mod("mcmodup_geo_client")
public final class GeoConnectedClientSmoke {
    private static final String NAMESPACE = "geostrata_rocks";
    private static final RandomSource RANDOM = RandomSource.create(0);
    private static final String PREFIX = "block/connected/";
    private static final Set<String> ISOLATED = Set.of("1", "2", "3", "4", "5", "6", "7", "8", "9");
    private Stage stage = Stage.TITLE;
    private BlockPos center;
    private int age;
    private CompletableFuture<Void> reload;
    private enum Stage { TITLE, JOIN, ISOLATED, JOINED, CHANGE, RELOAD, DONE }

    public GeoConnectedClientSmoke() { NeoForge.EVENT_BUS.addListener(this::tick); }

    private void tick(ClientTickEvent.Post event) {
        if (stage == Stage.DONE) return;
        var client = Minecraft.getInstance();
        try {
            if (++age > 1200) throw new AssertionError("Client check timed out in " + stage);
            if (client.getOverlay() != null) return;
            switch (stage) {
                case TITLE -> {
                    if (client.screen instanceof AccessibilityOnboardingScreen screen) screen.onClose();
                    else if (client.screen instanceof TitleScreen) {
                        require(Files.isRegularFile(client.gameDirectory.toPath().resolve("mods").resolve(System.getProperty("mcmodup.geoJar"))),
                            "Preview was not installed in mods/");
                        client.options.pauseOnLostFocus = false;
                        client.options.renderDistance().set(3);
                        client.options.simulationDistance().set(5);
                        client.createWorldOpenFlows().createFreshLevel("geo-connected-check",
                            new LevelSettings("GeoStrata model check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                                new GameRules(), WorldDataConfiguration.DEFAULT),
                            new WorldOptions(23L, false, false),
                            access -> access.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),
                            client.screen);
                        advance(Stage.JOIN);
                    }
                }
                case JOIN -> {
                    if (client.level == null || client.player == null || client.getSingleplayerServer() == null) return;
                    center = client.player.blockPosition().above(3);
                    place(client, false, false);
                    advance(Stage.ISOLATED);
                }
                case ISOLATED -> {
                    if (!matches(client, center, "granite_connected")) return;
                    checkBlock(client, ISOLATED);
                    checkItem(client);
                    place(client, true, false);
                    advance(Stage.JOINED);
                }
                case JOINED -> {
                    if (!matches(client, center.south(), "granite_brick") || !matches(client, center.east(), "granite") ||
                        !matches(client, center.south().east(), "granite_connected")) return;
                    checkBlock(client, Set.of("3", "5", "6", "7", "8", "9"));
                    place(client, true, true);
                    advance(Stage.CHANGE);
                }
                case CHANGE -> {
                    if (!matches(client, center.south().east(), "marble_connected")) return;
                    checkBlock(client, Set.of("1", "3", "5", "6", "7", "8", "9"));
                    reload = client.reloadResourcePacks();
                    advance(Stage.RELOAD);
                }
                case RELOAD -> {
                    if (!reload.isDone()) return;
                    reload.join();
                    checkBlock(client, Set.of("1", "3", "5", "6", "7", "8", "9"));
                    checkItem(client);
                    checkModel(client, NAMESPACE, "cave_crystal_cyan");
                    checkModel(client, NAMESPACE, "crystal_lamp_red");
                    if (Boolean.getBoolean("mcmodup.testbedClient")) {
                        checkModel(client, "icbm_glass_plate", "launcher_screen_mk3");
                        checkModel(client, "icbm_glass_plate", "laser_turret");
                        checkModel(client, "atomic_thermometer", "particle_accelerator");
                        checkModel(client, "atomic_thermometer", "creative_fe_supply");
                        checkModel(client, "minechem_chemistry", "decomposer");
                        checkModel(client, "minechem_chemistry_394", "synthesizer");
                    }
                    Files.writeString(Path.of(System.getProperty("mcmodup.geoEvidence")),
                        "Actual staged client: isolated borders, same-rock smooth/brick neighbors, different-rock diagonal, inventory border, additional block models and resource reload verified.\n");
                    advance(Stage.DONE);
                    client.stop();
                }
                default -> { }
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            try { Files.writeString(Path.of(System.getProperty("mcmodup.geoEvidence")), "FAIL: " + failure); }
            catch (Exception ignored) { }
            advance(Stage.DONE);
            client.stop();
        }
    }

    private void advance(Stage next) { stage = next; age = 0; }

    private void place(Minecraft client, boolean joined, boolean different) {
        var server = client.getSingleplayerServer();
        var dimension = client.level.dimension();
        server.execute(() -> {
            var level = server.getLevel(dimension);
            level.setBlockAndUpdate(center, state("granite_connected"));
            level.setBlockAndUpdate(center.south(), joined ? state("granite_brick") : Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(center.east(), joined ? state("granite") : Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(center.south().east(), joined ? state(different ? "marble_connected" : "granite_connected") : Blocks.AIR.defaultBlockState());
        });
    }

    private static boolean matches(Minecraft client, BlockPos pos, String name) {
        return client.level.getBlockState(pos).is(state(name).getBlock());
    }

    private void checkBlock(Minecraft client, Set<String> expected) {
        var state = client.level.getBlockState(center);
        var model = client.getBlockRenderer().getBlockModel(state);
        var data = model.getModelData(client.level, center, state, ModelData.EMPTY);
        var layers = model.getRenderTypes(state, RANDOM, data);
        require(layers.contains(RenderType.solid()) && layers.contains(RenderType.translucent()), "Solid base and translucent border layers expected");
        var solid = model.getQuads(state, Direction.UP, RANDOM, data, RenderType.solid());
        require(solid.size() == 1 && sprite(solid.getFirst()).equals(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "block/granite")),
            "Connected block has no smooth granite base");
        var quads = model.getQuads(state, Direction.UP, RANDOM, data, RenderType.translucent());
        var actual = quads.stream().map(GeoConnectedClientSmoke::sprite).filter(id -> id.getNamespace().equals(NAMESPACE) && id.getPath().startsWith(PREFIX))
            .map(id -> id.getPath().substring(PREFIX.length())).collect(Collectors.toSet());
        require(actual.equals(expected) && quads.size() == expected.size(), "Wrong top overlays: " + actual + ", expected " + expected);
        require(model.getQuads(state, null, RANDOM, data, RenderType.translucent()).isEmpty(), "Overlays must be culled by face");
    }

    private void checkItem(Minecraft client) {
        var item = new ItemStack(state("granite_connected").getBlock());
        var model = client.getItemRenderer().getModel(item, client.level, null, 0);
        List<ResourceLocation> quads = model.getQuads(null, Direction.UP, RANDOM, ModelData.EMPTY, null).stream().map(GeoConnectedClientSmoke::sprite).toList();
        require(quads.contains(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "block/granite")) &&
            quads.contains(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "block/connected/0")), "Missing item border or granite base: " + quads);
    }

    private static void checkModel(Minecraft client, String namespace, String block) {
        var id = ResourceLocation.fromNamespaceAndPath(namespace, block);
        require(BuiltInRegistries.BLOCK.containsKey(id), "Missing client block: " + id);
        var state = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
        var model = client.getBlockRenderer().getBlockModel(state);
        require(model != client.getModelManager().getMissingModel() && !model.getParticleIcon(ModelData.EMPTY).contents().name().getPath().equals("missingno"),
                "Missing baked block model or sprite: " + id);
    }

    private static ResourceLocation sprite(BakedQuad quad) { return quad.getSprite().contents().name(); }
    private static BlockState state(String name) { return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(NAMESPACE, name)).defaultBlockState(); }
    private static void require(boolean value, String failure) { if (!value) throw new AssertionError(failure); }
}
