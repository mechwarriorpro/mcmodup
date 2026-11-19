package @PACKAGE@;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import static @PACKAGE@.PolytoolRules.*;

/** Successful melee callbacks and player-melee loot rules. Every listener checks the release's own tool. */
final class PolytoolCombat {
    // An entity UUID and dimension cannot accidentally point to a different mob after a restart.
    private record Mark(ResourceLocation dimension, UUID entity) {}
    private static final DeferredRegister.DataComponents COMPONENTS =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, CatalogPort.MOD_ID);
    private static final DeferredHolder<DataComponentType<?>, DataComponentType<Mark>> HELIUM_MARK =
        COMPONENTS.registerComponentType("polytool_helium_target", builder -> {
            var codec = RecordCodecBuilder.<Mark>create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("dimension").forGetter(Mark::dimension),
                UUIDUtil.CODEC.fieldOf("entity").forGetter(Mark::entity)).apply(instance, Mark::new));
            return builder.persistent(codec).networkSynchronized(ByteBufCodecs.fromCodec(codec));
        });
    private static final List<EquipmentSlot> LEGACY_EQUIPMENT = List.of(EquipmentSlot.MAINHAND,
        EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD);

    static void register(IEventBus bus) {
        COMPONENTS.register(bus);
        if (!RELEASE_394) NeoForge.EVENT_BUS.addListener(PolytoolCombat::finalDamage);
        NeoForge.EVENT_BUS.addListener(PolytoolCombat::drops);
    }

    private static Player meleeAttacker(DamageSource source) {
        return source.is(DamageTypes.PLAYER_ATTACK) && source.getEntity() instanceof Player attacker &&
            source.getDirectEntity() == attacker && !attacker.isSpectator() ? attacker : null;
    }

    private static void finalDamage(LivingIncomingDamageEvent event) {
        var attacker = meleeAttacker(event.getSource());
        if (attacker == null || attacker.level().isClientSide() || !(attacker.getMainHandItem().getItem() instanceof PolytoolItem)) return;
        var ranks = state(attacker.getMainHandItem());
        int atomic = event.getEntity() instanceof Spider ? BORON : event.getEntity() instanceof EnderMan ? SILVER : 0;
        // FINAL adds 0.8 × rank to both old hurt phases. Fold both into the port's single base hit.
        if (atomic != 0) event.setAmount(event.getAmount() + 1.6F * ranks.rank(atomic));
    }

    static void strike(ItemStack tool, LivingEntity target, Player attacker, ServerLevel level) {
        for (var module : state(tool).ranks()) {
            int rank = module.rank();
            switch (module.element()) {
                case HYDROGEN, RUBIDIUM, FRANCIUM -> PolytoolWorld.hit(level, target, module.element(), rank);
                case HELIUM -> tool.set(HELIUM_MARK.get(), new Mark(level.dimension().location(), target.getUUID()));
                case BORON -> { if (RELEASE_394 && target instanceof Spider) playerDamage(target, attacker, .8F * rank); }
                case SILVER -> { if (RELEASE_394 && target instanceof EnderMan) playerDamage(target, attacker, .8F * rank); }
                case NEON -> attacker.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 10 * rank));
                case MAGNESIUM, CHLORINE, URANIUM -> {
                    var potion = switch (module.element()) {
                        case MAGNESIUM -> MobEffects.BLINDNESS;
                        case CHLORINE -> MobEffects.POISON;
                        default -> MobEffects.WITHER;
                    };
                    for (var nearby : inBox(level, target, rank)) nearby.addEffect(new MobEffectInstance(potion, 200, 1));
                }
                case SULFUR -> target.igniteForSeconds(rank + 1);
                case ARGON -> {
                    if (!level.canSeeSky(target.blockPosition())) target.hurt(level.damageSources().inWall(), rank);
                }
                case TITANIUM -> {
                    if (RELEASE_394 && target instanceof Mob mob) mob.setDropChance(EquipmentSlot.MAINHAND, .8F);
                }
                case KRYPTON -> {
                    if (target instanceof Player && target.getArmorValue() > 23)
                        target.hurt(level.damageSources().fellOutOfWorld(), rank);
                }
                case PLATINUM -> {
                    if (level.random.nextInt(50) < rank + 1) {
                        level.levelEvent(2002, BlockPos.containing(attacker.position().add(.5, .5, .5)), 0);
                        ExperienceOrb.award(level, attacker.position(), rank + level.random.nextInt(5) + level.random.nextInt(5));
                    }
                }
                case LEAD -> {
                    for (var nearby : inBox(level, target, rank * 3)) if (nearby != attacker) vertical(nearby, -50);
                }
                default -> {} // Effects owned by inventory, break or drop dispatch.
            }
        }
    }

    private static void playerDamage(LivingEntity target, Player player, float amount) {
        // .394 makes a second, smaller hit attempt; do not bypass vanilla hurt resistance.
        target.hurt(player.damageSources().playerAttack(player), amount);
    }

    private static List<LivingEntity> inBox(ServerLevel level, Entity center, int radius) {
        return level.getEntitiesOfClass(LivingEntity.class, new AABB(center.position(), center.position()).inflate(radius));
    }

    private static void vertical(Entity target, double speed) {
        var old = target.getDeltaMovement();
        target.setDeltaMovement(old.x, speed, old.z);
        target.hasImpulse = true;
        target.hurtMarked = true;
    }

    static void inventoryTick(ItemStack tool, ServerLevel level, Entity carrier) {
        for (var module : state(tool).ranks()) {
            if (module.element() == GOLD) PolytoolWorld.gold(level, carrier, module.rank());
            else if (module.element() == HELIUM) {
                var mark = tool.get(HELIUM_MARK.get());
                if (mark == null || !mark.dimension().equals(level.dimension().location())) continue;
                var target = level.getEntity(mark.entity());
                if (target != null && !target.isRemoved()) {
                    vertical(target, Math.min(target.getDeltaMovement().y + .1, 1));
                    if (level.random.nextInt(10 * module.rank()) == 0) tool.remove(HELIUM_MARK.get());
                }
            }
        }
    }

    private static void drops(LivingDropsEvent event) {
        var victim = event.getEntity();
        var killer = meleeAttacker(event.getSource());
        if (!(victim.level() instanceof ServerLevel level) || killer == null ||
            !(killer.getMainHandItem().getItem() instanceof PolytoolItem)) return;
        var ranks = state(killer.getMainHandItem());
        int silicon = ranks.rank(SILICON);
        var drops = event.getDrops();
        if (silicon > 0 && level.random.nextInt(16) < silicon + 1) {
            var products = new ArrayList<ItemEntity>();
            for (var drop : drops) decompose(drop, products, level, level.random);
            drops.clear();
            drops.addAll(products);
        }
        boolean skeleton = victim.getType() == EntityType.SKELETON || victim.getType() == EntityType.WITHER_SKELETON;
        boolean zombie = victim instanceof Zombie;
        if (!skeleton && !zombie && !(victim instanceof Player)) return;
        if (!RELEASE_394 && ranks.rank(TITANIUM) > 0) {
            for (var slot : LEGACY_EQUIPMENT) {
                var gear = victim.getItemBySlot(slot);
                if (!gear.isEmpty()) drops.add(drop(level, victim.position(), gear.copy()));
            }
        }
        if (zombie && ranks.rank(NITROGEN) > 0) {
            drops.removeIf(entity -> entity.getItem().is(Items.ROTTEN_FLESH));
            drops.add(drop(level, victim.position(), new ItemStack(Items.COOKED_BEEF, bonus(level.random, ranks.rank(NITROGEN)))));
        }
        if (skeleton && ranks.rank(CALCIUM) > 0) {
            int extra = bonus(level.random, ranks.rank(CALCIUM));
            var excess = new ArrayList<ItemEntity>();
            for (var entity : drops) if (entity.getItem().is(Items.BONE)) {
                var bones = entity.getItem();
                int fits = Math.min(extra, bones.getMaxStackSize() - bones.getCount());
                bones.grow(fits);
                if (fits < extra) excess.add(drop(level, entity.position(), new ItemStack(Items.BONE, extra - fits)));
            }
            drops.addAll(excess);
        }
        var head = skeleton ? (victim.getType() == EntityType.WITHER_SKELETON ? Items.WITHER_SKELETON_SKULL : Items.SKELETON_SKULL)
            : zombie ? Items.ZOMBIE_HEAD : Items.PLAYER_HEAD;
        for (int rank = ranks.rank(BERYLLIUM); rank > 5; rank--) drops.add(drop(level, victim.position(), new ItemStack(head)));
    }

    private static int bonus(RandomSource random, int rank) { return (int) Math.ceil(random.nextDouble() * rank); }

    private static void decompose(ItemEntity old, List<ItemEntity> result, ServerLevel level, RandomSource random) {
        var item = old.getItem();
        var recipe = ChemistryRecipes.input(item);
        // Legacy could NPE on unmapped drops. Leave those drops unchanged rather than crashing the world.
        if (recipe == null) { result.add(old); return; }
        Map<Item, Integer> parts = new LinkedHashMap<>();
        for (int i = 0; i < item.getCount(); i++) {
            var units = RELEASE_394 ? MaterialRules.roll(recipe, random) : MaterialRules.rawProducts(recipe);
            for (var part : units) parts.merge(part.item(), part.count(), Integer::sum);
        }
        parts.forEach((product, count) -> {
            while (count > 0) {
                var stack = new ItemStack(product, count);
                stack.limitSize(stack.getMaxStackSize());
                result.add(drop(level, old.position(), stack));
                count -= stack.getCount();
            }
        });
    }

    private static ItemEntity drop(ServerLevel level, Vec3 pos, ItemStack stack) {
        var entity = new ItemEntity(level, pos.x, pos.y, pos.z, stack);
        entity.setDefaultPickUpDelay();
        return entity;
    }
}
