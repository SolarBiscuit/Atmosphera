package com.solarbiscuit.atmosphera.atmosphera.cloud;

import static com.solarbiscuit.atmosphera.lib.util.AtmosMath.clamp01;

import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.atmosphera.AtmospheraConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * The regional cloud map (0.5.0 "Storm"): a world-locked 64x64 grid of 64-block cells (4 km) around the camera, so the
 * clouds change across biome borders and over terrain instead of following only the camera's biome.
 * <ul>
 *   <li>r: dryness of the cell's biome (as {@link CloudWeather} computes it for the camera);</li>
 *   <li>g: coldness;</li>
 *   <li>b: orographic lift, 0.5 = none: terrain rising into the wind (windward slopes) above, sinking (lee) below;</li>
 *   <li>a: unused.</li>
 * </ul>
 * Cells are refreshed two rows a frame (a full pass in about half a second at 60 fps) from loaded chunks only;
 * unloaded cells take the camera's values and no lift. Render thread; no allocation per frame.
 */
final class RegionMap {
    static final int SIZE = 64;
    static final float CELL = 64f;
    private static final int ROWS_PER_FRAME = 2;

    private static int texture;
    private static final ByteBuffer DATA = MemoryUtil.memAlloc(SIZE * SIZE * 4);
    private static final float[] HEIGHT = new float[SIZE * SIZE];
    private static final float[] DRY = new float[SIZE * SIZE];
    private static final float[] COLD = new float[SIZE * SIZE];
    private static final float[] LIFT = new float[SIZE * SIZE];
    private static final float[] TMP = new float[SIZE * SIZE];
    /** 0.6.2: box-blur radius in cells (5x5 = 320 blocks). Unblurred, one cell that differs from its neighbours (an
     * island in the sea) showed as a hard-edged square in the deck. */
    private static final int BLUR = 2;
    private static int originX;
    private static int originZ;
    private static int row;
    private static boolean filled;
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    private RegionMap() {
    }

    static int texture() {
        return filled ? texture : 0;
    }

    /** out: centre minus camera (x, z), 1 / map size, 1 while valid. */
    static void params(float[] out, int at, double cameraX, double cameraZ) {
        float size = SIZE * CELL;
        out[at] = (float) (originX * CELL + size * 0.5 - cameraX);
        out[at + 1] = (float) (originZ * CELL + size * 0.5 - cameraZ);
        out[at + 2] = 1f / size;
        out[at + 3] = filled ? 1f : 0f;
    }

    /** Refreshes two rows of cells and uploads them; recentres (and starts over) when the camera left the middle. */
    static void update(ClientLevel level, double cameraX, double cameraZ, float camDry, float camCold,
                       float windX, float windZ) {
        int cx = (int) Math.floor(cameraX / CELL) - SIZE / 2;
        int cz = (int) Math.floor(cameraZ / CELL) - SIZE / 2;
        if (Math.abs(cx - originX) > SIZE / 8 || Math.abs(cz - originZ) > SIZE / 8 || !filled && row == 0) {
            if (Math.abs(cx - originX) > SIZE / 8 || Math.abs(cz - originZ) > SIZE / 8) {
                originX = cx;
                originZ = cz;
                row = 0;
                filled = false;
            }
        }
        if (texture == 0) {
            create();
        }
        boolean follow = AtmospheraConfig.weather.get();
        for (int r = 0; r < ROWS_PER_FRAME; r++) {
            int z = row;
            for (int x = 0; x < SIZE; x++) {
                int i = z * SIZE + x;
                int wx = (int) ((originX + x + 0.5f) * CELL);
                int wz = (int) ((originZ + z + 0.5f) * CELL);
                if (level.hasChunk(wx >> 4, wz >> 4)) {
                    int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz);
                    HEIGHT[i] = surface;
                    POS.set(wx, surface, wz);
                    Biome biome = level.getBiome(POS).value();
                    Biome.Precipitation precipitation = biome.getPrecipitationAt(POS);
                    float temperature = biome.getBaseTemperature();
                    DRY[i] = !follow ? 0f : precipitation == Biome.Precipitation.NONE ? (temperature > 1.0f ? 1f : 0.6f) : 0f;
                    COLD[i] = !follow ? 0f : precipitation == Biome.Precipitation.SNOW ? 1f : clamp01(0.4f - temperature);
                } else {
                    HEIGHT[i] = Float.NaN;
                    DRY[i] = camDry;
                    COLD[i] = camCold;
                }
            }
            row = (row + 1) % SIZE;
            if (row == 0) {
                filled = true;
            }
        }
        // Orographic lift for the rows just refreshed and their neighbours' heights: the rise of the terrain along the
        // wind over two cells (128 blocks), a slope of 1:4 or more saturating.
        int upX = Math.round(-windX * 2f);
        int upZ = Math.round(-windZ * 2f);
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int i = z * SIZE + x;
                float lift = 0f;
                int ux = x + upX;
                int uz = z + upZ;
                if (ux >= 0 && ux < SIZE && uz >= 0 && uz < SIZE) {
                    float h = HEIGHT[i];
                    float hu = HEIGHT[uz * SIZE + ux];
                    if (!Float.isNaN(h) && !Float.isNaN(hu)) {
                        lift = Math.max(-1f, Math.min(1f, (h - hu) / (2f * CELL) * 4f));
                    }
                }
                LIFT[i] = lift;
            }
        }
        // Smoothed over 5x5 cells into the texture (the source arrays stay raw: rows keep refreshing from them).
        writeBlurred(DRY, 0, 1f, 0f);
        writeBlurred(COLD, 1, 1f, 0f);
        writeBlurred(LIFT, 2, 0.5f, 0.5f);
        for (int i = 0; i < SIZE * SIZE; i++) {
            DATA.put(i * 4 + 3, (byte) 0);
        }
        // Tight client-memory unpacking, no pixel-unpack buffer: vanilla (and other mods) leave a row length or a PBO
        // bound, and with them the driver read past this buffer (an access violation in the driver, 0.6.0 test build).
        com.solarbiscuit.atmosphera.lib.gpu.Textures.with2D(9, texture, () -> {
            com.solarbiscuit.atmosphera.lib.gpu.Uploads.begin(4);
            try {
                GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, DATA);
            } finally {
                com.solarbiscuit.atmosphera.lib.gpu.Uploads.end();
            }
        });
    }

    private static void create() {
        texture = com.solarbiscuit.atmosphera.lib.gpu.Textures.create2D("Atmosphera region map", SIZE, SIZE, GL11C.GL_RGBA8, GL11C.GL_RGBA,
                GL11C.GL_UNSIGNED_BYTE, GL11C.GL_LINEAR, GL12C.GL_CLAMP_TO_EDGE);
    }

    static void release() {
        if (texture != 0) {
            GpuResources.deleteTexture(texture);
            texture = 0;
        }
        filled = false;
        row = 0;
    }

    /** Separable box blur of {@code src} (radius {@link #BLUR}, clamped at the map's edge) into channel {@code ch} of
     * the upload, as {@code bias + scale * value}, 0..1. About 40k adds a frame. */
    private static void writeBlurred(float[] src, int ch, float scale, float bias) {
        float norm = 1f / (2 * BLUR + 1);
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                float sum = 0f;
                for (int k = -BLUR; k <= BLUR; k++) {
                    sum += src[z * SIZE + Math.max(0, Math.min(SIZE - 1, x + k))];
                }
                TMP[z * SIZE + x] = sum * norm;
            }
        }
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                float sum = 0f;
                for (int k = -BLUR; k <= BLUR; k++) {
                    sum += TMP[Math.max(0, Math.min(SIZE - 1, z + k)) * SIZE + x];
                }
                float v = clamp01(bias + scale * sum * norm);
                DATA.put((z * SIZE + x) * 4 + ch, (byte) Math.round(v * 255f));
            }
        }
    }
}
