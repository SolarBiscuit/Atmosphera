package com.solarbiscuit.atmosphera.atmosphera.cloud;

import static com.solarbiscuit.atmosphera.lib.util.AtmosMath.clamp01;

import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.lib.wind.AtmosClock;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * How much of the sun and the moon the clouds hide, measured on the GPU: the probe program writes the transmittance
 * towards each into a 2x1 target, which is copied into one of three pixel-pack buffers and read back once its fence
 * has passed (a few frames later; never waits). The values are smoothed over about 0.3 s, as Lumis does with Nuit's
 * skybox cover. Render thread only; no allocation per frame.
 */
final class SunProbe {
    private static final int RING = 3;
    private static int texture;
    private static int framebuffer;
    private static final int[] PBOS = new int[RING];
    private static final long[] FENCES = new long[RING];
    private static int next;
    private static float sinceProbe = 1f;
    private static float sinceSample;
    private static float sunCover;
    private static float moonCover;

    private SunProbe() {
    }

    static float sunCover() {
        return sunCover;
    }

    static float moonCover() {
        return moonCover;
    }

    /**
     * Binds the 2x1 target for the probe draw; false when it could not be made, or in the frames between probes
     * (0.2.3: ten probes a second whatever the frame rate, four on LOW since 0.2.4; the cover is smoothed over 0.3 s
     * anyway). No GL state query: the
     * caller's state snapshot puts the framebuffer back (0.2.2; the per-frame query and readback showed as 0.15-0.23 ms
     * in the pass at 1440p).
     */
    static boolean begin(float interval) {
        collect();
        if (sinceProbe < interval) {
            return false;
        }
        sinceProbe = 0f;
        if (texture == 0 && !create()) {
            return false;
        }
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        GL11C.glViewport(0, 0, 2, 1);
        return true;
    }

    /** After the probe draw: copies the two pixels into the next pixel-pack buffer, fenced. */
    static void end() {
        int slot = next;
        if (FENCES[slot] != 0) {
            // Not collected yet (very slow GPU): reuse the slot, dropping that reading.
            GL32C.glDeleteSync(FENCES[slot]);
            FENCES[slot] = 0;
        }
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, PBOS[slot]);
        GL11C.glReadPixels(0, 0, 2, 1, GL11C.GL_RED, GL11C.GL_FLOAT, 0L);
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
        FENCES[slot] = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        next = (slot + 1) % RING;
    }

    /** Reads every slot whose fence has passed (oldest first); never blocks. */
    private static void collect() {
        float dt = (float) Math.max(0.0, AtmosClock.deltaSeconds());
        sinceSample += dt;
        sinceProbe += dt;
        for (int i = 0; i < RING; i++) {
            int slot = (next + i) % RING;
            long fence = FENCES[slot];
            if (fence == 0) {
                continue;
            }
            int status = GL32C.glClientWaitSync(fence, 0, 0L);
            if (status != GL32C.GL_ALREADY_SIGNALED && status != GL32C.GL_CONDITION_SATISFIED) {
                continue;
            }
            GL32C.glDeleteSync(fence);
            FENCES[slot] = 0;
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, PBOS[slot]);
            ByteBuffer data = GL15C.glMapBuffer(GL21C.GL_PIXEL_PACK_BUFFER, GL15C.GL_READ_ONLY);
            if (data != null) {
                data.order(ByteOrder.nativeOrder());
                float sunT = clamp01(data.getFloat(0));
                float moonT = clamp01(data.getFloat(4));
                GL15C.glUnmapBuffer(GL21C.GL_PIXEL_PACK_BUFFER);
                float k = 1f - (float) Math.exp(-Math.min(sinceSample, 0.5f) / 0.3f);
                sinceSample = 0f;
                sunCover += (1f - sunT - sunCover) * k;
                moonCover += (1f - moonT - moonCover) * k;
            }
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
        }
    }

    private static boolean create() {
        texture = com.solarbiscuit.atmosphera.lib.gpu.Textures.create2D("Atmosphera sun cover probe", 2, 1, GL30C.GL_R32F, GL11C.GL_RED, GL11C.GL_FLOAT,
                GL11C.GL_NEAREST, GL11C.GL_REPEAT);
        framebuffer = com.solarbiscuit.atmosphera.lib.gpu.Textures.framebuffer("Atmosphera sun cover probe", texture);
        boolean ok = framebuffer != 0;
        for (int i = 0; i < RING; i++) {
            PBOS[i] = GpuResources.buffer("Atmosphera sun cover readback");
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, PBOS[i]);
            GL15C.glBufferData(GL21C.GL_PIXEL_PACK_BUFFER, 8L, GL15C.GL_STREAM_READ);
        }
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
        if (!ok) {
            release();
        }
        return ok;
    }

    static void release() {
        for (int i = 0; i < RING; i++) {
            if (FENCES[i] != 0) {
                GL32C.glDeleteSync(FENCES[i]);
                FENCES[i] = 0;
            }
            if (PBOS[i] != 0) {
                GpuResources.deleteBuffer(PBOS[i]);
                PBOS[i] = 0;
            }
        }
        if (framebuffer != 0) {
            GpuResources.deleteFramebuffer(framebuffer);
            framebuffer = 0;
        }
        if (texture != 0) {
            GpuResources.deleteTexture(texture);
            texture = 0;
        }
        sunCover = moonCover = 0f;
    }
}
