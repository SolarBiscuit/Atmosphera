package com.solarbiscuit.atmosphera.stratos.cloud;

import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.lib.gpu.Uploads;
import com.solarbiscuit.atmosphera.stratos.Stratos;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.stream.IntStream;

/**
 * The clouds' noise textures, all tileable and computed here (no shipped textures), with a fixed seed so every client
 * gets the same clouds:
 * <ul>
 *   <li>shape, 64³ RGBA8: R Perlin-Worley (billowy base), G/B/A inverted Worley fBm at three frequencies;</li>
 *   <li>detail, 32³ R8: higher-frequency Worley fBm that erodes the edges;</li>
 *   <li>weather, 256² RGBA8: R coverage (Perlin fBm), G cloud-type variation, B cirrus fibres, A altocumulus cells.</li>
 * </ul>
 * About 1.2 MB of texture memory. Generated once on a background thread when first asked for (well under a second on a
 * desktop CPU, in parallel), then uploaded on the render thread.
 */
public final class CloudNoise {
    static final int SHAPE = 64;
    static final int DETAIL = 32;
    static final int WEATHER = 256;
    private static final int SEED = 0x5715A705;

    private static volatile ByteBuffer shapeData;
    private static volatile ByteBuffer detailData;
    private static volatile ByteBuffer weatherData;
    private static volatile boolean generating;
    private static int shapeTex;
    private static int detailTex;
    private static int weatherTex;

    private CloudNoise() {
    }

    /** True once the three textures are on the GPU. Starts the generation on first call. Render thread. */
    public static boolean ready() {
        if (shapeTex != 0) {
            return true;
        }
        if (shapeData == null) {
            if (!generating) {
                generating = true;
                Thread thread = new Thread(CloudNoise::generate, "Stratos cloud noise");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY + 1);
                thread.start();
            }
            return false;
        }
        upload();
        return shapeTex != 0;
    }

    public static int shape() {
        return shapeTex;
    }

    public static int detail() {
        return detailTex;
    }

    public static int weather() {
        return weatherTex;
    }

    /** Frees the GL textures (the CPU data was freed after upload). The next {@link #ready()} generates again. */
    public static void release() {
        if (shapeTex != 0) {
            GpuResources.deleteTexture(shapeTex);
            GpuResources.deleteTexture(detailTex);
            GpuResources.deleteTexture(weatherTex);
            shapeTex = detailTex = weatherTex = 0;
        }
    }

    // ---------------------------------------------------------------- generation (background thread)

    private static void generate() {
        long start = System.nanoTime();
        try {
            ByteBuffer shape = MemoryUtil.memAlloc(SHAPE * SHAPE * SHAPE * 4);
            IntStream.range(0, SHAPE).parallel().forEach(z -> {
                for (int y = 0; y < SHAPE; y++) {
                    for (int x = 0; x < SHAPE; x++) {
                        float u = (x + 0.5f) / SHAPE;
                        float v = (y + 0.5f) / SHAPE;
                        float w = (z + 0.5f) / SHAPE;
                        float perlin = perlinFbm(u, v, w, 4, 3);
                        float worley = worleyFbm(u, v, w, 4);
                        float pw = clamp01(remap(perlin, worley - 1f, 1f, 0f, 1f));
                        int i = ((z * SHAPE + y) * SHAPE + x) * 4;
                        shape.put(i, toByte(pw));
                        shape.put(i + 1, toByte(worleyFbm(u, v, w, 8)));
                        shape.put(i + 2, toByte(worleyFbm(u, v, w, 16)));
                        shape.put(i + 3, toByte(worleyFbm(u, v, w, 32)));
                    }
                }
            });
            ByteBuffer detail = MemoryUtil.memAlloc(DETAIL * DETAIL * DETAIL);
            IntStream.range(0, DETAIL).parallel().forEach(z -> {
                for (int y = 0; y < DETAIL; y++) {
                    for (int x = 0; x < DETAIL; x++) {
                        float u = (x + 0.5f) / DETAIL;
                        float v = (y + 0.5f) / DETAIL;
                        float w = (z + 0.5f) / DETAIL;
                        detail.put((z * DETAIL + y) * DETAIL + x, toByte(worleyFbm(u, v, w, 4)));
                    }
                }
            });
            ByteBuffer weather = MemoryUtil.memAlloc(WEATHER * WEATHER * 4);
            IntStream.range(0, WEATHER).parallel().forEach(y -> {
                for (int x = 0; x < WEATHER; x++) {
                    float u = (x + 0.5f) / WEATHER;
                    float v = (y + 0.5f) / WEATHER;
                    // Coverage: large fields with ragged edges; stretched so both clear and overcast areas exist.
                    float cov = clamp01((perlinFbm(u, v, 0f, 4, 5) - 0.5f) * 1.6f + 0.5f);
                    // Type: slow variation between flatter and taller clouds.
                    float type = clamp01(perlinFbm(u + 0.37f, v + 0.11f, 0.5f, 3, 2) * 1.2f - 0.1f);
                    // Cirrus: fibres, long along one axis and fine across it, with a gentle warp.
                    float warp = perlinFbm(u, v, 0.25f, 4, 2) - 0.5f;
                    float fibre = perlinFbmAniso(u + warp * 0.08f, v, 8, 64, 3);
                    float cirrus = clamp01((fibre - 0.45f) * 2.2f) * clamp01(perlinFbm(u, v, 0.75f, 2, 2) * 1.6f - 0.3f);
                    // Altocumulus: small rounded cells, grouped in patches.
                    float cells = worley2(u, v, 48);
                    float patch = clamp01(perlinFbm(u + 0.61f, v + 0.29f, 0.9f, 4, 2) * 1.8f - 0.55f);
                    int i = (y * WEATHER + x) * 4;
                    weather.put(i, toByte(cov));
                    weather.put(i + 1, toByte(type));
                    weather.put(i + 2, toByte(cirrus));
                    weather.put(i + 3, toByte(clamp01(cells * 1.25f - 0.2f) * patch));
                }
            });
            detailData = detail;
            weatherData = weather;
            shapeData = shape; // last: ready() keys on it
            Stratos.LOGGER.info("Cloud noise generated in {} ms", (System.nanoTime() - start) / 1_000_000);
        } catch (Throwable t) {
            Stratos.LOGGER.error("Cloud noise generation failed; clouds stay off", t);
        }
    }

    private static void upload() {
        ByteBuffer shape = shapeData;
        ByteBuffer detail = detailData;
        ByteBuffer weather = weatherData;
        int previousUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
        Uploads.begin(1);
        try {
            shapeTex = texture3d("Stratos cloud shape", SHAPE, GL11C.GL_RGBA8, GL11C.GL_RGBA, shape, 4);
            detailTex = texture3d("Stratos cloud detail", DETAIL, GL30C.GL_R8, GL11C.GL_RED, detail, 1);
            weatherTex = GpuResources.texture("Stratos cloud weather");
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, weatherTex);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, WEATHER, WEATHER, 0, GL11C.GL_RGBA,
                    GL11C.GL_UNSIGNED_BYTE, weather);
            params(GL11C.GL_TEXTURE_2D);
            GL30C.glGenerateMipmap(GL11C.GL_TEXTURE_2D);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            GpuResources.textureBytes(weatherTex, WEATHER * WEATHER * 4L * 4 / 3);
        } finally {
            Uploads.end();
            GL13C.glActiveTexture(previousUnit);
            MemoryUtil.memFree(shape);
            MemoryUtil.memFree(detail);
            MemoryUtil.memFree(weather);
            shapeData = detailData = weatherData = null;
            generating = false;
        }
    }

    private static int texture3d(String owner, int n, int internal, int format, ByteBuffer data, int bytes) {
        int tex = GpuResources.texture(owner);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, tex);
        GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, internal, n, n, n, 0, format, GL11C.GL_UNSIGNED_BYTE, data);
        params(GL12C.GL_TEXTURE_3D);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_WRAP_R, GL11C.GL_REPEAT);
        GL30C.glGenerateMipmap(GL12C.GL_TEXTURE_3D);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, 0);
        GpuResources.textureBytes(tex, (long) n * n * n * bytes * 8 / 7);
        return tex;
    }

    private static void params(int target) {
        GL11C.glTexParameteri(target, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
        GL11C.glTexParameteri(target, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
        GL11C.glTexParameteri(target, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR_MIPMAP_LINEAR);
        GL11C.glTexParameteri(target, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
    }

    // ---------------------------------------------------------------- tileable noise (pure functions)

    private static int hash(int x, int y, int z, int salt) {
        int h = x * 0x27D4EB2D ^ y * 0x165667B1 ^ z * 0x61C88647 ^ salt * 0x9E3779B9 ^ SEED;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        h *= 0x297A2D39;
        h ^= h >>> 15;
        return h;
    }

    private static float rand01(int h) {
        return (h >>> 8) * (1f / (1 << 24));
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    /** Gradient noise of the unit cube tiled with {@code px*py*pz} cells, about -1..1. */
    private static float perlin(float u, float v, float w, int px, int py, int pz) {
        float x = u * px;
        float y = v * py;
        float z = w * pz;
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        float fx = x - x0;
        float fy = y - y0;
        float fz = z - z0;
        float sx = fade(fx);
        float sy = fade(fy);
        float sz = fade(fz);
        float result = 0f;
        float[] acc = new float[8];
        int k = 0;
        for (int dz = 0; dz <= 1; dz++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dx = 0; dx <= 1; dx++) {
                    int h = hash(Math.floorMod(x0 + dx, px), Math.floorMod(y0 + dy, py), Math.floorMod(z0 + dz, pz), 1);
                    acc[k++] = grad(h, fx - dx, fy - dy, fz - dz);
                }
            }
        }
        float x00 = lerp(acc[0], acc[1], sx);
        float x10 = lerp(acc[2], acc[3], sx);
        float x01 = lerp(acc[4], acc[5], sx);
        float x11 = lerp(acc[6], acc[7], sx);
        result = lerp(lerp(x00, x10, sy), lerp(x01, x11, sy), sz);
        return result;
    }

    private static float grad(int h, float x, float y, float z) {
        switch (h >>> 28) { // the 12 cube-edge directions (and 4 repeats)
            case 0: case 12: return x + y;
            case 1: case 13: return -x + y;
            case 2: return x - y;
            case 3: return -x - y;
            case 4: return x + z;
            case 5: return -x + z;
            case 6: return x - z;
            case 7: return -x - z;
            case 8: return y + z;
            case 9: case 14: return -y + z;
            case 10: return y - z;
            default: return -y - z;
        }
    }

    /** fBm of {@link #perlin}, mapped to 0..1; {@code period} cells at the first octave, doubling each octave. */
    private static float perlinFbm(float u, float v, float w, int period, int octaves) {
        float sum = 0f;
        float amp = 0.5f;
        float norm = 0f;
        int p = period;
        for (int o = 0; o < octaves; o++) {
            sum += perlin(u, v, w, p, p, p) * amp;
            norm += amp;
            amp *= 0.5f;
            p *= 2;
        }
        return clamp01(sum / norm * 0.75f + 0.5f);
    }

    private static float perlinFbmAniso(float u, float v, int periodU, int periodV, int octaves) {
        float sum = 0f;
        float amp = 0.5f;
        float norm = 0f;
        int pu = periodU;
        int pv = periodV;
        for (int o = 0; o < octaves; o++) {
            sum += perlin(u, v, 0f, pu, pv, 1) * amp;
            norm += amp;
            amp *= 0.5f;
            pu *= 2;
            pv *= 2;
        }
        return clamp01(sum / norm * 0.75f + 0.5f);
    }

    /** Inverted cellular noise (1 at feature points, 0 one cell away), tiled with {@code period} cells per axis. */
    private static float worley(float u, float v, float w, int period) {
        float x = u * period;
        float y = v * period;
        float z = w * period;
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        int cz = (int) Math.floor(z);
        float best = 9f;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = cx + dx;
                    int ny = cy + dy;
                    int nz = cz + dz;
                    int h = hash(Math.floorMod(nx, period), Math.floorMod(ny, period), Math.floorMod(nz, period), 2);
                    float px = nx + rand01(h) - x;
                    float py = ny + rand01(hash(h, 7, 0, 3)) - y;
                    float pz = nz + rand01(hash(h, 0, 11, 4)) - z;
                    float d = px * px + py * py + pz * pz;
                    if (d < best) {
                        best = d;
                    }
                }
            }
        }
        return 1f - clamp01((float) Math.sqrt(best));
    }

    private static float worleyFbm(float u, float v, float w, int period) {
        return worley(u, v, w, period) * 0.625f + worley(u, v, w, period * 2) * 0.25f
                + worley(u, v, w, period * 4) * 0.125f;
    }

    private static float worley2(float u, float v, int period) {
        float x = u * period;
        float y = v * period;
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        float best = 9f;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = cx + dx;
                int ny = cy + dy;
                int h = hash(Math.floorMod(nx, period), Math.floorMod(ny, period), 0, 5);
                float px = nx + 0.15f + 0.7f * rand01(h) - x;
                float py = ny + 0.15f + 0.7f * rand01(hash(h, 3, 0, 6)) - y;
                float d = px * px + py * py;
                if (d < best) {
                    best = d;
                }
            }
        }
        return 1f - clamp01((float) Math.sqrt(best) * 1.4f);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float remap(float value, float lo, float hi, float newLo, float newHi) {
        return newLo + (value - lo) / Math.max(1e-6f, hi - lo) * (newHi - newLo);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static byte toByte(float v) {
        return (byte) Math.round(clamp01(v) * 255f);
    }
}
