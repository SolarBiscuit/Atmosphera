package com.solarbiscuit.atmosphera.atmosphera.cloud;

import com.google.gson.JsonObject;
import com.solarbiscuit.atmosphera.atmosphera.Atmosphera;
import com.solarbiscuit.atmosphera.atmosphera.AtmospheraConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.Reader;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One look preset ({@code assets/atmosphera/styles/<style>.json}). Every value is a live shader uniform, so switching
 * styles costs nothing. Keys a file leaves out keep {@link #FALLBACK}'s value; sizes are in blocks.
 *
 * @param coverage    mean coverage of the low deck at 100% cloud amount (the amount option scales it)
 * @param extinction  optical depth per block of fully dense cloud
 * @param softness    width of the coverage edge: larger is softer, rounder clouds
 * @param detail      how strongly the detail noise erodes the edges (0 = smooth blobs)
 * @param shapeSize   size of one tile of the cloud shape noise
 * @param detailSize  size of one tile of the detail noise
 * @param weatherSize size of one tile of the coverage map (whole cloud fields)
 * @param forward     forward-scattering strength: the silver lining towards the sun (0..1)
 * @param ambient     how much sky light the clouds take in
 * @param powder      darkening of thin edges facing away from the sun (the "powder" effect)
 * @param rainDarken  how much darker clouds get in rain and storms (0..1)
 * @param blockCell   cell size of the blocky style (0 = smooth clouds)
 * @param blockDepth  thickness of the blocky style's layer
 */
public record CloudStyle(float coverage, float extinction, float softness, float detail, float shapeSize,
                         float detailSize, float weatherSize, float forward, float ambient, float powder,
                         float rainDarken, float blockCell, float blockDepth) {
    public static final CloudStyle FALLBACK = new CloudStyle(1.0f, 0.055f, 0.14f, 0.35f, 360f, 72f, 6144f, 0.75f,
            0.85f, 0.6f, 0.55f, 0f, 0f);

    private static volatile Map<AtmospheraConfig.Style, CloudStyle> styles = new EnumMap<>(AtmospheraConfig.Style.class);

    /** The style the config currently selects. Any thread. */
    public static CloudStyle current() {
        return styles.getOrDefault(AtmospheraConfig.style.get(), FALLBACK);
    }

    static CloudStyle fromJson(JsonObject json, CloudStyle base) {
        // 0.15.1: every value finite and in a range the shader can take (a NaN, Infinity, a zero softness or a negative
        // extinction from a pack went straight into the uniforms). The shipped styles are well inside these.
        return new CloudStyle(
                f(json, "coverage", base.coverage, 0f, 2f), f(json, "extinction", base.extinction, 0.001f, 1f),
                f(json, "softness", base.softness, 0.005f, 1f), f(json, "detail", base.detail, 0f, 2f),
                f(json, "shape_size", base.shapeSize, 16f, 1e5f), f(json, "detail_size", base.detailSize, 8f, 1e5f),
                f(json, "weather_size", base.weatherSize, 256f, 1e6f), f(json, "forward", base.forward, 0f, 1f),
                f(json, "ambient", base.ambient, 0f, 4f), f(json, "powder", base.powder, 0f, 2f),
                f(json, "rain_darken", base.rainDarken, 0f, 1f),
                f(json, "block_cell", base.blockCell, 0f, 256f), f(json, "block_depth", base.blockDepth, 0f, 256f));
    }

    private static float f(JsonObject json, String key, float fallback, float min, float max) {
        float v = com.solarbiscuit.atmosphera.lib.util.JsonPresets.f(json, key, fallback);
        return Float.isFinite(v) ? Math.max(min, Math.min(max, v)) : fallback;
    }

    /** Reads the presets at every resource reload; a resource pack replaces one by shipping the same path. */
    public static final class Loader extends SimplePreparableReloadListener<Map<AtmospheraConfig.Style, CloudStyle>> {
        @Override
        protected Map<AtmospheraConfig.Style, CloudStyle> prepare(ResourceManager manager, ProfilerFiller profiler) {
            // 0.14.2: AtmospheraLib's preset loader (0.11.0).
            return com.solarbiscuit.atmosphera.lib.util.JsonPresets.load(manager, Atmosphera.MOD_ID, "styles",
                    AtmospheraConfig.Style.class, style -> true, json -> fromJson(json, FALLBACK), Atmosphera.LOGGER,
                    "Cloud style");
        }

        @Override
        protected void apply(Map<AtmospheraConfig.Style, CloudStyle> loaded, ResourceManager manager, ProfilerFiller profiler) {
            styles = loaded;
            Atmosphera.LOGGER.info("Loaded {} cloud styles.", loaded.size());
        }
    }
}
