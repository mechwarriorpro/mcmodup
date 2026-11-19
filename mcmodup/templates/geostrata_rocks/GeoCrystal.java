package mcmodup.geostratarocks;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Color-specific crystal behavior, using native scheduled ticks rather than display-driven packets. */
public final class GeoCrystal extends Block {
    enum Kind { CAVE, LAMP, POTION }
    private static final MapCodec<GeoCrystal> CODEC = simpleCodec(properties -> new GeoCrystal(DyeColor.WHITE, Kind.CAVE, properties));
    private static final VoxelShape CAVE_SHAPE = Block.box(2, 0, 2, 14, 14, 14);
    private static final VoxelShape PEDESTAL = Block.box(0, 0, 0, 16, 14, 16);
    private final DyeColor color;
    private final Kind kind;

    GeoCrystal(DyeColor color, Kind kind, Properties properties) {
        super(properties);
        this.color = color;
        this.kind = kind;
    }

    @Override protected MapCodec<GeoCrystal> codec() { return CODEC; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return kind == Kind.CAVE ? CAVE_SHAPE : PEDESTAL;
    }

    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState previous, boolean moved) {
        if (!level.isClientSide() && !previous.is(this) && kind != Kind.LAMP) {
            level.scheduleTick(pos, this, 20 + level.getRandom().nextInt(10));
        }
    }
    @Override protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (kind != Kind.LAMP && !level.getBlockTicks().hasScheduledTick(pos, this)) level.scheduleTick(pos, this, 1);
    }
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (kind == Kind.LAMP) return;
        level.scheduleTick(pos, this, 20);
        boolean nether = level.dimensionType().ultraWarm();
        int duration = kind == Kind.CAVE ? 200 : 6000;
        int amplifier = kind == Kind.CAVE ? 0 : 2;
        var center = Vec3.atCenterOf(pos);
        for (var entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos).inflate(kind == Kind.CAVE ? 3 : 4), LivingEntity::isAlive)) {
            if (entity.position().add(0, entity.getEyeHeight() * .5, 0).distanceToSqr(center) > 16) continue;
            apply(level, entity, nether, duration, amplifier, random);
        }
    }

    private void apply(ServerLevel level, LivingEntity entity, boolean nether, int duration, int amplifier, RandomSource random) {
        if (!nether) {
            if (color == DyeColor.WHITE) { entity.removeAllEffects(); return; }
            if (color == DyeColor.BLACK) { if (entity instanceof Mob mob && entity instanceof Enemy) { mob.setTarget(null); mob.getNavigation().stop(); } return; }
            if (color == DyeColor.PURPLE) {
                if (entity instanceof ServerPlayer player && random.nextBoolean()) player.giveExperiencePoints(1);
                return;
            }
        } else {
            if (color == DyeColor.ORANGE) { entity.igniteForSeconds(2); return; }
            if (color == DyeColor.RED) { entity.hurt(level.damageSources().magic(), 1); return; }
            if (color == DyeColor.PURPLE) {
                if (entity instanceof ServerPlayer player && player.experienceLevel > 0 && random.nextInt(5) == 0) player.giveExperienceLevels(-1);
                return;
            }
        }
        Holder<MobEffect> effect = nether ? netherEffect() : overworldEffect();
        if (effect == null) return;
        boolean harmful = effect.value().getCategory() == MobEffectCategory.HARMFUL;
        if (!nether && entity instanceof Player && harmful || nether && !(entity instanceof Player) && harmful) return;
        MobEffectInstance current = entity.getEffect(effect);
        if (current == null || (current.getAmplifier() <= amplifier && current.getDuration() <= duration)) {
            entity.addEffect(new MobEffectInstance(effect, duration, amplifier, true, true));
        }
    }

    private Holder<MobEffect> overworldEffect() {
        return switch (color) {
            case BLUE -> MobEffects.NIGHT_VISION;
            case BROWN -> MobEffects.SATURATION;
            case CYAN -> MobEffects.WATER_BREATHING;
            case GRAY -> MobEffects.MOVEMENT_SLOWDOWN;
            case GREEN -> MobEffects.POISON;
            case LIGHT_BLUE -> MobEffects.MOVEMENT_SPEED;
            case LIGHT_GRAY -> MobEffects.WEAKNESS;
            case LIME -> MobEffects.JUMP;
            case MAGENTA -> MobEffects.REGENERATION;
            case ORANGE -> MobEffects.FIRE_RESISTANCE;
            case PINK -> MobEffects.DAMAGE_BOOST;
            case RED -> MobEffects.DAMAGE_RESISTANCE;
            case YELLOW -> MobEffects.DIG_SPEED;
            default -> null;
        };
    }
    private Holder<MobEffect> netherEffect() {
        return switch (color) {
            case BLACK -> MobEffects.WITHER;
            case CYAN -> MobEffects.HUNGER;
            case GRAY -> MobEffects.BLINDNESS;
            case LIGHT_BLUE, LIME -> MobEffects.MOVEMENT_SLOWDOWN;
            case LIGHT_GRAY -> MobEffects.WEAKNESS;
            case YELLOW -> MobEffects.DIG_SLOWDOWN;
            case WHITE -> MobEffects.INVISIBILITY;
            case BROWN -> MobEffects.CONFUSION;
            case BLUE, GREEN, PINK, MAGENTA -> overworldEffect();
            default -> null;
        };
    }
}
