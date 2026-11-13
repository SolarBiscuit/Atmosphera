package com.solarbiscuit.atmosphera.atmosphera.sky;

import com.solarbiscuit.atmosphera.atmosphera.Atmosphera;
import com.solarbiscuit.atmosphera.atmosphera.AtmospheraConfig;
import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
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
import java.util.concurrent.CompletableFuture;

/**
 * The Moon's surface (0.12.0): NASA's LRO map of the near side (the face the Moon always turns to the Earth), an
 * equirectangular greyscale image from longitude -90 to +90 degrees in {@code textures/moon/near_side.jpg}
 * (NASA's Scientific Visualization Studio, CGI Moon Kit; see THIRD_PARTY_NOTICES.md). Decoded off the render thread with
 * stb_image as one channel, halved to the "Moon detail" size, uploaded as R8 in strips of rows over a few frames,
 * mipmapped. Until it is ready the sky draws a smooth stand-in (no texture detail). Same pattern as {@link NebulaImages}.
 */
public final class MoonMap {
    /** Moon detail: the map's largest side (texels across the near side's 180 degrees of longitude). */
    public enum Detail {
        LOW(1024), MEDIUM(2048), HIGH(4096);

        final int maxSide;

        Detail(int maxSide) {
            this.maxSide = maxSide;
        }
    }

    private static final ResourceLocation ID = new ResourceLocation(Atmosphera.MOD_ID, "textures/moon/near_side.jpg");
    private static final int ROWS_PER_FRAME = 256;
    private static final float FADE_IN_SECONDS = 1.0f;

    private record Decoded(ByteBuffer pixels, int width, int height, boolean stb) {
        void free() {
            if (stb) {
                STBImage.stbi_image_free(pixels);
            } else {
                MemoryUtil.memFree(pixels);
            }
        }
    }

    private static int texture;
    private static int width;
    private static Detail loadedDetail;
    private static Detail pendingDetail;
    private static CompletableFuture<Decoded> pending;
    private static Decoded uploading;
    private static int uploadedRows;
    private static long readyNanos;
    private static boolean failed;

    private MoonMap() {
    }

    /** Render thread, once a frame while the sky is drawn: starts or continues loading at the detail the option asks. */
    public static void update() {
        Detail detail = AtmospheraConfig.moonDetail.get();
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
                failed = true;
                return;
            }
            if (pendingDetail != detail) {
                decoded.free();
                return;
            }
            beginUpload(decoded, detail);
            return;
        }
        if (failed || (texture != 0 && loadedDetail == detail)) {
            return;
        }
        pendingDetail = detail;
        int maxSide = detail.maxSide;
        pending = CompletableFuture.supplyAsync(() -> decode(maxSide), Util.backgroundExecutor());
    }

    /** The texture, or 0 while loading. */
    public static int texture() {
        return uploading == null && loadedDetail != null ? texture : 0;
    }

    /** Texels across the map (0 while loading). */
    public static int width() {
        return texture() != 0 ? width : 0;
    }

    /** 0..1 over the first second after the map is ready (the smooth stand-in fades into it). */
    public static float fade() {
        if (texture() == 0) {
            return 0f;
        }
        return Math.min(1f, (System.nanoTime() - readyNanos) / 1e9f / FADE_IN_SECONDS);
    }

    private static void beginUpload(Decoded decoded, Detail detail) {
        freeTexture();
        texture = GpuResources.texture("Atmosphera moon map");
        width = decoded.width;
        withTexture(texture, () -> {
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_R8, decoded.width, decoded.height, 0, GL11C.GL_RED,
                    GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        });
        GpuResources.textureBytes(texture, (long) decoded.width * decoded.height * 4 / 3);
        uploading = decoded;
        uploadedRows = 0;
        loadedDetail = detail;
    }

    private static void uploadStrip() {
        Decoded image = uploading;
        int rows = Math.min(ROWS_PER_FRAME, image.height - uploadedRows);
        withTexture(texture, () -> {
            int[] saved = {GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT), GL11C.glGetInteger(GL11C.GL_UNPACK_ROW_LENGTH),
                    GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_ROWS), GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_PIXELS),
                    GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING)};
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
            // Image rows run top (north) to bottom; the shader's v runs the same way.
            ByteBuffer strip = MemoryUtil.memSlice(image.pixels, uploadedRows * image.width, rows * image.width);
            GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, uploadedRows, image.width, rows, GL11C.GL_RED,
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
            image.free();
            uploading = null;
            readyNanos = System.nanoTime();
            Atmosphera.LOGGER.info("[Atmosphera] Moon map ready ({}x{}, {})", image.width, image.height, loadedDetail);
        }
    }

    private static Decoded decode(int maxSide) {
        try {
            Resource resource = Minecraft.getInstance().getResourceManager().getResource(ID).orElse(null);
            if (resource == null) {
                Atmosphera.LOGGER.warn("[Atmosphera] The moon map {} is missing; the moon is drawn without it.", ID);
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
                ByteBuffer pixels = STBImage.stbi_load_from_memory(file, w, h, comp, 1);
                if (pixels == null) {
                    Atmosphera.LOGGER.warn("[Atmosphera] Could not read the moon map {}: {}", ID, STBImage.stbi_failure_reason());
                    return null;
                }
                image = new Decoded(pixels, w.get(0), h.get(0), true);
            } finally {
                MemoryUtil.memFree(file);
            }
            while (Math.max(image.width, image.height) > maxSide) {
                image = halve(image);
            }
            return image;
        } catch (Throwable e) {
            Atmosphera.LOGGER.warn("[Atmosphera] Could not load the moon map {}", ID, e);
            return null;
        }
    }

    /** A 2x2 box filter over one channel. */
    private static Decoded halve(Decoded in) {
        int w = Math.max(1, in.width / 2);
        int h = Math.max(1, in.height / 2);
        ByteBuffer out = MemoryUtil.memAlloc(w * h);
        ByteBuffer src = in.pixels;
        for (int y = 0; y < h; y++) {
            int row0 = (2 * y) * in.width;
            int row1 = Math.min(2 * y + 1, in.height - 1) * in.width;
            for (int x = 0; x < w; x++) {
                int a = row0 + 2 * x;
                int b = Math.min(a + 1, row0 + in.width - 1);
                int c = row1 + 2 * x;
                int d = Math.min(c + 1, row1 + in.width - 1);
                int sum = (src.get(a) & 0xFF) + (src.get(b) & 0xFF) + (src.get(c) & 0xFF) + (src.get(d) & 0xFF);
                out.put(y * w + x, (byte) ((sum + 2) >> 2));
            }
        }
        in.free();
        return new Decoded(out, w, h, false);
    }

    private static void withTexture(int target, Runnable body) {
        int previousUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE9);
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, target);
        try {
            body.run();
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
            GL13C.glActiveTexture(previousUnit);
        }
    }

    private static void freeTexture() {
        if (texture != 0) {
            GpuResources.deleteTexture(texture);
            texture = 0;
        }
        loadedDetail = null;
    }

    /** Frees the map (resource reload, shutdown); the next frame loads it again. */
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
        failed = false;
    }

    /** Reloads the map with the resource packs (a pack may replace it). */
    public static final class Loader implements ResourceManagerReloadListener {
        @Override
        public void onResourceManagerReload(ResourceManager manager) {
            Minecraft.getInstance().execute(MoonMap::release);
        }
    }
}
