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
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
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
    private enum Stage { TITLE, JOIN, ISOLATED, JOINED, CHANGE, RELOAD, REACTOR, SYNTHESIS, PAID, DEPOSITED, RETRIEVED, DONE }

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
                        checkModel(client, "minechem_chemistry", "synthesizer");
                        checkModel(client, "minechem_chemistry_394", "synthesizer");
                        checkModel(client, "minechem_chemistry", "blueprint_projector");
                        checkModel(client, "minechem_chemistry_394", "ghost_coil");
                        checkModel(client, "minechem_chemistry_394", "fission_core");
                        openProjector(client);
                        advance(Stage.REACTOR);
                    } else finish(client, "");
                }
                case REACTOR -> {
                    if (!(client.screen instanceof AbstractContainerScreen<?> screen)) return;
                    var paper = ResourceLocation.fromNamespaceAndPath("minechem_chemistry_394", "fission_blueprint");
                    if (!screen.getMenu().getSlot(0).getItem().is(BuiltInRegistries.ITEM.get(paper))) return;
                    require(screen.getTitle().getString().equals("Blueprint Projector"), "Wrong client menu opened");
                    openFinalSynthesis(client);
                    advance(Stage.SYNTHESIS);
                }
                case SYNTHESIS -> {
                    var screen = synthesisScreen(client);
                    if (screen == null || !screen.getMenu().getSlot(0).getItem().is(Items.IRON_BLOCK)) return;
                    require(screen.getXSize() == 176 && screen.getYSize() == 187 && screen.getMenu().slots.size() == 56,
                        "Wrong FINAL client screen dimensions or slot layout");
                    require(client.getResourceManager().getResource(finalId("textures/gui/final_synthesis.png")).isPresent(),
                        "Missing imported FINAL GUI texture");
                    var ghost = screen.getMenu().getSlot(1).getItem();
                    if (!ghost.is(BuiltInRegistries.ITEM.get(finalId("element_fe"))) || screen.getMenu().getSlot(19).getItem().isEmpty()) return;
                    if (menuInt(screen, "ghostCount", 0) != 144 || menuInt(screen, "energy") != 66_536 || menuInt(screen, "status") != 2) return;
                    require(ghost.getCount() == 1 && menuInt(screen, "cost") == 1000,
                        "Large ghost was synced as real oversized storage, or cost was wrong");
                    click(screen, 0);
                    advance(Stage.PAID);
                }
                case PAID -> {
                    var screen = synthesisScreen(client);
                    if (screen == null || !screen.getMenu().getCarried().is(Items.IRON_BLOCK)) return;
                    var menu = screen.getMenu();
                    if (menu.getSlot(10).hasItem() || menu.getSlot(11).hasItem() || menu.getSlot(18).hasItem() ||
                        menuInt(screen, "energy") != 65_536 || menuInt(screen, "status") != 1) return;
                    require(menu.getCarried().getCount() == 1 && menuInt(screen, "ghostCount", 0) == 144, "Client received wrong amount or lost the ghost after paying");
                    click(screen, 20);
                    advance(Stage.DEPOSITED);
                }
                case DEPOSITED -> {
                    var screen = synthesisScreen(client);
                    if (screen == null || !screen.getMenu().getCarried().isEmpty() || !screen.getMenu().getSlot(20).getItem().is(Items.IRON_BLOCK)) return;
                    click(screen, 19);
                    advance(Stage.RETRIEVED);
                }
                case RETRIEVED -> {
                    var screen = synthesisScreen(client);
                    if (screen == null || !screen.getMenu().getCarried().is(BuiltInRegistries.ITEM.get(finalId("chemist_journal")))) return;
                    if (screen.getMenu().getSlot(0).hasItem() || screen.getMenu().getSlot(1).hasItem() || menuInt(screen, "ghostCount", 0) != 0) return;
                    finish(client, " The .394 projector synced its actual blueprint; FINAL's original-sized screen synced a 144-tube ghost and power, paid on a real screen click, and cleared on journal retrieval.");
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

    private void finish(Minecraft client, String extra) throws Exception {
        Files.writeString(Path.of(System.getProperty("mcmodup.geoEvidence")),
            "Actual staged client: isolated borders, same-rock smooth/brick neighbors, different-rock diagonal, inventory border, additional block models and resource reload verified." + extra + "\n");
        advance(Stage.DONE);
        client.stop();
    }

    private void openProjector(Minecraft client) {
        var server = client.getSingleplayerServer();
        var playerId = client.player.getUUID();
        var pos = client.player.blockPosition().above(2);
        var block = ResourceLocation.fromNamespaceAndPath("minechem_chemistry_394", "blueprint_projector");
        var paper = ResourceLocation.fromNamespaceAndPath("minechem_chemistry_394", "fission_blueprint");
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(playerId);
            var level = player.serverLevel();
            level.setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.get(block).defaultBlockState());
            if (level.getBlockEntity(pos) instanceof MenuProvider projector) {
                var menu = projector.createMenu(0, player.getInventory(), player);
                menu.getSlot(0).set(new ItemStack(BuiltInRegistries.ITEM.get(paper)));
                player.openMenu(projector);
            }
        });
    }

    private static ResourceLocation finalId(String path) { return ResourceLocation.fromNamespaceAndPath("minechem_chemistry", path); }

    private static AbstractContainerScreen<?> synthesisScreen(Minecraft client) {
        return client.screen instanceof AbstractContainerScreen<?> screen &&
            BuiltInRegistries.MENU.getKey(screen.getMenu().getType()).equals(finalId("synthesizer")) ? screen : null;
    }

    private static int menuInt(AbstractContainerScreen<?> screen, String name, int... arguments) throws Exception {
        var menu = screen.getMenu();
        var method = arguments.length == 0 ? menu.getClass().getDeclaredMethod(name) : menu.getClass().getDeclaredMethod(name, int.class);
        method.setAccessible(true);
        return (Integer) (arguments.length == 0 ? method.invoke(menu) : method.invoke(menu, arguments[0]));
    }

    private static void click(AbstractContainerScreen<?> screen, int index) {
        var slot = screen.getMenu().getSlot(index);
        int x = screen.getGuiLeft() + slot.x + 8;
        int y = screen.getGuiTop() + slot.y + 8;
        screen.mouseClicked(x, y, 0);
        screen.mouseReleased(x, y, 0);
    }

    private void openFinalSynthesis(Minecraft client) {
        var server = client.getSingleplayerServer();
        var playerId = client.player.getUUID();
        var pos = client.player.blockPosition().east(3).above(1);
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(playerId);
            var level = player.serverLevel();
            level.setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.get(finalId("synthesizer")).defaultBlockState());
            if (level.getBlockEntity(pos) instanceof MenuProvider synth) {
                var book = new ItemStack(BuiltInRegistries.ITEM.get(finalId("chemist_journal")));
                CustomData.update(DataComponents.CUSTOM_DATA, book, data -> {
                    var research = data.getCompound("ChemistJournal");
                    var known = new ListTag();
                    known.add(StringTag.valueOf("minecraft:iron_block"));
                    research.put("Discoveries", known);
                    research.putString("Active", "minecraft:iron_block");
                    data.put("ChemistJournal", research);
                });
                var menu = synth.createMenu(0, player.getInventory(), player);
                menu.getSlot(19).set(book);
                var iron = BuiltInRegistries.ITEM.get(finalId("element_fe"));
                var input = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
                input.insertItem(0, new ItemStack(iron, 64), false);
                input.insertItem(1, new ItemStack(iron, 64), false);
                input.insertItem(8, new ItemStack(iron, 16), false);
                var power = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.UP);
                int remaining = 66_536;
                while (remaining > 0) {
                    int accepted = power.receiveEnergy(remaining, false);
                    require(accepted > 0, "FINAL client fixture could not supply its starting charge");
                    remaining -= accepted;
                }
                player.openMenu(synth);
            }
        });
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
