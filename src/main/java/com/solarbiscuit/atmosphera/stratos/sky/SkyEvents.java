package com.solarbiscuit.atmosphera.stratos.sky;

import com.solarbiscuit.atmosphera.lib.client.AtmosClient;
import com.solarbiscuit.atmosphera.lib.wind.AtmosClock;
import com.solarbiscuit.atmosphera.lib.world.SunSource;
import com.solarbiscuit.atmosphera.stratos.StratosConfig;
import com.solarbiscuit.atmosphera.stratos.air.AirFog;
import com.solarbiscuit.atmosphera.stratos.cloud.CloudWeather;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;

/**
 * The sky's occasional sights (0.7.0 "Optics", 0.8.0 "Night", 0.9.0 "Omens"): which are showing now and how strongly.
 * <p>
 * Every effect has a switch and, where it is a matter of luck, a chance. The luck is a hash of the in-game day, the
 * dimension and the effect, so every player on a server sees the same halo, the same nebula and the same blood moon;
 * nothing is sent over the network. Conditions (the sun's height, cirrus, cold biomes, clear weather) come on top.
 * {@code /stratos skyeffect} can force an effect on or off for this client; forcing skips the luck and the biome and
 * weather conditions, but not what the effect needs to be drawn at all (a blood moon still needs the night).
 * <p>
 * Once per frame on the render thread ({@link #update}), with no allocation. Strengths ease in and out over a few
 * seconds, so nothing pops when a condition flips.
 */
public final class SkyEvents {
    /** The effects, with the names the command uses. */
    public enum Effect {
        HALO("halo"),
        SUN_DOGS("sun_dogs"),
        CIRCUMZENITHAL_ARC("circumzenithal_arc"),
        GREEN_FLASH("green_flash"),
        RAINBOW("rainbow"),
        MOONBOW("moonbow"),
        FOGBOW("fogbow"),
        LIGHT_PILLARS("light_pillars"),
        AURORA("aurora"),
        NOCTILUCENT("noctilucent"),
        ZODIACAL_LIGHT("zodiacal_light"),
        METEOR_SHOWER("meteor_shower"),
        NEBULA("nebula"),
        BLOOD_MOON("blood_moon"),
        MAMMATUS("mammatus");

        public final String id;

        Effect(String id) {
            this.id = id;
        }

        public static Effect byId(String id) {
            for (Effect e : ALL) {
                if (e.id.equals(id)) {
                    return e;
                }
            }
            return null;
        }
    }

    private static final Effect[] ALL = Effect.values();
    private static final int N = ALL.length;
    public static final int AUTO = 0, ON = 1, OFF = -1;

    /** What is in force now: the command's choice, else the config's {@code events.force} list. */
    private static final int[] forced = new int[N];
    private static final int[] commanded = new int[N];
    private static final int[] configured = new int[N];
    private static int commandedNebulaType;
    private static int configuredNebulaType;
    private static java.util.List<? extends String> appliedForceList;
    private static final float[] strength = new float[N];
    private static final float[] target = new float[N];
    private static int forcedNebulaType;
    private static long lastFrame = -1;
    private static ClientLevel lastLevel;

    // Inputs kept for the command's "what it needs" answers.
    private static float sunY;
    private static float moonLight;
    private static int moonPhase;
    private static float cold;
    private static float targetCold;
    private static int coldCountdown;
    private static float rainLevel;
    private static float prevRain;

    // Rainbow: seconds left of the bow that a shower's end started (0 = none), and whose light makes it.
    private static float bowSeconds;
    private static float bowTotal;
    private static boolean bowByMoon;

    // Light pillars: up to PILLARS seeded world positions around the camera, refreshed a few times a second.
    static final int PILLARS = 8;
    private static final double[] pillarX = new double[PILLARS];
    private static final double[] pillarZ = new double[PILLARS];
    private static final long[] pillarHash = new long[PILLARS];
    private static int pillarCount;
    private static int pillarCountdown;

    static final int METEORS = 4;

    // ---- Uniform data for the sky program (see SkyGlsl), filled by update(). ----
    /** u_Fx0..u_Fx7, four floats each. */
    static final float[] FX = new float[32];
    /** Two sun dogs: direction xyz, strength. */
    static final float[] DOGS = new float[8];
    /** Pillars: horizontal direction xy, 1 / width squared, height falloff. */
    static final float[] PILLAR = new float[PILLARS * 4];
    /** Pillars: colour times intensity. */
    static final float[] PILLAR_COL = new float[PILLARS * 4];
    /** Meteors, two rows each: head xyz + brightness, tail xyz + width. */
    static final float[] METEOR = new float[METEORS * 8];
    private static boolean any;
    private static int meteorCount;
    private static int pillarsDrawn;

    // Eclipse state for the moon's light.
    private static float eclipseCover;
    private static final float[] MOON_TINT = {1f, 1f, 1f};

    private SkyEvents() {
    }

    // ------------------------------------------------------------------------------------------------------------
    // The command's side

    /** Forces an effect on or off for this client, or hands it back to chance ({@link #AUTO}). */
    public static void force(Effect effect, int mode) {
        commanded[effect.ordinal()] = mode;
        resolveForced();
    }

    private static void resolveForced() {
        for (int i = 0; i < N; i++) {
            forced[i] = commanded[i] != AUTO ? commanded[i] : configured[i];
        }
        forcedNebulaType = commandedNebulaType > 0 ? commandedNebulaType : configuredNebulaType;
    }

    /**
     * The config's {@code events.force} list: effect names to force on ("aurora"), a nebula type ("nebula:3"), or off
     * ("-rainbow"). Read again only when Forge hands out a new list (the file changed). The command wins over it.
     */
    private static void readForceList() {
        java.util.List<? extends String> list = StratosConfig.forceEvents.get();
        if (list == appliedForceList) {
            return;
        }
        appliedForceList = list;
        java.util.Arrays.fill(configured, AUTO);
        configuredNebulaType = 0;
        for (String raw : list) {
            String entry = raw.trim().toLowerCase(java.util.Locale.ROOT);
            int mode = ON;
            if (entry.startsWith("-")) {
                mode = OFF;
                entry = entry.substring(1);
            }
            int colon = entry.indexOf(':');
            String name = colon < 0 ? entry : entry.substring(0, colon);
            Effect effect = Effect.byId("red_moon".equals(name) ? "blood_moon" : name);
            if (effect == null) {
                continue;
            }
            configured[effect.ordinal()] = mode;
            if (effect == Effect.NEBULA && colon >= 0) {
                try {
                    configuredNebulaType = Math.max(0, Math.min(999, Integer.parseInt(entry.substring(colon + 1))));
                } catch (NumberFormatException ignored) {
                    // No type: the night's own.
                }
            }
        }
        resolveForced();
    }

    public static int forced(Effect effect) {
        return forced[effect.ordinal()];
    }

    /** Which nebula image a forced nebula shows (1-based, wrapped into the list), or 0 for the night's own. */
    public static void forceNebulaType(int type) {
        commandedNebulaType = Math.max(0, Math.min(999, type));
        resolveForced();
    }

    /** Hands everything the command forced back to chance (the config's list stays in force). */
    public static void clearForced() {
        java.util.Arrays.fill(commanded, AUTO);
        commandedNebulaType = 0;
        resolveForced();
    }

    /** Current strength, 0..1 (eased). */
    public static float strength(Effect effect) {
        return strength[effect.ordinal()];
    }

    /** True while any sky-pass effect is showing (the shader skips the whole block otherwise). */
    static boolean any() {
        return any;
    }

    /**
     * Why a forced effect cannot be drawn right now, or null when it can. Forcing skips chance, biome and weather, but
     * a rainbow still needs the sun and a blood moon the night.
     */
    public static String needs(Effect effect) {
        boolean day = sunY > 0.02f;
        boolean night = sunY < -0.2f;
        return switch (effect) {
            case HALO -> day || sunY < -0.15f ? null : "the sun or the moon well above the horizon";
            case SUN_DOGS -> sunY > 0.03f && sunY < 0.6f ? null : "a sun lower than about 35 degrees";
            case CIRCUMZENITHAL_ARC -> sunY > 0.08f && sunY < 0.52f ? null : "a sun between 5 and 31 degrees up";
            case GREEN_FLASH -> "the moment the sun's top edge touches the horizon (it shows by itself then)";
            case RAINBOW -> sunY > 0f && sunY < 0.67f ? null : "a sun lower than 42 degrees (look away from it)";
            case MOONBOW -> sunY < -0.1f && sunY > -0.67f ? null : "a moon lower than 42 degrees (look away from it)";
            case FOGBOW -> sunY > 0.05f && sunY < 0.6f ? null : "a low sun (look away from it)";
            case LIGHT_PILLARS -> night || Math.abs(sunY) < 0.12f ? null : "the night, or a sun at the horizon";
            case AURORA, ZODIACAL_LIGHT, METEOR_SHOWER, NEBULA -> night ? null : "a dark night";
            case NOCTILUCENT -> sunY < -0.07f && sunY > -0.29f ? null : "deep twilight (the sun 6 to 16 degrees down)";
            case BLOOD_MOON -> sunY < -0.1f ? null : "the night";
            case MAMMATUS -> null;
        };
    }

    // ------------------------------------------------------------------------------------------------------------
    // What other parts of Stratos read

    /** Mammatus for the cloud march: {@link #ON}, {@link #OFF}, or {@link #AUTO} (the storm decides). */
    public static int mammatusMode() {
        return forced[Effect.MAMMATUS.ordinal()];
    }

    /** The moon's light during an eclipse, 0.12 (totality) to 1. */
    public static float moonLightScale() {
        return 1f - 0.88f * eclipseCover * strength[Effect.BLOOD_MOON.ordinal()];
    }

    /** Fills rgb with the moon's tint and returns true while an eclipse changes it (lib {@code SkySource.moonTint}). */
    public static boolean moonTint(float[] rgb) {
        float s = eclipseCover * strength[Effect.BLOOD_MOON.ordinal()];
        if (s < 0.01f) {
            return false;
        }
        float light = moonLightScale();
        rgb[0] = lerp(1f, MOON_TINT[0], s) * light;
        rgb[1] = lerp(1f, MOON_TINT[1], s) * light;
        rgb[2] = lerp(1f, MOON_TINT[2], s) * light;
        return true;
    }

    /**
     * Fills rgb with the tint of the moon's disc itself and returns true during an eclipse: for moons Stratos does not
     * draw (vanilla's, a skybox's). Copper red and dim in totality, but not as dark as the light it casts.
     */
    public static boolean discTint(float[] rgb) {
        float s = eclipseCover * strength[Effect.BLOOD_MOON.ordinal()];
        if (s < 0.01f) {
            return false;
        }
        rgb[0] = lerp(1f, 0.5f, s);
        rgb[1] = lerp(1f, 0.13f, s);
        rgb[2] = lerp(1f, 0.06f, s);
        return true;
    }

    /** True while a forced or seeded eclipse is on, so the moon is drawn full whatever its phase. */
    static boolean eclipseNow() {
        return strength[Effect.BLOOD_MOON.ordinal()] > 0.01f;
    }

    /** The bow's source for the bow pass: true = the moon. */
    static boolean bowByMoon() {
        return bowByMoon;
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Brings the state up to this frame; cheap to call from several passes (only the first call does the work). */
    public static void ensure(ClientLevel level, float partial) {
        long frame = AtmosClient.frame();
        if (frame == lastFrame || level == null) {
            return;
        }
        lastFrame = frame;
        update(level, partial);
    }

    private static void update(ClientLevel level, float partial) {
        float dt = (float) Math.min(0.25, Math.max(0.0, AtmosClock.deltaSeconds()));
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        boolean fresh = level != lastLevel;
        if (fresh) {
            lastLevel = level;
            coldCountdown = 0;
            cold = -1f;
            bowSeconds = 0f;
            pillarCountdown = 0;
            prevRain = level.getRainLevel(partial);
        }
        readForceList();
        boolean master = StratosConfig.events.get();
        float frequency = StratosConfig.eventFrequency.get().floatValue();
        long dayTime = level.getDayTime();
        long day = Math.floorDiv(dayTime, 24000L);
        int tod = (int) Math.floorMod(dayTime, 24000L);
        long dim = level.dimension().location().hashCode();
        float sx = SunSource.dirX();
        float sz = SunSource.dirZ();
        sunY = SunSource.dirY();
        moonPhase = level.getMoonPhase();
        moonLight = level.getMoonBrightness();
        rainLevel = level.getRainLevel(partial);
        float clear = 1f - smoothstep(0.05f, 0.4f, rainLevel);
        float dark = smoothstep(-0.10f, -0.26f, sunY);
        boolean clouds = com.solarbiscuit.atmosphera.stratos.cloud.CloudPass.drawing();
        float cirrus = clouds ? CloudWeather.cirrusCover() : 0.4f;

        if (--coldCountdown <= 0) {
            coldCountdown = 40;
            BlockPos pos = BlockPos.containing(cam.x, Math.min(cam.y, level.getMaxBuildHeight() - 1), cam.z);
            Biome biome = level.getBiome(pos).value();
            targetCold = biome.getPrecipitationAt(pos) == Biome.Precipitation.SNOW ? 1f
                    : clamp01(0.4f - biome.getBaseTemperature());
            if (cold < 0f) {
                cold = targetCold;
            }
        }
        cold += (targetCold - cold) * (1f - (float) Math.exp(-dt / 15f));
        float icy = smoothstep(0.45f, 0.8f, cold);

        java.util.Arrays.fill(target, 0f);

        // ---- 0.7.0 "Optics": ice crystals in cirrus, around the sun or a bright moon. ----
        float veil = smoothstep(0.2f, 0.5f, cirrus) * clear;
        boolean haloMoon = sunY < -0.15f;
        float haloLight = haloMoon ? smoothstep(0.15f, 0.3f, -sunY) * smoothstep(0.6f, 0.9f, moonLight)
                : smoothstep(0.05f, 0.2f, sunY);
        set(Effect.HALO, StratosConfig.halo.get(), lucky(Effect.HALO, day, dim, 0, 0.15f * frequency) ? veil : 0f, haloLight);
        float dogSun = smoothstep(0.02f, 0.1f, sunY) * (1f - smoothstep(0.5f, 0.62f, sunY));
        set(Effect.SUN_DOGS, StratosConfig.sunDogs.get(), lucky(Effect.SUN_DOGS, day, dim, 0, 0.08f * frequency) ? veil : 0f,
                dogSun);
        float czaSun = smoothstep(0.07f, 0.15f, sunY) * (1f - smoothstep(0.48f, 0.53f, sunY));
        set(Effect.CIRCUMZENITHAL_ARC, StratosConfig.circumzenithalArc.get(),
                lucky(Effect.CIRCUMZENITHAL_ARC, day, dim, 0, 0.05f * frequency) ? veil : 0f, czaSun);
        // The green flash: the last sliver of the sun, for the moment its top edge is at the horizon.
        float flash = 1f - smoothstep(0.006f, 0.02f, Math.abs(sunY + 0.004f));
        set(Effect.GREEN_FLASH, StratosConfig.greenFlash.get(),
                lucky(Effect.GREEN_FLASH, day, dim, tod < 18000 ? 1 : 2, 0.10f * frequency) ? clear : 0f, flash);

        // ---- Bows: a shower's end may leave one (seeded by the moment the rain stopped). ----
        boolean bowsOn = StratosConfig.rainbow.get();
        if (prevRain >= 0.2f && rainLevel < 0.2f && master && bowsOn) {
            long moment = dayTime / 600L;
            boolean byMoon = sunY < -0.1f;
            float chance = StratosConfig.rainbowChance.get().floatValue() * frequency;
            if (unit(mix(moment * 0x9E3779B97F4A7C15L ^ dim * 0xC2B2AE3D27D4EB4FL ^ 0x51L)) < chance
                    && (byMoon ? moonLight > 0.6f && sunY > -0.67f : sunY > 0.02f && sunY < 0.67f)) {
                bowTotal = 75f + 45f * unit(mix(moment * 0xD6E8FEB86659FD93L));
                bowSeconds = bowTotal;
                bowByMoon = byMoon;
            }
        }
        prevRain = rainLevel;
        float bow = 0f;
        if (bowSeconds > 0f) {
            bowSeconds = Math.max(0f, bowSeconds - dt);
            bow = Math.min(1f, (bowTotal - bowSeconds) / 5f) * Math.min(1f, bowSeconds / 12f);
        }
        float bowSun = smoothstep(0f, 0.05f, sunY) * (1f - smoothstep(0.6f, 0.67f, sunY));
        float bowMoon = smoothstep(0.1f, 0.2f, -sunY) * (1f - smoothstep(0.6f, 0.67f, -sunY)) * smoothstep(0.5f, 0.8f, moonLight);
        set(Effect.RAINBOW, bowsOn, bowByMoon ? 0f : bow, bowSun);
        set(Effect.MOONBOW, bowsOn, bowByMoon ? bow : 0f, bowMoon);
        if (forced[Effect.MOONBOW.ordinal()] == ON) {
            bowByMoon = true;
        } else if (forced[Effect.RAINBOW.ordinal()] == ON || bowSeconds <= 0f) {
            bowByMoon = false;
        }
        float mist = AirFog.mistNow();
        float fogSun = smoothstep(0.05f, 0.12f, sunY) * (1f - smoothstep(0.5f, 0.6f, sunY));
        set(Effect.FOGBOW, StratosConfig.fogbow.get(),
                lucky(Effect.FOGBOW, day, dim, 0, 0.06f * frequency) ? smoothstep(0.3f, 0.7f, mist) : 0f, fogSun);

        // ---- Light pillars: ice crystals near the ground over distant lights, and over a sun at the horizon. ----
        float pillarTime = Math.max(dark, 1f - smoothstep(0.08f, 0.14f, Math.abs(sunY)));
        set(Effect.LIGHT_PILLARS, StratosConfig.lightPillars.get(),
                lucky(Effect.LIGHT_PILLARS, day, dim, 0, StratosConfig.lightPillarChance.get().floatValue() * frequency)
                        ? icy * clear : 0f, pillarTime);

        // ---- 0.8.0 "Night". ----
        // Aurora: a geomagnetic activity that rises and falls over several days; cold biomes and heights see it often,
        // everywhere else only the strongest storms.
        double days = day + tod / 24000.0;
        float activity = activity(days / 3.0, dim);
        float quiet = Math.max(0.2f, 1f - 0.38f * frequency);
        float high = smoothstep(160f, 260f, (float) cam.y);
        float auroraHere = Math.max(Math.min(1f, icy + 0.4f * high) * smoothstep(quiet, quiet + 0.2f, activity),
                0.5f * smoothstep(0.93f, 0.99f, activity));
        set(Effect.AURORA, StratosConfig.aurora.get(), auroraHere * clear, dark);
        float dusk = smoothstep(-0.07f, -0.12f, sunY) * (1f - smoothstep(-0.24f, -0.29f, sunY));
        set(Effect.NOCTILUCENT, StratosConfig.noctilucent.get(),
                lucky(Effect.NOCTILUCENT, day, dim, tod < 18000 ? 1 : 2,
                        StratosConfig.noctilucentChance.get().floatValue() * frequency) ? icy * clear : 0f, dusk);
        float deep = smoothstep(-0.2f, -0.3f, sunY);
        // The zodiacal light is always there on a dark, clear night; faint. Forcing it triples it.
        set(Effect.ZODIACAL_LIGHT, StratosConfig.zodiacalLight.get(),
                lucky(Effect.ZODIACAL_LIGHT, day, dim, 0, 0.25f * frequency) ? 0.5f * clear : 0f, deep);
        set(Effect.METEOR_SHOWER, StratosConfig.meteors.get(),
                lucky(Effect.METEOR_SHOWER, day, dim, 0, 0.08f * frequency) ? clear : 0f, dark);
        float moonUp = smoothstep(0.0f, 0.15f, -sunY);
        float moonWash = 1f - 0.5f * moonLight * moonUp;
        set(Effect.NEBULA, StratosConfig.nebula.get(),
                lucky(Effect.NEBULA, day, dim, 0, StratosConfig.nebulaChance.get().floatValue() * frequency)
                        ? clear * moonWash : 0f, dark);

        // ---- 0.9.0 "Omens": a total lunar eclipse on a full-moon night. ----
        float moonNight = smoothstep(0.05f, 0.15f, -sunY);
        set(Effect.BLOOD_MOON, StratosConfig.bloodMoon.get(),
                moonPhase == 0 && lucky(Effect.BLOOD_MOON, day, dim, 0,
                        StratosConfig.bloodMoonChance.get().floatValue() * frequency) ? 1f : 0f, moonNight);

        // Ease, and note whether the sky pass has anything to do.
        any = false;
        for (int i = 0; i < N; i++) {
            float t = master || forced[i] == ON ? target[i] : 0f;
            float tau = i == Effect.GREEN_FLASH.ordinal() ? 0.4f : 3f;
            strength[i] = fresh ? t : strength[i] + (t - strength[i]) * (1f - (float) Math.exp(-dt / tau));
            if (strength[i] < 0.002f && t == 0f) {
                strength[i] = 0f;
            }
            Effect e = ALL[i];
            if (strength[i] > 0f && e != Effect.RAINBOW && e != Effect.MOONBOW && e != Effect.FOGBOW && e != Effect.MAMMATUS) {
                any = true;
            }
        }

        fillUniforms(level, partial, cam, day, dim, tod, sx, sz, dark, clear, master);
    }

    /**
     * An effect's target: {@code auto} is its strength when chance and conditions allow it (0 otherwise); {@code able}
     * is what it needs to be drawn at all (the sun's or moon's place). Forced on: {@code able} alone; forced off or
     * switched off in the config: nothing.
     */
    private static void set(Effect effect, boolean enabled, float auto, float able) {
        int mode = forced[effect.ordinal()];
        float value = mode == ON ? able : mode == OFF || !enabled ? 0f : auto * able;
        target[effect.ordinal()] = clamp01(value);
    }

    /** True with probability {@code chance}, the same for every player: a hash of the day, dimension and effect. */
    private static boolean lucky(Effect effect, long day, long dim, int salt, float chance) {
        long h = mix(day * 0x9E3779B97F4A7C15L ^ dim * 0xC2B2AE3D27D4EB4FL ^ (effect.ordinal() + 1L) * 0x165667B19E3779F9L
                ^ salt * 0x27D4EB2F165667C5L);
        return unit(h) < chance;
    }

    /** Smooth value noise over {@code x} (one lattice point per unit), 0..1. */
    private static float activity(double x, long dim) {
        long i = (long) Math.floor(x);
        float f = (float) (x - i);
        float a = unit(mix(i * 0x9E3779B97F4A7C15L ^ dim ^ 0xA0A0L));
        float b = unit(mix((i + 1) * 0x9E3779B97F4A7C15L ^ dim ^ 0xA0A0L));
        return a + (b - a) * f * f * (3f - 2f * f);
    }

    private static void fillUniforms(ClientLevel level, float partial, Vec3 cam, long day, long dim, int tod, float sx,
                                     float sz, float dark, float clear, boolean master) {
        float[] fx = FX;
        java.util.Arrays.fill(fx, 0f);
        float halo = strength[Effect.HALO.ordinal()];
        float dogs = strength[Effect.SUN_DOGS.ordinal()];
        float cza = strength[Effect.CIRCUMZENITHAL_ARC.ordinal()];
        // u_Fx0: halo, sun dogs, circumzenithal arc, green flash.
        fx[0] = halo;
        fx[1] = dogs;
        fx[2] = cza;
        fx[3] = strength[Effect.GREEN_FLASH.ordinal()];
        // u_Fx5: the halo's light (the sun, or the moon at night), w its brightness.
        boolean moon = sunY < -0.15f;
        float sign = moon ? -1f : 1f;
        fx[20] = sx * sign;
        fx[21] = sunY * sign;
        fx[22] = sz * sign;
        fx[23] = moon ? 0.4f : 1f;
        float hLen = (float) Math.sqrt(sx * sx + sz * sz);
        float hx = hLen > 1e-4f ? sx / hLen : 1f;
        float hz = hLen > 1e-4f ? sz / hLen : 0f;
        if (dogs > 0f) {
            // Sun dogs sit at the sun's height, 22 degrees to each side at the horizon and further out as the sun
            // climbs (about 28 degrees with the sun 40 degrees up). The azimuth step for that separation at this
            // height: cos(sep) = sin^2(el) + cos^2(el) cos(step).
            double el = Math.asin(Math.max(-1f, Math.min(1f, sunY)));
            double elDeg = Math.toDegrees(el);
            double sep = Math.toRadians(22.0 + 0.0035 * elDeg * elDeg);
            double ce = Math.cos(el);
            double offset = Math.acos(Math.max(-1.0, Math.min(1.0, (Math.cos(sep) - sunY * (double) sunY) / Math.max(ce * ce, 1e-3))));
            double az = Math.atan2(sz, sx);
            for (int i = 0; i < 2; i++) {
                double a = az + (i == 0 ? offset : -offset);
                DOGS[i * 4] = (float) (ce * Math.cos(a));
                DOGS[i * 4 + 1] = sunY;
                DOGS[i * 4 + 2] = (float) (ce * Math.sin(a));
                DOGS[i * 4 + 3] = 1f;
            }
        }
        // u_Fx7: the sun's horizontal direction (xy) and the circumzenithal arc's elevation in radians (z): light
        // enters the top of a plate crystal and leaves through its side, sin h' = sqrt(n^2 - cos^2 h), n = 1.31.
        fx[28] = hx;
        fx[29] = hz;
        double cosSun = Math.sqrt(Math.max(0.0, 1.0 - sunY * (double) sunY));
        fx[30] = (float) Math.asin(Math.min(1.0, Math.sqrt(Math.max(0.0, 1.31 * 1.31 - cosSun * cosSun))));

        // u_Fx1: aurora, noctilucent, zodiacal light, meteor count.
        float zodiacal = strength[Effect.ZODIACAL_LIGHT.ordinal()];
        fx[4] = strength[Effect.AURORA.ordinal()] * StratosConfig.auroraBrightness.get().floatValue();
        fx[5] = strength[Effect.NOCTILUCENT.ordinal()];
        fx[6] = zodiacal;
        // u_Fx6: aurora time and seed, noctilucent upper limit (sine of the elevation; it sinks as the sun does),
        // pillar count.
        float seconds = (float) ((level.getGameTime() % 72000L + partial) / 20.0);
        fx[24] = seconds;
        fx[25] = unit(mix(day * 0x9E3779B97F4A7C15L ^ dim ^ 0xAAL));
        fx[26] = lerp(0.5f, 0.12f, clamp01((-sunY - 0.105f) / 0.17f));

        // u_Fx2, u_Fx3: the night's nebula (its place among the stars, size, type and seed).
        float nebula = strength[Effect.NEBULA.ordinal()];
        if (nebula > 0f) {
            long h = mix(day * 0x9E3779B97F4A7C15L ^ dim * 0xC2B2AE3D27D4EB4FL ^ 0x4EBL);
            // 0.9.0: the type is the image (0-based, wrapped into the list by NebulaImages).
            int count = Math.max(1, NebulaImages.count());
            int type = forcedNebulaType > 0 && forced[Effect.NEBULA.ordinal()] == ON ? (forcedNebulaType - 1) % count
                    : (int) Math.floorMod(h >>> 11, (long) count);
            float u1 = unit(mix(h + 1));
            float u2 = unit(mix(h + 2));
            float u3 = unit(mix(h + 3));
            float u4 = unit(mix(h + 4));
            // In the celestial frame, y towards -1 is overhead at midnight.
            float cx = (u1 - 0.5f) * 1.2f;
            float cy = -(0.5f + 0.5f * u2);
            float cz = (u3 - 0.5f) * 1.6f;
            float len = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            // 0.9.0: 9 to 36 degrees in radius (the long side of the image), most nights about 17, times the size option.
            double radius = Math.toRadians((9.0 + 27.0 * Math.pow(u4, 1.7)) * StratosConfig.nebulaSize.get());
            fx[8] = nebula * StratosConfig.nebulaBrightness.get().floatValue();
            fx[9] = type;
            fx[10] = (float) (1.0 / Math.sin(radius));
            fx[11] = (float) Math.cos(Math.min(radius * 1.45, Math.PI * 0.5));
            fx[12] = cx / len;
            fx[13] = cy / len;
            fx[14] = cz / len;
            fx[15] = u1 * 7.31f + type * 0.173f;// the image's turn on the sky (radians)
            logNebula(level, partial, day, type, cx / len, cy / len, cz / len);
            // The centre's height above the horizon now (sine), for the night light.
            double rotation = level.getSunAngle(partial);
            nebulaHeight = (float) ((cx * Math.sin(rotation) + cy * Math.cos(rotation)) / len);
            nebulaRadius = (float) radius;
        }

        // u_Fx4: the Earth's shadow on the moon, in moon radii: its centre (xy), its radius (z), the effect (w).
        float blood = strength[Effect.BLOOD_MOON.ordinal()];
        eclipseCover = 0f;
        if (blood > 0f) {
            float umbra = 2.7f;
            // The shadow crosses the moon around midnight; a forced eclipse holds at totality.
            float x = forced[Effect.BLOOD_MOON.ordinal()] == ON ? 0.07f : (tod - 18000) / 3300f;
            float off = x * (umbra + 1.6f);
            fx[16] = off;
            fx[17] = 0.5f;
            fx[18] = umbra;
            fx[19] = blood;
            float d = (float) Math.sqrt(off * off + 0.25f);
            eclipseCover = smoothstep(umbra + 1f, umbra - 1f, d);
            MOON_TINT[0] = 1f;
            MOON_TINT[1] = 0.35f;
            MOON_TINT[2] = 0.2f;
        }

        fillPillars(cam, day, dim, hx, hz);
        fx[27] = pillarsDrawn;
        fillMeteors(level, partial, day, dim, dark * clear, master);
        fx[7] = meteorCount;
        if (pillarsDrawn > 0 || meteorCount > 0) {
            any = true;
        }
    }

    private static final float[][] PILLAR_HUES = {
            {1.0f, 0.72f, 0.38f}, {0.95f, 0.95f, 1.0f}, {0.5f, 0.95f, 0.85f}, {1.0f, 0.85f, 0.5f}, {0.75f, 0.7f, 1.0f}};

    private static void fillPillars(Vec3 cam, long day, long dim, float sunHx, float sunHz) {
        float s = strength[Effect.LIGHT_PILLARS.ordinal()];
        pillarsDrawn = 0;
        if (s <= 0f) {
            return;
        }
        int n = 0;
        // A sun pillar: a column of light standing on a sun at the horizon.
        float sunPillar = (1f - smoothstep(0.06f, 0.14f, Math.abs(sunY))) * s;
        if (sunPillar > 0.01f) {
            PILLAR[0] = sunHx;
            PILLAR[1] = sunHz;
            PILLAR[2] = 1f / (0.014f * 0.014f);
            PILLAR[3] = 7f;
            PILLAR_COL[0] = 1.0f * 0.38f * sunPillar;
            PILLAR_COL[1] = 0.55f * 0.38f * sunPillar;
            PILLAR_COL[2] = 0.25f * 0.38f * sunPillar;
            n = 1;
        }
        float night = smoothstep(-0.10f, -0.26f, sunY) * s;
        if (night > 0.01f) {
            if (--pillarCountdown <= 0) {
                pillarCountdown = 20;
                findPillars(cam, day, dim);
            }
            for (int i = 0; i < pillarCount && n < PILLARS; i++) {
                double dx = pillarX[i] - cam.x;
                double dz = pillarZ[i] - cam.z;
                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                float fade = smoothstep(100f, 200f, dist) * (1f - smoothstep(900f, 1400f, dist));
                if (fade <= 0.01f) {
                    continue;
                }
                long h = pillarHash[i];
                float width = Math.max(0.0035f, Math.min(0.02f, 5f / dist));
                float[] hue = PILLAR_HUES[(int) Math.floorMod(h >>> 20, (long) PILLAR_HUES.length)];
                float bright = (0.35f + 0.35f * unit(mix(h + 5))) * fade * night;
                PILLAR[n * 4] = (float) (dx / dist);
                PILLAR[n * 4 + 1] = (float) (dz / dist);
                PILLAR[n * 4 + 2] = 1f / (width * width);
                PILLAR[n * 4 + 3] = 3.5f + 3.5f * unit(mix(h + 9));
                PILLAR_COL[n * 4] = hue[0] * bright;
                PILLAR_COL[n * 4 + 1] = hue[1] * bright;
                PILLAR_COL[n * 4 + 2] = hue[2] * bright;
                n++;
            }
        }
        pillarsDrawn = n;
    }

    /** The nearest seeded light sources: about one 256-block cell in five holds one, the same for every player. */
    private static void findPillars(Vec3 cam, long day, long dim) {
        int cx = (int) Math.floor(cam.x / 256.0);
        int cz = (int) Math.floor(cam.z / 256.0);
        pillarCount = 0;
        for (int ring = 0; ring <= 5 && pillarCount < PILLARS - 1; ring++) {
            for (int dz = -ring; dz <= ring && pillarCount < PILLARS - 1; dz++) {
                for (int dx = -ring; dx <= ring && pillarCount < PILLARS - 1; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    long h = mix((cx + dx) * 0x9E3779B97F4A7C15L ^ (cz + dz) * 0xC2B2AE3D27D4EB4FL ^ day * 0x165667B19E3779F9L ^ dim);
                    if (unit(h) < 0.22f) {
                        pillarX[pillarCount] = (cx + dx + 0.15 + 0.7 * unit(mix(h + 1))) * 256.0;
                        pillarZ[pillarCount] = (cz + dz + 0.15 + 0.7 * unit(mix(h + 2))) * 256.0;
                        pillarHash[pillarCount] = h;
                        pillarCount++;
                    }
                }
            }
        }
    }

    /**
     * Meteors: each game second may start one (the same for every player). A sporadic one about every 45 s on a dark
     * night; a shower sends several a second from its radiant.
     */
    private static void fillMeteors(ClientLevel level, float partial, long day, long dim, float visible, boolean master) {
        meteorCount = 0;
        if (!master || !StratosConfig.meteors.get() || visible < 0.05f) {
            return;
        }
        float shower = strength[Effect.METEOR_SHOWER.ordinal()];
        double now = (level.getGameTime() + (double) partial) / 20.0;
        long second = (long) Math.floor(now);
        long rh = mix(day * 0x9E3779B97F4A7C15L ^ dim ^ 0x3E7L);
        // The shower's radiant, 25 to 70 degrees up.
        double rAz = unit(rh) * Math.PI * 2.0;
        double rEl = Math.toRadians(25.0 + 45.0 * unit(mix(rh + 1)));
        double rx = Math.cos(rEl) * Math.cos(rAz), ry = Math.sin(rEl), rz = Math.cos(rEl) * Math.sin(rAz);
        for (long s = second; s >= second - 1 && meteorCount < METEORS; s--) {
            for (int slot = 0; slot < 3 && meteorCount < METEORS; slot++) {
                long h = mix(s * 0x9E3779B97F4A7C15L ^ slot * 0xC2B2AE3D27D4EB4FL ^ dim);
                boolean fromShower = slot > 0;
                float rate = fromShower ? 0.3f * shower : 1f / 45f;
                if (unit(h) >= rate) {
                    continue;
                }
                double start = s + unit(mix(h + 1));
                double duration = 0.35 + 0.5 * unit(mix(h + 2));
                double age = now - start;
                if (age < 0.0 || age >= duration) {
                    continue;
                }
                // Where it appears: 15 to 80 degrees up, any azimuth.
                double az = unit(mix(h + 3)) * Math.PI * 2.0;
                double el = Math.toRadians(15.0 + 65.0 * unit(mix(h + 4)));
                double px = Math.cos(el) * Math.cos(az), py = Math.sin(el), pz = Math.cos(el) * Math.sin(az);
                // Its heading: away from the radiant (shower) or anywhere (sporadic), in the tangent plane at p.
                double vx, vy, vz;
                if (fromShower) {
                    vx = px - rx;
                    vy = py - ry;
                    vz = pz - rz;
                } else {
                    double a = unit(mix(h + 5)) * Math.PI * 2.0;
                    vx = Math.cos(a);
                    vy = -0.6 + 0.4 * Math.sin(a * 3.0);
                    vz = Math.sin(a);
                }
                double along = vx * px + vy * py + vz * pz;
                vx -= px * along;
                vy -= py * along;
                vz -= pz * along;
                double vl = Math.sqrt(vx * vx + vy * vy + vz * vz);
                if (vl < 1e-4) {
                    continue;
                }
                double length = Math.toRadians(8.0 + 12.0 * unit(mix(h + 6)));
                double travelled = length * age / duration;
                double tail = Math.min(travelled, Math.toRadians(4.5));
                int o = meteorCount * 8;
                float glow = (float) Math.sin(Math.PI * age / duration);
                METEOR[o] = (float) (px + vx / vl * travelled);
                METEOR[o + 1] = (float) (py + vy / vl * travelled);
                METEOR[o + 2] = (float) (pz + vz / vl * travelled);
                METEOR[o + 3] = glow * (0.5f + 1.0f * unit(mix(h + 7))) * visible;
                METEOR[o + 4] = (float) (px + vx / vl * (travelled - tail));
                METEOR[o + 5] = (float) (py + vy / vl * (travelled - tail));
                METEOR[o + 6] = (float) (pz + vz / vl * (travelled - tail));
                METEOR[o + 7] = 0.0016f;
                meteorCount++;
            }
        }
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 0.9.0: the nebula's centre: sine of its height above the horizon now, and its radius (radians). */
    public static float nebulaHeight = -1f;
    public static float nebulaRadius;
    private static long loggedNebulaDay = Long.MIN_VALUE;
    private static int loggedNebulaType = -1;

    /**
     * 0.9.0: once per night and image, where the nebula is: its image and the camera angles (yaw, pitch, as /tp takes
     * them) that face its centre now. For players looking for it and for test scenes.
     */
    private static void logNebula(ClientLevel level, float partial, long day, int type, float cx, float cy, float cz) {
        if (day == loggedNebulaDay && type == loggedNebulaType) {
            return;
        }
        loggedNebulaDay = day;
        loggedNebulaType = type;
        // Celestial to world: the inverse of the sky shader's turn about the z axis.
        double rotation = level.getSunAngle(partial);
        double c = Math.cos(rotation);
        double s = Math.sin(rotation);
        double x = cx * c - cy * s;
        double y = cx * s + cy * c;
        double yaw = Math.toDegrees(Math.atan2(-x, cz));
        double pitch = -Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, y))));
        com.solarbiscuit.atmosphera.stratos.Stratos.LOGGER.info(
                "[Atmosphera] Stratos nebula tonight: image {}, centre now at yaw {} pitch {} (elevation {} degrees)",
                NebulaImages.describe(type), Math.round(yaw), Math.round(pitch), Math.round(-pitch));
    }

    private static float unit(long h) {
        return (h >>> 40) / (float) (1L << 24);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float smoothstep(float a, float b, float x) {
        float t = clamp01((x - a) / (b - a));
        return t * t * (3f - 2f * t);
    }
}
