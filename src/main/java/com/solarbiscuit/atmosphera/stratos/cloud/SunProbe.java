package com.solarbiscuit.atmosphera.stratos.cloud;

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
    private static int previousFbo;
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

    /** Binds the 2x1 target for the probe draw; false when it could not be made. */
    static boolean begin() {
        collect();
        if (texture == 0 && !create()) {
            return false;
        }
        previousFbo = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
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
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, previousFbo);
    }

    /** Reads every slot whose fence has passed (oldest first); never blocks. */
    private static void collect() {
        float dt = (float) Math.min(0.25, Math.max(0.0, AtmosClock.deltaSeconds()));
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
                float k = 1f - (float) Math.exp(-dt / 0.3f);
                sunCover += (1f - sunT - sunCover) * k;
                moonCover += (1f - moonT - moonCover) * k;
            }
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
        }
    }

    private static boolean create() {
        texture = GpuResources.texture("Stratos sun cover probe");
        int previousUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_R32F, 2, 1, 0, GL11C.GL_RED, GL11C.GL_FLOAT, (ByteBuffer) null);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        GL13C.glActiveTexture(previousUnit);
        framebuffer = GpuResources.framebuffer("Stratos sun cover probe");
        int previous = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, texture, 0);
        boolean ok = GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER) == GL30C.GL_FRAMEBUFFER_COMPLETE;
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previous);
        for (int i = 0; i < RING; i++) {
            PBOS[i] = GpuResources.buffer("Stratos sun cover readback");
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

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
