package @PACKAGE@;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

final class PolytoolRules {
    static final boolean RELEASE_394 = @RELEASE_394@;
    static final int BATCH = 64, MAX_RANK = 64, CAPACITY = Integer.MAX_VALUE;
    static final int HYDROGEN = 1, HELIUM = 2, LITHIUM = 3, BERYLLIUM = 4, BORON = 5, CARBON = 6,
        NITROGEN = 7, OXYGEN = 8, FLUORINE = 9, NEON = 10, SODIUM = 11, MAGNESIUM = 12, SILICON = 14,
        PHOSPHORUS = 15, SULFUR = 16, CHLORINE = 17, ARGON = 18, CALCIUM = 20, TITANIUM = 22,
        CHROMIUM = 24, IRON = 26, NICKEL = 28, ZINC = 30, BROMINE = 35, KRYPTON = 36, RUBIDIUM = 37,
        SILVER = 47, CAESIUM = 55, PLATINUM = 78, GOLD = 79, MERCURY = 80, LEAD = 82, FRANCIUM = 87, URANIUM = 92;
    static final String TEXT = "gui.@NAMESPACE@.polytool.";

    record Stats(int sword, int ore, int stone, int axe, int shovel) {
        private Stats add(Stats change, int rank) {
            return new Stats(sword + change.sword * rank, ore + change.ore * rank, stone + change.stone * rank,
                             axe + change.axe * rank, shovel + change.shovel * rank);
        }
        boolean positive() { return sword > 0 && ore > 0 && stone > 0 && axe > 0 && shovel > 0; }
    }

    private static final int BASE = 8;
    private static final Stats BASE_STATS = new Stats(BASE, BASE, BASE, BASE, BASE);
    record Infusion(int atomic, ResourceLocation tube, Stats alloy) {
        Component name() { return BuiltInRegistries.ITEM.get(tube).getDescription(); }
        String help() {
            if (alloy != null) return "alloy";
            return switch (atomic) {
                case LITHIUM -> "lithium";
                case OXYGEN -> "air";
                case SULFUR -> "fire";
                case IRON -> "vein";
                case CARBON -> "carbon";
                case FLUORINE -> "fluorine";
                case SODIUM -> "sodium";
                case CHROMIUM -> "chromium";
                case NICKEL -> "nickel";
                case BROMINE -> "bromine";
                case MERCURY -> "mercury";
                case HYDROGEN -> "hydrogen";
                case HELIUM -> "helium";
                case BERYLLIUM -> "beryllium";
                case BORON -> "boron";
                case NITROGEN -> "nitrogen";
                case NEON -> "neon";
                case MAGNESIUM -> "magnesium";
                case SILICON -> "silicon";
                case PHOSPHORUS -> "phosphorus";
                case CHLORINE -> "chlorine";
                case ARGON -> "argon";
                case CALCIUM -> "calcium";
                case TITANIUM -> "titanium";
                case ZINC -> "zinc";
                case KRYPTON -> "krypton";
                case RUBIDIUM -> "rubidium";
                case SILVER -> "silver";
                case CAESIUM -> "caesium";
                case PLATINUM -> "platinum";
                case GOLD -> "gold";
                case LEAD -> "lead";
                case FRANCIUM -> "francium";
                case URANIUM -> "uranium";
                default -> "inactive";
            };
        }
    }
    private static final List<Infusion> INFUSIONS = List.of(
@INFUSIONS@
    );
    private static final Map<Integer, Infusion> BY_ATOMIC = INFUSIONS.stream().collect(Collectors.toUnmodifiableMap(Infusion::atomic, Function.identity()));
    private static final Map<ResourceLocation, Infusion> BY_ITEM = INFUSIONS.stream().collect(Collectors.toUnmodifiableMap(Infusion::tube, Function.identity()));

    record Rank(int element, int rank) {
        private static final Codec<Rank> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(1, 118).fieldOf("element").forGetter(Rank::element),
            Codec.intRange(1, MAX_RANK).fieldOf("rank").forGetter(Rank::rank)).apply(instance, Rank::new));
    }

    record State(List<Rank> ranks, int charge) {
        private static final State EMPTY = new State(List.of(), 0);
        private static final Codec<State> CODEC = RecordCodecBuilder.<State>create(instance -> instance.group(
            Rank.CODEC.listOf().fieldOf("ranks").forGetter(State::ranks),
            Codec.intRange(0, CAPACITY).optionalFieldOf("charge", 0).forGetter(State::charge)).apply(instance, State::new))
            .validate(value -> value.ranks.size() <= INFUSIONS.size() && value.ranks.stream().allMatch(rank -> BY_ATOMIC.containsKey(rank.element)) &&
                value.ranks.stream().map(Rank::element).distinct().count() == value.ranks.size() &&
                value.stats().positive() && (value.charge == 0 || (!RELEASE_394 && value.rank(LITHIUM) > 0))
                ? DataResult.success(value) : DataResult.error(() -> "Invalid @NAMESPACE@ Polytool state"));

        State { ranks = List.copyOf(ranks); }
        int rank(int atomic) {
            for (var rank : ranks) if (rank.element == atomic) return rank.rank;
            return 0;
        }
        State add(int atomic) {
            var updated = new ArrayList<>(ranks);
            for (int i = 0; i < updated.size(); i++) {
                var current = updated.get(i);
                if (current.element == atomic) {
                    updated.set(i, new Rank(atomic, current.rank + 1));
                    return new State(updated, charge);
                }
            }
            updated.add(new Rank(atomic, 1));
            return new State(updated, charge);
        }
        Stats stats() {
            var result = BASE_STATS;
            for (var rank : ranks) {
                var alloy = BY_ATOMIC.get(rank.element).alloy;
                if (alloy != null) result = result.add(alloy, rank.rank);
            }
            // FINAL computed the sword stat through the cobweb dig path; the pinned Forge
            // run never applied an alloy to that path. .394 reads blade strength directly.
            return RELEASE_394 ? result : new Stats(BASE, result.ore, result.stone, result.axe, result.shovel);
        }
        int bonus() { return charge == 0 ? 0 : (int) Math.ceil(Math.max(0, Math.log10(charge) - 7)); }
    }

    private static final DeferredRegister.DataComponents COMPONENTS =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, CatalogPort.MOD_ID);
    private static final DeferredHolder<DataComponentType<?>, DataComponentType<State>> STATE =
        COMPONENTS.registerComponentType("polytool_state", builder -> builder.persistent(State.CODEC).networkSynchronized(ByteBufCodecs.fromCodec(State.CODEC)));

    private PolytoolRules() {}
    static void register(IEventBus bus) { COMPONENTS.register(bus); }
    static State state(ItemStack tool) { return tool.getOrDefault(STATE.get(), State.EMPTY); }
    static void write(ItemStack tool, State state) { tool.set(STATE.get(), state); }
    static Infusion element(ItemStack stack) { return BY_ITEM.get(BuiltInRegistries.ITEM.getKey(stack.getItem())); }
    static boolean isPayment(ItemStack stack) { return stack.getCount() == BATCH && element(stack) != null; }
    static Component rankLine(Rank rank) {
        return Component.translatable(TEXT + "rank", BY_ATOMIC.get(rank.element).name(), rank.rank);
    }

    static State candidate(ItemStack tool, ItemStack tubes) {
        var infusion = element(tubes);
        if (tubes.getCount() != BATCH || infusion == null || tool.getCount() != 1 || !(tool.getItem() instanceof PolytoolItem)) return null;
        var state = state(tool);
        if (state.rank(infusion.atomic) >= MAX_RANK) return null;
        var next = state.add(infusion.atomic);
        return infusion.alloy == null || next.stats().positive() ? next : null;
    }

    static int speed(State state, BlockState block) {
        // Modern tags substitute for the Forge effectiveness and stone OreDictionary lookup.
        // Cobwebs and obsidian were neutral on both pinned releases despite their tool requirements.
        if (block.is(Blocks.COBWEB) || block.is(Blocks.OBSIDIAN)) return BASE;
        var stats = state.stats();
        if (block.is(BlockTags.MINEABLE_WITH_PICKAXE))
            return block.is(Tags.Blocks.STONES) || block.is(Tags.Blocks.COBBLESTONES) ? stats.stone : stats.ore;
        if (block.is(BlockTags.MINEABLE_WITH_SHOVEL)) return stats.shovel;
        if (block.is(BlockTags.MINEABLE_WITH_AXE)) return stats.axe;
        return BASE;
    }
}
