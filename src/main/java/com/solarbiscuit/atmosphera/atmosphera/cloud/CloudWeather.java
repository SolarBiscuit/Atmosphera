package com.solarbiscuit.atmosphera.atmosphera.cloud;

import static com.solarbiscuit.atmosphera.lib.util.AtmosMath.smoothstep;
import static com.solarbiscuit.atmosphera.lib.util.AtmosMath.approach;
import static com.solarbiscuit.atmosphera.lib.util.AtmosMath.clamp01;

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
    /** 0..1 storm towers (cumulonimbus) in thunder. */
    static float towers;
    /** 0.13.0: towers including those a coming storm grows (deck height, cloud tops). */
    static float deckTowers;
    static float darken;
    /** 0.8.0: mammatus under the storm deck, 0..1: with the thunder, and fading over about 3 minutes after it. */
    static float mammatus;
    static float midCover;
    static float highCover;
    static float hazeDistance;
    /** 0.13.0 "Cloud life": share of the heaped deck that is stratocumulus (flat rolls), 0..1. */
    static float scShare;
    /** 0.13.0: convective potential, 0..1: cumulus towering into congestus and cumulonimbus (with anvils). */
    static float conv;
    /** 0.13.0: cirrocumulus cover, 0..1. */
    static float ccCover;
    /** 0.13.0: how far a forecast rain has built the sky (0 none, 1 rain now), eased. */
    static float pre;
    private static float postRain;
    private static float sinceRain = 1e4f;
    private static float warmth;
    /** 0.13.0: a forecast faked by /atmosphera clouds forecast (client only, for testing): game time and kind. */
    private static long fakeAt = -1L;
    private static com.solarbiscuit.atmosphera.lib.world.Weather.Type fakeKind;
    /** 0.13.0: a frozen life phase (0..1) from /atmosphera clouds life, or -1 to run. */
    static float lifeFreeze = -1f;

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

    /** 0.12.0: the weather kind's shift, -1 (drizzle, light rain) .. 1 (heavy rain, storm), eased. */
    public static float kind;
    private static final WindVector WIND = new WindVector();
    private static final com.solarbiscuit.atmosphera.lib.world.Weather.Sample WEATHER =
            new com.solarbiscuit.atmosphera.lib.world.Weather.Sample();
    private static ClientLevel lastLevel;

    private CloudWeather() {
    }

    /** 0.14.1: the client left a level; drop it (it kept the old world in memory at the title screen). */
    public static void forgetLevel() {
        lastLevel = null;
        // 0.15.1: a faked forecast is a game time of the world it was made in.
        fakeAt = -1L;
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
        // 0.12.0 (Stratos 0.4.0 "Kinds"): heavy rain and storms under darker, closer, lower decks; drizzle and light
        // rain under paler ones. Eased like the rain itself.
        float kindTarget = !WEATHER.modelled ? 0f : switch (WEATHER.type) {
            case HEAVY_RAIN, STORM -> 1f;
            case DRIZZLE, LIGHT_RAIN -> -1f;
            default -> 0f;
        };
        rain = approach(rain, rainNow, dt, 6f);
        thunder = approach(thunder, thunderNow, dt, 6f);
        wet = wetness(rain, thunder);
        kind = approach(kind, kindTarget, dt, 10f);
        // Share of a full downpour, for terms that are not zero in clear weather.
        float heavy = rain > 1e-3f ? wet / rain : 0f;

        if (--biomeCountdown <= 0) {
            biomeCountdown = 30;
            BlockPos pos = BlockPos.containing(cameraX, Math.min(cameraY, level.getMaxBuildHeight() - 1), cameraZ);
            Biome biome = level.getBiome(pos).value();
            Biome.Precipitation precipitation = biome.getPrecipitationAt(pos);
            float temperature = biome.getBaseTemperature();
            targetDry = !followWeather ? 0f : precipitation == Biome.Precipitation.NONE ? (temperature > 1.0f ? 1f : 0.6f) : 0f;
            warmth = precipitation == Biome.Precipitation.RAIN ? clamp01((temperature - 0.5f) * 2f) : 0f;
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
        coverage = clamp01(clear + rain * (0.78f + 0.17f * heavy - clear) + thunder * 0.03f + 0.05f * rain * Math.max(kind, 0f));
        stratiform = clamp01(rain * (1f - thunder) * (0.6f + 0.3f * heavy)
                + (coldness * 0.35f * Math.min(stratus, 1f) + 0.45f * Math.max(stratus - 1f, 0f)) * (1f - thunder));
        // 0.13.0 "Cloud life": the forecast builds the sky ahead of rain (cirrus, then cirrocumulus, an altostratus veil,
        // stratus and nimbostratus; towers before a storm), and the deck breaks into stratocumulus after it.
        forecast(level, dt, followWeather, heavy);
        coverage = clamp01(coverage + preLow);
        stratiform = clamp01(Math.max(stratiform, preStrat));
        // Thunder alone drives the storm extras (shelf cloud, mammatus, rain shafts); the forecast's convection only
        // grows the towers and the deck (deckTowers).
        towers = clamp01(thunder * 1.2f);
        deckTowers = clamp01(Math.max(towers, AtmospheraConfig.towersOutsideThunder.get() ? 0.9f * conv : 0f));
        // 0.10.0: mammatus follow the thunder, building over about 20 s and gone within a minute after it ends (was a
        // 3-minute linear fade, and the shader then hung pouches under any large fair-weather cloud). Below 10% they
        // are cut (CloudPass), so the fading tail does not linger.
        mammatus = approach(mammatus, towers, dt, towers > mammatus ? 20f : 25f);
        float maxDepth = AtmospheraConfig.lowThickness.get();
        base = AtmospheraConfig.lowBase.get() - 25f * rain - 10f * rain * Math.max(kind, 0f) - preBase;
        // Fair-weather cumulus use part of the deck, sheets less, storm towers all of it.
        // Nimbostratus in rain is thick too (0.1.1: was thinned, which left a featureless film).
        thickness = maxDepth * (0.62f + 0.38f * deckTowers + 0.1f * wet) * (1f - 0.4f * preThin);
        darken = style.rainDarken() * clamp01(wet * 0.8f + thunder * 0.4f + rain * (kind > 0f ? 0.15f : 0.1f) * kind);
        // In rain the altocumulus melts into a thin veil (the shader blends it by the stratiform weight).
        midCover = AtmospheraConfig.midDeck.get() ? clamp01((0.5f - 0.3f * dryness) * alto) * (1f - 0.3f * wet) : 0f;
        highCover = AtmospheraConfig.highDeck.get() ? clamp01((0.6f + 0.2f * coldness - 0.4f * wet) * cirrus + preHigh) : 0f;
        ccCover = AtmospheraConfig.highDeck.get()
                ? clamp01((0.15f * cirrus + preCc) * AtmospheraConfig.cirrocumulusAmount.get().floatValue()) : 0f;
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

    /** This frame's forecast terms (see {@link #forecast}). */
    private static float preLow, preStrat, preBase, preThin, preHigh, preCc;

    /**
     * 0.13.0: the forecast's terms. Stratos publishes the next spell's kind and when it comes (lib Weather); within the
     * lead time (5 minutes, 6 before a storm) the sky builds towards it: cirrus thickening, cirrocumulus, an altostratus
     * veil (the altocumulus melting), then a lowering, closing stratus deck, nimbostratus as the rain starts. Before a
     * storm, cumulus grow into towers (congestus, then cumulonimbus with anvils) instead. During rain, thunder that is
     * due ramps the towers up over the 3 minutes before it. After rain, the deck breaks up into stratocumulus over 4
     * minutes. Without a forecast (vanilla server), warm wet afternoons grow cumulus congestus.
     */
    private static void forecast(ClientLevel level, float dt, boolean followWeather, float heavy) {
        com.solarbiscuit.atmosphera.lib.world.Weather.Type next = WEATHER.next;
        long ticks = WEATHER.ticksToChange;
        boolean clear = rain < 0.05f;
        if (fakeAt >= 0L) {
            long left = fakeAt - level.getGameTime();
            if (left < -2400L) {
                fakeAt = -1L;
            } else if (clear) {
                next = fakeKind;
                ticks = Math.max(0L, left);
            }
        }
        boolean use = followWeather && AtmospheraConfig.forecast.get();
        float preTarget = 0f;
        boolean storm = next == com.solarbiscuit.atmosphera.lib.world.Weather.Type.STORM;
        if (use && clear && ticks >= 0L && next != com.solarbiscuit.atmosphera.lib.world.Weather.Type.CLEAR) {
            float lead = storm ? 7200f : 6000f;
            preTarget = smoothstep(lead, 0f, ticks);
        }
        pre = approach(pre, preTarget, dt, 8f);
        float rainKind = !storm ? pre : 0f;
        boolean drizzle = next == com.solarbiscuit.atmosphera.lib.world.Weather.Type.DRIZZLE;
        float late = smoothstep(0.6f, 1f, rainKind);
        if (drizzle) {
            // Drizzle: low stratus only, at the end.
            preHigh = 0f;
            preCc = 0f;
            preLow = 0.2f * late;
            preStrat = late;
            preBase = 20f * late;
            preThin = late;
        } else {
            preHigh = 0.35f * smoothstep(0f, 0.4f, rainKind) + 0.2f * smoothstep(0f, 0.3f, storm ? pre : 0f);
            preCc = 0.8f * smoothstep(0.1f, 0.35f, rainKind) * (1f - smoothstep(0.45f, 0.7f, rainKind));
            preLow = 0.25f * smoothstep(0.5f, 1f, rainKind) + (storm ? 0.1f * pre : 0f);
            preStrat = late;
            preBase = 15f * late;
            preThin = 0f;
        }
        // Convection: a coming storm, heavy rain coming, thunder due within the spell, thunder itself.
        float convTarget = 0f;
        if (use) {
            if (storm) {
                convTarget = smoothstep(0.2f, 1f, pre);
            } else if (next == com.solarbiscuit.atmosphera.lib.world.Weather.Type.HEAVY_RAIN) {
                convTarget = 0.4f * smoothstep(0.3f, 1f, pre);
            }
            if (!clear && WEATHER.ticksToStorm >= 0L) {
                convTarget = Math.max(convTarget, smoothstep(3600f, 0f, WEATHER.ticksToStorm));
            }
            if (!WEATHER.modelled) {
                // Vanilla server: warm, wet afternoons grow congestus (not cumulonimbus: below the anvil gate).
                long day = Math.floorMod(level.getDayTime(), 24000L);
                float afternoon = smoothstep(6000f, 8000f, day) * (1f - smoothstep(10000f, 11500f, day));
                convTarget = Math.max(convTarget, 0.3f * afternoon * warmth * (1f - dryness()));
            }
        }
        convTarget = Math.max(convTarget, thunder);
        conv = approach(conv, convTarget, dt, 12f);
        // After rain: the deck breaks up into stratocumulus over about 4 minutes.
        sinceRain = rain > 0.1f ? 0f : sinceRain + dt;
        postRain = use ? 1f - clamp01(sinceRain / 240f) : 0f;
        scShare = clamp01(0.2f + 0.3f * coldness() + 0.8f * postRain) * (1f - rain);
    }

    /** /atmosphera clouds forecast: fakes a coming spell of {@code kind} in {@code seconds} (client only). */
    public static void fakeForecast(ClientLevel level, int seconds, com.solarbiscuit.atmosphera.lib.world.Weather.Type kind) {
        fakeKind = kind;
        fakeAt = kind == null || level == null ? -1L : level.getGameTime() + seconds * 20L;
    }

    /** /atmosphera clouds life: holds the life cycle at a phase 0..1, or runs it (-1). */
    public static void freezeLife(float phase) {
        lifeFreeze = phase;
    }

    /** For /atmosphera status. */
    public static String describe() {
        return String.format(java.util.Locale.ROOT, "Clouds: coverage %.2f, stratiform %.2f, towers %.2f, convection %.2f, "
                        + "stratocumulus %.2f, cirrocumulus %.2f, forecast build-up %.2f%s", coverage, stratiform, towers,
                conv, scShare, ccCover, pre, fakeAt >= 0L ? " (faked)" : "");
    }

    /** Exponential approach with time constant {@code tau} seconds. */}
