package com.solarbiscuit.atmosphera.atmosphera.cloud;

import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.atmosphera.Atmosphera;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;

import java.nio.ByteBuffer;

/**
 * The cloud shadow map (0.2.0): an R8 texture of the clouds' transmittance along the sun on a horizontal reference
 * plane inside the low deck, {@value #SIZE_BLOCKS} blocks across around the camera. Its centre snaps to whole texels in
 * world space, so the shadows stay put on the ground while the camera moves. Drawn every frame by the shadow program
 * (a few thousand samples in all) and handed to other mods through the lib's {@code CloudCover} provider; terrain,
 * LODs and Lumis's light shafts read it with {@code atmos_cloudShadow}. Render thread only.
 */
final class CloudShadow {
    static final float SIZE_BLOCKS = 4096f;

    private static int texture;
    private static int framebuffer;
    private static int resolution;
    private static double centreX;
    private static double centreZ;
    private static float plane;
    private static long frame = -1;

    private CloudShadow() {
    }

    /** The map's texture, or 0 when it was not drawn this frame or the one before. */
    static int texture(long now) {
        return now - frame <= 1 ? texture : 0;
    }

    /** out[0..3]: centre minus camera (x, z), size in blocks, reference plane height. */
    static boolean params(float[] out, double cameraX, double cameraZ) {
        if (texture == 0) {
            return false;
        }
        out[0] = (float) (centreX - cameraX);
        out[1] = (float) (centreZ - cameraZ);
        out[2] = SIZE_BLOCKS;
        out[3] = plane;
        return true;
    }

    /**
     * Binds the map for drawing at the given resolution, with its centre snapped to the camera's texel. Returns the
     * centre minus the camera through {@code rel}; false when the target could not be made.
     */
    static boolean begin(int res, double cameraX, double cameraZ, float planeY, float[] rel) {
        if (res != resolution || texture == 0) {
            release();
            if (!create(res)) {
                return false;
            }
        }
        double texel = SIZE_BLOCKS / res;
        centreX = (Math.floor(cameraX / texel) + 0.5) * texel;
        centreZ = (Math.floor(cameraZ / texel) + 0.5) * texel;
        plane = planeY;
        rel[0] = (float) (centreX - cameraX);
        rel[1] = (float) (centreZ - cameraZ);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        GL11C.glViewport(0, 0, res, res);
        return true;
    }

    static void end(long now) {
        frame = now;
    }

    private static boolean create(int res) {
        texture = com.solarbiscuit.atmosphera.lib.gpu.Textures.create2D("Atmosphera cloud shadow map", res, res, GL30C.GL_R8, GL11C.GL_RED,
                GL11C.GL_UNSIGNED_BYTE, GL11C.GL_LINEAR, GL13C.GL_CLAMP_TO_EDGE);
        framebuffer = com.solarbiscuit.atmosphera.lib.gpu.Textures.framebuffer("Atmosphera cloud shadow map", texture);
        boolean ok = framebuffer != 0;
        if (!ok) {
            Atmosphera.LOGGER.error("Atmosphera cloud shadow map framebuffer incomplete; cloud shadows off");
            release();
            return false;
        }
        resolution = res;
        return true;
    }

    static void release() {
        if (framebuffer != 0) {
            GpuResources.deleteFramebuffer(framebuffer);
            framebuffer = 0;
        }
        if (texture != 0) {
            GpuResources.deleteTexture(texture);
            texture = 0;
        }
        resolution = 0;
        frame = -1;
    }
}
