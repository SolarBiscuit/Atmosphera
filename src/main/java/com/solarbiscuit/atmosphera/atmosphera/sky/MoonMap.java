package com.solarbiscuit.atmosphera.atmosphera.sky;

import com.solarbiscuit.atmosphera.lib.gpu.StreamedImage;
import com.solarbiscuit.atmosphera.lib.gpu.StreamedImage.Decoded;
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
        // 0.14.2: AtmospheraLib's image streaming (0.11.0).
        texture = StreamedImage.allocate("Atmosphera moon map", decoded, 9, GL30C.GL_R8, GL11C.GL_RED);
        width = decoded.width;
        uploading = decoded;
        uploadedRows = 0;
        loadedDetail = detail;
    }

    private static void uploadStrip() {
        Decoded image = uploading;
        // Image rows run top (north) to bottom; the shader's v runs the same way.
        uploadedRows += StreamedImage.uploadRows(texture, image, uploadedRows, ROWS_PER_FRAME, 9, GL11C.GL_RED);
        if (uploadedRows >= image.height) {
            image.free();
            uploading = null;
            readyNanos = System.nanoTime();
            Atmosphera.LOGGER.info("[Atmosphera] Moon map ready ({}x{}, {})", image.width, image.height, loadedDetail);
        }
    }

    private static Decoded decode(int maxSide) {
        return StreamedImage.decode(Minecraft.getInstance().getResourceManager(), ID, 1, maxSide, Atmosphera.LOGGER,
                "moon map");
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
