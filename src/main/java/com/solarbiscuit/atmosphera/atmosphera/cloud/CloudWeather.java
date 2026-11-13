package com.solarbiscuit.atmosphera.atmosphera.cloud;

import com.solarbiscuit.atmosphera.lib.wind.AtmosClock;
import com.solarbiscuit.atmosphera.lib.wind.Wind;
import com.solarbiscuit.atmosphera.lib.wind.WindVector;
import com.solarbiscuit.atmosphera.atmosphera.AtmospheraConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;

/**
 * The CPU side of the clouds, once per frame on the render thread with no allocation: weather and the camera's biome
 * smoothed into deck parameters (coverage, base, thickness, cloud-type weights), and the drift of each deck with the
 * shared wind ({@link Wind#global}, Gale's when installed).
 * <p>
 * The noise fields are the same on every client (fixed seed, world coordinates). The drift is integrated from the wind
 * on each client, like Gale's gust patches, so two players see the same kinds of clouds but not always the same cloud
 * at the same place.
 */
public final class CloudWeather {
    /** Low deck drift (blocks), unwrapped; the pass takes the fraction per noise tile in double precision. */
    static double lowX;
    static double lowZ;
    static double highX;
    static double highZ;
    /** 0.10.0: the shape noise's own drift (clouds form and dissolve instead of sliding as one rigid field). */
    static double evoX;
    static double evoZ;

    /** Smoothed inputs. */
    static float rain;
    static float thunder;
    /**
     * 0.6.4: how heavy the weather is for the clouds, sky and fog, 0..1 ({@link #wetness}). Vanilla rain always reaches
     * level 1, so the level alone made every shower as dark as a downpour.
     */
    static float wet;
    /**
     * How heavy ordinary rain is, 0..1: 0.8 is steady rain, with an overcast, mid-grey deck. 1 is the downpour look
     * every rain had before 0.6.4. (0.65, the first value, measured nearly as bright as a clear sky: 172/255 against
     * 97/255 at 1.) A weather mod (Stratos) will set it per spell.
     */
    private static volatile float rainIntensity = 0.8f;
    private static float dryness;
    private static float coldness;
    private static float targetDry;
    private static float targetCold;
    private static int biomeCountdown;

    /** Outputs for the pass. */
    static float coverage;
    static float base;
    static float thickness;
    /** 0 cumulus, 1 flat sheet (stratus/stratocumulus/nimbostratus). */
    static float stratiform;
    /** 0..1 storm towers (cumulonimbus). */
    static float towers;
    static float darken;
    /** 0.8.0: mammatus under the storm deck, 0..1: with the thunder, and fading over about 3 minutes after it. */
    static float mammatus;
    static float midCover;
    static float highCover;
    static float hazeDistance;

    /** 0.5.0: the surface wind's direction (unit, xz) for anvils, shelf clouds, rain shafts and orographic lift. */
    static float windX = 1f;
    static float windZ;

    /** The cirrus deck's cover now, 0..1 (halos and sun dogs form in it). Valid while the clouds are drawn. */
    public static float cirrusCover() {
        return highCover;
    }

    static float dryness() {
        return Math.max(0f, dryness);
    }

    static float coldness() {
        return Math.max(0f, coldness);
    }

    /** Sets how heavy rain is, 0 (drizzle) to 1 (downpour); see {@link #wetness}. Any thread. */
    public static void rainIntensity(float intensity) {
        rainIntensity = clamp01(intensity);
    }

    /**
     * The weather's weight for darkening and thickening, from the game's rain and thunder levels: the rain level times
     * the rain's intensity, with thunder raising it to a full downpour. Shared by the clouds, the sky, the fog and the
     * haze so they agree.
     */
    public static float wetness(float rainLevel, float thunderLevel) {
        float intensity = rainIntensity;
        return clamp01(rainLevel) * clamp01(intensity + (1f - intensity) * clamp01(thunderLevel));
    }

    private static final WindVector WIND = new WindVector();
    private static final com.solarbiscuit.atmosphera.lib.world.Weather.Sample WEATHER =
            new com.solarbiscuit.atmosphera.lib.world.Weather.Sample();
    private static ClientLevel lastLevel;

    private CloudWeather() {
    }

    static void update(ClientLevel level, float partialTick, double cameraX, double cameraY, double cameraZ) {
        float dt = (float) Math.min(0.25, Math.max(0.0, AtmosClock.deltaSeconds()));
        if (level != lastLevel) {
            lastLevel = level;
            rain = level.getRainLevel(partialTick);
            thunder = level.getThunderLevel(partialTick);
            biomeCountdown = 0;
            dryness = coldness = -1f;
        }
        boolean followWeather = AtmospheraConfig.weather.get();
        float rainNow = followWeather ? level.getRainLevel(partialTick) : 0f;
        float thunderNow = followWeather ? level.getThunderLevel(partialTick) : 0f;
        // 0.8.3: how heavy the rain is, from the library's Weather service when a weather mod (Stratos) states it;
        // otherwise ordinary rain (0.8).
        com.solarbiscuit.atmosphera.lib.world.Weather.current(level, partialTick, WEATHER);
        rainIntensity = WEATHER.modelled && rainNow > 0.05f ? clamp01(WEATHER.intensity / rainNow) : 0.8f;
        rain = approach(rain, rainNow, dt, 6f);
        thunder = approach(thunder, thunderNow, dt, 6f);
        wet = wetness(rain, thunder);
        // Share of a full downpour, for terms that are not zero in clear weather.
        float heavy = rain > 1e-3f ? wet / rain : 0f;

        if (--biomeCountdown <= 0) {
            biomeCountdown = 30;
            BlockPos pos = BlockPos.containing(cameraX, Math.min(cameraY, level.getMaxBuildHeight() - 1), cameraZ);
            Biome biome = level.getBiome(pos).value();
            Biome.Precipitation precipitation = biome.getPrecipitationAt(pos);
            float temperature = biome.getBaseTemperature();
            targetDry = !followWeather ? 0f : precipitation == Biome.Precipitation.NONE ? (temperature > 1.0f ? 1f : 0.6f) : 0f;
            targetCold = !followWeather ? 0f : precipitation == Biome.Precipitation.SNOW ? 1f : clamp01(0.4f - temperature);
            if (dryness < 0f) {
                dryness = targetDry;
                coldness = targetCold;
            }
        }
        // Across a biome border the sky changes over about 20 s, not at the line.
        dryness = approach(dryness, targetDry, dt, 20f);
        coldness = approach(coldness, targetCold, dt, 20f);

        CloudStyle style = CloudStyle.current();
        float amount = AtmospheraConfig.coverage.get().floatValue();
        // 0.9.0: amounts per cloud type (cumulus is the main amount; stratus beyond 100% also closes the sky a little).
        float stratus = AtmospheraConfig.stratusAmount.get().floatValue();
        float alto = AtmospheraConfig.altocumulusAmount.get().floatValue();
        float cirrus = AtmospheraConfig.cirrusAmount.get().floatValue();
        float clear = clamp01(style.coverage() * amount * (1f - 0.55f * dryness) + 0.2f * Math.max(stratus - 1f, 0f));
        // 0.6.4: steady rain is an overcast deck with thinner, brighter patches (0.92); a downpour closes it (0.95).
        coverage = clamp01(clear + rain * (0.78f + 0.17f * heavy - clear) + thunder * 0.03f);
        stratiform = clamp01(rain * (1f - thunder) * (0.6f + 0.3f * heavy)
                + (coldness * 0.35f * Math.min(stratus, 1f) + 0.45f * Math.max(stratus - 1f, 0f)) * (1f - thunder));
        towers = clamp01(thunder * 1.2f);
        // 0.10.0: mammatus follow the thunder, building over about 20 s and gone within a minute after it ends (was a
        // 3-minute linear fade, and the shader then hung pouches under any large fair-weather cloud). Below 10% they
        // are cut (CloudPass), so the fading tail does not linger.
        mammatus = approach(mammatus, towers, dt, towers > mammatus ? 20f : 25f);
        float maxDepth = AtmospheraConfig.lowThickness.get();
        base = AtmospheraConfig.lowBase.get() - 25f * rain;
        // Fair-weather cumulus use part of the deck, sheets less, storm towers all of it.
        // Nimbostratus in rain is thick too (0.1.1: was thinned, which left a featureless film).
        thickness = maxDepth * (0.62f + 0.38f * towers + 0.1f * wet);
        darken = style.rainDarken() * clamp01(wet * 0.8f + thunder * 0.4f);
        // In rain the altocumulus melts into a thin veil (the shader blends it by the stratiform weight).
        midCover = AtmospheraConfig.midDeck.get() ? clamp01((0.5f - 0.3f * dryness) * alto) * (1f - 0.3f * wet) : 0f;
        highCover = AtmospheraConfig.highDeck.get() ? clamp01((0.6f + 0.2f * coldness - 0.4f * wet) * cirrus) : 0f;
        hazeDistance = 3200f - 2100f * wet;

        // Drift: the low deck with the surface wind; the high deck faster and turned (wind shear aloft).
        Wind.global(level, partialTick, WIND);
        float speed = (float) (1.5 + 6.0 * WIND.strength + 3.0 * WIND.gust) * AtmospheraConfig.drift.get().floatValue();
        float windLen = (float) Math.sqrt(WIND.dirX * WIND.dirX + WIND.dirZ * WIND.dirZ);
        if (windLen > 1e-4f) {
            windX = (float) (WIND.dirX / windLen);
            windZ = (float) (WIND.dirZ / windLen);
        }
        lowX += WIND.dirX * speed * dt;
        lowZ += WIND.dirZ * speed * dt;
        float shear = AtmospheraConfig.shear.get().floatValue();
        double angle = Math.toRadians(35.0 * shear);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        float highSpeed = speed * (1f + 1.5f * shear);
        highX += (WIND.dirX * cos - WIND.dirZ * sin) * highSpeed * dt;
        highZ += (WIND.dirX * sin + WIND.dirZ * cos) * highSpeed * dt;
        // 0.10.0: the cloud shapes drift a little slower than the cover map and at an angle to it (60 degrees), so each
        // cloud changes as it travels, as real cumulus do; with the drift slider at 0 nothing moves.
        double evoSpeed = (0.6 + 0.25 * speed) * AtmospheraConfig.drift.get();
        evoX += (WIND.dirX * 0.5 - WIND.dirZ * 0.866) * evoSpeed * dt;
        evoZ += (WIND.dirX * 0.866 + WIND.dirZ * 0.5) * evoSpeed * dt;
    }

    /** Exponential approach with time constant {@code tau} seconds. */
    private static float approach(float value, float target, float dt, float tau) {
        return value + (target - value) * (1f - (float) Math.exp(-dt / tau));
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
