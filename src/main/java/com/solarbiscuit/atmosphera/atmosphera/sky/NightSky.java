package com.solarbiscuit.atmosphera.atmosphera.sky;

import com.solarbiscuit.atmosphera.lib.gpu.StreamedImage;
import com.solarbiscuit.atmosphera.lib.gpu.StreamedImage.Decoded;
import static com.solarbiscuit.atmosphera.lib.util.AtmosMath.mix;
import static com.solarbiscuit.atmosphera.lib.util.AtmosMath.unit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.solarbiscuit.atmosphera.atmosphera.Atmosphera;
import com.solarbiscuit.atmosphera.atmosphera.AtmospheraConfig;
import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * 0.14.0 "Night skies": night-sky styles from photographs (docs/Ideas/sky images, prepared by
 * tools/nightsky/prep_nightsky.py into {@code textures/night/<style>.jpg} and {@code night_styles/<style>.json}). A
 * style colours the whole night sky (its photo's edge colour) and lays its photo on the sky dome as one large patch in
 * the stars' frame, so it rises and sets with them; the procedural stars stay on top, thinned inside it.
 * <p>
 * A style lasts 14 to 28 nights (about 21), changing at noon; which and how long come from a hash of the dimension
 * and the period's number, so every player on a server sees the same sky. The option "Night sky style" forces one.
 * The photo is decoded off the render thread and uploaded in strips (as {@link MoonMap}). Render thread.
 */
public final class NightSky {
    /** The built-in styles (resource packs may replace their images and numbers). */
    public enum Style {
        AUTO("auto"), CLASSIC("classic"), VIOLET_CORE("violet_core"), SILVER_RIVER("silver_river"), AMETHYST("amethyst"),
        TEAL_DUSK("teal_dusk"), FROST("frost"), ELECTRIC_BLUE("electric_blue"), ARCH("arch"), EMBER("ember");

        public final String id;

        Style(String id) {
            this.id = id;
        }
    }

    /** Photo detail: the photo's largest side. */
    public enum Detail {
        LOW(1024), MEDIUM(2048), HIGH(4096);

        final int maxSide;

        Detail(int maxSide) {
            this.maxSide = maxSide;
        }
    }

    private record Meta(String id, ResourceLocation image, float fov, float[] tint, float exposure, boolean milkyWay,
                        float weight) {
    }


    private static final Style[] PHOTOS = {Style.VIOLET_CORE, Style.SILVER_RIVER, Style.AMETHYST, Style.TEAL_DUSK,
            Style.FROST, Style.ELECTRIC_BLUE, Style.ARCH, Style.EMBER};
    private static final int ROWS_PER_FRAME = 128;
    private static final Meta[] META = new Meta[PHOTOS.length];
    private static volatile boolean metaLoaded;

    private static int texture;
    private static int width;
    private static int height;
    private static int loadedIndex = -1;
    private static Detail loadedDetail;
    private static int pendingIndex = -1;
    private static Detail pendingDetail;
    private static CompletableFuture<Decoded> pending;
    private static Decoded uploading;
    private static int uploadedRows;
    private static long readyNanos;

    /** The style shown now: index into PHOTOS, or -1 (classic); the period it belongs to and how far into it. */
    private static int current = -1;
    private static long periodStart;
    private static int periodLength;
    private static int periodNumber;
    /** Cache of the period walk: the period containing night {@code cacheNight}. */
    private static long cacheNight = Long.MIN_VALUE;
    private static long cacheSeed;
    /** The patch's placement in the stars' frame for the current period: centre, long axis. */
    private static final float[] CENTRE = new float[3];
    private static final float[] AXIS = new float[3];

    private NightSky() {
    }

    /** Render thread, once a frame while the sky is drawn: picks the style and loads its photo. */
    public static void update(ClientLevel level) {
        if (!metaLoaded) {
            loadMeta();
        }
        current = pick(level);
        int want = current;
        Detail detail = AtmospheraConfig.nightDetail.get();
        if (uploading != null) {
            uploadStrip();
            return;
        }
        if (pending != null) {
            if (!pending.isDone()) {
                return;
            }
            Decoded decoded = pending.getNow(null);
            pending = null;
            if (decoded == null) {
                META[pendingIndex] = null;
                return;
            }
            if (pendingIndex != want || pendingDetail != detail) {
                decoded.free();
                return;
            }
            beginUpload(decoded, want, detail);
            return;
        }
        if (want < 0 || META[want] == null || (texture != 0 && loadedIndex == want && loadedDetail == detail)) {
            return;
        }
        pendingIndex = want;
        pendingDetail = detail;
        ResourceLocation image = META[want].image();
        int maxSide = detail.maxSide;
        pending = CompletableFuture.supplyAsync(() -> decode(image, maxSide), Util.backgroundExecutor());
    }

    // ------------------------------------------------------------------ the style of the night

    private static int pick(ClientLevel level) {
        if (!AtmospheraConfig.nightStyles.get()) {
            return -1;
        }
        Style forced = AtmospheraConfig.nightStyle.get();
        if (forced == Style.CLASSIC) {
            return -1;
        }
        if (forced != Style.AUTO) {
            for (int i = 0; i < PHOTOS.length; i++) {
                if (PHOTOS[i] == forced) {
                    placeFor(level, 0);
                    return META[i] != null ? i : -1;
                }
            }
        }
        long night = Math.max(0L, Math.floorDiv(level.getDayTime() - 6000L, 24000L));
        long seed = dimHash(level) * 0x9E3779B97F4A7C15L ^ 0x6E69676874L;
        if (seed != cacheSeed || night < cacheNight || night >= periodStart + periodLength) {
            // Walk the periods from night 0 (a few thousand cheap steps at most; redone only on a new period).
            long start = 0L;
            int k = 0;
            while (true) {
                int length = 14 + (int) (15 * unit(mix(seed ^ (k + 1L) * 0xBF58476D1CE4E5B9L)));
                if (night < start + length) {
                    periodStart = start;
                    periodLength = length;
                    periodNumber = k;
                    break;
                }
                start += length;
                k++;
            }
            cacheSeed = seed;
            cacheNight = night;
            placeFor(level, periodNumber);
        }
        int raw = weighted(seed, periodNumber);
        if (periodNumber > 0 && raw == weighted(seed, periodNumber - 1)) {
            raw = (raw + 1) % PHOTOS.length;
        }
        return META[raw] != null ? raw : -1;
    }

    private static int weighted(long seed, int k) {
        float total = 0f;
        for (Meta m : META) {
            total += m != null ? m.weight() : 0f;
        }
        if (total <= 0f) {
            return 0;
        }
        float u = unit(mix(seed ^ k * 0x94D049BB133111EBL ^ 0x5354594CL)) * total;
        for (int i = 0; i < META.length; i++) {
            float w = META[i] != null ? META[i].weight() : 0f;
            if (u < w) {
                return i;
            }
            u -= w;
        }
        return META.length - 1;
    }

    /**
     * Where the period's photo stands: in the stars' frame, the sky overhead at midnight is (0, -1, 0); the centre
     * leans 15 to 35 degrees north or south and up to 20 degrees towards east or west, and the long side turns.
     */
    private static void placeFor(ClientLevel level, int k) {
        long h = mix(dimHash(level) * 31L + k * 0xD6E8FEB86659FD93L);
        double tilt = Math.toRadians(15 + 20 * unit(h));
        double side = unit(mix(h + 1)) < 0.5 ? -1.0 : 1.0;
        double hour = Math.toRadians(-20 + 40 * unit(mix(h + 2)));
        double x = Math.sin(hour);
        double z = side * Math.tan(tilt);
        double y = -1.0;
        double len = Math.sqrt(x * x + y * y + z * z);
        CENTRE[0] = (float) (x / len);
        CENTRE[1] = (float) (y / len);
        CENTRE[2] = (float) (z / len);
        // A long axis in the tangent plane, turned by a hashed angle.
        double turn = Math.PI * unit(mix(h + 3));
        // 0.15.1: plain locals (this runs every frame while a style is forced; it made three arrays each time).
        double d = CENTRE[0];
        double t1x = 1.0 - d * CENTRE[0];
        double t1y = -d * CENTRE[1];
        double t1z = -d * CENTRE[2];
        double l1 = Math.sqrt(t1x * t1x + t1y * t1y + t1z * t1z);
        t1x /= l1;
        t1y /= l1;
        t1z /= l1;
        double t2x = CENTRE[1] * t1z - CENTRE[2] * t1y;
        double t2y = CENTRE[2] * t1x - CENTRE[0] * t1z;
        double t2z = CENTRE[0] * t1y - CENTRE[1] * t1x;
        double cos = Math.cos(turn);
        double sin = Math.sin(turn);
        AXIS[0] = (float) (cos * t1x + sin * t2x);
        AXIS[1] = (float) (cos * t1y + sin * t2y);
        AXIS[2] = (float) (cos * t1z + sin * t2z);
    }

    /** 0.15.1: the dimension id's string hash, kept per dimension key (the id string was built every frame). */
    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> hashedDimension;
    private static int dimensionHash;

    private static int dimHash(ClientLevel level) {
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension = level.dimension();
        if (dimension != hashedDimension) {
            hashedDimension = dimension;
            dimensionHash = dimension.location().toString().hashCode();
        }
        return dimensionHash;
    }

    // ------------------------------------------------------------------ for the sky pass

    /** The photo's texture, or 0 (none, or still loading). */
    public static int texture() {
        return uploading == null && loadedIndex == current && current >= 0 ? texture : 0;
    }

    /**
     * The four uniform vectors: u_Photo0 (centre, 1 / tan of half the long side), u_Photo1 (long axis, short / long
     * side, negative when the long side is the image's height), u_Photo2 (the night sky's colour, w strength:
     * exposure x option x fade-in; 0 without a photo), u_Photo3 (x 1 hides the procedural Milky Way inside the photo).
     */
    public static void uniforms(float[] out) {
        java.util.Arrays.fill(out, 0f);
        int i = current;
        Meta m = i >= 0 ? META[i] : null;
        if (m == null) {
            return;
        }
        float strength = AtmospheraConfig.nightStrength.get().floatValue();
        out[8] = m.tint()[0] * 0.35f;
        out[9] = m.tint()[1] * 0.35f;
        out[10] = m.tint()[2] * 0.35f;
        if (texture() == 0) {
            return;
        }
        float fade = Math.min(1f, (System.nanoTime() - readyNanos) / 2e9f);
        out[0] = CENTRE[0];
        out[1] = CENTRE[1];
        out[2] = CENTRE[2];
        // 30% larger than the lens saw: the soft edge takes the outer part.
        out[3] = (float) (1.0 / Math.tan(Math.toRadians(Math.min(150.0, m.fov() * 1.3) * 0.5)));
        out[4] = AXIS[0];
        out[5] = AXIS[1];
        out[6] = AXIS[2];
        out[7] = width >= height ? (float) height / width : -(float) width / height;
        out[11] = m.exposure() * strength * fade;
        out[12] = m.milkyWay() ? 1f : 0f;
    }

    /** For /atmosphera nightsky and status: "amethyst, night 3 of 21". */
    public static String describe(ClientLevel level) {
        if (level == null) {
            return "Night sky: no level";
        }
        int i = pick(level);
        if (i < 0) {
            return "Night sky: classic (procedural)";
        }
        long night = Math.max(0L, Math.floorDiv(level.getDayTime() - 6000L, 24000L));
        boolean forced = AtmospheraConfig.nightStyle.get() != Style.AUTO;
        return forced ? "Night sky: " + PHOTOS[i].id + " (forced)"
                : String.format(Locale.ROOT, "Night sky: %s, night %d of %d", PHOTOS[i].id, night - periodStart + 1,
                periodLength);
    }

    // ------------------------------------------------------------------ loading

    private static void loadMeta() {
        metaLoaded = true;
        var manager = Minecraft.getInstance().getResourceManager();
        for (int i = 0; i < PHOTOS.length; i++) {
            ResourceLocation id = new ResourceLocation(Atmosphera.MOD_ID, "night_styles/" + PHOTOS[i].id + ".json");
            META[i] = null;
            try {
                Resource resource = manager.getResource(id).orElse(null);
                if (resource == null) {
                    continue;
                }
                JsonObject o;
                try (InputStream in = resource.open()) {
                    o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                }
                var tint = o.getAsJsonArray("tint");
                META[i] = new Meta(PHOTOS[i].id, new ResourceLocation(o.get("image").getAsString()), o.get("fov").getAsFloat(),
                        new float[]{tint.get(0).getAsFloat(), tint.get(1).getAsFloat(), tint.get(2).getAsFloat()},
                        o.get("exposure").getAsFloat(), o.get("milky_way").getAsBoolean(),
                        o.has("weight") ? o.get("weight").getAsFloat() : 1f);
            } catch (Exception e) {
                Atmosphera.LOGGER.warn("[Atmosphera] Night sky style {} is unreadable: {}", id, e.toString());
            }
        }
    }

    private static void beginUpload(Decoded decoded, int index, Detail detail) {
        freeTexture();
        // 0.14.2: AtmospheraLib's image streaming (0.11.0).
        texture = StreamedImage.allocate("Atmosphera night sky photo", decoded, 9, GL11C.GL_RGB8, GL11C.GL_RGB);
        // RGB8 is padded to four bytes a texel by most drivers; the mip chain adds a third.
        GpuResources.textureBytes(texture, (long) decoded.width * decoded.height * 4 * 4 / 3);
        width = decoded.width;
        height = decoded.height;
        uploading = decoded;
        uploadedRows = 0;
        loadedIndex = index;
        loadedDetail = detail;
    }

    private static void uploadStrip() {
        Decoded image = uploading;
        uploadedRows += StreamedImage.uploadRows(texture, image, uploadedRows, ROWS_PER_FRAME, 9, GL11C.GL_RGB);
        if (uploadedRows >= image.height) {
            image.free();
            uploading = null;
            readyNanos = System.nanoTime();
            Atmosphera.LOGGER.info("[Atmosphera] Night sky {} ready ({}x{}, {})", PHOTOS[loadedIndex].id, width, height,
                    loadedDetail);
        }
    }

    private static Decoded decode(ResourceLocation id, int maxSide) {
        return StreamedImage.decode(Minecraft.getInstance().getResourceManager(), id, 3, maxSide, Atmosphera.LOGGER,
                "night sky photo");
    }

    private static void freeTexture() {
        if (texture != 0) {
            GpuResources.deleteTexture(texture);
            texture = 0;
        }
        loadedIndex = -1;
        loadedDetail = null;
    }

    /** Frees the photo (resource reload, shutdown); the next frame loads it again. */
    public static void release() {
        if (uploading != null) {
            uploading.free();
            uploading = null;
        }
        if (pending != null) {
            pending.thenAccept(decoded -> {
                if (decoded != null) {
                    decoded.free();
                }
            });
            pending = null;
        }
        freeTexture();
        metaLoaded = false;
        cacheNight = Long.MIN_VALUE;
    }

    /** Reloads the styles with the resource packs (a pack may replace them). */
    public static final class Loader implements ResourceManagerReloadListener {
        @Override
        public void onResourceManagerReload(ResourceManager manager) {
            Minecraft.getInstance().execute(NightSky::release);
        }
    }
}
