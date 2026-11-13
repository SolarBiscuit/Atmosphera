package com.solarbiscuit.atmosphera.atmosphera;

import com.solarbiscuit.atmosphera.lib.options.AtmosConfig;
import com.solarbiscuit.atmosphera.lib.options.AtmosOption;
import com.solarbiscuit.atmosphera.lib.options.ChangeTier;

/** {@code config/Atmosphera/atmosphera-client.toml} and the "Atmosphera" Video Settings page. */
public final class AtmospheraConfig {
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

    /** Look presets, from {@code assets/atmosphera/styles/<name>.json} (resource packs can replace them). */
    public enum Style {
        /** Realistic, the default. */
        ATMOSPHERA,
        /** Softer, rounder, cheaper (fewer noise octaves). */
        SOFT,
        /** Vanilla-like blocks of cloud in a thin layer. */
        BLOCKY
    }

    public static AtmosConfig.FeatureSwitch clouds;
    public static AtmosOption<Quality> quality;
    public static AtmosOption<Style> style;
    public static AtmosOption<Double> coverage;
    public static AtmosOption<Double> stratusAmount;
    public static AtmosOption<Double> altocumulusAmount;
    public static AtmosOption<Double> cirrusAmount;
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
    public static AtmosOption<Boolean> altoVolume;
    public static AtmosOption<Boolean> stormShapes;
    public static AtmosOption<Boolean> rainShafts;
    public static AtmosOption<Boolean> lightning;
    public static AtmosOption<Boolean> regional;
    public static AtmosConfig.FeatureSwitch air;
    public static AtmosOption<Double> biomeFog;
    public static AtmosOption<Double> haze;
    public static AtmosOption<Boolean> weatherFog;
    public static AtmosOption<Boolean> morningMist;
    public static AtmosConfig.FeatureSwitch sky;
    public static AtmosOption<Double> skyBrightness;
    public static AtmosOption<Double> skySaturation;
    public static AtmosOption<Integer> horizonBlend;
    public static AtmosOption<Boolean> packSunMoon;
    public static AtmosOption<Double> sunSize;
    public static AtmosOption<Double> moonSize;
    public static AtmosOption<Double> stars;
    public static AtmosOption<Double> starAmount;
    public static AtmosOption<Double> milkyWay;
    public static AtmosOption<Double> airglow;
    public static AtmosOption<Boolean> events;
    public static AtmosOption<Double> eventFrequency;
    public static AtmosOption<Boolean> halo;
    public static AtmosOption<Boolean> sunDogs;
    public static AtmosOption<Boolean> circumzenithalArc;
    public static AtmosOption<Boolean> greenFlash;
    public static AtmosOption<Boolean> rainbow;
    public static AtmosOption<Double> rainbowChance;
    public static AtmosOption<Boolean> fogbow;
    public static AtmosOption<Boolean> lightPillars;
    public static AtmosOption<Double> lightPillarChance;
    public static AtmosOption<Boolean> aurora;
    public static AtmosOption<Double> auroraBrightness;
    public static AtmosOption<Boolean> noctilucent;
    public static AtmosOption<Double> noctilucentChance;
    public static AtmosOption<Boolean> zodiacalLight;
    public static AtmosOption<Boolean> meteors;
    public static AtmosOption<Boolean> nebula;
    public static AtmosOption<Double> nebulaChance;
    public static AtmosOption<Double> nebulaBrightness;
    public static AtmosOption<Double> nebulaSize;
    public static AtmosOption<Integer> nebulaCount;
    public static AtmosOption<Double> twinkle;
    public static AtmosOption<Double> twinkleSpeed;
    public static AtmosOption<com.solarbiscuit.atmosphera.atmosphera.sky.NebulaImages.Detail> nebulaDetail;
    public static AtmosOption<Boolean> bloodMoon;
    public static AtmosOption<Double> bloodMoonChance;
    public static AtmosOption<Boolean> mammatus;
    public static AtmosOption<java.util.List<? extends String>> forceEvents;

    private AtmospheraConfig() {
    }

    static void register() {
        AtmosConfig config = AtmosConfig.create(Atmosphera.MOD_ID, "Atmosphera");
        config.section("clouds", "Clouds: weather-driven decks of volumetric cumulus, sheets and towers, with altocumulus "
                + "and cirrus above.");
        clouds = config.featureSwitch("enabled", true, "Atmosphera clouds",
                "Replaces vanilla's clouds with Atmosphera's. Off under a shaderpack (it draws its own) and under "
                        + "Fabulous graphics.");
        quality = config.choice("quality", Quality.MEDIUM, ChangeTier.LIVE, "Cloud quality",
                "LOW: flat decks and a coarse low deck, for integrated GPUs. MEDIUM: volumetric clouds at a quarter "
                        + "of the resolution, smoothed over frames. HIGH: half resolution, more detail.");
        style = config.choice("style", Style.ATMOSPHERA, ChangeTier.LIVE, "Cloud style",
                "ATMOSPHERA: realistic. SOFT: softer and rounder, a little cheaper. BLOCKY: vanilla-like blocks of cloud.");
        coverage = config.percent("coverage", 0.5, 0.0, 1.0, ChangeTier.LIVE, "Cumulus amount",
                "How much of the sky the low heaped clouds cover in clear weather: 50% gives scattered fair-weather "
                        + "cumulus, 100% an unbroken layer. Rain and storms add more.");
        stratusAmount = config.percent("stratus_amount", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Stratus amount",
                "How much of the low cloud is flat sheets (stratus) rather than heaps: 0% none in fair weather, 100% "
                        + "the default (some in cold biomes), 200% grey layers on most days. Rain is stratus anyway.");
        altocumulusAmount = config.percent("altocumulus_amount", 1.0, 0.0, 2.0, ChangeTier.LIVE,
                "Altocumulus amount", "How much of the sky the middle layer of rippled cloud covers.");
        cirrusAmount = config.percent("cirrus_amount", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Cirrus amount",
                "How much of the sky the high, wispy ice clouds cover.");
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
                "On: Atmosphera clouds show only while vanilla's Clouds option is not OFF. Off: they always show.");
        dhDepth = config.toggle("distant_horizons_depth", true, ChangeTier.LIVE, "Distant Horizons terrain",
                "Clouds stop at Distant Horizons' LOD terrain (needs Distant Horizons).");
        debug = config.slider("debug", 0, 0, 3, 1, ChangeTier.LIVE, "Debug view",
                "0 off, 1 transmittance, 2 march limit, 3 coverage. For testing.");
        config.section("shade", "Cloud shadows (0.2.0).");
        shadows = config.featureSwitch("shadows", true, "Cloud shadows",
                "Clouds shade the ground, water and distant terrain, and light pours through their gaps (with Lumis: "
                        + "on terrain, LODs, entities and in the light shafts).");

        config.section("storm", "Storms and regions (0.5.0 \"Storm\"). All on by default; TOML only since 0.6.4.");
        config.fileOnly(true);
        stormShapes = config.toggle("storm_shapes", true, ChangeTier.LIVE, "Storm clouds",
                "In thunder, towers spread into anvils with mammatus pouches beneath, and a dark shelf cloud runs ahead "
                        + "of each storm.");
        rainShafts = config.toggle("rain_shafts", true, ChangeTier.LIVE, "Rain shafts",
                "Grey curtains of rain under raining clouds, slanted by the wind; light rain fades out before the "
                        + "ground. Seen from afar before the rain arrives.");
        lightning = config.toggle("lightning", true, ChangeTier.LIVE, "Lightning in clouds",
                "Flashes light storm clouds from within (the same flashes for every player on a server).");
        regional = config.toggle("regional", true, ChangeTier.LIVE, "Regional clouds",
                "Clouds follow the biomes around you, not only your own: fewer over deserts, flatter over snow. "
                        + "Hills and mountains facing the wind gather cloud; their lee sides clear.");

        mammatus = config.toggle("mammatus", true, ChangeTier.LIVE, "Mammatus",
                "Pouches hanging under the anvil and the base of a storm deck, in thunder and for a while after it.");
        config.fileOnly(false);

        config.section("air", "The air (0.6.0 \"Air\"): biome fog, weather fog, dawn mist and distant haze. All on by default; "
                + "while Atmospherics is installed it keeps the fog unless the overlap option below is off.");
        air = config.featureSwitch("enabled", true, "Atmosphera air",
                "Fog coloured and thickened by the biomes around you (green-grey swamps, dusty deserts, cool taiga, "
                        + "blue sea air), drawn in by rain, with mist at dawn, and a haze over distant terrain and LODs.");
        biomeFog = config.percent("biome_fog", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Biome fog",
                "How strongly biomes colour and thicken the fog. 0% leaves the game's fog.");
        haze = config.percent("haze", 1.0, 0.0, 3.0, ChangeTier.LIVE, "Distant haze",
                "How hazy far terrain and LODs look. 0% turns the haze off.");
        weatherFog = config.toggle("weather_fog", true, ChangeTier.LIVE, "Weather fog",
                "Rain and storms draw the fog in and grey it.");
        morningMist = config.toggle("morning_mist", true, ChangeTier.LIVE, "Dawn mist",
                "Mist low over water and wet ground around sunrise, lifting as the sun climbs.");

        config.section("march", "Cloud march tuning (0.2.0). Each item is measured on its own before it becomes a default.");
        marchNear = config.toggle("near_disparity", false, ChangeTier.LIVE, "Near steps in 1/distance",
                "With the camera in or just under the clouds, space the near steps evenly in 1/distance, so the first "
                        + "ones are close to the camera.");
        marchThirdOctave = config.toggle("third_octave", false, ChangeTier.LIVE, "Third scattering octave",
                "A softer third light-scattering term that lifts deep cloud cores out of grey.");
        config.fileOnly(true);
        altoVolume = config.toggle("alto_volume", true, ChangeTier.LIVE, "Volumetric altocumulus",
                "MEDIUM and HIGH quality: the altocumulus is a thin volume with rounded tops and shaded bases, lit like "
                        + "the low clouds. Off: the flat layer of 0.7 and earlier (LOW always uses it).");
        config.fileOnly(false);

        config.section("sky", "The sky (0.3.0 \"Firmament\"): a physically based atmosphere, sun, moon and stars. Every sky "
                + "feature is on by default and has its own switch; chance events are seeded by the day, so every player "
                + "on a server sees the same ones.");
        sky = config.featureSwitch("enabled", true, "Atmosphera sky",
                "Replaces vanilla's sky with Atmosphera's: blue overhead, hazy at the horizon, true sunsets and twilight, "
                        + "the Earth's shadow, a starry night. Off under a shaderpack (it draws its own).");
        skyBrightness = config.percent("brightness", 1.0, 0.5, 2.0, ChangeTier.LIVE, "Sky brightness",
                "Exposure of the daylight sky.");
        skySaturation = config.percent("saturation", 1.0, 0.0, 1.5, ChangeTier.LIVE, "Sky saturation",
                "How colourful the sky is. 0% is grey.");
        horizonBlend = config.slider("horizon_blend", 6, 0, 20, 1, ChangeTier.LIVE, "Horizon haze",
                "How far above the horizon (degrees) the sky fades into the fog colour, so distant terrain meets the "
                        + "sky without a seam (biome fog from Atmospherics comes through).");
        packSunMoon = config.toggle("pack_sun_moon", false, ChangeTier.LIVE, "Resource pack sun and moon",
                "Use the resource pack's sun and moon textures instead of Atmosphera's own sun and moon.");
        sunSize = config.percent("sun_size", 1.0, 0.25, 4.0, ChangeTier.LIVE, "Sun size",
                "100% is a disc 1.5 degrees across (the real sun is half that; vanilla's is far larger).");
        moonSize = config.percent("moon_size", 1.0, 0.25, 4.0, ChangeTier.LIVE, "Moon size",
                "100% is a disc 2.2 degrees across.");
        stars = config.percent("stars", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Star brightness", "Brightness of the stars.");
        twinkle = config.percent("twinkle", 0.4, 0.0, 1.0, ChangeTier.LIVE, "Star twinkle",
                "How much the stars twinkle: most near the horizon, less for the brightest (40% by default).");
        twinkleSpeed = config.percent("twinkle_speed", 0.5, 0.1, 2.0, ChangeTier.LIVE, "Twinkle speed",
                "How fast the stars twinkle (50%: a slow shimmer).");
        starAmount = config.percent("star_amount", 1.0, 0.0, 3.0, ChangeTier.LIVE, "Star amount",
                "How many stars there are. 100% is a clear country sky, 300% a mountain-top sky; 0% hides them.");
        milkyWay = config.percent("milky_way", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Milky Way",
                "Brightness of the faint band of the galaxy.");
        airglow = config.percent("airglow", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Airglow",
                "The faint green and amber glow low in a dark night sky.");

        config.section("events", "Sky events (0.7.0 \"Optics\", 0.8.0 \"Night\", 0.9.0 \"Omens\"): the sky's occasional sights. "
                + "Which days and nights have them is a hash of the in-game day, so every player on a server sees the same "
                + "ones. /atmosphera skyeffect <name> on|off|auto shows one at will.");
        events = config.toggle("enabled", true, ChangeTier.LIVE, "Sky events",
                "Halos and sun dogs, rainbows, light pillars, auroras, noctilucent clouds, meteors, nebulae and the blood "
                        + "moon appear now and then. Off: none of them (the command still lists them).");
        eventFrequency = config.percent("frequency", 1.0, 0.0, 3.0, ChangeTier.LIVE, "Sky event frequency",
                "How often the chance events come, as a multiple of each one's own chance. 100% keeps them rare.");
        config.fileOnly(true);
        halo = config.toggle("halo", true, ChangeTier.LIVE, "Halo",
                "A 22-degree ring round the sun, or a bright moon, on some days with cirrus.");
        sunDogs = config.toggle("sun_dogs", true, ChangeTier.LIVE, "Sun dogs",
                "Bright spots left and right of a low sun, on some days with cirrus.");
        circumzenithalArc = config.toggle("circumzenithal_arc", true, ChangeTier.LIVE, "Circumzenithal arc",
                "A short rainbow-coloured arc high overhead, with the sun 5 to 31 degrees up, on a few days with cirrus.");
        greenFlash = config.toggle("green_flash", true, ChangeTier.LIVE, "Green flash",
                "The last sliver of the setting or rising sun flashes green, at about one sunset in ten.");
        rainbow = config.toggle("rainbow", true, ChangeTier.LIVE, "Rainbow",
                "A rainbow opposite a low sun when a shower ends (and a pale moonbow by a bright moon), now and then.");
        rainbowChance = config.percent("rainbow_chance", 0.025, 0.0, 1.0, ChangeTier.LIVE, "Rainbow chance",
                "The chance that a shower's end leaves a rainbow (0.025 = 2.5%).");
        fogbow = config.toggle("fogbow", true, ChangeTier.LIVE, "Fogbow",
                "A broad white bow opposite a low sun in dawn mist, on a few mornings.");
        lightPillars = config.toggle("light_pillars", true, ChangeTier.LIVE, "Light pillars",
                "Columns of light over distant lights on cold, clear nights in snowy biomes, and over a sun at the "
                        + "horizon there.");
        lightPillarChance = config.percent("light_pillar_chance", 0.10, 0.0, 1.0, ChangeTier.LIVE, "Light pillar chance",
                "The share of nights with light pillars in snowy biomes (0.10 = one night in ten).");
        aurora = config.toggle("aurora", true, ChangeTier.LIVE, "Aurora",
                "Curtains of green and red light in the northern night sky. Often in snowy biomes and high up, following "
                        + "an activity that rises and falls over several days; elsewhere only in the strongest storms.");
        auroraBrightness = config.percent("aurora_brightness", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Aurora brightness",
                "Brightness of the aurora.");
        noctilucent = config.toggle("noctilucent", true, ChangeTier.LIVE, "Noctilucent clouds",
                "Rippled blue-white clouds low over the horizon in deep twilight, on some evenings and mornings in "
                        + "snowy biomes.");
        noctilucentChance = config.percent("noctilucent_chance", 0.08, 0.0, 1.0, ChangeTier.LIVE,
                "Noctilucent cloud chance", "The share of twilights with noctilucent clouds in snowy biomes (0.08 = 8%).");
        zodiacalLight = config.toggle("zodiacal_light", true, ChangeTier.LIVE, "Zodiacal light",
                "A faint cone of light along the sun's path, on about one dark, clear night in four.");
        meteors = config.toggle("meteors", true, ChangeTier.LIVE, "Meteors",
                "A shooting star now and then on dark nights, and a meteor shower on a few nights.");
        nebula = config.toggle("nebula", true, ChangeTier.LIVE, "Nebulae",
                "On some clear nights a nebula shows among the stars: a real telescope image (Hubble and Webb), a "
                        + "different one and in a different place each time. Resource packs can add their own.");
        nebulaChance = config.percent("nebula_chance", 0.20, 0.0, 1.0, ChangeTier.LIVE, "Nebula chance",
                "The share of clear nights with a nebula (0.20 = one night in five).");
        nebulaBrightness = config.percent("nebula_brightness", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Nebula brightness",
                "100% is vivid, like a long-exposure photograph; about 25% is what the eye would see.");
        nebulaSize = config.percent("nebula_size", 1.0, 0.5, 2.0, ChangeTier.LIVE, "Nebula size",
                "How much of the sky a nebula covers: 100% is 18 to 72 degrees across, most about 34.");
        nebulaCount = config.slider("nebula_count", 3, 1, 3, 1, ChangeTier.LIVE, "Nebula count",
                "The most nebulae on one night: a second shows on about 30% of nebula nights, a third on 10%.");
        nebulaDetail = config.choice("nebula_detail", com.solarbiscuit.atmosphera.atmosphera.sky.NebulaImages.Detail.HIGH,
                ChangeTier.LIVE, "Nebula detail",
                "The resolution the image is kept at. High: the full image (up to 4096 pixels, about 85 MB of video "
                        + "memory while a nebula shows), sharp under a zoom. Medium: 2048 (21 MB). Low: 1024 (5 MB).");
        bloodMoon = config.toggle("blood_moon", true, ChangeTier.LIVE, "Blood moon",
                "On a rare full-moon night the moon passes through the Earth's shadow and turns copper red (Atmosphera's "
                        + "moon, the resource pack's, vanilla's and skybox moons alike).");
        bloodMoonChance = config.percent("blood_moon_chance", 0.01, 0.0, 1.0, ChangeTier.LIVE, "Blood moon chance",
                "The share of full-moon nights with a lunar eclipse (0.01 = 1%).");
        forceEvents = config.stringList("force", java.util.List.of(), ChangeTier.LIVE, "Forced sky events",
                "Effects to show whatever the day's luck says, by the names of /atmosphera skyeffect: for example "
                        + "[\"aurora\", \"nebula:3\"] (the third nebula image), or \"-rainbow\" to keep one off. Like the command, "
                        + "but kept across sessions; the command wins while it is used.");
        config.fileOnly(false);
        config.register();
    }
}
