package @PACKAGE@;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FlowingFluid;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Bounded .394 gameplay reactions, driven only by local fluids and the measured original table. */
final class FluidReactions {
    private static final String WATER = CatalogPort.MOD_ID + ":molecule_water";
    private static final ModConfigSpec CONFIG;
    private static final ModConfigSpec.BooleanValue ITEM_CONTACT, FLUID_CONTACT;
    private static final boolean DEFAULT_ITEM = @ITEM_CONTACT@, DEFAULT_FLUID = @FLUID_CONTACT@;
    static {
        var builder = new ModConfigSpec.Builder();
        ITEM_CONTACT = builder.comment("React when a dropped chemical bucket meets a matching fluid.")
            .define("reactionItemMeetFluid", DEFAULT_ITEM);
        FLUID_CONTACT = builder.comment("React when matching fluids touch as blocks.")
            .define("reactionFluidMeetFluid", DEFAULT_FLUID);
        CONFIG = builder.build();
    }
    private record Pair(String first, String second) {
        static Pair of(String a, String b) { return a.compareTo(b) < 0 ? new Pair(a, b) : new Pair(b, a); }
    }
    private record Rule(float explosion, List<String> products) {}
    private static final Map<Pair, Rule> RULES = Map.ofEntries(
@REACTION_RULES@
    );
    private static final Map<String, Float> IGNITION = Map.ofEntries(
@IGNITION_RULES@
    );

    static void register(ModContainer mod) {
        mod.registerConfig(ModConfig.Type.SERVER, CONFIG, CatalogPort.MOD_ID + "-fluid-reactions.toml");
        NeoForge.EVENT_BUS.addListener(FluidReactions::itemContact);
    }

    static void touch(ServerLevel level, BlockPos pos) {
        String current = chemicalAt(level, pos);
        if (current == null || WATER.equals(current)) return;
        if (CONFIG.isLoaded() ? FLUID_CONTACT.get() : DEFAULT_FLUID) for (var face : Direction.values()) {
            var neighbor = pos.relative(face);
            String next = chemicalAt(level, neighbor);
            if (next == null) continue;
            var rule = RULES.get(Pair.of(current, next));
            if (rule == null) continue;
            boolean full = level.getFluidState(pos).isSource() && level.getFluidState(neighbor).isSource();
            // Both inputs are consumed. Output uses the neighbor as the center, like the old callback.
            if (level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()) &&
                level.setBlockAndUpdate(neighbor, Blocks.AIR.defaultBlockState())) produce(level, neighbor, rule, full);
            return;
        }
        var power = IGNITION.get(current);
        if (power == null) return;
        for (var face : Direction.values()) {
            var neighbor = pos.relative(face);
            if (!accessible(level, neighbor)) continue;
            var state = level.getBlockState(neighbor);
            if (state.is(Blocks.FIRE) || state.is(Blocks.LAVA)) {
                if (level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())) explode(level, pos, power);
                return;
            }
        }
    }

    static void onExplosion(ServerLevel level, BlockPos pos, String chemical) {
        var power = IGNITION.get(chemical);
        if (power != null) explode(level, pos, power);
    }

    private static void itemContact(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof ItemEntity item) || !(item.level() instanceof ServerLevel level) ||
                item.isRemoved() || !(CONFIG.isLoaded() ? ITEM_CONTACT.get() : DEFAULT_ITEM)) return;
        var stack = item.getItem();
        var bucket = ChemistryFluids.bucketType(stack);
        if (bucket == null) return;
        var pos = item.blockPosition();
        String fluid = chemicalAt(level, pos);
        if (fluid == null) return;
        var rule = RULES.get(Pair.of(bucket.chemical(), fluid));
        if (rule == null) return;
        // Don't consume the target pool in the item-triggered path. Pay once before applying effects.
        if (stack.getCount() <= 1) item.discard();
        else item.setItem(stack.copyWithCount(stack.getCount() - 1));
        produce(level, pos, rule, level.getFluidState(pos).isSource());
        drop(level, pos, new ItemStack(Items.BUCKET));
    }

    private static boolean accessible(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.hasChunkAt(pos) && level.getWorldBorder().isWithinBounds(pos);
    }
    private static String chemicalAt(ServerLevel level, BlockPos pos) {
        if (!accessible(level, pos)) return null;
        var state = level.getBlockState(pos);
        if (state.is(Blocks.WATER)) return WATER;
        var chemical = ChemistryFluids.worldType(state.getFluidState().getType());
        return chemical == null ? null : chemical.chemical();
    }
    private static void produce(ServerLevel level, BlockPos center, Rule rule, boolean full) {
        if (rule.explosion() > 0) explode(level, center, rule.explosion());
        Set<BlockPos> used = new HashSet<>();
        for (String key : rule.products()) {
            var tube = BuiltInRegistries.ITEM.get(ResourceLocation.parse(key));
            var sample = new ItemStack(tube, 8);
            var chemical = ChemistryFluids.tubeType(sample);
            if (chemical == null && !WATER.equals(key)) continue;
            int density = chemical == null ? 10 : chemical.density();
            if (!full && density == 0) continue;
            var spot = space(level, center, density < 0, used);
            if (spot == null) {
                if (full) drop(level, center, sample);
                continue;
            }
            used.add(spot);
            // The original registers water as a reagent, but no Minechem world block
            // for that product. It only drops water tubes when no output cell is free.
            if (chemical == null) continue;
            var fluid = (FlowingFluid) chemical.source();
            var state = full ? fluid.defaultFluidState() : fluid.getFlowing(7, false);
            if (!level.setBlockAndUpdate(spot, state.createLegacyBlock()) && full) drop(level, center, sample);
        }
    }
    private static BlockPos space(ServerLevel level, BlockPos center, boolean gas, Set<BlockPos> used) {
        // Three layers, gas from the top and other products from the bottom. Never replace another block.
        for (int layer = 0; layer < 3; layer++) {
            int y = gas ? 1 - layer : layer - 1;
            for (int x = 1; x >= -1; x--) for (int z = 1; z >= -1; z--) {
                var spot = center.offset(x, y, z);
                if (!used.contains(spot) && accessible(level, spot) && level.isEmptyBlock(spot)) return spot;
            }
        }
        return null;
    }
    private static void drop(ServerLevel level, BlockPos pos, ItemStack item) {
        Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, item);
    }
    private static void explode(ServerLevel level, BlockPos pos, float power) {
        level.explode(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, power, false, Level.ExplosionInteraction.BLOCK);
    }
    private FluidReactions() {}
}
