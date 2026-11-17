package com.solarbiscuit.atmosphera.atmosphera.sky;

import com.solarbiscuit.atmosphera.lib.gpu.StreamedImage;
import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.atmosphera.Atmosphera;
import com.solarbiscuit.atmosphera.atmosphera.AtmospheraConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * 0.9.0: a nebula is a real telescope image (ESA/Hubble and ESA/Webb, CC BY 4.0: see THIRD_PARTY_NOTICES.md), from
 * {@code assets/<namespace>/textures/nebula/*.jpg|png}; resource packs can add their own. Atmosphera's own come first, then
 * the others by name.
 * <p>
 * 0.10.0: up to three are held at a time (slots 0-2, one per nebula of the night). Each is decoded off the render thread
 * (LWJGL's stb_image: JPEG and PNG), halved while it is larger than its detail size (slot 0 the "Nebula detail" option,
 * the others one step lower), uploaded in strips over a few frames (no hitch for a 4000-pixel image) and mipmapped; it
 * is freed a minute after its nebula has gone. The JPEGs are kept at their full resolution (up to 4000 pixels) so they
 * stay sharp under a zoom.
 */
public final class NebulaImages {
    /** Sizes of the "Nebula detail" option: the longest side the image is kept at. */
    public enum Detail {
        LOW(1024), MEDIUM(2048), HIGH(4096);

        final int maxSide;

        Detail(int maxSide) {
            this.maxSide = maxSide;
        }

        Detail lower() {
            return this == HIGH ? MEDIUM : LOW;
        }
    }

    public static final int SLOTS = 3;
    private static final String FOLDER = "textures/nebula";
    private static final int ROWS_PER_FRAME = 256;
    private static final long KEEP_NANOS = 60_000_000_000L;
    private static final float FADE_IN_SECONDS = 1.5f;

    private static List<ResourceLocation> images;
    private static final Slot[] SLOT = {new Slot(0), new Slot(1), new Slot(2)};
    private static boolean failedLogged;

    /** A decoded image (AtmospheraLib's StreamedImage, 0.14.2) with its mean colour. */
    private static final class Decoded {
        final StreamedImage.Decoded image;
        final ByteBuffer pixels;
        final int width;
        final int height;
        final float[] mean = new float[3];

        Decoded(StreamedImage.Decoded image) {
            this.image = image;
            this.pixels = image.pixels;
            this.width = image.width;
            this.height = image.height;
        }

        void free() {
            image.free();
        }
    }

    private NebulaImages() {
    }

    /** The images found in the resource packs (listed once per resource reload). */
    public static List<ResourceLocation> list() {
        if (images == null) {
            List<ResourceLocation> found = new ArrayList<>(Minecraft.getInstance().getResourceManager().listResources(FOLDER,
                    id -> id.getPath().endsWith(".jpg") || id.getPath().endsWith(".jpeg") || id.getPath().endsWith(".png")).keySet());
            found.sort(Comparator.comparing((ResourceLocation id) -> !Atmosphera.MOD_ID.equals(id.getNamespace()))
                    .thenComparing(ResourceLocation::getNamespace).thenComparing(ResourceLocation::getPath));
            images = List.copyOf(found);
        }
        return images;
    }

    public static int count() {
        return list().size();
    }

    /** A readable name: "lagoon" for {@code atmosphera:textures/nebula/01_lagoon.jpg}. */
    public static String name(int index) {
        List<ResourceLocation> all = list();
        if (all.isEmpty()) {
            return "none";
        }
        String path = all.get(Math.floorMod(index, all.size())).getPath();
        String file = path.substring(path.lastIndexOf('/') + 1, path.lastIndexOf('.'));
        int underscore = file.indexOf('_');
        if (underscore > 0 && file.substring(0, underscore).chars().allMatch(Character::isDigit)) {
            file = file.substring(underscore + 1);
        }
        return file.replace('_', ' ');
    }

    public static String describe(int index) {
        return String.format(Locale.ROOT, "%d (%s)", Math.floorMod(index, Math.max(count(), 1)) + 1, name(index));
    }

    /**
     * Render thread, every frame the sky is drawn: the image each slot should show (wrapped into the list), or -1 for
     * none. Starts and continues the decodes and uploads, and frees images a while after they are last wanted.
     */
    public static void update(int[] wanted) {
        Detail detail = AtmospheraConfig.nebulaDetail.get();
        for (int i = 0; i < SLOTS; i++) {
            SLOT[i].update(i < wanted.length ? wanted[i] : -1, i == 0 ? detail : detail.lower());
        }
    }

    /** The GL texture of the image in this slot (0 while none is ready). */
    public static int texture(int slot) {
        return SLOT[slot].texture();
    }

    /** Fade in over 1.5 s after the upload (0..1). */
    public static float fade(int slot) {
        return SLOT[slot].fade();
    }

    public static int width(int slot) {
        return SLOT[slot].width;
    }

    public static int height(int slot) {
        return SLOT[slot].height;
    }

    /** The slot's image mean colour (display values), or false while none is ready. */
    public static boolean meanColour(int slot, float[] out) {
        Slot s = SLOT[slot];
        if (s.texture() == 0) {
            return false;
        }
        System.arraycopy(s.mean, 0, out, 0, 3);
        return true;
    }

    /** Frees every image and forgets the list (resource reload, shader reload). */
    public static void release() {
        for (Slot s : SLOT) {
            s.release();
        }
        images = null;
        if (!failedLogged && count() == 0) {
            failedLogged = true;
            Atmosphera.LOGGER.info("[Atmosphera] Atmosphera found no nebula images under {}", FOLDER);
        }
    }

    /** One image: its decode, its strip upload and its texture. */
    private static final class Slot {
        private final int number;
        private int wantedIndex = -1;
        private int loadedIndex = -1;
        private Detail loadedDetail;
        private CompletableFuture<Decoded> pending;
        private int pendingIndex = -1;
        private Detail pendingDetail;
        private Decoded uploading;
        private int uploadedRows;
        private int texture;
        private int width;
        private int height;
        private long readyNanos;
        private long lastWanted;
        private final float[] mean = new float[3];

        Slot(int number) {
            this.number = number;
        }

        void update(int index, Detail detail) {
            long now = System.nanoTime();
            int count = count();
            if (index >= 0 && count > 0) {
                wantedIndex = Math.floorMod(index, count);
                lastWanted = now;
            } else {
                if (texture != 0 && now - lastWanted > KEEP_NANOS) {
                    freeTexture();
                }
                if (index < 0) {
                    return;
                }
            }
            if (wantedIndex < 0 || count == 0) {
                return;
            }
            if (loadedIndex == wantedIndex && loadedDetail == detail && uploading == null) {
                return;
            }
            if (uploading != null) {
                uploadStrip();
                return;
            }
            if (pending != null) {
                if (!pending.isDone()) {
                    return;
                }
                Decoded decoded = pending.getNow(null);
                int index0 = pendingIndex;
                Detail detail0 = pendingDetail;
                pending = null;
                if (decoded == null) {
                    loadedIndex = index0;
                    loadedDetail = detail0;
                    return;
                }
                if (index0 != wantedIndex || detail0 != detail) {
                    decoded.free();
                    return;
                }
                beginUpload(decoded, index0, detail0);
                return;
            }
            ResourceLocation id = list().get(wantedIndex);
            int maxSide = detail.maxSide;
            pendingIndex = wantedIndex;
            pendingDetail = detail;
            pending = CompletableFuture.supplyAsync(() -> decode(id, maxSide), Util.backgroundExecutor());
        }

        int texture() {
            return texture != 0 && uploading == null && loadedIndex == wantedIndex ? texture : 0;
        }

        float fade() {
            if (texture() == 0) {
                return 0f;
            }
            return Math.min(1f, (System.nanoTime() - readyNanos) / 1e9f / FADE_IN_SECONDS);
        }

        private void beginUpload(Decoded decoded, int index, Detail detail) {
            freeTexture();
            // 0.14.2: AtmospheraLib's image streaming (0.11.0).
            texture = StreamedImage.allocate("Atmosphera nebula " + number, decoded.image, 9, GL11C.GL_RGB8, GL11C.GL_RGB);
            width = decoded.width;
            height = decoded.height;
            // RGB8 is padded to four bytes a texel by most drivers; the mip chain adds a third.
            GpuResources.textureBytes(texture, (long) width * height * 4 * 4 / 3);
            uploading = decoded;
            uploadedRows = 0;
            loadedIndex = index;
            loadedDetail = detail;
        }

        private void uploadStrip() {
            Decoded image = uploading;
            // Image rows run top to bottom; the texture's v runs the same way here (the shader flips v).
            uploadedRows += StreamedImage.uploadRows(texture, image.image, uploadedRows, ROWS_PER_FRAME, 9, GL11C.GL_RGB);
            if (uploadedRows >= image.height) {
                System.arraycopy(image.mean, 0, mean, 0, 3);
                image.free();
                uploading = null;
                readyNanos = System.nanoTime();
                Atmosphera.LOGGER.debug("[Atmosphera] Atmosphera nebula {} ready in slot {} ({}x{})", name(loadedIndex), number,
                        width, height);
            }
        }

        private void freeTexture() {
            if (uploading != null) {
                uploading.free();
                uploading = null;
            }
            if (texture != 0) {
                GpuResources.deleteTexture(texture);
                texture = 0;
            }
            loadedIndex = -1;
            loadedDetail = null;
        }

        void release() {
            freeTexture();
            if (pending != null) {
                pending.thenAccept(d -> {
                    if (d != null) {
                        d.free();
                    }
                });
                pending = null;
            }
            wantedIndex = -1;
        }
    }

    private static Decoded decode(ResourceLocation id, int maxSide) {
        StreamedImage.Decoded decoded = StreamedImage.decode(Minecraft.getInstance().getResourceManager(), id, 3, maxSide,
                Atmosphera.LOGGER, "nebula image");
        if (decoded == null) {
            return null;
        }
        Decoded image = new Decoded(decoded);
        meanColour(image);
        return image;
    }

    /** The mean colour of every 16th texel of every 16th row. */
    private static void meanColour(Decoded image) {
        double r = 0, g = 0, b = 0;
        long n = 0;
        for (int y = 0; y < image.height; y += 16) {
            for (int x = 0; x < image.width; x += 16) {
                int i = (y * image.width + x) * 3;
                r += image.pixels.get(i) & 0xFF;
                g += image.pixels.get(i + 1) & 0xFF;
                b += image.pixels.get(i + 2) & 0xFF;
                n++;
            }
        }
        if (n > 0) {
            image.mean[0] = (float) (r / n / 255.0);
            image.mean[1] = (float) (g / n / 255.0);
            image.mean[2] = (float) (b / n / 255.0);
        }
    }

    /** Resource reloads: the list and the images are read again; the pack moon's disc is measured again. */
    public static final class Loader implements ResourceManagerReloadListener {
        @Override
        public void onResourceManagerReload(ResourceManager manager) {
            com.mojang.blaze3d.systems.RenderSystem.recordRenderCall(() -> {
                release();
                SkyPass.packMoonChanged();
            });
        }
    }
}
