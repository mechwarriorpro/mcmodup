package mcmodup.geostratarocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

@EventBusSubscriber(modid = GeoRocks.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class GeoConnectedClient {
    private static final RenderType OVERLAY = RenderType.translucent();
    private static final ChunkRenderTypeSet OVERLAY_LAYER = ChunkRenderTypeSet.of(OVERLAY);
    private static final ModelProperty<Integer> IN_PLANE = new ModelProperty<>();

    @SubscribeEvent
    public static void loadOverlays(ModelEvent.RegisterAdditional event) {
        for (int index = 1; index <= 9; index++) event.register(overlay(index));
    }

    @SubscribeEvent
    public static void attachOverlays(ModelEvent.ModifyBakingResult event) {
        var masks = new Overlays(event.getModels());
        event.getModels().replaceAll((name, base) -> name.id().getNamespace().equals(GeoRocks.ID) &&
            name.id().getPath().endsWith("_connected") && name.getVariant().isEmpty() ? new ConnectedModel(base, masks) : base);
    }

    private static ModelResourceLocation overlay(int index) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(GeoRocks.ID, "block/connected/" + index));
    }

    private static final class ConnectedModel extends BakedModelWrapper<BakedModel> {
        private final Overlays overlays;
        ConnectedModel(BakedModel base, Overlays overlays) { super(base); this.overlays = overlays; }

        @Override public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
            // An overlay mask takes 10 bits. Opposite faces share one plane.
            int packed = 0;
            for (var axis : Direction.Axis.values()) packed |= ConnectedRock.edges(level, pos, state, axis) << (10 * axis.ordinal());
            return super.getModelData(level, pos, state, data).derive().with(IN_PLANE, packed).build();
        }

        @Override public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
            return ChunkRenderTypeSet.union(super.getRenderTypes(state, random, data), OVERLAY_LAYER);
        }

        @Override public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType layer) {
            if (layer != OVERLAY) return super.getQuads(state, side, random, data, layer);
            if (side == null) return List.of();
            Integer bits = data.get(IN_PLANE);
            int mask = bits == null ? ConnectedRock.ALL_EDGES : (bits >>> (10 * side.getAxis().ordinal())) & ConnectedRock.ALL_EDGES;
            return overlays.get(side, mask);
        }
    }

    private static final class Overlays {
        private final BakedQuad[][] quads = new BakedQuad[Direction.values().length][10];
        private final Map<Integer, List<BakedQuad>> merged = new ConcurrentHashMap<>();

        Overlays(Map<ModelResourceLocation, BakedModel> models) {
            var random = RandomSource.create(0);
            for (int index = 1; index <= 9; index++) {
                var model = models.get(overlay(index));
                if (model == null) continue;
                for (var face : Direction.values()) {
                    var faceQuads = model.getQuads(null, face, random, ModelData.EMPTY, null);
                    if (faceQuads.size() == 1) quads[face.ordinal()][index] = faceQuads.getFirst();
                }
            }
        }

        List<BakedQuad> get(Direction face, int mask) {
            return merged.computeIfAbsent((face.ordinal() << 10) | mask, key -> {
                var out = new ArrayList<BakedQuad>(Integer.bitCount(mask));
                for (int index = 1; index <= 9; index++) {
                    var quad = quads[face.ordinal()][index];
                    if (quad != null && (mask & (1 << index)) != 0) out.add(quad);
                }
                return List.copyOf(out);
            });
        }
    }
}
