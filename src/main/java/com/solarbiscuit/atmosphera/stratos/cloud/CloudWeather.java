package com.solarbiscuit.atmosphera.stratos.cloud;

import com.solarbiscuit.atmosphera.lib.wind.AtmosClock;
import com.solarbiscuit.atmosphera.lib.wind.Wind;
import com.solarbiscuit.atmosphera.lib.wind.WindVector;
import com.solarbiscuit.atmosphera.stratos.StratosConfig;
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

    /** Smoothed inputs. */
    static float rain;
    static float thunder;
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
    static float midCover;
    static float highCover;
    static float hazeDistance;

    private static final WindVector WIND = new WindVector();
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
        boolean followWeather = StratosConfig.weather.get();
        float rainNow = followWeather ? level.getRainLevel(partialTick) : 0f;
        float thunderNow = followWeather ? level.getThunderLevel(partialTick) : 0f;
        rain = approach(rain, rainNow, dt, 6f);
        thunder = approach(thunder, thunderNow, dt, 6f);

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
        float amount = StratosConfig.coverage.get().floatValue();
        float clear = style.coverage() * amount * (1f - 0.55f * dryness);
        coverage = clamp01(clear + rain * (0.95f - clear) + thunder * 0.03f);
        stratiform = clamp01(rain * (1f - thunder) * 0.9f + coldness * 0.35f);
        towers = clamp01(thunder * 1.2f);
        float maxDepth = StratosConfig.lowThickness.get();
        base = StratosConfig.lowBase.get() - 25f * rain;
        // Fair-weather cumulus use part of the deck, sheets less, storm towers all of it.
        // Nimbostratus in rain is thick too (0.1.1: was thinned, which left a featureless film).
        thickness = maxDepth * (0.62f + 0.38f * towers + 0.1f * rain);
        darken = style.rainDarken() * clamp01(rain * 0.8f + thunder * 0.4f);
        // In rain the altocumulus melts into a thin veil (the shader blends it by the stratiform weight).
        midCover = StratosConfig.midDeck.get() ? clamp01(0.5f - 0.3f * dryness) * (1f - 0.3f * rain) : 0f;
        highCover = StratosConfig.highDeck.get() ? clamp01(0.6f + 0.2f * coldness - 0.4f * rain) : 0f;
        hazeDistance = 3200f - 2100f * rain;

        // Drift: the low deck with the surface wind; the high deck faster and turned (wind shear aloft).
        Wind.global(level, partialTick, WIND);
        float speed = (float) (1.5 + 6.0 * WIND.strength + 3.0 * WIND.gust) * StratosConfig.drift.get().floatValue();
        lowX += WIND.dirX * speed * dt;
        lowZ += WIND.dirZ * speed * dt;
        float shear = StratosConfig.shear.get().floatValue();
        double angle = Math.toRadians(35.0 * shear);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        float highSpeed = speed * (1f + 1.5f * shear);
        highX += (WIND.dirX * cos - WIND.dirZ * sin) * highSpeed * dt;
        highZ += (WIND.dirX * sin + WIND.dirZ * cos) * highSpeed * dt;
    }

    /** Exponential approach with time constant {@code tau} seconds. */
    private static float approach(float value, float target, float dt, float tau) {
        return value + (target - value) * (1f - (float) Math.exp(-dt / tau));
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
