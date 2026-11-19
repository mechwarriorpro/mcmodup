package mcmodup.geostratarocks;

import java.util.EnumMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

final class GeoCrystals {
    private static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, GeoRocks.ID);
    private static final EnumMap<DyeColor, DeferredBlock<GeoCrystal>> CAVE = new EnumMap<>(DyeColor.class);

    static void register(IEventBus bus, DeferredRegister.Blocks blocks, DeferredRegister.Items items, List<DeferredItem<?>> display) {
        for (var color : DyeColor.values()) {
            var name = color.getName();
            CAVE.put(color, register(blocks, items, display, "cave_crystal_" + name, color, GeoCrystal.Kind.CAVE, 9));
            register(blocks, items, display, "crystal_lamp_" + name, color, GeoCrystal.Kind.LAMP, 15);
            register(blocks, items, display, "potion_crystal_" + name, color, GeoCrystal.Kind.POTION, 15);
            display.add(items.registerSimpleItem("crystal_shard_" + name));
        }
        FEATURES.register("cave_crystal", CrystalFeature::new);
        FEATURES.register(bus);
    }

    private static DeferredBlock<GeoCrystal> register(DeferredRegister.Blocks blocks, DeferredRegister.Items items,
            List<DeferredItem<?>> display, String name, DyeColor color, GeoCrystal.Kind kind, int light) {
        var block = blocks.register(name, () -> {
            var properties = BlockBehaviour.Properties.of().strength(1F, .6F).noOcclusion().lightLevel(state -> light);
            if (kind != GeoCrystal.Kind.LAMP) properties.randomTicks();
            return new GeoCrystal(color, kind, properties);
        });
        display.add(items.registerSimpleBlockItem(block));
        return block;
    }

    private static final class CrystalFeature extends Feature<NoneFeatureConfiguration> {
        CrystalFeature() { super(NoneFeatureConfiguration.CODEC); }
        @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            var level = context.level();
            var random = context.random();
            var origin = context.origin();
            var color = DyeColor.byId(random.nextInt(16));
            boolean placed = false;
            // Stay inside the current generation column and never replace stone or fluids.
            for (int i = 0; i < 8; ++i) {
                var pos = origin.below(i);
                if (level.isOutsideBuildHeight(pos.below())) break;
                if (!level.isEmptyBlock(pos)) continue;
                var support = pos.below();
                var state = level.getBlockState(support);
                if (!state.is(BlockTags.BASE_STONE_OVERWORLD) || !state.isFaceSturdy(level, support, Direction.UP)) continue;
                BlockState crystal = CAVE.get(color).get().defaultBlockState();
                if (level.setBlock(pos, crystal, 2)) {
                    level.scheduleTick(pos, crystal.getBlock(), 20);
                    placed = true;
                }
                break;
            }
            return placed;
        }
    }
}
