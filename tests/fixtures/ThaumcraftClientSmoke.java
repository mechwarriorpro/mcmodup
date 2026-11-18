package mcmodup.thaumcraftcheck;

import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Runs on a real client with a fresh integrated server and unmodified staged JARs in mods/. */
@Mod("mcmodup_thaumcraft_client")
public final class ThaumcraftClientSmoke {
    private static final String[] MODS = {"thaumcraft", "architectury", "thaumictinkerer"};
    private static final Map<String, String> WARP = Map.of(
            "blurred_vision", "warp_blurred", "death_gaze", "warp_death_gaze",
            "sun_scorned", "warp_sun_scorned", "unnatural_hunger", "warp_hunger");
    private static final ResourceLocation BOOK = ResourceLocation.parse("thaumcraft:thaumonomicon");
    private static final ResourceLocation ENCHANTER = ResourceLocation.parse("thaumictinkerer:enchanter");
    private static final ResourceLocation PORTAL = ResourceLocation.parse("thaumictinkerer:bedrock_portal");
    private static final ResourceKey<Level> BEDROCK = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("thaumictinkerer:bedrock"));
    private static final String BROWSER = "thaumcraft.client.gui.GuiResearchBrowser";
    private static final String ENCHANTING = "thaumic.tinkerer.client.gui.GuiEnchanting";
    private static final int ACTIVE_FRAMES = 5;
    private final boolean paired = Boolean.getBoolean("mcmodup.withTinkerer");
    private Stage stage = Stage.START;
    private int ticks, frames;
    private CompletableFuture<?> operation;
    private BlockPos enchanterPosition;
    private Map<String, PostChain> initialChains;

    private enum Stage { START, JOIN, BOOK, BROWSER, EFFECTS, RELOADED, CLEARED, TABLE, TABLE_GUI, PORTAL, DONE }

    public ThaumcraftClientSmoke() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::afterFrame);
    }

    private void tick(ClientTickEvent.Post event) {
        if (stage == Stage.DONE) return;
        var client = Minecraft.getInstance();
        try {
            require(++ticks <= 2400, "Stage timed out: " + stage);
            client.options.pauseOnLostFocus = false;
            if (stage == Stage.START && client.getOverlay() == null) {
                if (client.screen instanceof AccessibilityOnboardingScreen onboarding) onboarding.onClose();
                else if (client.screen instanceof TitleScreen) {
                    checkStagedJars(client);
                    client.options.tutorialStep = TutorialSteps.NONE;
                    client.options.renderDistance().set(3);
                    client.options.simulationDistance().set(5);
                    client.options.maxFps().set(30);
                    client.createWorldOpenFlows().createFreshLevel("mcmodup-runtime-check",
                            new LevelSettings("Native staged JAR client check", GameType.CREATIVE, false,
                                    Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                            new WorldOptions(20261118L, false, false),
                            access -> access.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),
                            client.screen);
                    advance(Stage.JOIN);
                }
                return;
            }
            if (client.player == null || client.level == null || client.gameMode == null || client.getOverlay() != null) return;
            if (operation != null) {
                if (!operation.isDone()) return;
                operation.join();
                operation = null;
            }
            switch (stage) {
                case JOIN -> {
                    if (client.screen != null) return;
                    require(client.hasSingleplayerServer(), "No integrated server for the newly created world");
                    require(BuiltInRegistries.ITEM.containsKey(BOOK), "Missing Thaumonomicon registration");
                    server(client, actor -> actor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BuiltInRegistries.ITEM.get(BOOK))));
                    advance(Stage.BOOK);
                }
                case BOOK -> {
                    if (!client.player.getMainHandItem().is(BuiltInRegistries.ITEM.get(BOOK))) return;
                    // Same player action and network path as right-clicking the book in a world.
                    client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                    advance(Stage.BROWSER);
                }
                case BROWSER -> {
                    if (!screenIs(client, BROWSER) || frames < ACTIVE_FRAMES) return;
                    client.screen.onClose();
                    require(client.screen == null, "The Thaumonomicon didn't close into the game");
                    System.out.println("MCMD_CLIENT_BOOK_RENDERED");
                    server(client, actor -> WARP.keySet().forEach(path -> {
                        var id = ResourceLocation.fromNamespaceAndPath("thaumcraft", path);
                        var effect = BuiltInRegistries.MOB_EFFECT.getHolder(id).orElseThrow();
                        require(actor.addEffect(new MobEffectInstance(effect, 2400)), "Unable to apply " + id);
                    }));
                    advance(Stage.EFFECTS);
                }
                case EFFECTS -> {
                    if (frames < ACTIVE_FRAMES) return;
                    initialChains = Map.copyOf(activePostChains());
                    operation = client.reloadResourcePacks();
                    advance(Stage.RELOADED);
                }
                case RELOADED -> {
                    if (frames < ACTIVE_FRAMES) return;
                    var chains = activePostChains();
                    require(WARP.values().stream().allMatch(name -> chains.get(name) != initialChains.get(name)),
                            "Resource reload did not recreate all four Warp post chains");
                    server(client, ServerPlayer::removeAllEffects);
                    advance(Stage.CLEARED);
                }
                case CLEARED -> {
                    if (frames < ACTIVE_FRAMES) return;
                    System.out.println("MCMD_CLIENT_WARP_RENDERED_RELOADED_CLEARED");
                    if (!paired) { finish(client, null); return; }
                    require(BuiltInRegistries.BLOCK.containsKey(ENCHANTER), "Missing Osmotic Enchanter registration");
                    enchanterPosition = client.player.blockPosition().east(2);
                    server(client, actor -> {
                        actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        require(actor.serverLevel().setBlockAndUpdate(enchanterPosition, BuiltInRegistries.BLOCK.get(ENCHANTER).defaultBlockState()),
                                "Could not place the Osmotic Enchanter");
                    });
                    advance(Stage.TABLE);
                }
                case TABLE -> {
                    if (!client.player.getMainHandItem().isEmpty() || client.level.getBlockEntity(enchanterPosition) == null) return;
                    var hit = new BlockHitResult(Vec3.atCenterOf(enchanterPosition).add(0, .25, 0), Direction.UP, enchanterPosition, false);
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
                    advance(Stage.TABLE_GUI);
                }
                case TABLE_GUI -> {
                    if (!screenIs(client, ENCHANTING) || frames < ACTIVE_FRAMES) return;
                    client.screen.onClose();
                    System.out.println("MCMD_CLIENT_TINKERER_RENDERED");
                    require(BuiltInRegistries.BLOCK.containsKey(PORTAL), "Missing Bedrock portal registration");
                    server(client, actor -> {
                        require(actor.serverLevel().getServer().getLevel(BEDROCK) != null, "Missing Bedrock dimension in fresh world");
                        require(actor.serverLevel().setBlockAndUpdate(actor.blockPosition(), BuiltInRegistries.BLOCK.get(PORTAL).defaultBlockState()),
                                "Could not place a Bedrock portal at the player's feet");
                    });
                    advance(Stage.PORTAL);
                }
                case PORTAL -> {
                    if (frames < ACTIVE_FRAMES) return;
                    var effects = client.level.effects();
                    var expected = new Vec3(160 / 255F * .15F, 128 / 255F * .15F, 160 / 255F * .15F);
                    var night = effects.getBrightnessDependentFogColor(Vec3.ZERO, 0);
                    var day = effects.getBrightnessDependentFogColor(new Vec3(1, 1, 1), 1);
                    require(night.distanceTo(expected) < 1e-7 && day.distanceTo(expected) < 1e-7,
                            "Legacy Bedrock base fog must keep its original colour in daylight and at night");
                    require(effects.getCloudHeight() == 0, "Legacy Bedrock cloud height must be zero");
                    System.out.println("MCMD_CLIENT_BEDROCK_PORTAL_FOG_CLOUDS");
                    finish(client, null);
                }
                default -> { }
            }
        } catch (Throwable error) { finish(client, error); }
    }

    private void afterFrame(RenderFrameEvent.Post event) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.getOverlay() != null || operation != null) return;
        try {
            var counting = switch (stage) {
                case BROWSER -> screenIs(client, BROWSER);
                case EFFECTS, RELOADED -> activePostChains().keySet().containsAll(WARP.values());
                case CLEARED -> activePostChains().isEmpty();
                case TABLE_GUI -> screenIs(client, ENCHANTING);
                case PORTAL -> client.level != null && client.level.dimension().equals(BEDROCK) && client.screen == null;
                default -> false;
            };
            frames = counting ? frames + 1 : 0;
        } catch (Throwable error) { finish(client, error); }
    }

    private static boolean screenIs(Minecraft client, String className) {
        return client.screen != null && client.screen.getClass().getName().equals(className);
    }

    // The pinned implementation removes any chain from this map when process() fails. Observe it after
    // real frames, never call the private renderer or the effect implementation from the fixture.
    @SuppressWarnings("unchecked")
    private static Map<String, PostChain> activePostChains() throws ReflectiveOperationException {
        var field = Class.forName("thaumcraft.client.ThaumcraftWarpClient").getDeclaredField("CHAINS");
        field.setAccessible(true);
        return (Map<String, PostChain>) field.get(null);
    }

    private void checkStagedJars(Minecraft client) throws Exception {
        var modsDir = client.gameDirectory.toPath().resolve("mods").toRealPath();
        for (var mod : MODS) {
            var file = ModList.get().getModFileById(mod);
            if (mod.equals("thaumictinkerer") && !paired) {
                require(file == null, "Unexpected Tinkerer installation");
                continue;
            }
            require(file != null, "Missing mod " + mod);
            Path jar = file.getFile().getFilePath().toRealPath();
            require(Files.isRegularFile(jar) && jar.getParent().equals(modsDir), "Not a staged production JAR: " + jar);
        }
        require(!ModList.get().isLoaded("legacyconnector"), "Unexpected Legacy Connector installation");
    }

    private void server(Minecraft client, Consumer<ServerPlayer> work) {
        var server = client.getSingleplayerServer();
        var uuid = client.player.getUUID();
        operation = server.submit(() -> {
            var player = server.getPlayerList().getPlayer(uuid);
            require(player != null, "Integrated server has no player");
            work.accept(player);
        });
    }

    private void advance(Stage next) { stage = next; ticks = frames = 0; }

    private void finish(Minecraft client, Throwable error) {
        if (error != null) LogUtils.getLogger().error("MCMD_CLIENT_FAILED at " + stage, error);
        else System.out.println("MCMD_CLIENT_PASSED");
        stage = Stage.DONE;
        client.stop();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
