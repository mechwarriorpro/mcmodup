package @PACKAGE@;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.event.ItemAttributeModifierEvent;
import net.neoforged.neoforge.registries.DeferredItem;

import static @PACKAGE@.PolytoolRules.*;

/** Native held-item implementation for the synthesized Polytool, with no legacy item APIs. */
final class PolytoolItem extends PickaxeItem {
    private boolean breakingExtraOre;

    PolytoolItem(Item.Properties properties) {
        super(Tiers.IRON, properties.attributes(PickaxeItem.createAttributes(Tiers.IRON, 1.0F, -2.8F)));
    }

    static void register(IEventBus bus, DeferredItem<PolytoolItem> item) {
        PolytoolRules.register(bus);
        PolytoolMenu.register(bus);
        NeoForge.EVENT_BUS.addListener((ItemAttributeModifierEvent event) -> {
            var stack = event.getItemStack();
            if (!(stack.getItem() instanceof PolytoolItem)) return;
            var view = state(stack);
            // Use one native attack: FINAL's legacy on-hit strength, or .394's observed attribute.
            int amount = view.stats().sword() + view.bonus() - (RELEASE_394 ? 0 : 1);
            event.replaceModifier(Attributes.ATTACK_DAMAGE,
                new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID, amount, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
        });
        if (!RELEASE_394) bus.addListener((RegisterCapabilitiesEvent event) ->
            event.registerItem(Capabilities.EnergyStorage.ITEM, (stack, context) ->
                state(stack).rank(LITHIUM) > 0 ? new Receiver(stack) : null, item.get()));
    }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var tool = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(tool);
        if (!level.isClientSide()) player.openMenu(new SimpleMenuProvider(
            (id, inventory, actor) -> new PolytoolMenu(id, inventory, tool), Component.translatable(TEXT + "title")));
        return InteractionResultHolder.sidedSuccess(tool, level.isClientSide());
    }

    @Override public float getDestroySpeed(ItemStack stack, BlockState block) { return speed(state(stack), block); }

    @Override public boolean mineBlock(ItemStack stack, Level level, BlockState block, BlockPos pos, LivingEntity miner) {
        if (!level.isClientSide() && !breakingExtraOre) {
            var upgrades = state(stack);
            int oxygen = upgrades.rank(OXYGEN);
            if (oxygen > 0 && miner.getAirSupply() < miner.getMaxAirSupply())
                miner.setAirSupply(Math.min(miner.getMaxAirSupply(), miner.getAirSupply() + oxygen));
            int iron = upgrades.rank(IRON);
            if (iron > 0 && miner instanceof ServerPlayer player && player.getMainHandItem() == stack && block.is(Tags.Blocks.ORES))
                mineAdjacent(player, stack, block, pos, iron);
        }
        return true; // Neither original consumed durability on a break.
    }

    private void mineAdjacent(ServerPlayer player, ItemStack stack, BlockState block, BlockPos origin, int budget) {
        var level = player.serverLevel();
        var open = new ArrayDeque<BlockPos>();
        var visited = new HashSet<BlockPos>();
        open.add(origin);
        visited.add(origin);
        breakingExtraOre = true;
        try {
            while (!open.isEmpty() && budget > 0 && player.getMainHandItem() == stack) {
                var pos = open.removeFirst();
                for (var direction : Direction.values()) {
                    var next = pos.relative(direction);
                    if (!visited.add(next) || !level.isInWorldBounds(next) || !level.hasChunkAt(next) || !level.mayInteract(player, next)) continue;
                    var other = level.getBlockState(next);
                    if (!other.is(block.getBlock()) || (RELEASE_394 && other != block) || !stack.isCorrectToolForDrops(other)) continue;
                    // Take the player's normal break path, not a raw block replacement. Canceled breaks
                    // are not counted or traversed. Do not recurse into module hooks for bonus ore.
                    if (player.gameMode.destroyBlock(next) && level.getBlockState(next) != other) {
                        open.add(next);
                        if (--budget == 0 || player.getMainHandItem() != stack) break;
                    }
                }
            }
        } finally {
            breakingExtraOre = false;
        }
    }

    @Override public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (!attacker.level().isClientSide()) {
            int sulfur = state(stack).rank(SULFUR);
            if (sulfur > 0) target.igniteForSeconds(sulfur + 1);
        }
        return true;
    }

    @Override public void postHurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        // The legacy item hook reported success but never wore down the tool.
    }

    @Override public void appendHoverText(ItemStack tool, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        var view = state(tool);
        lines.add(Component.translatable(TEXT + "usage"));
        for (var rank : view.ranks().stream().limit(7).toList()) lines.add(rankLine(rank));
        if (view.ranks().size() > 7) lines.add(Component.translatable(TEXT + "more", view.ranks().size() - 7));
        if (!RELEASE_394 && view.rank(LITHIUM) > 0) {
            lines.add(Component.translatable(TEXT + "charge", view.charge()));
            lines.add(Component.translatable(TEXT + "bonus", view.bonus()));
        }
    }

    private record Receiver(ItemStack tool) implements IEnergyStorage {
        @Override public int receiveEnergy(int requested, boolean simulate) {
            if (requested <= 0 || !canReceive()) return 0;
            var view = state(tool);
            int rank = view.rank(LITHIUM);
            long room = (long) CAPACITY - view.charge();
            int accepted = (int) Math.min(requested, (room + rank - 1) / rank);
            if (accepted > 0 && !simulate) {
                int charged = (int) Math.min(CAPACITY, view.charge() + (long) accepted * rank);
                write(tool, new State(view.ranks(), charged));
            }
            return accepted;
        }
        @Override public int extractEnergy(int requested, boolean simulate) { return 0; }
        @Override public int getEnergyStored() { return state(tool).charge(); }
        @Override public int getMaxEnergyStored() { return CAPACITY; }
        @Override public boolean canExtract() { return false; }
        @Override public boolean canReceive() { return !tool.isEmpty() && state(tool).rank(LITHIUM) > 0; }
    }
}
