package @PACKAGE@;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** In-game pharmacology. The profile data and saved doses are local to this release. */
final class ChemistryEffects {
    private static final boolean RELEASE_394 = @PREPAID_DECOMPOSER@;
    private static final int LIMIT = 16;
    private static final Codec<List<String>> DOSES = Codec.STRING.listOf().validate(values ->
        values.size() <= LIMIT && values.stream().allMatch(value -> value.length() <= 160)
            ? DataResult.success(List.copyOf(values)) : DataResult.error(() -> "Invalid Minechem treatment"));
    private static final DeferredRegister.DataComponents COMPONENTS =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, CatalogPort.MOD_ID);
    private static final DeferredHolder<DataComponentType<?>, DataComponentType<List<String>>> TREATMENT =
        COMPONENTS.registerComponentType("chemical_treatment", builder ->
            builder.persistent(DOSES).networkSynchronized(ByteBufCodecs.fromCodec(DOSES)));
    private static final DeferredRegister<MobEffect> POTIONS = DeferredRegister.create(Registries.MOB_EFFECT, CatalogPort.MOD_ID);
    static final Holder<MobEffect> DELIRIUM = POTIONS.register("delirium", Delirium::new);
    private static final ResourceLocation OTHER_DOSE = ResourceLocation.fromNamespaceAndPath(
        RELEASE_394 ? "minechem_chemistry" : "minechem_chemistry_394", "chemical_treatment");
    private static final ModConfigSpec CONFIG;
    private static final ModConfigSpec.BooleanValue SWORDS, FOODS, RECREATIONAL;

    static {
        var builder = new ModConfigSpec.Builder();
        SWORDS = builder.define("swordEffects", true);
        FOODS = builder.define("foodSpiking", true);
        RECREATIONAL = builder.define("recreationalChemicalEffects", true);
        CONFIG = builder.build();
    }

    private record Profile(boolean coat, boolean food, boolean recreational, List<Action> actions) {
        boolean enabled() { return !recreational || enabledConfig(RECREATIONAL); }
    }
    private record Action(char kind, int a, int b, int c, float amount) {
        void apply(LivingEntity target) {
            switch (kind) {
                case 'p' -> target.addEffect(new MobEffectInstance(potion(a), b, c));
                case 'f' -> { if (target instanceof Player player) player.getFoodData().eat(a, amount); }
                case 'b' -> target.igniteForSeconds(a);
                case 'd' -> target.hurt(target.damageSources().generic(), amount);
                case 'c' -> {
                    if (RELEASE_394 || target instanceof Player) {
                        for (var effect : List.copyOf(target.getActiveEffects())) {
                            if (!RELEASE_394 || !effect.getCures().isEmpty()) target.removeEffect(effect.getEffect());
                        }
                    }
                }
            }
        }
    }
    private static final Map<String, Profile> PROFILES = Map.ofEntries(
@EFFECT_ROWS@
    );

    private static final class Delirium extends MobEffect {
        Delirium() { super(MobEffectCategory.HARMFUL, 0x00FF6E); }
    }

    private static Holder<MobEffect> potion(int id) {
        return switch (id) {
            case 1 -> MobEffects.MOVEMENT_SPEED;
            case 2 -> MobEffects.MOVEMENT_SLOWDOWN;
            case 3 -> MobEffects.DIG_SPEED;
            case 5 -> MobEffects.DAMAGE_BOOST;
            case 8 -> MobEffects.JUMP;
            case 9 -> MobEffects.CONFUSION;
            case 10 -> MobEffects.REGENERATION;
            case 15 -> MobEffects.BLINDNESS;
            case 16 -> MobEffects.NIGHT_VISION;
            case 17 -> MobEffects.HUNGER;
            case 18 -> MobEffects.WEAKNESS;
            case 19 -> MobEffects.POISON;
            case 20 -> MobEffects.WITHER;
            case 32 -> DELIRIUM;
            default -> throw new IllegalStateException("Unknown frozen Minechem potion " + id);
        };
    }

    static void register(IEventBus bus, ModContainer mod) {
        COMPONENTS.register(bus);
        POTIONS.register(bus);
        ChemicalTreatmentRecipe.register(bus);
        if (RELEASE_394) mod.registerConfig(ModConfig.Type.SERVER, CONFIG, CatalogPort.MOD_ID + "-effects.toml");
        NeoForge.EVENT_BUS.addListener(ChemistryEffects::strike);
        NeoForge.EVENT_BUS.addListener(ChemistryEffects::eat);
        NeoForge.EVENT_BUS.addListener(ChemistryEffects::tooltip);
    }

    private static boolean enabledConfig(ModConfigSpec.BooleanValue value) {
        return !RELEASE_394 || !CONFIG.isLoaded() || value.get();
    }

    private static String key(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }

    static boolean canTreat(ItemStack base, ItemStack chemical, boolean sword) {
        if (!(chemical.getItem() instanceof MoleculeItem) || !enabledConfig(sword ? SWORDS : FOODS)) return false;
        var profile = PROFILES.get(key(chemical));
        if (profile == null || !profile.enabled() || !(sword ? profile.coat() : profile.food())) return false;
        if (sword ? !(base.getItem() instanceof SwordItem) : !base.has(DataComponents.FOOD)) return false;
        var other = BuiltInRegistries.DATA_COMPONENT_TYPE.get(OTHER_DOSE);
        if (other != null && base.has(other)) return false;
        return (!sword && !RELEASE_394) || doses(base).size() < LIMIT;
    }

    static ItemStack treat(ItemStack base, ItemStack chemical, boolean sword) {
        var result = base.copyWithCount(1);
        var applied = new ArrayList<String>();
        if (sword || RELEASE_394) applied.addAll(doses(base));
        applied.add(key(chemical));
        result.set(TREATMENT.get(), List.copyOf(applied));
        return result;
    }

    private static List<String> doses(ItemStack stack) { return stack.getOrDefault(TREATMENT.get(), List.of()); }

    private static void apply(LivingEntity target, String key) {
        var profile = PROFILES.get(key);
        if (profile != null && profile.enabled()) for (var action : profile.actions()) action.apply(target);
    }

    private static void strike(LivingIncomingDamageEvent event) {
        var entity = event.getEntity();
        var damage = event.getSource();
        if (entity.level().isClientSide() || !enabledConfig(SWORDS) ||
                !(damage.getDirectEntity() instanceof LivingEntity attacker) || attacker != damage.getEntity()) return;
        var weapon = attacker.getMainHandItem();
        if (weapon.getItem() instanceof SwordItem) for (String dose : doses(weapon)) apply(entity, dose);
    }

    private static void eat(LivingEntityUseItemEvent.Finish event) {
        var entity = event.getEntity();
        var food = event.getItem();
        if (!entity.level().isClientSide() && food.has(DataComponents.FOOD) && enabledConfig(FOODS)) {
            for (String dose : doses(food)) apply(entity, dose);
        }
    }

    private static void tooltip(ItemTooltipEvent event) {
        var stack = event.getItemStack();
        var key = stack.getItem() instanceof SwordItem ? "coated" : "spiked";
        for (String dose : doses(stack)) {
            if (!PROFILES.containsKey(dose)) continue;
            var name = BuiltInRegistries.ITEM.get(ResourceLocation.parse(dose)).getDescription();
            event.getToolTip().add(Component.translatable("item.@NAMESPACE@.treatment." + key, name));
        }
    }

    static final class MoleculeItem extends Item {
        MoleculeItem(Properties properties) { super(properties); }
        @Override public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.DRINK; }
        @Override public int getUseDuration(ItemStack stack, LivingEntity user) { return 16; }
        @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            return ItemUtils.startUsingInstantly(level, player, hand);
        }
        @Override public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity user) {
            if (!level.isClientSide()) {
                apply(user, key(stack));
                level.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.PLAYER_BURP,
                        SoundSource.PLAYERS, 0.5F, 0.9F + level.getRandom().nextFloat() * 0.1F);
            }
            if (!RELEASE_394 || !(user instanceof Player player) || !player.getAbilities().instabuild) stack.shrink(1);
            return stack;
        }
    }

    private ChemistryEffects() {}
}
