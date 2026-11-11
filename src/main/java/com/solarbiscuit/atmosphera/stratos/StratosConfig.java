package com.solarbiscuit.atmosphera.stratos;

import com.solarbiscuit.atmosphera.lib.options.AtmosConfig;
import com.solarbiscuit.atmosphera.lib.options.AtmosOption;
import com.solarbiscuit.atmosphera.lib.options.ChangeTier;

/** {@code config/Atmosphera/stratos-client.toml} and the "Stratos (clouds)" Video Settings page. */
public final class StratosConfig {
    /** Cost tier of the cloud march. Divisor: the march runs at 1/divisor of the screen's width and height. */
    public enum Quality {
        /**
         * Flat decks plus a coarse low deck, no history: for integrated GPUs. (1/6 of the resolution was tried in 0.2.5
         * and measured no cheaper than 1/4: LOW's cost is per-pass overhead, not pixels.)
         */
        LOW(4, 6, 1, false, false),
        /** Volumetric low deck at a quarter of the resolution, temporal accumulation (default). */
        MEDIUM(4, 12, 3, true, true),
        /** Half resolution, more steps and light samples. */
        HIGH(2, 18, 4, true, true);

        public final int divisor;
        public final int steps;
        public final int lightSteps;
        public final boolean detail;
        public final boolean temporal;

        Quality(int divisor, int steps, int lightSteps, boolean detail, boolean temporal) {
            this.divisor = divisor;
            this.steps = steps;
            this.lightSteps = lightSteps;
            this.detail = detail;
            this.temporal = temporal;
        }
    }

    /** Look presets, from {@code assets/stratos/styles/<name>.json} (resource packs can replace them). */
    public enum Style {
        /** Realistic, the default. */
        STRATOS,
        /** Softer, rounder, cheaper (fewer noise octaves). */
        SOFT,
        /** Vanilla-like blocks of cloud in a thin layer. */
        BLOCKY
    }

    public static AtmosConfig.FeatureSwitch clouds;
    public static AtmosOption<Quality> quality;
    public static AtmosOption<Style> style;
    public static AtmosOption<Double> coverage;
    public static AtmosOption<Double> density;
    public static AtmosOption<Integer> lowBase;
    public static AtmosOption<Integer> lowThickness;
    public static AtmosOption<Boolean> midDeck;
    public static AtmosOption<Integer> midHeight;
    public static AtmosOption<Boolean> highDeck;
    public static AtmosOption<Integer> highHeight;
    public static AtmosOption<Double> drift;
    public static AtmosOption<Double> shear;
    public static AtmosOption<Boolean> weather;
    public static AtmosOption<Integer> farDistance;
    public static AtmosOption<Integer> curvature;
    public static AtmosOption<Double> brightness;
    public static AtmosOption<String> tint;
    public static AtmosOption<Boolean> levelCloudColor;
    public static AtmosOption<Boolean> followVanilla;
    public static AtmosOption<Boolean> dhDepth;
    public static AtmosOption<Integer> debug;
    public static AtmosConfig.FeatureSwitch shadows;
    public static AtmosOption<Boolean> marchNear;
    public static AtmosOption<Boolean> marchThirdOctave;
    public static AtmosConfig.FeatureSwitch sky;
    public static AtmosOption<Double> skyBrightness;
    public static AtmosOption<Double> skySaturation;
    public static AtmosOption<Integer> horizonBlend;
    public static AtmosOption<Boolean> packSunMoon;
    public static AtmosOption<Double> sunSize;
    public static AtmosOption<Double> moonSize;
    public static AtmosOption<Double> stars;
    public static AtmosOption<Double> milkyWay;
    public static AtmosOption<Double> airglow;

    private StratosConfig() {
    }

    static void register() {
        AtmosConfig config = AtmosConfig.create(Stratos.MOD_ID, "Stratos (sky and clouds)");
        config.section("clouds", "Clouds: weather-driven decks of volumetric cumulus, sheets and towers, with altocumulus "
                + "and cirrus above.");
        clouds = config.featureSwitch("enabled", true, "Stratos clouds",
                "Replaces vanilla's clouds with Stratos's. Off under a shaderpack (it draws its own) and under "
                        + "Fabulous graphics.");
        quality = config.choice("quality", Quality.MEDIUM, ChangeTier.LIVE, "Cloud quality",
                "LOW: flat decks and a coarse low deck, for integrated GPUs. MEDIUM: volumetric clouds at a quarter "
                        + "of the resolution, smoothed over frames. HIGH: half resolution, more detail.");
        style = config.choice("style", Style.STRATOS, ChangeTier.LIVE, "Cloud style",
                "STRATOS: realistic. SOFT: softer and rounder, a little cheaper. BLOCKY: vanilla-like blocks of cloud.");
        coverage = config.percent("coverage", 0.5, 0.0, 1.0, ChangeTier.LIVE, "Cloud amount",
                "How much of the sky is cloudy in clear weather: 50% gives scattered fair-weather clouds, 100% an "
                        + "unbroken layer. Rain and storms add more.");
        density = config.percent("density", 0.7, 0.2, 2.0, ChangeTier.LIVE, "Cloud thickness",
                "How opaque the clouds are (70% by default).");
        weather = config.toggle("follow_weather", true, ChangeTier.LIVE, "Clouds follow the weather",
                "Rain turns the sky into a low grey sheet, thunder builds towering storm clouds, dry biomes have fewer "
                        + "clouds and cold ones flatter ones. Off: always fair-weather clouds.");

        config.section("decks", "Cloud decks: heights and motion.");
        lowBase = config.slider("low_base", 200, 120, 400, 5, ChangeTier.LIVE, "Low cloud base",
                "World height of the base of the low clouds (vanilla clouds are at 192).");
        lowThickness = config.slider("low_thickness", 260, 40, 600, 10, ChangeTier.LIVE, "Low cloud depth",
                "How tall the low clouds can grow (in blocks). Storm towers use all of it.");
        midDeck = config.toggle("mid_deck", true, ChangeTier.LIVE, "Altocumulus",
                "A thin middle layer of rippled cloud above the low clouds.");
        midHeight = config.slider("mid_height", 650, 300, 1200, 10, ChangeTier.LIVE, "Altocumulus height",
                "World height of the middle layer.");
        highDeck = config.toggle("high_deck", true, ChangeTier.LIVE, "Cirrus",
                "High, wispy ice clouds, sheared by the faster wind aloft.");
        highHeight = config.slider("high_height", 1100, 600, 2400, 20, ChangeTier.LIVE, "Cirrus height",
                "World height of the cirrus.");
        drift = config.percent("drift", 1.0, 0.0, 3.0, ChangeTier.LIVE, "Drift speed",
                "How fast clouds move with the wind (100% = a few blocks a second in a breeze).");
        shear = config.percent("shear", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Wind shear",
                "High clouds move faster and turn away from the surface wind, as the wind does aloft.");

        config.section("view", "Distance and look.");
        farDistance = config.slider("far_distance", 6000, 1000, 16000, 250, ChangeTier.LIVE, "Cloud distance",
                "How far clouds are drawn (blocks). Distant clouds cost little; Distant Horizons and Voxy terrain "
                        + "still covers them correctly.");
        curvature = config.slider("planet_radius", 50000, 0, 200000, 5000, ChangeTier.LIVE, "Horizon curvature",
                "Radius of the pretend planet (blocks) that makes distant clouds sink to the horizon. 0 = flat.");
        brightness = config.percent("brightness", 1.0, 0.25, 2.0, ChangeTier.LIVE, "Cloud brightness",
                "Scales the light on the clouds.");
        tint = config.color("tint", "#FFFFFF", ChangeTier.LIVE, "Cloud tint",
                "Multiplies the clouds' colour (TOML only).");
        levelCloudColor = config.toggle("use_level_cloud_color", true, ChangeTier.LIVE, "Biome cloud colour",
                "Take the hue of the game's cloud colour, so mods that tint clouds per biome (Atmospherics) still work.");
        followVanilla = config.toggle("follow_vanilla_cloud_option", false, ChangeTier.LIVE,
                "Follow vanilla cloud setting",
                "On: Stratos clouds show only while vanilla's Clouds option is not OFF. Off: they always show.");
        dhDepth = config.toggle("distant_horizons_depth", true, ChangeTier.LIVE, "Distant Horizons terrain",
                "Clouds stop at Distant Horizons' LOD terrain (needs Distant Horizons).");
        debug = config.slider("debug", 0, 0, 3, 1, ChangeTier.LIVE, "Debug view",
                "0 off, 1 transmittance, 2 march limit, 3 coverage. For testing.");
        config.section("shade", "Cloud shadows (0.2.0).");
        shadows = config.featureSwitch("shadows", true, "Cloud shadows",
                "Clouds shade the ground, water and distant terrain, and light pours through their gaps (with Lumis: "
                        + "on terrain, LODs, entities and in the light shafts).");

        config.section("march", "Cloud march tuning (0.2.0). Each item is measured on its own before it becomes a default.");
        marchNear = config.toggle("near_disparity", false, ChangeTier.LIVE, "Near steps in 1/distance",
                "With the camera in or just under the clouds, space the near steps evenly in 1/distance, so the first "
                        + "ones are close to the camera.");
        marchThirdOctave = config.toggle("third_octave", false, ChangeTier.LIVE, "Third scattering octave",
                "A softer third light-scattering term that lifts deep cloud cores out of grey.");

        config.section("sky", "The sky (0.3.0 \"Firmament\"): a physically based atmosphere, sun, moon and stars.");
        sky = config.featureSwitch("enabled", true, "Stratos sky",
                "Replaces vanilla's sky with Stratos's: blue overhead, hazy at the horizon, true sunsets and twilight, "
                        + "the Earth's shadow, a starry night. Off under a shaderpack (it draws its own).");
        skyBrightness = config.percent("brightness", 1.0, 0.5, 2.0, ChangeTier.LIVE, "Sky brightness",
                "Exposure of the daylight sky.");
        skySaturation = config.percent("saturation", 1.0, 0.0, 1.5, ChangeTier.LIVE, "Sky saturation",
                "How colourful the sky is. 0% is grey.");
        horizonBlend = config.slider("horizon_blend", 6, 0, 20, 1, ChangeTier.LIVE, "Horizon haze",
                "How far above the horizon (degrees) the sky fades into the fog colour, so distant terrain meets the "
                        + "sky without a seam (biome fog from Atmospherics comes through).");
        packSunMoon = config.toggle("pack_sun_moon", false, ChangeTier.LIVE, "Resource pack sun and moon",
                "Use the resource pack's sun and moon textures instead of Stratos's own sun and moon.");
        sunSize = config.percent("sun_size", 1.0, 0.25, 4.0, ChangeTier.LIVE, "Sun size",
                "100% is a disc 1.5 degrees across (the real sun is half that; vanilla's is far larger).");
        moonSize = config.percent("moon_size", 1.0, 0.25, 4.0, ChangeTier.LIVE, "Moon size",
                "100% is a disc 2.2 degrees across.");
        stars = config.percent("stars", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Stars", "Brightness of the stars.");
        milkyWay = config.percent("milky_way", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Milky Way",
                "Brightness of the faint band of the galaxy.");
        airglow = config.percent("airglow", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Airglow",
                "The faint green and amber glow low in a dark night sky.");
        config.register();
    }
}
