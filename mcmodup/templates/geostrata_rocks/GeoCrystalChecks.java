package @PACKAGE@;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("@NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class GeoCrystalChecks {
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("geostrata_rocks", path); }
    private static ItemStack item(String path) { return new ItemStack(BuiltInRegistries.ITEM.get(id(path))); }
    private static Block block(String path) { return BuiltInRegistries.BLOCK.get(id(path)); }

    @GameTest(template = "crystal_room", timeoutTicks = 100)
    public static void crystalFamiliesAndAuras(GameTestHelper test) {
        var world = test.getLevel();
        var pos = test.absolutePos(new BlockPos(4, 3, 4));
        var stone = Blocks.STONE_SLAB.asItem().getDefaultInstance();
        var empty = ItemStack.EMPTY;
        for (var color : DyeColor.values()) {
            String name = color.getName();
            var cave = block("cave_crystal_" + name);
            var lamp = block("crystal_lamp_" + name);
            var potion = block("potion_crystal_" + name);
            var shard = item("crystal_shard_" + name);
            test.assertTrue(cave != Blocks.AIR && lamp != Blocks.AIR && potion != Blocks.AIR && !shard.isEmpty(), "Unregistered color: " + name);
            world.setBlock(pos, cave.defaultBlockState(), Block.UPDATE_CLIENTS);
            var drops = Block.getDrops(cave.defaultBlockState(), world, pos, null, null, Items.IRON_PICKAXE.getDefaultInstance());
            test.assertTrue(drops.size() == 1 && drops.getFirst().is(shard.getItem()) && drops.getFirst().getCount() >= 1 && drops.getFirst().getCount() <= 8,
                "Bad original-range drops for " + name);
            test.assertTrue(cave.defaultBlockState().getLightEmission() == 9 && lamp.defaultBlockState().getLightEmission() == 15 && potion.defaultBlockState().getLightEmission() == 15,
                "Unexpected glow for " + name);
            var input = CraftingInput.of(3, 3, List.of(empty, shard, empty, shard, shard, shard, stone, stone, stone));
            var recipe = world.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, world).orElseThrow();
            test.assertTrue(recipe.value().assemble(input, world.registryAccess()).is(lamp.asItem()), "Lamp did not keep crystal color: " + name);
            input = CraftingInput.of(3, 2, List.of(Items.REDSTONE_BLOCK.getDefaultInstance(), item("crystal_lamp_" + name),
                Items.GLOWSTONE.getDefaultInstance(), Items.OBSIDIAN.getDefaultInstance(), Items.GOLD_BLOCK.getDefaultInstance(), Items.OBSIDIAN.getDefaultInstance()));
            recipe = world.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, world).orElseThrow();
            test.assertTrue(recipe.value().assemble(input, world.registryAccess()).is(potion.asItem()), "Potion crystal did not keep color: " + name);
        }
        var configured = world.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).get(id("cave_crystal"));
        test.assertTrue(configured != null, "Missing configured cave crystal feature");
        var pocket = pos.north(3);
        world.setBlockAndUpdate(pocket.below(), Blocks.STONE.defaultBlockState());
        test.assertTrue(configured.place(world, world.getChunkSource().getGenerator(), world.getRandom(), pocket) && !world.isEmptyBlock(pocket)
            && world.getBlockState(pocket.below()).is(Blocks.STONE), "Crystal generation did not preserve cave support");
        world.setBlockAndUpdate(pocket, Blocks.AIR.defaultBlockState());
        world.setBlockAndUpdate(pos, block("potion_crystal_light_blue").defaultBlockState());
        var pig = test.spawn(EntityType.PIG, new BlockPos(5, 3, 4));
        pig.setNoAi(true);
        pig.setNoGravity(true);
        test.runAfterDelay(35, () -> {
            MobEffectInstance speed = pig.getEffect(MobEffects.MOVEMENT_SPEED);
            test.assertTrue(speed != null && speed.getAmplifier() == 2 && speed.getDuration() > 5_900, "Potion crystal didn't apply a scheduled rank-3 speed aura");
            world.setBlockAndUpdate(pos, block("cave_crystal_white").defaultBlockState());
        });
        test.runAfterDelay(70, () -> {
            test.assertTrue(!pig.hasEffect(MobEffects.MOVEMENT_SPEED), "White crystal did not clear the nearby effect");
            test.succeed();
        });
    }
}
