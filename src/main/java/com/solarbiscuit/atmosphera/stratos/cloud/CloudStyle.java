package com.solarbiscuit.atmosphera.stratos.cloud;

import com.google.gson.JsonObject;
import com.solarbiscuit.atmosphera.stratos.Stratos;
import com.solarbiscuit.atmosphera.stratos.StratosConfig;
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
 * One look preset ({@code assets/stratos/styles/<style>.json}). Every value is a live shader uniform, so switching
 * styles costs nothing. Keys a file leaves out keep {@link #FALLBACK}'s value; sizes are in blocks.
 *
 * @param coverage    share of the sky covered in clear weather, before the coverage option (0..1)
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
    public static final CloudStyle FALLBACK = new CloudStyle(0.42f, 0.055f, 0.14f, 0.35f, 360f, 72f, 6144f, 0.75f,
            0.85f, 0.6f, 0.55f, 0f, 0f);

    private static volatile Map<StratosConfig.Style, CloudStyle> styles = new EnumMap<>(StratosConfig.Style.class);

    /** The style the config currently selects. Any thread. */
    public static CloudStyle current() {
        return styles.getOrDefault(StratosConfig.style.get(), FALLBACK);
    }

    static CloudStyle fromJson(JsonObject json, CloudStyle base) {
        return new CloudStyle(
                f(json, "coverage", base.coverage), f(json, "extinction", base.extinction),
                f(json, "softness", base.softness), f(json, "detail", base.detail),
                Math.max(16f, f(json, "shape_size", base.shapeSize)), Math.max(8f, f(json, "detail_size", base.detailSize)),
                Math.max(256f, f(json, "weather_size", base.weatherSize)), f(json, "forward", base.forward),
                f(json, "ambient", base.ambient), f(json, "powder", base.powder), f(json, "rain_darken", base.rainDarken),
                Math.max(0f, f(json, "block_cell", base.blockCell)), Math.max(0f, f(json, "block_depth", base.blockDepth)));
    }

    private static float f(JsonObject json, String key, float fallback) {
        return json.has(key) ? json.get(key).getAsFloat() : fallback;
    }

    /** Reads the presets at every resource reload; a resource pack replaces one by shipping the same path. */
    public static final class Loader extends SimplePreparableReloadListener<Map<StratosConfig.Style, CloudStyle>> {
        @Override
        protected Map<StratosConfig.Style, CloudStyle> prepare(ResourceManager manager, ProfilerFiller profiler) {
            Map<StratosConfig.Style, CloudStyle> loaded = new EnumMap<>(StratosConfig.Style.class);
            for (StratosConfig.Style style : StratosConfig.Style.values()) {
                ResourceLocation id = new ResourceLocation(Stratos.MOD_ID,
                        "styles/" + style.name().toLowerCase(Locale.ROOT) + ".json");
                Optional<Resource> resource = manager.getResource(id);
                if (resource.isEmpty()) {
                    Stratos.LOGGER.warn("Cloud style {} is missing; using the fallback style.", id);
                    continue;
                }
                try (Reader reader = resource.get().openAsReader()) {
                    loaded.put(style, fromJson(com.google.gson.JsonParser.parseReader(reader).getAsJsonObject(), FALLBACK));
                } catch (Exception e) {
                    Stratos.LOGGER.warn("Cloud style {} could not be read ({}); using the fallback style.", id, e.toString());
                }
            }
            return loaded;
        }

        @Override
        protected void apply(Map<StratosConfig.Style, CloudStyle> loaded, ResourceManager manager, ProfilerFiller profiler) {
            styles = loaded;
            Stratos.LOGGER.info("Loaded {} cloud styles.", loaded.size());
        }
    }
}
