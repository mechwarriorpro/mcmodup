package @PACKAGE@;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Release-local clocks. Never mutate an item from the other Minechem namespace. */
final class ChemistryRadiation {
    static final boolean RELEASE_394 = @RELEASE_394@;
    private static final int[] LIVES = {0, 1_728_000, 864_000, 432_000, 216_000, 72_000};
    private static final int[] DAMAGE = {0, 1, 2, 6, 8, 16};
    private static final int DEFAULT_PERCENT = @HALF_LIFE@;
    private static final boolean DEFAULT_MACHINE_OPTION = @DECAY_SAFE@;
    private static final ModConfigSpec CONFIG;
    private static final ModConfigSpec.IntValue HALF_LIFE;
    private static final ModConfigSpec.BooleanValue DECAY_SAFE, FLUID_EFFECTS;

    static {
        var config = new ModConfigSpec.Builder();
        HALF_LIFE = config.comment("Original .394 lifetime as a percentage of the base duration.").defineInRange("halfLifeMultiplier", DEFAULT_PERCENT, 0, 1_000_000);
        DECAY_SAFE = config.comment("Original .394 switch: true ages machine inventory while its decomposer menu is open; false shields it.")
                .define("decaySafeMachines", DEFAULT_MACHINE_OPTION);
        FLUID_EFFECTS = config.define("fluidEffects", @FLUID_EFFECTS@);
        CONFIG = config.build();
    }

    record Clock(long age, long tick, String dimension) {
        static final Codec<Clock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("age").forGetter(Clock::age), Codec.LONG.fieldOf("tick").forGetter(Clock::tick),
            Codec.STRING.fieldOf("dimension").forGetter(Clock::dimension)).apply(instance, Clock::new));
    }
    private record Rule(int tier, List<ChemistryRecipes.Part> products) {}
    private record Decayed(List<ItemStack> products, int dose) {}

    private static final Map<String, Rule> RULES = Map.ofEntries(
@RADIATION_ROWS@
    );
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, CatalogPort.MOD_ID);
    static final java.util.function.Supplier<DataComponentType<Clock>> CLOCK = COMPONENTS.registerComponentType("decay_clock",
        builder -> builder.persistent(Clock.CODEC).networkSynchronized(ByteBufCodecs.fromCodec(Clock.CODEC)));

    /** Only physical slots participate; display results and ghost templates are never radioactive inventory. */
    interface MenuRules {
        default boolean virtualRadiationSlot(int slot) { return false; }
        default boolean shieldedRadiationSlot(int slot) { return false; }
        default float radiationReduction() { return 0; }
    }

    static void register(IEventBus bus, ModContainer mod) {
        COMPONENTS.register(bus);
        if (RELEASE_394) mod.registerConfig(ModConfig.Type.SERVER, CONFIG, CatalogPort.MOD_ID + "-matter.toml");
        NeoForge.EVENT_BUS.addListener(ChemistryRadiation::tick);
        NeoForge.EVENT_BUS.addListener(ChemistryRadiation::tooltip);
    }

    static boolean shieldDecomposer() { return !RELEASE_394 || !(CONFIG.isLoaded() ? DECAY_SAFE.get() : DEFAULT_MACHINE_OPTION); }
    static boolean fluidEffects() { return CONFIG.isLoaded() ? FLUID_EFFECTS.get() : @FLUID_EFFECTS@; }
    static int tier(String item) { var rule = RULES.get(item); return rule == null ? 0 : rule.tier(); }
    private static String id(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }
    private static long life(int tier) {
        int percent = CONFIG.isLoaded() ? HALF_LIFE.get() : DEFAULT_PERCENT;
        return (long)(LIVES[tier] * (percent / 100F));
    }
    private static String dimension(Level level) { return level.dimension().location().toString(); }
    private static Clock fresh(Level level) { return new Clock(0, level.getGameTime(), dimension(level)); }
    private static long elapsed(Clock clock, Level level) {
        return clock.tick() >= 0 && level.getGameTime() >= clock.tick() && dimension(level).equals(clock.dimension())
            ? level.getGameTime() - clock.tick() : 0;
    }
    private static long addAge(long age, long elapsed) {
        age = Math.max(0, age);
        return age + Math.min(Long.MAX_VALUE - age, elapsed);
    }

    static boolean checkpoint(ItemStack stack, Level level) {
        if (stack.isEmpty() || tier(id(stack)) == 0) return false;
        var previous = stack.get(CLOCK.get());
        var updated = new Clock(previous == null ? 0 : previous.age(), level.getGameTime(), dimension(level));
        if (updated.equals(previous)) return false;
        stack.set(CLOCK.get(), updated);
        return true;
    }

    private static Decayed step(ItemStack stack, Level level) {
        var rule = RULES.get(id(stack));
        if (rule == null || stack.isEmpty()) return null;
        var clock = stack.getOrDefault(CLOCK.get(), fresh(level));
        long age = addAge(clock.age(), elapsed(clock, level));
        long threshold = life(rule.tier());
        if (RELEASE_394 ? age <= threshold : age < threshold) {
            stack.set(CLOCK.get(), new Clock(age, level.getGameTime(), dimension(level)));
            return null;
        }
        if (RELEASE_394) {
            // The legacy element-bucket callback resets the same bucket's age, not the element ID.
            var bucket = ChemistryFluids.bucketType(stack);
            if (bucket != null && !bucket.chemical().contains(":molecule_")) {
                stack.set(CLOCK.get(), fresh(level));
                return new Decayed(List.of(stack), DAMAGE[rule.tier()] * stack.getCount());
            }
            var result = new ArrayList<ItemStack>();
            int count = stack.getCount();
            if (bucket != null) {
                count *= 8;
                result.add(net.minecraft.world.item.Items.BUCKET.getDefaultInstance());
            }
            for (var part : rule.products()) {
                int remaining = part.count() * count;
                while (remaining > 0) {
                    var product = new ItemStack(part.item(), Math.min(remaining, part.item().getDefaultMaxStackSize()));
                    if (tier(part.id()) > 0) product.set(CLOCK.get(), fresh(level));
                    result.add(product);
                    remaining -= product.getCount();
                }
            }
            return new Decayed(List.copyOf(result), DAMAGE[rule.tier()] * stack.getCount());
        }
        int dose = 0;
        ChemistryRecipes.Part product;
        do {
            age -= life(rule.tier());
            dose = Math.max(dose, DAMAGE[rule.tier()]);
            product = rule.products().getFirst();
            rule = RULES.get(product.id());
        } while (rule != null && age >= life(rule.tier()));
        var result = new ItemStack(product.item(), stack.getCount());
        if (rule != null) result.set(CLOCK.get(), new Clock(age, level.getGameTime(), dimension(level)));
        return new Decayed(List.of(result), dose);
    }

    private static void tick(PlayerTickEvent.Pre event) {
        Player player = event.getEntity();
        var level = player.level();
        if (level.isClientSide() || (!RELEASE_394 && level.getGameTime() % 20 != 0)) return;
        update(player);
    }

    // Keep overflow separate until all original slots have been checked once.
    static void update(Player player) {
        var menu = player.containerMenu;
        var level = player.level();
        var rules = menu instanceof MenuRules configured ? configured : null;
        var inventory = player.getInventory();
        var leftovers = new ArrayList<ItemStack>();
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            var changes = step(inventory.getItem(index), level);
            if (changes == null) continue;
            inventory.setItem(index, replacement(changes, leftovers));
            applyDose(player, rules, changes);
        }
        if (menu != player.inventoryMenu) for (var slot : menu.slots) {
            if (!slot.isActive() || slot.container == inventory || (rules != null && rules.virtualRadiationSlot(slot.index))) continue;
            if (rules != null && rules.shieldedRadiationSlot(slot.index)) {
                if (checkpoint(slot.getItem(), level)) slot.setChanged();
                continue;
            }
            var changes = step(slot.getItem(), level);
            if (changes == null) continue;
            var result = replacement(changes, leftovers);
            if (!slot.mayPlace(result) && !slot.getItem().is(result.getItem())) {
                // In a restricted input/output slot, replacing the item must not bypass its rules.
                slot.set(ItemStack.EMPTY);
                leftovers.add(result);
            } else slot.set(result);
            applyDose(player, rules, changes);
        }
        var cursor = step(menu.getCarried(), level);
        if (cursor != null) {
            menu.setCarried(replacement(cursor, leftovers));
            applyDose(player, rules, cursor);
        }
        for (var leftover : leftovers) if (!inventory.add(leftover)) player.drop(leftover, false);
    }

    private static ItemStack replacement(Decayed changes, List<ItemStack> leftovers) {
        var products = changes.products();
        leftovers.addAll(products.subList(1, products.size()));
        return products.getFirst();
    }
    private static void applyDose(Player player, MenuRules menu, Decayed decay) {
        int dose = Math.round(decay.dose() * (1F - (menu == null ? 0F : menu.radiationReduction())));
        if (dose > 0) player.hurt(player.damageSources().magic(), dose);
    }

    private static void tooltip(ItemTooltipEvent event) {
        var stack = event.getItemStack();
        int tier = tier(id(stack));
        if (tier == 0) return;
        var clock = stack.get(CLOCK.get());
        long left = Math.max(0, life(tier) - (clock == null ? 0 : clock.age()));
        event.getToolTip().add(Component.translatable("item.@NAMESPACE@.radioactive", tier, (left + 19) / 20));
    }

    private ChemistryRadiation() {}
}
