package com.solarbiscuit.atmosphera.stratos.air;

import com.mojang.blaze3d.shaders.FogShape;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.wind.AtmosClock;
import com.solarbiscuit.atmosphera.stratos.Stratos;
import com.solarbiscuit.atmosphera.stratos.StratosConfig;
import com.solarbiscuit.atmosphera.stratos.cloud.CloudWeather;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * The air (0.6.0 "Air"): the game's fog shaped by the biomes around the camera, the weather and the time of day,
 * re-implemented from scratch for Stratos (Atmospherics, whose fog this replaces, is ARR).
 * <ul>
 *   <li>Biome fog: colour tint and fog distance from {@link AirData} profiles, averaged over the camera's biome and
 *       eight around it (48 blocks out), sampled a few times a second and eased over about 3 s, so borders blend.</li>
 *   <li>Weather fog: rain draws the fog in and greys it, thunder more.</li>
 *   <li>Dawn mist: low over water and wet ground around sunrise, thinning as the sun climbs; rarer in dry biomes.</li>
 * </ul>
 * Only the overworld's kind of sky, only terrain fog (not water, lava, powder snow or blindness, which vanilla and
 * Torrente handle). While Atmospherics is installed the feature defers to it (capability {@code ATMOSPHERIC_FOG}).
 * The distant haze over the terrain and LODs is {@link HazePass}.
 */
public final class AirFog {
    private static AtmosFeature feature;

    // Eased state: the fog tint colour and amount, fog distance scale, haze scale, mist scale.
    private static float tintR;
    private static float tintG;
    private static float tintB;
    private static float tint;
    private static float distance = 1f;
    private static float haze = 1f;
    private static float mist = 1f;
    private static float wet;
    /** The dawn mist's strength now, 0..1 (for the fogbow). */
    private static float mistNow;
    // Targets, from the last biome sampling.
    private static float tR;
    private static float tG;
    private static float tB;
    private static float tT;
    private static float tD = 1f;
    private static float tH = 1f;
    private static float tM = 1f;
    private static float tWet;
    private static int sampleCountdown;
    private static String lastBiome = "";
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    private static final int[][] RING = {{0, 0}, {48, 0}, {-48, 0}, {0, 48}, {0, -48}, {34, 34}, {-34, 34}, {34, -34},
            {-34, -34}};

    private AirFog() {
    }

    public static void register() {
        feature = AtmosFeature.builder(Stratos.MOD_ID, "air")
                .provides(Capability.ATMOSPHERIC_FOG)
                .uses(AtmosFeature.Uses.OTHER)
                .switchedBy(StratosConfig.air)
                .register();
        MinecraftForge.EVENT_BUS.addListener(AirFog::fogColor);
        MinecraftForge.EVENT_BUS.addListener(AirFog::renderFog);
        HazePass.register(feature);
    }

    public static AtmosFeature feature() {
        return feature;
    }

    /** The eased haze scale of the biomes around (1 neutral), for the haze pass. */
    /** The dawn mist around the camera now, 0..1. */
    public static float mistNow() {
        return mistNow;
    }

    static float hazeScale() {
        return haze;
    }

    private static boolean applies(Camera camera, ClientLevel level) {
        return feature != null && feature.isActive() && level != null
                && level.effects().skyType() == DimensionSpecialEffects.SkyType.NORMAL
                && camera.getFluidInCamera() == FogType.NONE;
    }

    /** Samples the biomes around the camera (every 10 ticks) and eases the state. Called from the colour event. */
    private static void update(ClientLevel level, Camera camera) {
        if (--sampleCountdown <= 0) {
            sampleCountdown = 10;
            float r = 0f, g = 0f, b = 0f, t = 0f, d = 0f, h = 0f, m = 0f, w = 0f;
            BlockPos at = camera.getBlockPosition();
            for (int[] off : RING) {
                POS.set(at.getX() + off[0], at.getY(), at.getZ() + off[1]);
                Holder<Biome> biome = level.getBiome(POS);
                AirData.Profile p = AirData.of(biome);
                r += p.r() * p.tint();
                g += p.g() * p.tint();
                b += p.b() * p.tint();
                t += p.tint();
                d += p.fogDistance();
                h += p.haze();
                m += p.mist();
                boolean water = biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)
                        || biome.is(BiomeTags.IS_BEACH) || biome.value().getBaseTemperature() < 0.95f
                        && biome.value().getModifiedClimateSettings().downfall() > 0.7f;
                w += water ? 1f : 0f;
            }
            // The camera's biome and its profile, logged when it changes (support logs and tests).
            String here = level.getBiome(at).unwrapKey().map(k -> k.location().toString()).orElse("?");
            if (!here.equals(lastBiome)) {
                lastBiome = here;
                AirData.Profile p = AirData.of(level.getBiome(at));
                Stratos.LOGGER.info("[air] {}: tint {} fog distance {} haze {} mist {}", here, p.tint(), p.fogDistance(),
                        p.haze(), p.mist());
            }
            float n = RING.length;
            tT = t / n;
            if (t > 1e-4f) {
                tR = r / t;
                tG = g / t;
                tB = b / t;
            }
            tD = d / n;
            tH = h / n;
            tM = m / n;
            tWet = w / n;
        }
        float dt = (float) Math.min(0.25, Math.max(0.0, AtmosClock.deltaSeconds()));
        float k = 1f - (float) Math.exp(-dt / 3f);
        tintR += (tR - tintR) * k;
        tintG += (tG - tintG) * k;
        tintB += (tB - tintB) * k;
        tint += (tT - tint) * k;
        distance += (tD - distance) * k;
        haze += (tH - haze) * k;
        mist += (tM - mist) * k;
        wet += (tWet - wet) * k;
    }

    private static void fogColor(ViewportEvent.ComputeFogColor event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Camera camera = event.getCamera();
        if (!applies(camera, level)) {
            return;
        }
        update(level, camera);
        float strength = StratosConfig.biomeFog.get().floatValue();
        float a = Math.min(1f, tint * strength);
        float red = event.getRed() + (tintR * brightness(event) - event.getRed()) * a;
        float green = event.getGreen() + (tintG * brightness(event) - event.getGreen()) * a;
        float blue = event.getBlue() + (tintB * brightness(event) - event.getBlue()) * a;
        if (StratosConfig.weatherFog.get()) {
            // Rain and thunder grey the fog (towards its own brightness).
            float partial = (float) event.getPartialTick();
            float grey = 0.5f * CloudWeather.wetness(level.getRainLevel(partial), level.getThunderLevel(partial))
                    + 0.2f * level.getThunderLevel(partial);
            float l = 0.3f * red + 0.59f * green + 0.11f * blue;
            red += (l - red) * grey;
            green += (l - green) * grey;
            blue += (l - blue) * grey;
        }
        event.setRed(red);
        event.setGreen(green);
        event.setBlue(blue);
    }

    /** The fog colour's own brightness (day/night): the biome colour is scaled to it, so nights stay dark. */
    private static float brightness(ViewportEvent.ComputeFogColor event) {
        float max = Math.max(event.getRed(), Math.max(event.getGreen(), event.getBlue()));
        return Math.min(1f, max * 1.15f);
    }

    private static void renderFog(ViewportEvent.RenderFog event) {
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Camera camera = event.getCamera();
        if (!applies(camera, level) || event.getFogShape() != FogShape.CYLINDER && event.getFogShape() != FogShape.SPHERE) {
            return;
        }
        float partial = (float) event.getPartialTick();
        float scale = 1f + (distance - 1f) * StratosConfig.biomeFog.get().floatValue();
        if (StratosConfig.weatherFog.get()) {
            scale *= 1f - 0.35f * CloudWeather.wetness(level.getRainLevel(partial), level.getThunderLevel(partial))
                    - 0.2f * level.getThunderLevel(partial);
        }
        if (StratosConfig.morningMist.get()) {
            // Dawn mist: from just before sunrise until the sun is about 20 degrees up, low over wet ground.
            long time = level.getDayTime() % 24000L;
            float dawn = time > 22500L ? (time - 22500L) / 1500f : time < 2500L ? 1f - time / 2500f : 0f;
            float low = 1f - smoothstep(64f, 110f, (float) camera.getPosition().y);
            float m = dawn * low * wet * Math.min(1.5f, mist);
            mistNow = Math.min(1f, m);
            scale *= 1f - 0.6f * Math.min(1f, m);
        } else {
            mistNow = 0f;
        }
        if (Math.abs(scale - 1f) < 0.002f) {
            return;
        }
        float far = event.getFarPlaneDistance();
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance() * scale, far * scale * 0.9f));
        event.setFarPlaneDistance(far * scale);
        event.setCanceled(true);
    }

    private static float smoothstep(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }
}
