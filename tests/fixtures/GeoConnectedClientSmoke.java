package mcmodup.geoclient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
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
    private BlockPos center, charger, matterMachine, matterChest;
    private int age, emptyHandSlot;
    private CompletableFuture<Void> reload;
    private CompletableFuture<Boolean> meleeReady;
    private String decayWarning;
    private UUID combatTarget;
    private boolean combat394, sawNeon;
    private enum Stage {
        TITLE, JOIN, ISOLATED, JOINED, CHANGE, RELOAD, REACTOR, SYNTHESIS, PAID, DEPOSITED, RETRIEVED,
        BATCH, BATCH_PAID, BATCH_DEPOSITED, BULK_PAID, TREATMENT, FINAL_TOOL, FINAL_TOOL_MENU, FINAL_TOOL_CURSOR,
        FINAL_ALLOY, FINAL_LITHIUM_CURSOR, FINAL_LITHIUM, FINAL_NEON_CURSOR, FINAL_CLOSE, FINAL_CHARGE,
        TOOL_394, TOOL_394_MENU, TOOL_394_SYNC, TOOL_394_NEON, MELEE_READY, MELEE_RESULT,
        FLUID_HAND, FLUID_MENU, LEADED, LEADED_TAKEN, RADIATION, DONE
    }

    public GeoConnectedClientSmoke() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::chat);
    }

    private void chat(ClientChatReceivedEvent.System event) {
        if ((stage != Stage.LEADED_TAKEN && stage != Stage.RADIATION) || event.isOverlay()) return;
        String message = event.getMessage().getString();
        if (message.startsWith("Radiation Warning:")) decayWarning = message;
    }

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
                        checkModel(client, "minechem_chemistry", "uranium_ore");
                        checkModel(client, "minechem_chemistry", "chemical_storage");
                        checkModel(client, "minechem_chemistry_394", "leaded_chest");
                        checkModel(client, "minechem_chemistry_394", "fluid_element_fe");
                        require(client.getResourceManager().getResource(release394Id("textures/block/fluid_still.png")).isPresent(),
                            "Missing .394 animated chemical-fluid texture");
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
                    open394Synthesis(client);
                    advance(Stage.BATCH);
                }
                case BATCH -> {
                    var screen = synthesisScreen(client, release394Id("synthesizer"));
                    if (screen == null || menuInt(screen, "energy") != 900 || !screen.getMenu().getSlot(9).getItem().is(Items.STONE)) return;
                    require(menuInt(screen, "cost") == 300 && screen.getMenu().getSlot(9).getItem().getCount() == 16, "Wrong .394 stone batch in sync");
                    click(screen, 9);
                    advance(Stage.BATCH_PAID);
                }
                case BATCH_PAID -> {
                    var screen = synthesisScreen(client, release394Id("synthesizer"));
                    if (screen == null || menuInt(screen, "energy") != 600 || !screen.getMenu().getCarried().is(Items.STONE)) return;
                    require(screen.getMenu().getCarried().getCount() == 16, "Regular .394 click should deliver exactly one batch");
                    click(screen, 30);
                    advance(Stage.BATCH_DEPOSITED);
                }
                case BATCH_DEPOSITED -> {
                    var screen = synthesisScreen(client, release394Id("synthesizer"));
                    if (screen == null || !screen.getMenu().getCarried().isEmpty() || screen.getMenu().getSlot(30).getItem().getCount() != 16) return;
                    // Send the same reachable packet as shift-click. This bypasses GLFW key state, not the container or server.
                    client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, 9, 0, ClickType.QUICK_MOVE, client.player);
                    advance(Stage.BULK_PAID);
                }
                case BULK_PAID -> {
                    var screen = synthesisScreen(client, release394Id("synthesizer"));
                    if (screen == null || menuInt(screen, "energy") != 0 || screen.getMenu().getSlot(30).getItem().getCount() != 48) return;
                    require(screen.getMenu().slots.subList(10, 28).stream().noneMatch(slot -> slot.hasItem()), "Bulk withdrawal did not spend all three batches");
                    startTreatment(client);
                    advance(Stage.TREATMENT);
                }
                case TREATMENT -> {
                    var sword = client.player.getMainHandItem();
                    if (!sword.is(Items.IRON_SWORD) || !client.player.hasEffect(delirium(release394Id("delirium")))) return;
                    require(!client.player.hasEffect(delirium(finalId("delirium"))), "Delirium mixed releases");
                    var lines = sword.getTooltipLines(Item.TooltipContext.EMPTY, client.player, TooltipFlag.NORMAL);
                    require(lines.stream().anyMatch(line -> line.getString().contains("Coated")), "Saved coating did not reach the client tooltip");
                    prepareTool(client, false);
                    advance(Stage.FINAL_TOOL);
                }
                case FINAL_TOOL, TOOL_394 -> {
                    boolean newer = stage == Stage.TOOL_394;
                    var id = toolId(newer, "polytool");
                    if (client.screen != null || !client.player.getMainHandItem().is(BuiltInRegistries.ITEM.get(id))) return;
                    client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                    advance(newer ? Stage.TOOL_394_MENU : Stage.FINAL_TOOL_MENU);
                }
                case FINAL_TOOL_MENU, TOOL_394_MENU -> {
                    boolean newer = stage == Stage.TOOL_394_MENU;
                    var screen = polytoolScreen(client, newer);
                    if (screen == null || screen.getMenu().getSlot(10).getItem().getCount() != 64) return;
                    require(screen.getXSize() == 176 && screen.getYSize() == 218 && screen.getMenu().slots.size() == 37 &&
                        client.getResourceManager().getResource(toolId(newer, "textures/gui/polytool.png")).isPresent(),
                        "Wrong Polytool menu geometry or missing original background");
                    require(client.player.getMainHandItem().getDestroySpeed(Blocks.OAK_LOG.defaultBlockState()) == 8,
                        "Uninfused Polytool already changed speed");
                    if (newer) {
                        client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, 10, 0, ClickType.QUICK_MOVE, client.player);
                        advance(Stage.TOOL_394_SYNC);
                    } else {
                        click(screen, 10);
                        advance(Stage.FINAL_TOOL_CURSOR);
                    }
                }
                case FINAL_TOOL_CURSOR, FINAL_LITHIUM_CURSOR -> {
                    var screen = polytoolScreen(client, false);
                    if (screen == null || screen.getMenu().getCarried().getCount() != 64) return;
                    click(screen, 0);
                    advance(stage == Stage.FINAL_TOOL_CURSOR ? Stage.FINAL_ALLOY : Stage.FINAL_LITHIUM);
                }
                case FINAL_ALLOY -> {
                    var screen = polytoolScreen(client, false);
                    if (screen == null || !screen.getMenu().getCarried().isEmpty() ||
                        client.player.getMainHandItem().getDestroySpeed(Blocks.OAK_LOG.defaultBlockState()) != 16) return;
                    require(screen.getMenu().getSlot(10).getItem().isEmpty() && screen.getMenu().getSlot(0).getItem().isEmpty(),
                        "Infusion did not pay for and clear the aluminium stack");
                    click(screen, 11);
                    advance(Stage.FINAL_LITHIUM_CURSOR);
                }
                case FINAL_LITHIUM -> {
                    var screen = polytoolScreen(client, false);
                    var charge = client.player.getMainHandItem().getCapability(Capabilities.EnergyStorage.ITEM);
                    if (screen == null || !screen.getMenu().getCarried().isEmpty() || charge == null) return;
                    require(charge.getEnergyStored() == 0 && screen.getMenu().getSlot(11).getItem().isEmpty(), "Lithium didn't sync as a paid, empty receiver");
                    click(screen, 12);
                    advance(Stage.FINAL_NEON_CURSOR);
                }
                case FINAL_NEON_CURSOR -> {
                    var screen = polytoolScreen(client, false);
                    if (screen == null || screen.getMenu().getCarried().getCount() != 64) return;
                    click(screen, 0);
                    advance(Stage.FINAL_CLOSE);
                }
                case FINAL_CLOSE -> {
                    var screen = polytoolScreen(client, false);
                    if (screen == null || !screen.getMenu().getCarried().isEmpty() ||
                        !screen.getMenu().getSlot(12).getItem().isEmpty() || !hasTooltip(client.player.getMainHandItem(), client, "Neon")) return;
                    client.player.closeContainer();
                    advance(Stage.FINAL_CHARGE);
                }
                case FINAL_CHARGE -> {
                    if (client.screen != null || !matchesCharger(client)) return;
                    var tool = client.player.getMainHandItem();
                    var charge = tool.getCapability(Capabilities.EnergyStorage.ITEM);
                    if (charge == null) return;
                    if (charge.getEnergyStored() == 0) {
                        // Wait for the actual server's item transfer and component packet.
                        if (age % 20 == 0) client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(charger), Direction.WEST, charger, false));
                        return;
                    }
                    require(charge.getEnergyStored() >= 20_000_000 && hasTooltip(tool, client, "Lithium melee bonus: +1"),
                        "Creative FE Supply did not charge FINAL's tool and sync its melee bonus");
                    prepareCombat(client, false);
                }
                case TOOL_394_SYNC -> {
                    var screen = polytoolScreen(client, true);
                    var tool = client.player.getMainHandItem();
                    if (screen == null || !screen.getMenu().getSlot(10).getItem().isEmpty() ||
                        tool.getDestroySpeed(Blocks.OAK_LOG.defaultBlockState()) != 16) return;
                    require(tool.getCapability(Capabilities.EnergyStorage.ITEM) == null && hasTooltip(tool, client, "rank 1"),
                        ".394 tool gained an FE capability or didn't sync the rank tooltip");
                    client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, 12, 0, ClickType.QUICK_MOVE, client.player);
                    advance(Stage.TOOL_394_NEON);
                }
                case TOOL_394_NEON -> {
                    var screen = polytoolScreen(client, true);
                    if (screen == null || !screen.getMenu().getSlot(12).getItem().isEmpty() ||
                        !hasTooltip(client.player.getMainHandItem(), client, "Neon")) return;
                    client.player.closeContainer();
                    prepareCombat(client, true);
                }
                case MELEE_READY -> {
                    var pig = combatPig(client);
                    if (pig == null || !pig.isAlive() || client.screen != null || client.player.isCreative() ||
                        !client.player.onGround() || client.player.getAttackStrengthScale(.5F) < 1F ||
                        client.player.hasEffect(MobEffects.NIGHT_VISION)) return;
                    // The client can finish its swing cooldown one tick before the server.
                    if (meleeReady == null || (meleeReady.isDone() && !meleeReady.join())) {
                        var server = client.getSingleplayerServer();
                        var id = client.player.getUUID();
                        meleeReady = server.submit(() -> {
                            var player = server.getPlayerList().getPlayer(id);
                            return player != null && player.onGround() && !player.isCreative() && player.getAttackStrengthScale(.5F) >= 1F;
                        });
                        return;
                    }
                    if (!meleeReady.isDone()) return;
                    client.gameMode.attack(client.player, pig); // send the real serverbound interact packet
                    advance(Stage.MELEE_RESULT);
                }
                case MELEE_RESULT -> {
                    var pig = combatPig(client);
                    sawNeon |= client.player.hasEffect(MobEffects.NIGHT_VISION);
                    if (pig == null || pig.getHealth() >= pig.getMaxHealth() || !sawNeon) return;
                    require(Math.abs(pig.getMaxHealth() - pig.getHealth() - 9F) < .02F,
                        "Wrong server melee damage for " + (combat394 ? ".394" : "charged FINAL") + ": " + pig.getHealth());
                    var server = client.getSingleplayerServer();
                    var playerId = client.player.getUUID();
                    var targetId = combatTarget;
                    server.execute(() -> {
                        var target = server.getPlayerList().getPlayer(playerId).serverLevel().getEntity(targetId);
                        if (target != null) target.discard();
                    });
                    if (combat394) {
                        prepareMatter(client);
                        advance(Stage.FLUID_HAND);
                    } else {
                        prepareTool(client, true);
                        advance(Stage.TOOL_394);
                    }
                }
                case FLUID_HAND -> {
                    if (client.screen != null || client.player.isCreative() ||
                        !client.level.getBlockState(matterMachine).is(BuiltInRegistries.BLOCK.get(release394Id("decomposer")))) return;
                    var vial = client.player.getMainHandItem();
                    if (vial.is(BuiltInRegistries.ITEM.get(release394Id("test_tube")))) {
                        // Vanilla opens the block menu with the main hand only. Select the empty slot
                        // rather than refilling the returned test tube on the next click.
                        client.player.getInventory().selected = emptyHandSlot;
                        advance(Stage.FLUID_MENU);
                    } else if (age % 20 == 0 && vial.is(BuiltInRegistries.ITEM.get(release394Id("element_fe")))) {
                        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit(matterMachine));
                    }
                }
                case FLUID_MENU -> {
                    var screen = synthesisScreen(client, release394Id("chemical_machine"));
                    if (screen == null) {
                        if (client.screen == null && age % 20 == 0 && client.player.getMainHandItem().isEmpty())
                            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit(matterMachine));
                        return;
                    }
                    if (menuInt(screen, "fluidVolume") != 125) return;
                    var label = screen.getMenu().getClass().getDeclaredMethod("fluidLabel");
                    label.setAccessible(true);
                    require(((net.minecraft.network.chat.Component) label.invoke(screen.getMenu())).getString().contains("Iron"),
                        "Chemical tank showed the wrong fluid name");
                    client.player.closeContainer();
                    advance(Stage.LEADED);
                }
                case LEADED -> {
                    var screen = synthesisScreen(client, release394Id("leaded_chest"));
                    if (screen == null) {
                        if (client.screen == null && age % 20 == 0) client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit(matterChest));
                        return;
                    }
                    var stored = screen.getMenu().getSlot(0).getItem();
                    if (stored.getCount() != 3) return;
                    require(screen.getMenu().slots.size() == 45 && screen.getXSize() == 176 && screen.getYSize() == 132 &&
                        hasTooltip(stored, client, "Radioactivity 1"),
                        "Leaded storage didn't sync its inventory or the uranium clock");
                    click(screen, 0);
                    advance(Stage.LEADED_TAKEN);
                }
                case LEADED_TAKEN -> {
                    var screen = synthesisScreen(client, release394Id("leaded_chest"));
                    if (screen == null || screen.getMenu().getCarried().getCount() != 3 || !screen.getMenu().getSlot(0).getItem().isEmpty()) return;
                    if (age < 8) return;
                    require(screen.getMenu().getCarried().is(BuiltInRegistries.ITEM.get(release394Id("element_u"))) && decayWarning == null,
                        "An overdue sample decayed before it left the cursor");
                    click(screen, screen.getMenu().slots.size() - 9 + emptyHandSlot);
                    advance(Stage.RADIATION);
                }
                case RADIATION -> {
                    var slot = client.player.getInventory().getItem(emptyHandSlot);
                    if (decayWarning == null || !slot.is(BuiltInRegistries.ITEM.get(release394Id("element_pa")))) return;
                    require(slot.getCount() == 3 && decayWarning.startsWith("Radiation Warning: Element Uranium decayed into Protactinium after "),
                        "Wrong localized .394 decay warning or daughter count: " + decayWarning);
                    finish(client, " The .394 projector synced its blueprint; FINAL paid for a 144-tube ghost recipe and cleared its journal. .394 paid for regular and bulk stone batches and synced its coating and Delirium. FINAL infused aluminium and Lithium and charged its tool; .394 shift-infused without an FE capability. Both infused Neon and synced night vision and damage from a real survival attack. A survival iron vial filled the .394 machine, returned an empty tube, and synced the 125 mB and fluid name. The leaded chest synced an overdue sample; it stayed uranium on the cursor and became three protactinium tubes in the hotbar. Received: " + decayWarning);
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

    private static ResourceLocation release394Id(String path) { return ResourceLocation.fromNamespaceAndPath("minechem_chemistry_394", path); }

    private static AbstractContainerScreen<?> synthesisScreen(Minecraft client) { return synthesisScreen(client, finalId("synthesizer")); }

    private static AbstractContainerScreen<?> synthesisScreen(Minecraft client, ResourceLocation id) {
        return client.screen instanceof AbstractContainerScreen<?> screen &&
            id.equals(BuiltInRegistries.MENU.getKey(screen.getMenu().getType())) ? screen : null;
    }

    private static net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> delirium(ResourceLocation id) {
        return BuiltInRegistries.MOB_EFFECT.getHolderOrThrow(ResourceKey.create(Registries.MOB_EFFECT, id));
    }

    private static ItemStack journal(ResourceLocation id, String output) {
        var book = new ItemStack(BuiltInRegistries.ITEM.get(id));
        CustomData.update(DataComponents.CUSTOM_DATA, book, data -> {
            var research = data.getCompound("ChemistJournal");
            var known = new ListTag();
            known.add(StringTag.valueOf(output));
            research.put("Discoveries", known);
            research.putString("Active", output);
            data.put("ChemistJournal", research);
        });
        return book;
    }

    private void open394Synthesis(Minecraft client) {
        var server = client.getSingleplayerServer();
        var playerId = client.player.getUUID();
        var pos = client.player.blockPosition().west(3).above(1);
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(playerId);
            var level = player.serverLevel();
            level.setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.get(release394Id("synthesizer")).defaultBlockState());
            if (level.getBlockEntity(pos) instanceof MenuProvider synth) {
                var menu = synth.createMenu(0, player.getInventory(), player);
                menu.getSlot(28).set(journal(release394Id("chemist_journal"), "minecraft:stone"));
                var input = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
                require(input.insertItem(0, new ItemStack(BuiltInRegistries.ITEM.get(release394Id("element_si")), 3), false).isEmpty(), "Cannot fill silicon");
                require(input.insertItem(1, new ItemStack(BuiltInRegistries.ITEM.get(release394Id("element_o")), 15), false).isEmpty(), "Cannot fill oxygen");
                require(input.insertItem(2, new ItemStack(BuiltInRegistries.ITEM.get(release394Id("element_al")), 6), false).isEmpty(), "Cannot fill aluminium");
                var power = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.UP);
                for (int i = 0; i < 9; i++) require(power.receiveEnergy(100, false) == 100, "Cannot charge .394 synthesizer");
                player.openMenu(synth);
            }
        });
    }

    private void startTreatment(Minecraft client) {
        var server = client.getSingleplayerServer();
        var playerId = client.player.getUUID();
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(playerId);
            var level = player.serverLevel();
            player.closeContainer();
            var tube = new ItemStack(BuiltInRegistries.ITEM.get(release394Id("molecule_latropine")));
            var grid = CraftingInput.of(2, 1, List.of(new ItemStack(Items.IRON_SWORD), tube));
            var sword = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level).orElseThrow().value().assemble(grid, level.registryAccess());
            tube.getItem().finishUsingItem(tube, level, player);
            player.setItemInHand(InteractionHand.MAIN_HAND, sword);
        });
    }

    private static ResourceLocation toolId(boolean newer, String path) { return newer ? release394Id(path) : finalId(path); }

    private static AbstractContainerScreen<?> polytoolScreen(Minecraft client, boolean newer) {
        if (client.screen instanceof AbstractContainerScreen<?> screen &&
            screen.getMenu().getType() == BuiltInRegistries.MENU.get(toolId(newer, "polytool"))) return screen;
        return null;
    }

    private static boolean hasTooltip(ItemStack stack, Minecraft client, String text) {
        return stack.getTooltipLines(Item.TooltipContext.EMPTY, client.player, TooltipFlag.NORMAL).stream().anyMatch(line -> line.getString().contains(text));
    }

    private boolean matchesCharger(Minecraft client) {
        return client.level.getBlockState(charger).is(BuiltInRegistries.BLOCK.get(ResourceLocation.parse("atomic_thermometer:creative_fe_supply")));
    }

    private void prepareTool(Minecraft client, boolean newer) {
        var server = client.getSingleplayerServer();
        var playerId = client.player.getUUID();
        if (!newer) charger = client.player.blockPosition().east(2);
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(playerId);
            player.closeContainer();
            var registry = BuiltInRegistries.ITEM;
            player.getInventory().setItem(9, new ItemStack(registry.get(toolId(newer, "element_al")), 64));
            player.getInventory().setItem(11, new ItemStack(registry.get(toolId(newer, "element_ne")), 64));
            if (!newer) {
                player.getInventory().setItem(10, new ItemStack(registry.get(finalId("element_li")), 64));
                var supply = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("atomic_thermometer:creative_fe_supply"));
                player.serverLevel().setBlockAndUpdate(charger, supply.defaultBlockState());
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(registry.get(toolId(newer, "polytool"))));
        });
    }

    private void prepareCombat(Minecraft client, boolean newer) {
        var server = client.getSingleplayerServer();
        var playerId = client.player.getUUID();
        var targetId = UUID.randomUUID();
        combatTarget = targetId;
        combat394 = newer;
        sawNeon = false;
        meleeReady = null;
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(playerId);
            var level = player.serverLevel();
            player.setGameMode(GameType.SURVIVAL);
            player.removeEffect(MobEffects.NIGHT_VISION);
            var testPig = EntityType.PIG.create(level);
            if (testPig == null) throw new AssertionError("Could not create the connected melee target");
            testPig.setUUID(targetId);
            testPig.setNoAi(true);
            testPig.moveTo(player.getX(), player.getY(), player.getZ() + 1.5, 0, 0);
            if (!level.addFreshEntity(testPig)) throw new AssertionError("Could not spawn the connected melee target");
        });
        advance(Stage.MELEE_READY);
    }

    private Pig combatPig(Minecraft client) {
        return client.level.getEntitiesOfClass(Pig.class, client.player.getBoundingBox().inflate(5),
            pig -> pig.getUUID().equals(combatTarget)).stream().findFirst().orElse(null);
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }

    private void prepareMatter(Minecraft client) {
        var server = client.getSingleplayerServer();
        var playerId = client.player.getUUID();
        emptyHandSlot = (client.player.getInventory().selected + 1) % 9;
        matterMachine = client.player.blockPosition().south(2);
        matterChest = client.player.blockPosition().north(2);
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(playerId);
            var level = player.serverLevel();
            player.closeContainer();
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().setItem(emptyHandSlot, ItemStack.EMPTY);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BuiltInRegistries.ITEM.get(release394Id("element_fe"))));
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            level.setBlockAndUpdate(matterMachine, BuiltInRegistries.BLOCK.get(release394Id("decomposer")).defaultBlockState());
            level.setBlockAndUpdate(matterChest, BuiltInRegistries.BLOCK.get(release394Id("leaded_chest")).defaultBlockState());
            var storage = level.getCapability(Capabilities.ItemHandler.BLOCK, matterChest, Direction.UP);
            var specimen = new ItemStack(BuiltInRegistries.ITEM.get(release394Id("element_u")), 3);
            var encoded = (CompoundTag) specimen.save(level.registryAccess());
            var components = encoded.getCompound("components");
            var clock = new CompoundTag();
            clock.putLong("age", 345_600 + 1_201);
            clock.putLong("tick", level.getGameTime());
            clock.putString("dimension", level.dimension().location().toString());
            components.put(release394Id("decay_clock").toString(), clock);
            encoded.put("components", components);
            require(storage.insertItem(0, ItemStack.parse(level.registryAccess(), encoded).orElseThrow(), false).isEmpty(),
                "Client fixture could not stock the leaded chest");
        });
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
                var menu = synth.createMenu(0, player.getInventory(), player);
                menu.getSlot(19).set(journal(finalId("chemist_journal"), "minecraft:iron_block"));
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
