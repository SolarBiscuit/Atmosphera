package com.solarbiscuit.atmosphera.stratos.air;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.solarbiscuit.atmosphera.stratos.Stratos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.biome.Biome;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Per-biome air (0.6.0 "Air"): data files {@code assets/stratos/air/*.json} (resource packs can replace or add them).
 * Each file is one profile:
 * <pre>
 * { "biomes": ["minecraft:swamp", "#forge:is_swamp"], "priority": 10,
 *   "fog_color": "#8E9A84", "tint": 0.45, "fog_distance": 0.55, "haze": 1.3, "mist": 1.5 }
 * </pre>
 * {@code tint} blends the game's fog colour towards {@code fog_color}; {@code fog_distance} scales how far terrain fog
 * starts and ends (below 1 = denser); {@code haze} scales the distant haze; {@code mist} scales the dawn mist. The
 * highest-priority profile matching a biome wins; a biome no file names gets the neutral profile.
 */
public final class AirData {
    /** One profile; colours linear 0..1 like the game's fog colour. */
    public record Profile(float r, float g, float b, float tint, float fogDistance, float haze, float mist) {
    }

    public static final Profile NEUTRAL = new Profile(0f, 0f, 0f, 0f, 1f, 1f, 1f);

    private record Entry(List<ResourceLocation> ids, List<TagKey<Biome>> tags, int priority, Profile profile) {
    }

    private static volatile List<Entry> entries = List.of();

    private AirData() {
    }

    /** The profile for a biome. Render thread (cheap: a few list checks; callers sample a handful of biomes a second). */
    public static Profile of(Holder<Biome> biome) {
        Profile best = NEUTRAL;
        int bestPriority = Integer.MIN_VALUE;
        for (Entry e : entries) {
            if (e.priority() <= bestPriority) {
                continue;
            }
            boolean match = false;
            for (ResourceLocation id : e.ids()) {
                if (biome.is(id)) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                for (TagKey<Biome> tag : e.tags()) {
                    if (biome.is(tag)) {
                        match = true;
                        break;
                    }
                }
            }
            if (match) {
                best = e.profile();
                bestPriority = e.priority();
            }
        }
        return best;
    }

    /** Reads the profiles at every resource reload. */
    public static final class Loader extends SimplePreparableReloadListener<List<Entry>> {
        @Override
        protected List<Entry> prepare(ResourceManager manager, ProfilerFiller profiler) {
            List<Entry> loaded = new ArrayList<>();
            Map<ResourceLocation, Resource> files = manager.listResources("air", id -> id.getPath().endsWith(".json")
                    && id.getNamespace().equals(Stratos.MOD_ID));
            for (Map.Entry<ResourceLocation, Resource> file : files.entrySet()) {
                try (Reader reader = file.getValue().openAsReader()) {
                    loaded.add(parse(JsonParser.parseReader(reader).getAsJsonObject()));
                } catch (Exception e) {
                    Stratos.LOGGER.warn("Air profile {} could not be read ({}); skipped.", file.getKey(), e.toString());
                }
            }
            return loaded;
        }

        @Override
        protected void apply(List<Entry> loaded, ResourceManager manager, ProfilerFiller profiler) {
            entries = List.copyOf(loaded);
            Stratos.LOGGER.info("Loaded {} air profiles.", loaded.size());
        }
    }

    private static Entry parse(JsonObject json) {
        List<ResourceLocation> ids = new ArrayList<>();
        List<TagKey<Biome>> tags = new ArrayList<>();
        JsonArray biomes = json.getAsJsonArray("biomes");
        for (JsonElement element : biomes) {
            String s = element.getAsString();
            if (s.startsWith("#")) {
                tags.add(TagKey.create(Registries.BIOME, new ResourceLocation(s.substring(1))));
            } else {
                ids.add(new ResourceLocation(s));
            }
        }
        int rgb = json.has("fog_color") ? Integer.parseInt(json.get("fog_color").getAsString().replace("#", ""), 16) : 0;
        Profile profile = new Profile(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f,
                f(json, "tint", 0f), Math.max(0.1f, f(json, "fog_distance", 1f)), Math.max(0f, f(json, "haze", 1f)),
                Math.max(0f, f(json, "mist", 1f)));
        return new Entry(ids, tags, json.has("priority") ? json.get("priority").getAsInt() : 0, profile);
    }

    private static float f(JsonObject json, String key, float fallback) {
        return json.has(key) ? json.get(key).getAsFloat() : fallback;
    }
}
