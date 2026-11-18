/* NeoForge port of the Bedrock fog/cloud/light rules, for use with Thaumic Tinkerer (CC BY-NC-SA 3.0). */
package thaumic.tinkerer.neoforge.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import org.joml.Vector3f;

final class BedrockEffects extends DimensionSpecialEffects {
    // GTNH Thaumic Tinkerer 2.13.0, WorldProviderBedrock.getFogColor/getCloudHeight.
    private static final double FOG_FACTOR = .15 / 255;
    private static final Vec3 FOG = new Vec3(160 * FOG_FACTOR, 128 * FOG_FACTOR, 160 * FOG_FACTOR);

    BedrockEffects() {
        super(0, true, SkyType.NORMAL, false, false);
    }

    static void register(RegisterDimensionSpecialEffectsEvent event) {
        event.register(ResourceLocation.fromNamespaceAndPath("thaumictinkerer", "bedrock"), new BedrockEffects());
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 biomeFog, float daylight) {
        return FOG;
    }

    @Override
    public boolean isFoggyAt(int x, int z) {
        return false;
    }

    @Override
    public void adjustLightmapColors(ClientLevel level, float partialTicks, float skyDarken, float flicker,
                                    float skyLight, int blockLight, int skyLevel, Vector3f colors) {
        // WorldProviderBedrock's brightness table exceeded the final colour cap for all levels.
        // Set that full-bright input rather than letting daylight and torch flicker tint it.
        // Later vanilla client effects (including 1.21's Darkness) still apply.
        colors.set(1F, 1F, 1F);
    }
}
