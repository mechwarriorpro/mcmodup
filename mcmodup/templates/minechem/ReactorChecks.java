package @PACKAGE@;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("@TEST_NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class @CLASS@ {
    private static final String NAMESPACE = "@NAMESPACE@";
    private static final boolean RF = @RELEASE394@;
    private static final Grid FISSION = new Grid(@FISSION_SIZE@, @FISSION_CORE@, "@FISSION_GRID@");
    private static final Grid FUSION = new Grid(@FUSION_SIZE@, @FUSION_CORE@, "@FUSION_GRID@");

    // Use the pinned data independently of the port's runtime classes. This fixture can run with only the production JAR.
    private record Grid(int width, int height, int depth, int cx, int cy, int cz, String cells) {
        BlockPos core(BlockPos origin, Direction direction) { return pos(origin, direction, cx, cy, cz); }
        BlockPos pos(BlockPos origin, Direction direction, int x, int y, int z) {
            int forward = width + 1 - x, side = z - depth / 2;
            return switch (direction) {
                case WEST -> origin.offset(-forward, y, -side);
                case EAST -> origin.offset(forward, y, side);
                case NORTH -> origin.offset(side, y, -forward);
                case SOUTH -> origin.offset(-side, y, forward);
                default -> throw new AssertionError("Expected horizontal projection");
            };
        }
        void build(GameTestHelper test, BlockPos origin, Direction dir, int start, int end) {
            for (int x = start; x < end; x++) for (int y = 0; y < height; y++) for (int z = 0; z < depth; z++) {
                char cell = cells.charAt((y * width + x) * depth + z);
                if (cell == 'A' || cell == 'C') place(test, pos(origin, dir, x, y, z), cell == 'A' ? "reactor_casing" : "reactor_coil", dir);
            }
        }
        int ghosts(GameTestHelper test, BlockPos origin, Direction dir) {
            int count = 0;
            for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) for (int z = 0; z < depth; z++) {
                var state = test.getLevel().getBlockState(pos(origin, dir, x, y, z));
                if (state.is(block("ghost_casing")) || state.is(block("ghost_coil"))) count++;
            }
            return count;
        }
    }

    @GameTest(template = "reactor_room", timeoutTicks = 190)
    public static void fissionProjectionAndReaction(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos position = test.absolutePos(new BlockPos(8, 2, 8));
        BlockPos core = FISSION.core(position, Direction.WEST);
        place(test, position, "blueprint_projector", Direction.WEST);
        var actor = player(test, position);
        var projection = menu(test, position, actor);
        var plan = stack("fission_blueprint");
        test.assertTrue(projection.stillValid(actor) && projection.getSlot(0).mayPlace(plan) && projection.getSlot(0).getMaxStackSize() == 1,
                "Projector's native menu needs one compatible blueprint");
        String foreign = RF ? "minechem_chemistry" : "minechem_chemistry_394";
        var foreignId = ResourceLocation.fromNamespaceAndPath(foreign, "fission_blueprint");
        if (BuiltInRegistries.ITEM.containsKey(foreignId))
            test.assertTrue(!projection.getSlot(0).mayPlace(new ItemStack(BuiltInRegistries.ITEM.get(foreignId))), "A foreign blueprint was accepted");
        projection.getSlot(0).set(plan);
        test.runAfterDelay(2, () -> {
            test.assertTrue(FISSION.ghosts(test, position, Direction.WEST) == 25, "Only the first vertical fission slice should project");
            // A real block (not a ghost) at the formation position must not be replaced or dropped.
            level.setBlockAndUpdate(core, Blocks.OBSIDIAN.defaultBlockState());
            FISSION.build(test, position, Direction.WEST, 0, 1);
            test.runAfterDelay(2, () -> {
                test.assertTrue(FISSION.ghosts(test, position, Direction.WEST) == 16, "Second slice should have 16 ghosts");
                var state = level.getBlockState(position);
                var saved = level.getBlockEntity(position).saveWithFullMetadata(level.registryAccess());
                level.removeBlockEntity(position);
                level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(position, state);
                level.getBlockEntity(position).loadWithComponents(saved, level.registryAccess());
                var restored = menu(test, position, actor);
                test.assertTrue(!projection.stillValid(actor) && restored.getSlot(0).getItem().is(plan.getItem()), "Projector blueprint or menu lifetime was not saved");
                test.assertTrue(restored.getSlot(0).remove(1).is(plan.getItem()), "Blueprint could not be retrieved");
                test.runAfterDelay(22, () -> {
                    test.assertTrue(FISSION.ghosts(test, position, Direction.WEST) == 0, "Retrieval left ghosts after reload");
                    FISSION.build(test, position, Direction.WEST, 1, 5);
                    restored.getSlot(0).set(plan.copy());
                    test.runAfterDelay(2, () -> {
                        test.assertTrue(level.getBlockState(core).is(Blocks.OBSIDIAN), "Projector overwrote a real obstruction");
                        level.removeBlock(core, false);
                        test.runAfterDelay(3, () -> finishFission(test, core, actor));
                    });
                });
            });
        });
    }

    private static void finishFission(GameTestHelper test, BlockPos core, Player actor) {
        var level = test.getLevel();
        test.assertTrue(level.getBlockState(core).is(block("fission_core")), "Fission core did not form at the original manager cell");
        var menu = menu(test, core, actor);
        int output = RF ? 1 : 2;
        test.assertTrue(menu.stillValid(actor) && !menu.getSlot(output).mayPlace(stack("element_be")), "Fission menu is not ready or admits manual insertion into output");
        var power = level.getCapability(Capabilities.EnergyStorage.BLOCK, core, Direction.NORTH);
        menu.getSlot(0).set(stack("element_f"));
        if (RF) {
            test.assertTrue(level.getCapability(Capabilities.ItemHandler.BLOCK, core, Direction.UP) == null, ".394 fission shouldn't expose automation");
            charge(test, power, 899);
        } else {
            var fuel = level.getCapability(Capabilities.ItemHandler.BLOCK, core, Direction.NORTH);
            var input = level.getCapability(Capabilities.ItemHandler.BLOCK, core, Direction.UP);
            test.assertTrue(power == null && fuel.getSlots() == 1 && input.getStackInSlot(0).is(item("element_f")), "FINAL fission sided slots or fuel profile changed");
            fuel.insertItem(0, stack("element_u"), false);
        }
        test.runAfterDelay(RF ? 3 : 52, () -> {
            if (RF) {
                test.assertTrue(menu.getSlot(output).getItem().isEmpty() && power.getEnergyStored() == 899, "Unpaid fission ran early");
                charge(test, power, 1);
            }
            test.runAfterDelay(3, () -> {
                var product = menu.getSlot(output).getItem();
                test.assertTrue(product.is(item("element_be")) && product.getCount() == 2 && menu.getSlot(0).getItem().isEmpty(), "Fission of atomic number 9 did not yield 2 of number 4");
                test.assertTrue(RF ? power.getEnergyStored() == 0 : menu.getSlot(1).getItem().isEmpty(), "Fission did not pay its exact fuel/FE cost");
                menu.getSlot(output).remove(2);
                menu.getSlot(0).set(stack("element_li"));
                if (RF) charge(test, power, 300);
                else menu.getSlot(1).set(stack("element_u"));
                test.runAfterDelay(RF ? 3 : 52, () -> {
                    var light = menu.getSlot(output).getItem();
                    test.assertTrue(menu.getSlot(0).getItem().isEmpty() && (RF ? light.is(item("element_h")) && light.getCount() == 2
                            : light.isEmpty() && menu.getSlot(1).getItem().isEmpty()), "Low atomic number boundary differs from the exact release");
                    test.succeed();
                });
            });
        });
    }

    @GameTest(template = "reactor_room", timeoutTicks = 100)
    public static void fusionStructureAndRules(GameTestHelper test) {
        var level = test.getLevel();
        var position = test.absolutePos(new BlockPos(14, 2, 8));
        var dir = Direction.EAST;
        var core = FUSION.core(position, dir);
        place(test, position, "blueprint_projector", dir);
        var projector = menu(test, position, player(test, position));
        projector.getSlot(0).set(stack("fusion_blueprint"));
        FUSION.build(test, position, dir, 0, 13);
        var air = FUSION.pos(position, dir, 6, 2, 6);
        level.setBlockAndUpdate(air, Blocks.STONE.defaultBlockState());
        test.runAfterDelay(2, () -> {
            test.assertTrue(level.getBlockState(core).isAir(), "Required fusion air is obstructed");
            level.removeBlock(air, false);
            test.runAfterDelay(3, () -> {
                test.assertTrue(level.getBlockState(core).is(block("fusion_core")), "Rotated fusion structure did not form");
                var actor = player(test, core);
                var menu = menu(test, core, actor);
                int left = RF ? 0 : 1, right = left + 1, output = right + 1;
                test.assertTrue(menu.stillValid(actor) && menu.getSlot(left).mayPlace(stack("element_h")) && !menu.getSlot(output).mayPlace(stack("element_he")),
                        "Fusion menu slot mapping or access differs");
                menu.getSlot(left).set(stack("element_h").copyWithCount(2));
                menu.getSlot(right).set(stack("element_h").copyWithCount(2));
                var power = level.getCapability(Capabilities.EnergyStorage.BLOCK, core, Direction.UP);
                if (RF) {
                    charge(test, power, 100);
                    var wall = FUSION.pos(position, dir, 0, 0, 4);
                    var feed = level.getCapability(Capabilities.EnergyStorage.BLOCK, wall, Direction.UP);
                    test.assertTrue(feed != null && feed.getMaxEnergyStored() == 100 && feed.receiveEnergy(1_000, false) == 100, "Original RF wall buffer was not available as FE");
                    test.runAfterDelay(3, () -> {
                        test.assertTrue(power.getEnergyStored() == 200 && feed.getEnergyStored() == 0 && menu.getSlot(output).getItem().isEmpty(),
                                "Wall did not forward charge, or fusion started at exactly its cost");
                        charge(test, power, 1);
                    });
                } else {
                    test.assertTrue(power == null && level.getCapability(Capabilities.ItemHandler.BLOCK, core, Direction.UP) == null, "FINAL fusion was not fuel-only / manual");
                    menu.getSlot(0).set(new ItemStack(Items.NETHER_STAR));
                }
                test.runAfterDelay(6, () -> {
                    var product = menu.getSlot(output).getItem();
                    test.assertTrue(product.is(item("element_he")) && product.getCount() == (RF ? 1 : 2), "Wrong fusion product or batch count");
                    test.assertTrue(RF ? power.getEnergyStored() == 1 : menu.getSlot(0).getItem().is(item("fusion_star")) && menu.getSlot(0).getItem().getDamageValue() == 1,
                            "Fusion did not pay 200 FE or apply one wear after making the star");
                    var saved = level.getBlockEntity(core).saveWithFullMetadata(level.registryAccess());
                    var state = level.getBlockState(core);
                    level.removeBlockEntity(core);
                    level.removeBlock(core, false);
                    level.setBlockAndUpdate(core, state);
                    level.getBlockEntity(core).loadWithComponents(saved, level.registryAccess());
                    var restored = menu(test, core, actor);
                    var restoredPower = level.getCapability(Capabilities.EnergyStorage.BLOCK, core, Direction.UP);
                    test.assertTrue(!menu.stillValid(actor) && ItemStack.matches(product, restored.getSlot(output).getItem()) && (!RF || restoredPower.getEnergyStored() == 1),
                            "Fusion inventory or charge was not restored");
                    // The structure must revalidate after a part is broken, even with the old inventory loaded.
                    level.removeBlock(FUSION.pos(position, dir, 0, 0, 4), false);
                    test.runAfterDelay(41, () -> {
                        test.assertTrue(!restored.stillValid(actor), "A missing fusion casing did not invalidate the core/menu");
                        test.succeed();
                    });
                });
            });
        });
    }

    @GameTest(template = "test_room")
    public static void reactorCraftChain(GameTestHelper test) {
        boolean paired = RF && ModList.get().isLoaded("minechem_chemistry");
        var glass = new ItemStack(paired ? Items.GLASS_PANE : Items.GLASS);
        var blank = new ItemStack(paired ? Items.MAP : Items.PAPER);
        var iron = new ItemStack(Items.IRON_INGOT);
        var empty = ItemStack.EMPTY;
        var concave = craft(test, "concave_lens", 1, 3, 3, glass, empty, glass, glass, glass, glass, glass, empty, glass);
        var convex = craft(test, "convex_lens", 1, 3, 3, empty, glass, empty, glass, glass, glass, empty, glass, empty);
        var lens = craft(test, "projector_lens", 1, 3, 1, concave, convex, concave);
        var microscopeLens = craft(test, "microscope_lens", 1, 1, 3, convex, concave, convex);
        craft(test, "blueprint_projector", 1, 3, 3, empty, iron, empty, new ItemStack(Items.REDSTONE_LAMP), new ItemStack(Items.GLASS_PANE), lens, empty, iron, empty);
        craft(test, "microscope", 1, 3, 3, empty, microscopeLens, iron, empty, new ItemStack(Items.GLASS_PANE), iron, iron, iron, iron);
        craft(test, "fission_blueprint", 1, 2, 1, blank, new ItemStack(Items.DIAMOND));
        craft(test, "fusion_blueprint", 1, 2, 1, blank, new ItemStack(Items.DIAMOND_BLOCK));
        var lead = stack("element_pb");
        var tungsten = stack("element_w");
        craft(test, "reactor_casing", 16, 3, 3, iron, lead, iron, iron, lead, iron, iron, lead, iron);
        craft(test, "reactor_coil", 16, 3, 3, iron, tungsten, iron, iron, stack("element_be"), iron, iron, tungsten, iron);
        test.succeed();
    }

    private static ItemStack craft(GameTestHelper test, String result, int count, int width, int height, ItemStack... inputs) {
        var level = test.getLevel();
        var grid = CraftingInput.of(width, height, List.of(inputs));
        var match = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level);
        test.assertTrue(match.isPresent(), "No registered recipe for " + NAMESPACE + ":" + result);
        var out = match.orElseThrow().value().assemble(grid, level.registryAccess());
        test.assertTrue(out.is(item(result)) && out.getCount() == count, "Another recipe captured " + NAMESPACE + ":" + result + " -> " + out);
        return out;
    }
    private static void charge(GameTestHelper test, IEnergyStorage power, int count) {
        test.assertTrue(power != null && power.getMaxEnergyStored() == 100_000 && power.receiveEnergy(2_000, true) == 100, "Wrong .394 reactor power limits");
        while (count > 0) {
            int accepted = power.receiveEnergy(count, false);
            test.assertTrue(accepted > 0, "Couldn't charge .394 reactor");
            count -= accepted;
        }
    }
    private static AbstractContainerMenu menu(GameTestHelper test, BlockPos at, Player actor) {
        var tile = test.getLevel().getBlockEntity(at);
        test.assertTrue(tile instanceof MenuProvider, "Missing machine menu at " + at);
        return ((MenuProvider) tile).createMenu(1, actor.getInventory(), actor);
    }
    private static Player player(GameTestHelper test, BlockPos at) {
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        return player;
    }
    private static void place(GameTestHelper test, BlockPos at, String name, Direction face) {
        test.getLevel().setBlockAndUpdate(at, block(name).defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, face));
    }
    private static Block block(String name) { return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(NAMESPACE, name)); }
    private static Item item(String name) { return BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(NAMESPACE, name)); }
    private static ItemStack stack(String name) { return new ItemStack(item(name)); }
}
