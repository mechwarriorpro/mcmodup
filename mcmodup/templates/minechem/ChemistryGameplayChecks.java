package @PACKAGE@;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("@TEST_NAMESPACE@")
@PrefixGameTestTemplate(false)
public final class @CLASS@ {
    private static final String MOD = "@NAMESPACE@";
    private static final boolean PREPAID = @PREPAID@;
    private static final int SPEED = PREPAID ? 400 : 1000;

    @GameTest(template = "test_room")
    public static void @PREFIX@DrinkAndCoat(GameTestHelper test) {
        var player = player(test);
        var sugar = item("molecule_sucrose").copyWithCount(2);
        drink(test, player, sugar);
        test.assertTrue(sugar.getCount() == 1 && player.getFoodData().getFoodLevel() == 9, "Drinking didn't consume one tube and add nutrition");
        speed(test, player);
        player.addEffect(new MobEffectInstance(MobEffects.POISON, 99));
        drink(test, player, item("molecule_asprin"));
        test.assertTrue(!player.hasEffect(MobEffects.POISON) && !player.hasEffect(MobEffects.MOVEMENT_SPEED) &&
                player.hasEffect(MobEffects.REGENERATION), "Cure didn't remove existing effects before adding regeneration");
        var sword = new ItemStack(Items.IRON_SWORD);
        sword.setDamageValue(7);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Coating check"));
        var coated = craft(test, sword, sugar);
        test.assertTrue(coated.is(Items.IRON_SWORD) && coated.getDamageValue() == 7 && coated.getHoverName().getString().equals("Coating check") &&
                !ItemStack.isSameItemSameComponents(coated, sword) && sugar.getCount() == 1, "Coating didn't copy components or altered the inputs");
        var registry = test.getLevel().registryAccess();
        var saved = ItemStack.parse(registry, coated.save(registry)).orElse(ItemStack.EMPTY);
        test.assertTrue(ItemStack.matches(coated, saved), "Coating didn't serialize");
        test.assertTrue(craft(test, sword, sugar, new ItemStack(Items.DIAMOND)).isEmpty(), "Coating must reject extra ingredients");
        test.assertTrue(craft(test, new ItemStack(Items.IRON_PICKAXE), sugar).isEmpty(), "Only swords can be coated");
        var foreign = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(
                PREPAID ? "minechem_chemistry" : "minechem_chemistry_394", "molecule_sucrose"));
        if (foreign != Items.AIR) test.assertTrue(craft(test, saved, new ItemStack(foreign)).isEmpty(), "Swords must not mix the two release treatments");
        var pig = test.spawn(EntityType.PIG, new BlockPos(1, 1, 1));
        player.setItemInHand(InteractionHand.MAIN_HAND, saved);
        pig.hurt(test.getLevel().damageSources().playerAttack(player), 1F);
        speed(test, pig);
        var strong = craft(test, sword, item("molecule_latropine"));
        test.assertTrue(strong.is(Items.IRON_SWORD), "Missing original Delirium coating recipe");
        var delirium = BuiltInRegistries.MOB_EFFECT.getHolderOrThrow(ResourceKey.create(Registries.MOB_EFFECT,
                ResourceLocation.fromNamespaceAndPath(MOD, "delirium")));
        var otherPig = test.spawn(EntityType.PIG, new BlockPos(2, 1, 1));
        player.setItemInHand(InteractionHand.MAIN_HAND, strong);
        otherPig.hurt(test.getLevel().damageSources().playerAttack(player), 1F);
        test.assertTrue(otherPig.hasEffect(delirium) && otherPig.getHealth() <= otherPig.getMaxHealth() - 4F,
                "A coated hit didn't apply the observed chemical damage and Delirium");
        if (PREPAID) {
            var option = config("SWORDS");
            var before = option.get();
            try {
                option.set(false);
                test.assertTrue(craft(test, sword, sugar).isEmpty(), "Sword config didn't disable crafting");
                var unaffected = test.spawn(EntityType.PIG, new BlockPos(3, 1, 1));
                unaffected.hurt(test.getLevel().damageSources().playerAttack(player), 1F);
                test.assertTrue(!unaffected.hasEffect(delirium), "Sword config didn't disable an existing saved coating");
            } finally { option.set(before); }
        }
        test.succeed();
    }

    @GameTest(template = "test_room")
    public static void @PREFIX@FoodMixingAndFinish(GameTestHelper test) {
        var base = new ItemStack(Items.BREAD, 3);
        base.set(DataComponents.CUSTOM_NAME, Component.literal("Lunch"));
        var first = craft(test, item("molecule_sucrose"), base);
        test.assertTrue(first.is(Items.BREAD) && first.getCount() == 1 && first.getHoverName().getString().equals("Lunch"),
                "Food spiking did not keep the original food/name");
        var mixed = craft(test, first, item("molecule_ethanol"));
        test.assertTrue(mixed.is(Items.BREAD) && !ItemStack.isSameItemSameComponents(first, mixed) && base.getCount() == 3,
                "Re-spiking should leave the input untouched");
        var player = player(test);
        consume(test, player, mixed);
        test.assertTrue(player.getFoodData().getFoodLevel() == (PREPAID ? 15 : 14) && player.hasEffect(MobEffects.CONFUSION) &&
                player.hasEffect(MobEffects.MOVEMENT_SPEED) == PREPAID, "Food or the release-specific replacement/append behavior failed");
        var other = player(test);
        consume(test, other, base.copyWithCount(1));
        test.assertTrue(other.getFoodData().getFoodLevel() == 13 && !other.hasEffect(MobEffects.CONFUSION), "Unmodified food gained a chemical effect");
        test.assertTrue(PREPAID != craft(test, base, item("molecule_water")).isEmpty(), "Food matching didn't respect the original release allowlist");
        if (PREPAID) {
            var option = config("FOODS");
            var before = option.get();
            try {
                option.set(false);
                test.assertTrue(craft(test, base, item("molecule_sucrose")).isEmpty(), "Food config didn't disable crafting");
                var unaffected = player(test);
                consume(test, unaffected, first);
                test.assertTrue(unaffected.getFoodData().getFoodLevel() == 13 && !unaffected.hasEffect(MobEffects.MOVEMENT_SPEED),
                        "Food config didn't disable an existing dose or broke vanilla hunger");
            } finally { option.set(before); }
            var recreational = config("RECREATIONAL");
            var setting = recreational.get();
            var optional = item("molecule_psilocybin");
            test.assertTrue(craft(test, base, optional).is(Items.BREAD), "Missing default optional .394 treatment");
            try {
                recreational.set(false);
                test.assertTrue(craft(test, base, optional).isEmpty(), "Optional .394 treatment still matched with its setting off");
                var unaffected = player(test);
                float health = unaffected.getHealth();
                // Test just this completion, not ambient damage while ticking a mock player.
                optional.getItem().finishUsingItem(optional, test.getLevel(), unaffected);
                test.assertTrue(!unaffected.hasEffect(MobEffects.CONFUSION) && unaffected.getHealth() == health,
                        "Disabling optional profiles did not block their drink effects");
            } finally { recreational.set(setting); }
        }
        test.succeed();
    }

    @GameTest(template = "test_room")
    public static void @PREFIX@MaterialYieldsAndChance(GameTestHelper test) {
        test.setBlock(BlockPos.ZERO, BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(MOD, "decomposer")));
        var level = test.getLevel();
        var pos = test.absolutePos(BlockPos.ZERO);
        var entity = level.getBlockEntity(pos);
        var input = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
        var output = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.DOWN);
        var fe = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.UP);
        var fluid = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, Direction.NORTH);
        test.assertTrue(input != null && output != null && fe != null && fluid != null, "Missing decomposer capabilities");
        test.assertTrue(!input.isItemValid(0, new ItemStack(Items.BEDROCK)) && input.isItemValid(0, new ItemStack(Items.GOLD_ORE)),
                "Registry accepts a missing recipe or rejects a measured ore");
        var potion = new ItemStack(Items.POTION);
        potion.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.WATER));
        test.assertTrue(input.isItemValid(0, potion), "The measured water bottle must have the water potion component");
        potion.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.AWKWARD));
        test.assertTrue(!input.isItemValid(0, potion), "An unrelated potion matched the original water bottle");
        if (PREPAID) {
            test.assertTrue(fluid.fill(new FluidStack(Fluids.LAVA, 501), FluidAction.EXECUTE) == 501 &&
                    fluid.fill(new FluidStack(Fluids.WATER, 1), FluidAction.SIMULATE) == 0, "Tank mixed incompatible fluids");
            var saved = entity.saveWithFullMetadata(level.registryAccess());
            fluid.drain(501, FluidAction.EXECUTE);
            entity.loadWithComponents(saved, level.registryAccess());
            var restored = fluid.drain(501, FluidAction.EXECUTE);
            test.assertTrue(restored.getFluid() == Fluids.LAVA && restored.getAmount() == 501, "The .394 tank lost its fluid identity on reload");
            var raw = new ItemStack(Items.WHITE_STAINED_GLASS);
            test.assertTrue(input.insertItem(0, raw, false).isEmpty(), "Missing .394 weighted stained-glass rule");
            for (int i = 0; i < 10; i++) fe.receiveEnergy(100, false);
            tick(entity);
            int tubes = 0;
            for (int i = 0; i < 64; i++) {
                tick(entity);
                tubes += output.extractItem(0, 64, false).getCount();
            }
            test.assertTrue((tubes == 16 || tubes == 17) && fe.getEnergyStored() == 0, ".394 weighted stained-glass recursion/payment returned " + tubes);
        }
        boolean miss = false, hit = false;
        for (int attempt = 0; attempt < 96; attempt++) {
            if (PREPAID) {
                for (int i = 0; i < 10; i++) fe.receiveEnergy(100, false);
                test.assertTrue(fluid.fill(new FluidStack(Fluids.LAVA, 250), FluidAction.EXECUTE) == 250, "Measured lava recipe didn't accept 250 mB");
            } else {
                fe.receiveEnergy(4, false);
                test.assertTrue(input.insertItem(0, new ItemStack(Items.GRAVEL), false).isEmpty(), "Missing FINAL chance recipe");
            }
            tick(entity);
            test.assertTrue(input.getStackInSlot(0).isEmpty() && fluid.getFluidInTank(0).isEmpty(), "Chance batch didn't debit the input on intake");
            if (PREPAID) test.assertTrue(fe.getEnergyStored() == 0, "A failed lava roll was free");
            int emitted = 0;
            for (int i = 0; i < 3; i++) {
                tick(entity);
                var tube = output.extractItem(0, 64, false);
                if (tube.isEmpty()) continue;
                emitted += tube.getCount();
                test.assertTrue(PREPAID ? BuiltInRegistries.ITEM.getKey(tube.getItem()).getPath().startsWith("element_") :
                        tube.is(item("molecule_silicondioxide").getItem()), "Chance batch produced an unobserved result " + tube);
            }
            if (emitted == 0) miss = true;
            else {
                hit = true;
                test.assertTrue(emitted == (PREPAID ? 2 : 1), "Choice emitted more than one branch");
            }
            if (miss && hit) break;
        }
        test.assertTrue(miss && hit, "Chance batches must be able to produce a result or nothing");
        test.succeed();
    }

    private static ModConfigSpec.BooleanValue config(String name) {
        try {
            var config = Class.forName("mcmodup.generated." + MOD + ".ChemistryEffects").getDeclaredField(name);
            config.setAccessible(true);
            return (ModConfigSpec.BooleanValue) config.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Player player(GameTestHelper test) {
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        var pos = test.absolutePos(new BlockPos(1, 1, 0));
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        player.getFoodData().setFoodLevel(8);
        return player;
    }

    private static ItemStack craft(GameTestHelper test, ItemStack... items) {
        var level = test.getLevel();
        var grid = CraftingInput.of(items.length, 1, List.of(items));
        return level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level)
                .map(recipe -> recipe.value().assemble(grid, level.registryAccess())).orElse(ItemStack.EMPTY);
    }

    private static void drink(GameTestHelper test, Player player, ItemStack tube) {
        player.setItemInHand(InteractionHand.MAIN_HAND, tube);
        tube.getItem().use(test.getLevel(), player, InteractionHand.MAIN_HAND);
        test.assertTrue(player.isUsingItem() && player.getUseItemRemainingTicks() == 16, "Original drink animation must start for 16 ticks");
        finish(test, player);
    }

    private static void consume(GameTestHelper test, Player player, ItemStack food) {
        player.setItemInHand(InteractionHand.MAIN_HAND, food);
        food.getItem().use(test.getLevel(), player, InteractionHand.MAIN_HAND);
        test.assertTrue(player.isUsingItem(), "Food did not start use");
        finish(test, player);
        test.assertTrue(player.getMainHandItem().isEmpty(), "Food didn't consume exactly one item");
    }

    private static void finish(GameTestHelper test, Player player) {
        for (int i = 0; i < 40 && player.isUsingItem(); i++) player.tick();
        test.assertTrue(!player.isUsingItem(), "Item use did not finish");
    }

    private static void speed(GameTestHelper test, net.minecraft.world.entity.LivingEntity target) {
        var effect = target.getEffect(MobEffects.MOVEMENT_SPEED);
        test.assertTrue(effect != null && effect.getAmplifier() == 1 && effect.getDuration() >= SPEED - 1 && effect.getDuration() <= SPEED,
                "Synthesis release gave wrong sucrose effect: " + effect);
    }

    private static ItemStack item(String path) { return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(MOD, path))); }

    @SuppressWarnings("unchecked")
    private static void tick(BlockEntity entity) {
        var state = entity.getBlockState();
        ((EntityBlock) state.getBlock()).getTicker(entity.getLevel(), state, (BlockEntityType<BlockEntity>) entity.getType())
                .tick(entity.getLevel(), entity.getBlockPos(), state, entity);
    }
}
