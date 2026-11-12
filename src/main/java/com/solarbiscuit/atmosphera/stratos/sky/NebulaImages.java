package com.solarbiscuit.atmosphera.stratos.sky;

import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.stratos.Stratos;
import com.solarbiscuit.atmosphera.stratos.StratosConfig;
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
 * 0.9.0: the night's nebula is a real telescope image (ESA/Hubble and ESA/Webb, CC BY 4.0: see THIRD_PARTY_NOTICES.md),
 * from {@code assets/<namespace>/textures/nebula/*.jpg|png}; resource packs can add their own. Stratos's own come first,
 * then the others by name.
 * <p>
 * One image is held at a time. It is decoded off the render thread (LWJGL's stb_image: JPEG and PNG), halved while it
 * is larger than the "Nebula detail" size, uploaded in strips over a few frames (no hitch for a 4000-pixel image) and
 * mipmapped; it is freed a minute after the nebula has gone. The JPEGs are kept at their full resolution (up to 4000
 * pixels) so they stay sharp under a zoom.
 */
public final class NebulaImages {
    /** Sizes of the "Nebula detail" option: the longest side the image is kept at. */
    public enum Detail {
        LOW(1024), MEDIUM(2048), HIGH(4096);

        final int maxSide;

        Detail(int maxSide) {
            this.maxSide = maxSide;
        }
    }

    private static final String FOLDER = "textures/nebula";
    private static final int ROWS_PER_FRAME = 256;
    private static final long KEEP_NANOS = 60_000_000_000L;
    private static final float FADE_IN_SECONDS = 1.5f;

    private static List<ResourceLocation> images;
    private static int wantedIndex = -1;
    private static int loadedIndex = -1;
    private static Detail loadedDetail;
    private static CompletableFuture<Decoded> pending;
    private static int pendingIndex = -1;
    private static Decoded uploading;
    private static int uploadedRows;
    private static int texture;
    private static int width;
    private static int height;
    private static long readyNanos;
    /** The image's mean colour (display values 0..1), for the glow round it and the night light. */
    private static final float[] MEAN = new float[3];
    private static long lastWanted;
    private static boolean failedLogged;

    private record Decoded(ByteBuffer pixels, int width, int height, boolean stb, float[] mean) {
        Decoded(ByteBuffer pixels, int width, int height, boolean stb) {
            this(pixels, width, height, stb, new float[3]);
        }

        void free() {
            if (stb) {
                STBImage.stbi_image_free(pixels);
            } else {
                MemoryUtil.memFree(pixels);
            }
        }
    }

    private NebulaImages() {
    }

    /** The images found in the resource packs (listed once per resource reload). */
    public static List<ResourceLocation> list() {
        if (images == null) {
            List<ResourceLocation> found = new ArrayList<>(Minecraft.getInstance().getResourceManager().listResources(FOLDER,
                    id -> id.getPath().endsWith(".jpg") || id.getPath().endsWith(".jpeg") || id.getPath().endsWith(".png")).keySet());
            found.sort(Comparator.comparing((ResourceLocation id) -> !Stratos.MOD_ID.equals(id.getNamespace()))
                    .thenComparing(ResourceLocation::getNamespace).thenComparing(ResourceLocation::getPath));
            images = List.copyOf(found);
        }
        return images;
    }

    public static int count() {
        return list().size();
    }

    /** A readable name: "lagoon" for {@code stratos:textures/nebula/01_lagoon.jpg}. */
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

    /**
     * Render thread, every frame the sky is drawn: index is the night's image (wrapped into the list), or -1 when no
     * nebula shows. Starts and continues the decode and upload, and frees the image a while after it is last wanted.
     */
    public static void update(int index) {
        long now = System.nanoTime();
        int count = count();
        if (index >= 0 && count > 0) {
            wantedIndex = Math.floorMod(index, count);
            lastWanted = now;
        } else if (texture != 0 && now - lastWanted > KEEP_NANOS) {
            freeTexture();
        }
        if (wantedIndex < 0 || count == 0) {
            return;
        }
        Detail detail = StratosConfig.nebulaDetail.get();
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
            pending = null;
            if (decoded == null) {
                loadedIndex = index0;
                loadedDetail = detail;
                return;
            }
            if (index0 != wantedIndex) {
                decoded.free();
                return;
            }
            beginUpload(decoded, index0, detail);
            return;
        }
        if (loadedIndex != wantedIndex || loadedDetail != detail) {
            ResourceLocation id = list().get(wantedIndex);
            int maxSide = detail.maxSide;
            pendingIndex = wantedIndex;
            pending = CompletableFuture.supplyAsync(() -> decode(id, maxSide), Util.backgroundExecutor());
        }
    }

    /** The GL texture of the image on show (0 while none is ready). */
    public static int texture() {
        return texture != 0 && uploading == null && loadedIndex == wantedIndex ? texture : 0;
    }

    /** Fade in over 1.5 s after the upload (0..1). */
    public static float fade() {
        if (texture() == 0) {
            return 0f;
        }
        return Math.min(1f, (System.nanoTime() - readyNanos) / 1e9f / FADE_IN_SECONDS);
    }

    /** The shown image's mean colour (display values), or false while none is ready. */
    public static boolean meanColour(float[] out) {
        if (texture() == 0) {
            return false;
        }
        System.arraycopy(MEAN, 0, out, 0, 3);
        return true;
    }

    public static int width() {
        return width;
    }

    public static int height() {
        return height;
    }

    private static Decoded decode(ResourceLocation id, int maxSide) {
        try {
            Resource resource = Minecraft.getInstance().getResourceManager().getResource(id).orElse(null);
            if (resource == null) {
                return null;
            }
            byte[] bytes;
            try (InputStream in = resource.open()) {
                bytes = in.readAllBytes();
            }
            ByteBuffer file = MemoryUtil.memAlloc(bytes.length);
            Decoded image;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                file.put(bytes).flip();
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                IntBuffer comp = stack.mallocInt(1);
                ByteBuffer pixels = STBImage.stbi_load_from_memory(file, w, h, comp, 3);
                if (pixels == null) {
                    Stratos.LOGGER.warn("[Atmosphera] Stratos could not read the nebula image {}: {}", id, STBImage.stbi_failure_reason());
                    return null;
                }
                image = new Decoded(pixels, w.get(0), h.get(0), true);
            } finally {
                MemoryUtil.memFree(file);
            }
            while (Math.max(image.width, image.height) > maxSide) {
                image = halve(image);
            }
            meanColour(image);
            return image;
        } catch (Throwable e) {
            Stratos.LOGGER.warn("[Atmosphera] Stratos could not load the nebula image {}", id, e);
            return null;
        }
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

    /** A 2x2 box filter (the "Nebula detail" size; odd edges drop their last row or column). */
    private static Decoded halve(Decoded in) {
        int w = Math.max(1, in.width / 2);
        int h = Math.max(1, in.height / 2);
        ByteBuffer out = MemoryUtil.memAlloc(w * h * 3);
        ByteBuffer src = in.pixels;
        int stride = in.width * 3;
        for (int y = 0; y < h; y++) {
            int row0 = (2 * y) * stride;
            int row1 = Math.min(2 * y + 1, in.height - 1) * stride;
            for (int x = 0; x < w; x++) {
                int a = row0 + 6 * x;
                int b = Math.min(a + 3, row0 + stride - 3);
                int c = row1 + 6 * x;
                int d = Math.min(c + 3, row1 + stride - 3);
                for (int k = 0; k < 3; k++) {
                    int sum = (src.get(a + k) & 0xFF) + (src.get(b + k) & 0xFF) + (src.get(c + k) & 0xFF) + (src.get(d + k) & 0xFF);
                    out.put((y * w + x) * 3 + k, (byte) ((sum + 2) >> 2));
                }
            }
        }
        in.free();
        return new Decoded(out, w, h, false, in.mean);
    }

    private static void beginUpload(Decoded decoded, int index, Detail detail) {
        freeTexture();
        texture = GpuResources.texture("Stratos nebula");
        width = decoded.width;
        height = decoded.height;
        withTexture(() -> {
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGB8, width, height, 0, GL11C.GL_RGB, GL11C.GL_UNSIGNED_BYTE,
                    (ByteBuffer) null);
        });
        // RGB8 is padded to four bytes a texel by most drivers; the mip chain adds a third.
        GpuResources.textureBytes(texture, (long) width * height * 4 * 4 / 3);
        uploading = decoded;
        uploadedRows = 0;
        loadedIndex = index;
        loadedDetail = detail;
    }

    private static void uploadStrip() {
        Decoded image = uploading;
        int rows = Math.min(ROWS_PER_FRAME, image.height - uploadedRows);
        withTexture(() -> {
            int[] saved = {GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT), GL11C.glGetInteger(GL11C.GL_UNPACK_ROW_LENGTH),
                    GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_ROWS), GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_PIXELS),
                    GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING)};
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
            // Image rows run top to bottom; the texture's v runs the same way here (the shader flips v).
            ByteBuffer strip = MemoryUtil.memSlice(image.pixels, uploadedRows * image.width * 3, rows * image.width * 3);
            GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, uploadedRows, image.width, rows, GL11C.GL_RGB,
                    GL11C.GL_UNSIGNED_BYTE, strip);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, saved[0]);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, saved[1]);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, saved[2]);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, saved[3]);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, saved[4]);
            if (uploadedRows + rows >= image.height) {
                GL30C.glGenerateMipmap(GL11C.GL_TEXTURE_2D);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR_MIPMAP_LINEAR);
                if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
                    float max = GL11C.glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
                    GL11C.glTexParameterf(GL11C.GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT,
                            Math.min(8f, max));
                }
            }
        });
        uploadedRows += rows;
        if (uploadedRows >= image.height) {
            System.arraycopy(image.mean, 0, MEAN, 0, 3);
            image.free();
            uploading = null;
            readyNanos = System.nanoTime();
            Stratos.LOGGER.debug("[Atmosphera] Stratos nebula {} ready ({}x{})", name(loadedIndex), width, height);
        }
    }

    private static void withTexture(Runnable body) {
        int previousUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE9);
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        try {
            body.run();
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
            GL13C.glActiveTexture(previousUnit);
        }
    }

    private static void freeTexture() {
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

    /** Frees the image and forgets the list (resource reload, shader reload). */
    public static void release() {
        freeTexture();
        if (pending != null) {
            pending.thenAccept(d -> {
                if (d != null) {
                    d.free();
                }
            });
            pending = null;
        }
        images = null;
        wantedIndex = -1;
        if (!failedLogged && count() == 0) {
            failedLogged = true;
            Stratos.LOGGER.info("[Atmosphera] Stratos found no nebula images under {}", FOLDER);
        }
    }

    /** Resource reloads: the list and the image are read again. */
    public static final class Loader implements ResourceManagerReloadListener {
        @Override
        public void onResourceManagerReload(ResourceManager manager) {
            RenderThread.run(NebulaImages::release);
        }
    }

    /** Runs on the render thread (reload listeners may be called off it). */
    private static final class RenderThread {
        static void run(Runnable task) {
            com.mojang.blaze3d.systems.RenderSystem.recordRenderCall(task::run);
        }
    }

    public static String describe(int index) {
        return String.format(Locale.ROOT, "%d (%s)", Math.floorMod(index, Math.max(count(), 1)) + 1, name(index));
    }
}
