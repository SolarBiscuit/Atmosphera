package com.solarbiscuit.atmosphera.stratos.cloud;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Lightning inside the clouds (0.5.0 "Storm"): flashes that light a storm cell from within. Two sources:
 * <ul>
 *   <li>in-cloud flashes on a schedule seeded by the game time, so every player on a server sees the same ones (the
 *       chance per second grows with the thunder level);</li>
 *   <li>vanilla's lightning bolts: while the level's sky flash is lit, a strong flash near the camera.</li>
 * </ul>
 * A flash has a world position inside the low deck and flickers for about 0.4 s (stroke, dim, re-stroke). The march
 * adds its light to cloud samples near it ({@code u_Flash}); the lib's cloud cover hands its strength to other mods.
 * Render thread, no allocation.
 */
final class Lightning {
    /** Flicker over 8 steps of 0.05 s: a stroke, a dip, a re-stroke, fading. */
    private static final float[] FLICKER = {1f, 0.35f, 0.9f, 0.25f, 0.6f, 0.3f, 0.15f, 0.05f};

    private static long lastSecond = Long.MIN_VALUE;
    private static double flashX;
    private static double flashY;
    private static double flashZ;
    private static double flashStart = -1.0;
    private static float flashScale;
    private static double clock;

    /** Camera-relative position (xyz) and current intensity (w) for the march. */
    static final float[] STATE = new float[4];

    private Lightning() {
    }

    static void update(ClientLevel level, float partialTick, double cameraX, double cameraY, double cameraZ,
                       float thunder, float deckBase, float deckThickness, float dt) {
        clock += dt;
        long gameTime = level.getGameTime();
        long second = gameTime / 20L;
        if (thunder > 0.2f && second != lastSecond) {
            lastSecond = second;
            long h = mix(second * 0x9E3779B97F4A7C15L);
            float roll = (h >>> 40) / (float) (1L << 24);
            if (roll < 0.22f * thunder) {
                // Somewhere in the storm, 300 to 2800 blocks away, inside the deck's lower half.
                double angle = ((h >>> 8) & 0xFFFF) / 65536.0 * Math.PI * 2.0;
                double dist = 300.0 + ((h >>> 24) & 0xFFFF) / 65536.0 * 2500.0;
                start(cameraX + Math.cos(angle) * dist, deckBase + deckThickness * 0.35, cameraZ + Math.sin(angle) * dist,
                        0.6f + 0.4f * ((h & 0xFF) / 255f));
            }
        }
        // Vanilla bolts light the sky (skyFlashTime counts down from 2 on the client).
        if (level.getSkyFlashTime() > 0 && (flashStart < 0 || clock - flashStart > 0.4)) {
            start(cameraX + 200.0, deckBase + deckThickness * 0.3, cameraZ - 150.0, 1.3f);
        }
        float intensity = 0f;
        if (flashStart >= 0) {
            double age = clock - flashStart;
            int step = (int) (age / 0.05);
            if (step < FLICKER.length) {
                intensity = FLICKER[step] * flashScale;
            } else {
                flashStart = -1;
            }
        }
        STATE[0] = (float) (flashX - cameraX);
        STATE[1] = (float) (flashY - cameraY);
        STATE[2] = (float) (flashZ - cameraZ);
        STATE[3] = intensity;
    }

    private static void start(double x, double y, double z, float scale) {
        flashX = x;
        flashY = y;
        flashZ = z;
        flashScale = scale;
        flashStart = clock;
    }

    /** Current flash strength 0..~1.3 (for the lib's cloud cover). */
    static float intensity() {
        return STATE[3];
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
