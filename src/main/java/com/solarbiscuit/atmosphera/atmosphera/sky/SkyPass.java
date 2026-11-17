package com.solarbiscuit.atmosphera.atmosphera.sky;

import static com.solarbiscuit.atmosphera.lib.util.AtmosMath.smoothstep;

import com.mojang.blaze3d.systems.RenderSystem;
import com.solarbiscuit.atmosphera.lib.client.AtmosClient;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.gpu.GlStateSnapshot;
import com.solarbiscuit.atmosphera.lib.gpu.GlslPrograms;
import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.lib.gpu.GpuTimers;
import com.solarbiscuit.atmosphera.lib.gpu.PostPasses;
import com.solarbiscuit.atmosphera.lib.gpu.RenderTargets;
import com.solarbiscuit.atmosphera.lib.world.SkySource;
import com.solarbiscuit.atmosphera.lib.world.SunSource;
import com.solarbiscuit.atmosphera.atmosphera.Atmosphera;
import com.solarbiscuit.atmosphera.atmosphera.AtmospheraConfig;
import com.solarbiscuit.atmosphera.atmosphera.cloud.CloudWeather;
import com.solarbiscuit.atmosphera.atmosphera.cloud.CloudNoise;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

/**
 * Atmosphera's sky (0.3.0 "Firmament"): one full-screen draw at the lib's {@code SKY} stage (Forge's AFTER_SKY, before
 * terrain), over vanilla's sky. A physically based atmosphere from three look-up tables (see {@link SkyGlsl}), a
 * procedural sun, moon and stars (or the resource pack's sun and moon), and a horizon that fades into the game's fog
 * colour, so terrain fog (and Atmospherics' biome fog) meets the sky without a seam.
 * <p>
 * The drawn sky's colour above the camera goes to the lib's {@link SkySource} from {@link SkyModel} (a Java twin of the
 * model, no GPU readback), so Torrente's reflections, Lumis and Atmosphera's own clouds follow it.
 */
public final class SkyPass {
    /**
     * Linear light to display: the exposure at 100% sky brightness. 0.12.1: 14 (was 32, calibrated against vanilla's
     * noon sky, which read as a washed-out white third of the sky around the sun); old settings are rescaled once by
     * 32 / 14 so a saved look is kept.
     */
    static final float EXPOSURE = 14f;
    /** The exposure before 0.12.1 (the one-time conversion's ratio and the clouds' light gain refer to it). */
    public static final float OLD_EXPOSURE = 32f;
    private static final int TRANS_W = 256, TRANS_H = 64, MS_SIZE = 32, VIEW_W = 192, VIEW_H = 108;
    private static final ResourceLocation PACK_SUN = new ResourceLocation("textures/environment/sun.png");
    private static final ResourceLocation PACK_MOON = new ResourceLocation("textures/environment/moon_phases.png");

    private static AtmosFeature feature;
    private static int transmittanceProgram;
    private static int multiScatterProgram;
    private static int skyViewProgram;
    private static int skyProgram;
    private static int[] viewU;
    private static int[] skyU;
    private static boolean compileFailed;
    private static int transmittance;
    private static int multiScatter;
    private static int skyView;
    private static int fbo;
    private static boolean staticLuts;
    private static float lutSun = Float.NaN;
    private static float lutRadius = Float.NaN;

    // 0.6.0: what the haze pass needs to read the sky-view table as the sky draws it.
    private static float lastExposure = EXPOSURE;
    private static float lastSunAzimuth;
    private static float lastHorizon;

    /** The sky-view table while the sky was drawn lately, else 0 (the haze then uses the fog colour). */
    public static int horizonLut() {
        return drawn() ? skyView : 0;
    }

    public static float horizonExposure() {
        return lastExposure;
    }

    public static float sunAzimuth() {
        return lastSunAzimuth;
    }

    public static float horizonAngle() {
        return lastHorizon;
    }

    // Per-frame values shared with the SkySource provider (render thread).
    private static float rain;
    private static float thunder;
    private static float moonBrightness = 1f;
    private static long drawnFrame = -1;
    /** 0.4.0 "Mirror": the lib's SkyView map (128x64 RGBA16F, display values), 8 rows a frame. */
    private static int skyMap;
    private static int skyMapFbo;
    private static int skyMapRow;
    private static long skyMapFrame = -1;
    private static final float[] TMP = new float[3];
    /** 0.15.1: the night sky's base colour (was an array made on every call). */
    private static final float[] NIGHT_SKY = {0.010f, 0.014f, 0.030f};
    private static boolean eventsUploaded;

    private SkyPass() {
    }

    public static void register() {
        Thread bake = new Thread(SkyModel::build, "Atmosphera sky model");
        bake.setDaemon(true);
        bake.start();
        feature = AtmosFeature.builder(Atmosphera.MOD_ID, "sky")
                .provides(Capability.SKY)
                .uses(AtmosFeature.Uses.WORLD_PASS)
                .switchedBy(AtmospheraConfig.sky)
                .register();
        PostPasses.register(feature, PostPasses.Stage.SKY, 0, "atmosphera_sky", SkyPass::render);
        // 0.7.0: rainbows are drawn over the clouds, in a pass of their own at the cloud stage.
        BowPass.register(feature);
        SkySource.register(feature, new SkySource.Provider() {
            @Override
            public boolean skyColor(float[] rgb) {
                return SkyPass.skyColor(rgb);
            }

            @Override
            public boolean moonTint(float[] rgb) {
                // 0.9.0: a lunar eclipse dims and reddens the moon's light.
                return SkyEvents.moonTint(rgb);
            }

            @Override
            public boolean nightLight(float[] rgb) {
                // 0.9.0: a nebula's light on the night (Lumis tints and lifts the sky light by it).
                return SkyPass.nebulaLight(rgb);
            }

            @Override
            public boolean sunColor(float altitude, float[] rgb) {
                // 0.12.0: the sunlight colour at a height (blocks, mapped like the sky's camera height), for the clouds
                // and Lumis: one sunset colour for the sky, the clouds and the light on the ground.
                double km = SkyModel.TABLE_HEIGHT + Math.max(0.0, altitude - 63.0) / 1000.0;
                return SkyModel.sunlight(km, SunSource.dirY(), rgb);
            }

            @Override
            public boolean dayLight(float[] rgb) {
                // 0.13.0 (lib 0.10.2): the daytime ice-crystal sights brighten the daylight a little (Lumis adds up to
                // 15%): a bright halo and sun dogs warm-white, a circumzenithal arc bluish, light pillars the sun's colour.
                if (SunSource.dirY() <= 0f) {
                    return false;
                }
                float halo = SkyEvents.strength(SkyEvents.Effect.HALO);
                float dogs = SkyEvents.strength(SkyEvents.Effect.SUN_DOGS);
                float arc = SkyEvents.strength(SkyEvents.Effect.CIRCUMZENITHAL_ARC);
                float pillars = SkyEvents.strength(SkyEvents.Effect.LIGHT_PILLARS);
                float total = 0.5f * halo + 0.6f * dogs + 0.3f * arc + 0.4f * pillars;
                if (total < 0.01f) {
                    return false;
                }
                rgb[0] = 1.0f * (0.5f * halo + 0.6f * dogs + 0.4f * pillars) + 0.85f * 0.3f * arc;
                rgb[1] = 0.96f * (0.5f * halo + 0.6f * dogs) + 0.9f * 0.4f * pillars + 0.95f * 0.3f * arc;
                rgb[2] = 0.9f * (0.5f * halo + 0.6f * dogs) + 0.75f * 0.4f * pillars + 1.1f * 0.3f * arc;
                return true;
            }
        });
        com.solarbiscuit.atmosphera.lib.world.SkyView.register(feature,
                () -> skyMap != 0 && AtmosClient.frame() - skyMapFrame <= 2 ? skyMap : 0);
    }

    private static final float[] NEB_MEAN = new float[3];
    /** 0.14.0: the night sky photo's uniforms (u_PhotoV[4]). */
    private static final float[] PHOTO_U = new float[16];
    /** 0.10.0: the nebulae's uniforms (u_Neb[12]) and each one's visibility now (its height, faded in above 25 deg). */
    private static final float[] NEB_U = new float[48];
    private static final float[] NEB_VIS = new float[3];
    private static final int[] NEB_WANTED = {-1, -1, -1};

    /**
     * The nebulae's light (0.9.0; 0.10.0: all of them): each one's mean colour in linear values scaled to luminance 1,
     * times how strongly it shows (its brightness option, how high it stands, the night, the cloud, and its size:
     * about 1 for a nebula of 17 degrees, up to 1.6 for the largest), summed. Lumis weighs it against the moon.
     */
    static boolean nebulaLight(float[] rgb) {
        if (!drawn() || SkyEvents.nebulaCount == 0) {
            return false;
        }
        float night = 1f - smoothstep(-0.18f, 0.02f, SunSource.dirY());
        rgb[0] = rgb[1] = rgb[2] = 0f;
        boolean any = false;
        for (int k = 0; k < SkyEvents.nebulaCount; k++) {
            if (!NebulaImages.meanColour(k, NEB_MEAN)) {
                continue;
            }
            float size = Math.max(0.5f, Math.min(1.6f, SkyEvents.NEB[8 * k + 7] / 0.3f));
            float w = SkyEvents.NEB[8 * k + 6] * NebulaImages.fade(k) * night * NEB_VIS[k] * (1f - 0.85f * rain) * size;
            if (w <= 0.001f) {
                continue;
            }
            float r = (float) Math.pow(NEB_MEAN[0], 2.2);
            float g = (float) Math.pow(NEB_MEAN[1], 2.2);
            float b = (float) Math.pow(NEB_MEAN[2], 2.2);
            float lum = Math.max(1e-4f, 0.2126f * r + 0.7152f * g + 0.0722f * b);
            rgb[0] += r / lum * w;
            rgb[1] += g / lum * w;
            rgb[2] += b / lum * w;
            any = true;
        }
        return any;
    }

    /** 0.10.0: the resource pack moon's disc radius (moon radii), measured once per resource reload; NaN = unknown. */
    private static float packDisc = Float.NaN;

    /** Resource reload: the pack moon is measured again. */
    static void packMoonChanged() {
        packDisc = Float.NaN;
    }

    /**
     * The full-moon cell of the pack's moon_phases.png: the farthest texel from the cell's centre that is at least half
     * as bright as the brightest, in moon radii (the shader's quad spans +-2 moon radii over the cell). 1 when the
     * texture cannot be read (vanilla's moon fills half the cell).
     */
    private static float packDisc() {
        if (Float.isNaN(packDisc)) {
            packDisc = 1f;
            try (java.io.InputStream in = Minecraft.getInstance().getResourceManager().getResource(PACK_MOON).orElseThrow().open();
                 com.mojang.blaze3d.platform.NativeImage img = com.mojang.blaze3d.platform.NativeImage.read(in)) {
                int cw = img.getWidth() / 4;
                int ch = img.getHeight() / 2;
                float peak = 0f;
                for (int y = 0; y < ch; y++) {
                    for (int x = 0; x < cw; x++) {
                        peak = Math.max(peak, texel(img.getPixelRGBA(x, y)));
                    }
                }
                double cx = (cw - 1) / 2.0;
                double cy = (ch - 1) / 2.0;
                double far = 0.0;
                for (int y = 0; y < ch; y++) {
                    for (int x = 0; x < cw; x++) {
                        if (peak > 0f && texel(img.getPixelRGBA(x, y)) >= 0.5f * peak) {
                            far = Math.max(far, Math.hypot(x - cx, y - cy) + 0.5);
                        }
                    }
                }
                if (far > 0.0) {
                    packDisc = (float) Math.min(2.0, far / (cw / 2.0) * 2.0);
                }
            } catch (Exception e) {
                Atmosphera.LOGGER.debug("[Atmosphera] Atmosphera could not measure the pack moon: {}", e.toString());
            }
        }
        return packDisc;
    }

    /** Brightness times opacity of a NativeImage texel (ABGR). */
    private static float texel(int p) {
        float r = (p & 0xFF) / 255f;
        float g = ((p >> 8) & 0xFF) / 255f;
        float b = ((p >> 16) & 0xFF) / 255f;
        float a = ((p >>> 24) & 0xFF) / 255f;
        return (0.3f * r + 0.6f * g + 0.1f * b) * a;
    }

    /** True while the sky was drawn this frame or the one before. */
    public static boolean drawn() {
        return feature != null && feature.isActive() && AtmosClient.frame() - drawnFrame <= 1;
    }

    /**
     * The drawn sky's colour high above the camera, in the game's display values (like {@code getSkyColor}); mirrors
     * the sky program's main for the zenith.
     */
    private static boolean skyColor(float[] rgb) {
        if (!drawn() || !SkyModel.ready()) {
            return false;
        }
        float sunY = SunSource.dirY();
        SkyModel.zenith(sunY, TMP);
        float exposure = exposure(sunY);
        float dim = 1f - 0.35f * rain - 0.2f * thunder;
        float grey = 0.2126f * TMP[0] + 0.7152f * TMP[1] + 0.0722f * TMP[2];
        float wet = 1f - 0.6f * rain;
        float night = 1f - smoothstep(-0.18f, 0.02f, sunY);
        float moon = 0.35f + 0.65f * moonBrightness;
        for (int c = 0; c < 3; c++) {
            float lum = (grey + (TMP[c] - grey) * wet) * dim;
            rgb[c] = (float) Math.pow(1.0 - Math.exp(-lum * exposure), 1.0 / 2.2) + NIGHT_SKY[c] * moon * 0.6f * night;
        }
        float sat = AtmospheraConfig.skySaturation.get().floatValue();
        float l = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2];
        for (int c = 0; c < 3; c++) {
            rgb[c] = Math.max(0f, Math.min(1f, l + (rgb[c] - l) * sat));
        }
        return true;
    }

    /**
     * The exposure for this sun height: {@link #EXPOSURE} by day, more as the sun sinks below the horizon (eyes adapt
     * to twilight; without it the sky went black within 3 degrees and the Belt of Venus never showed). 0.12.1: the
     * adaptation starts once the sun has set (it began 1 degree above the horizon, so the glow round a setting sun was
     * raised up to 1.5x into a white mass); still up to 8x in deep twilight.
     */
    static float exposure(float sunY) {
        float adapt = 1f + 7f * smoothstep(-0.01f, -0.12f, sunY);
        return EXPOSURE * adapt * AtmospheraConfig.skyBrightness.get().floatValue();
    }

    /**
     * 0.12.1: the daytime exposure against the old fixed one (sky brightness included), so the clouds' sunlight follows
     * the sky's brightness (lowering only the sky left the sunlit clouds as bright as before, whiter against it).
     */
    public static float exposureRatio() {
        return EXPOSURE * AtmospheraConfig.skyBrightness.get().floatValue() / OLD_EXPOSURE;
    }

    /** Whether the sky is drawn here (the overworld's kind of sky, not hidden by thick fog or blindness). */
    private static boolean wanted(Minecraft mc, ClientLevel level) {
        if (level.effects().skyType() != DimensionSpecialEffects.SkyType.NORMAL) {
            return false;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        FogType fog = camera.getFluidInCamera();
        if (fog == FogType.LAVA || fog == FogType.POWDER_SNOW) {
            return false;
        }
        return !(camera.getEntity() instanceof LivingEntity living)
                || !(living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS));
    }

    private static void render(PostPasses.Context ctx) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || compileFailed || !CloudNoise.ready() || !wanted(mc, level)) {
            return;
        }
        RenderTargets.Scene scene = RenderTargets.scene();
        if (!scene.usable()) {
            return;
        }
        if (skyProgram == 0 && !compile()) {
            return;
        }
        float partial = ctx.partialTick();
        thunder = level.getThunderLevel(partial);
        // 0.6.4: the weather's weight, not the raw rain level (steady rain no longer dims the sky like a downpour).
        rain = CloudWeather.wetness(level.getRainLevel(partial), thunder);
        // 0.7.0: the sky's occasional sights. During a lunar eclipse the moon is drawn full and its light sinks.
        SkyEvents.ensure(level, partial);
        boolean eclipse = SkyEvents.eclipseNow();
        moonBrightness = (eclipse ? 1f : level.getMoonBrightness()) * SkyEvents.moonLightScale();
        long frame = AtmosClient.frame();
        FrameUniforms.update(frame);
        GlStateSnapshot saved = GlStateSnapshot.save();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthMask(false);
            if (!staticLuts && !buildStaticLuts()) {
                return;
            }
            float sunY = SunSource.dirY();
            float radius = (float) (SkyModel.RG + SkyModel.TABLE_HEIGHT + Math.max(0.0, ctx.camera().y - 63.0) / 1000.0);
            if (Float.isNaN(lutSun) || Math.abs(sunY - lutSun) > 0.001f || Math.abs(radius - lutRadius) > 0.02f) {
                if (!drawLut(skyView, VIEW_W, VIEW_H)) {
                    return;
                }
                GpuTimers.begin("atmosphera_sky_lut");
                bind(13, transmittance);
                bind(14, multiScatter);
                GL20C.glUseProgram(skyViewProgram);
                GL20C.glUniform1i(viewU[0], 13);
                GL20C.glUniform1i(viewU[1], 14);
                GL20C.glUniform4f(viewU[2], radius, sunY, 0f, 0f);
                GlslPrograms.drawFullscreen();
                GpuTimers.end("atmosphera_sky_lut");
                lutSun = sunY;
                lutRadius = radius;
            }

            if (!RenderTargets.bindColorOnly(scene)) {
                return;
            }
            boolean pack = AtmospheraConfig.packSunMoon.get();
            bind(13, transmittance);
            bind(14, skyView);
            bind(15, CloudNoise.layers());
            bind(11, pack ? mc.getTextureManager().getTexture(PACK_SUN).getId() : 0);
            bind(10, pack ? mc.getTextureManager().getTexture(PACK_MOON).getId() : 0);
            // 0.9.0: the night's nebula images (decoded and uploaded over a few frames; 0 until ready); 0.10.0: up to
            // three, on units 6-8.
            for (int k = 0; k < NebulaImages.SLOTS; k++) {
                NEB_WANTED[k] = k < SkyEvents.nebulaCount ? SkyEvents.NEB_IMAGE[k] : -1;
            }
            NebulaImages.update(NEB_WANTED);
            // 0.12.0: the moon's surface map (unit 9, after the loaders that use 9 as scratch).
            MoonMap.update();
            bind(9, pack ? 0 : MoonMap.texture());
            // 0.14.0: the night sky's photo on unit 5.
            NightSky.update(level);
            bind(5, NightSky.texture());
            NightSky.uniforms(PHOTO_U);
            int shown = 0;
            java.util.Arrays.fill(NEB_U, 0f);
            float turnCos = (float) Math.cos(SkyEvents.nebulaTurn);
            float turnSin = (float) Math.sin(SkyEvents.nebulaTurn);
            for (int k = 0; k < NebulaImages.SLOTS; k++) {
                int tex = k < SkyEvents.nebulaCount ? NebulaImages.texture(k) : 0;
                bind(6 + k, tex);
                NEB_VIS[k] = 0f;
                if (tex == 0) {
                    continue;
                }
                float[] n = SkyEvents.NEB;
                int o = 8 * k;
                int u0 = 16 * k;
                // Its height now (nebula frame to world), faded in from 25 to 35 degrees up.
                float y = n[o] * turnSin + n[o + 1] * turnCos;
                NEB_VIS[k] = smoothstep(0.42f, 0.57f, y);
                NEB_U[u0] = n[o];
                NEB_U[u0 + 1] = n[o + 1];
                NEB_U[u0 + 2] = n[o + 2];
                NEB_U[u0 + 3] = n[o + 3];
                NEB_U[u0 + 4] = n[o + 4];
                NEB_U[u0 + 5] = n[o + 5];
                NEB_U[u0 + 6] = n[o + 6] * NEB_VIS[k];
                NEB_U[u0 + 7] = n[o + 7];
                // uv = 0.5 + q * scale: the long side spans the nebula's radius (q = +-1); v runs down the image.
                float w = NebulaImages.width(k);
                float h = NebulaImages.height(k);
                NEB_U[u0 + 8] = w >= h ? 0.5f : 0.5f * h / w;
                NEB_U[u0 + 9] = -(w >= h ? 0.5f * w / h : 0.5f);
                NEB_U[u0 + 10] = NebulaImages.fade(k);
                NEB_U[u0 + 11] = Math.max(w, h);
                // The glow round it: the image's mean colour, dimmer under a bright moon (it outshines the glow).
                NebulaImages.meanColour(k, NEB_MEAN);
                float glow = NebulaImages.fade(k) * (1f - 0.5f * moonBrightness);
                NEB_U[u0 + 12] = NEB_MEAN[0] * glow;
                NEB_U[u0 + 13] = NEB_MEAN[1] * glow;
                NEB_U[u0 + 14] = NEB_MEAN[2] * glow;
                shown = k + 1;
            }
            GL20C.glUseProgram(skyProgram);
            int[] u = skyU;
            GL20C.glUniform1i(u[0], 13);
            GL20C.glUniform1i(u[1], 14);
            GL20C.glUniform1i(u[2], 15);
            GL20C.glUniform1i(u[3], 11);
            GL20C.glUniform1i(u[4], 10);
            GL20C.glUniform1i(u[22], 6);
            GL20C.glUniform1i(u[23], 7);
            GL20C.glUniform1i(u[24], 8);
            GL20C.glUniform1i(u[29], 9);
            GL20C.glUniform4f(u[30], pack ? 0f : MoonMap.width(), MoonMap.fade(), 0f, 0f);
            GL20C.glUniform1i(u[31], 5);
            GL20C.glUniform4fv(u[32], stack.floats(PHOTO_U));
            GL20C.glUniform4fv(u[25], stack.floats(NEB_U));
            GL20C.glUniform4f(u[26], turnCos, turnSin, shown, 0f);
            GL20C.glUniform4f(u[27], pack ? packDisc() : 1f, 0f, 0f, 0f);
            // 0.10.0: the stars' twinkle on the smoothed world clock (wrapped every 6 hours).
            GL20C.glUniform4f(u[28], (float) (com.solarbiscuit.atmosphera.lib.wind.AtmosClock.worldSeconds(level, partial) % 21600.0),
                    AtmospheraConfig.twinkle.get().floatValue(), AtmospheraConfig.twinkleSpeed.get().floatValue(), 0f);
            GL20C.glUniformMatrix4fv(u[5], false, ctx.invViewProjection().get(stack.mallocFloat(16)));
            float sx = SunSource.dirX();
            float sz = SunSource.dirZ();
            lastExposure = exposure(sunY);
            lastSunAzimuth = (float) Math.atan2(sz, sx);
            lastHorizon = (float) -Math.asin(Math.min(1.0, Math.sqrt(Math.max(radius * (double) radius
                    - SkyModel.RG * SkyModel.RG, 0.0)) / radius));
            GL20C.glUniform4f(u[6], radius, sunY, lastSunAzimuth, lastExposure);
            float sunRadius = (float) Math.toRadians(0.75 * AtmospheraConfig.sunSize.get());
            float moonRadius = (float) Math.toRadians(1.1 * AtmospheraConfig.moonSize.get());
            GL20C.glUniform4f(u[7], sx, sunY, sz, sunRadius);
            GL20C.glUniform4f(u[8], -sx, -sunY, -sz, moonRadius);
            int phase = eclipse ? 0 : level.getMoonPhase();
            GL20C.glUniform4f(u[9], (float) (phase * Math.PI / 4.0), moonBrightness, pack ? 1f : 0f, phase);
            float rotation = level.getSunAngle(partial);
            GL20C.glUniform4f(u[10], AtmospheraConfig.stars.get().floatValue(), AtmospheraConfig.milkyWay.get().floatValue(),
                    (float) Math.cos(rotation), (float) Math.sin(rotation));
            float deckTop = com.solarbiscuit.atmosphera.atmosphera.cloud.CloudPass.deckTop();
            float above = Float.isNaN(deckTop) ? 0f : smoothstep(deckTop, deckTop + 150f, (float) ctx.camera().y);
            GL20C.glUniform4f(u[14], AtmospheraConfig.airglow.get().floatValue(), AtmospheraConfig.starAmount.get().floatValue(),
                    above, 0f);
            GL20C.glUniform4f(u[11], AtmospheraConfig.skySaturation.get().floatValue(),
                    (float) Math.toRadians(AtmospheraConfig.horizonBlend.get()), rain, thunder);
            float[] fog = RenderSystem.getShaderFogColor();
            float pixel = 2f / (Math.max(ctx.projection().m11(), 1e-3f) * Math.max(scene.height(), 1));
            GL20C.glUniform4f(u[12], fog[0], fog[1], fog[2], pixel);
            // y, z: cosines of the sun's and moon's early-out angles (8 and 3 radii).
            GL20C.glUniform4f(u[13], ((level.getGameTime() % 24000L) + partial) / 20f,
                    (float) Math.cos(Math.min(sunRadius * 8f, Math.PI)), (float) Math.cos(Math.min(moonRadius * 3f, Math.PI)),
                    (float) -Math.asin(Math.min(1.0, Math.sqrt(Math.max(radius * (double) radius - SkyModel.RG * SkyModel.RG, 0.0)) / radius)));
            // 0.3.2: the draw alone, apart from the pass's outer timer (which also catches work queued before it).
            boolean events = SkyEvents.any();
            GL20C.glUniform4f(u[15], 0f, 0f, events ? 1f : 0f, 0f);
            // The eclipse is part of the moon (u_Fx[4]), so the block goes up whenever anything in it is set.
            if (events || eclipse) {
                GL20C.glUniform4fv(u[17], stack.floats(SkyEvents.FX));
                GL20C.glUniform4fv(u[18], stack.floats(SkyEvents.DOGS));
                GL20C.glUniform4fv(u[19], stack.floats(SkyEvents.PILLAR));
                GL20C.glUniform4fv(u[20], stack.floats(SkyEvents.PILLAR_COL));
                GL20C.glUniform4fv(u[21], stack.floats(SkyEvents.METEOR));
                eventsUploaded = true;
            } else if (eventsUploaded) {
                // Once, when the last one has faded: clear the block (the moon reads the eclipse row every frame).
                GL20C.glUniform4fv(u[17], stack.callocFloat(32));
                eventsUploaded = false;
            }
            GpuTimers.begin("atmosphera_sky_draw");
            GlslPrograms.drawFullscreen();
            GpuTimers.end("atmosphera_sky_draw");

            // 0.4.0 "Mirror": the sky-view map (sky and clouds) for water reflections, 8 of 64 rows a frame.
            boolean fresh = skyMap == 0;
            if (com.solarbiscuit.atmosphera.lib.world.SkyView.wanted() && (!fresh || createSkyMap())) {
                GpuTimers.begin("atmosphera_skyview");
                int clouds = com.solarbiscuit.atmosphera.atmosphera.cloud.CloudPass.cloudMap();
                bind(12, clouds);
                GL20C.glUniform1i(u[16], 12);
                GL20C.glUniform4f(u[15], 1f, clouds != 0 ? 1f : 0f, 0f, 0f);
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, skyMapFbo);
                GL11C.glViewport(0, 0, MAP_W, MAP_H);
                if (!fresh) {
                    GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
                    GL11C.glScissor(0, skyMapRow, MAP_W, MAP_ROWS);
                }
                GlslPrograms.drawFullscreen();
                GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
                skyMapRow = (skyMapRow + MAP_ROWS) % MAP_H;
                skyMapFrame = frame;
                GpuTimers.end("atmosphera_skyview");
            }
            drawnFrame = frame;
        } finally {
            for (int unit = 6; unit <= 15; unit++) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            }
            saved.restore();
        }
    }

    private static final int MAP_W = 128;
    private static final int MAP_H = 64;
    private static final int MAP_ROWS = 8;

    private static boolean createSkyMap() {
        skyMap = lut("Atmosphera sky view map", MAP_W, MAP_H, true);
        skyMapFbo = GpuResources.framebuffer("Atmosphera sky view map");
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, skyMapFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, skyMap, 0);
        GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
        if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            Atmosphera.LOGGER.error("Atmosphera sky view framebuffer incomplete; water reflections keep the plain sky colour");
            releaseSkyMap();
            return false;
        }
        return true;
    }

    private static void releaseSkyMap() {
        if (skyMapFbo != 0) {
            GpuResources.deleteFramebuffer(skyMapFbo);
            skyMapFbo = 0;
        }
        if (skyMap != 0) {
            GpuResources.deleteTexture(skyMap);
            skyMap = 0;
        }
        skyMapFrame = -1;
    }

    private static void bind(int unit, int texture) {
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
    }

    /** Transmittance, then multiple scattering: once (the atmosphere does not change). */
    private static boolean buildStaticLuts() {
        if (!drawLut(transmittance, TRANS_W, TRANS_H)) {
            return false;
        }
        GL20C.glUseProgram(transmittanceProgram);
        GlslPrograms.drawFullscreen();
        if (!drawLut(multiScatter, MS_SIZE, MS_SIZE)) {
            return false;
        }
        bind(13, transmittance);
        GL20C.glUseProgram(multiScatterProgram);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(multiScatterProgram, "u_Transmittance"), 13);
        GlslPrograms.drawFullscreen();
        staticLuts = true;
        return true;
    }

    private static boolean drawLut(int texture, int w, int h) {
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, texture, 0);
        GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
        if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            Atmosphera.LOGGER.error("[Atmosphera] Atmosphera sky look-up framebuffer incomplete; the sky is off");
            compileFailed = true;
            return false;
        }
        GL11C.glViewport(0, 0, w, h);
        return true;
    }

    private static int lut(String name, int w, int h, boolean wrapS) {
        int id = GpuResources.texture(name);
        int previousUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, wrapS ? GL11C.GL_REPEAT : GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA16F, w, h, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, (ByteBuffer) null);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        GL13C.glActiveTexture(previousUnit);
        GpuResources.textureBytes(id, (long) w * h * 8);
        return id;
    }

    private static boolean compile() {
        String header = GlslPrograms.header();
        transmittanceProgram = GlslPrograms.compile("Atmosphera sky transmittance", GlslPrograms.FULLSCREEN_VERTEX,
                header + SkyGlsl.TRANSMITTANCE);
        multiScatterProgram = GlslPrograms.compile("Atmosphera sky multiple scattering", GlslPrograms.FULLSCREEN_VERTEX,
                header + SkyGlsl.MULTISCATTER);
        skyViewProgram = GlslPrograms.compile("Atmosphera sky view", GlslPrograms.FULLSCREEN_VERTEX, header + SkyGlsl.SKYVIEW);
        skyProgram = GlslPrograms.compile("Atmosphera sky", GlslPrograms.FULLSCREEN_VERTEX, header + SkyGlsl.SKY);
        if (transmittanceProgram == 0 || multiScatterProgram == 0 || skyViewProgram == 0 || skyProgram == 0) {
            Atmosphera.LOGGER.error("[Atmosphera] Atmosphera sky is off: a sky program did not compile (see above)");
            release();
            compileFailed = true;
            return false;
        }
        viewU = com.solarbiscuit.atmosphera.lib.gpu.PostProgram.locate(skyViewProgram, "u_Transmittance", "u_MultiScatter", "u_SkyCam");
        skyU = com.solarbiscuit.atmosphera.lib.gpu.PostProgram.locate(skyProgram, "u_Transmittance", "u_SkyView", "u_Noise", "u_PackSun", "u_PackMoon", "u_InvViewProj",
                "u_SkyCam", "u_Sun", "u_Moon", "u_MoonPhase", "u_Stars", "u_Look", "u_Fog", "u_Time", "u_Look2",
                "u_Map", "u_CloudMap", "u_Fx", "u_Dog", "u_Pillar", "u_PillarCol", "u_Meteor", "u_Nebula0", "u_Nebula1", "u_Nebula2", "u_Neb", "u_NebFrame", "u_PackDisc", "u_Twinkle",
                "u_MoonMap", "u_MoonInfo", "u_Photo", "u_PhotoV");
        eventsUploaded = false;
        transmittance = lut("Atmosphera sky transmittance", TRANS_W, TRANS_H, false);
        multiScatter = lut("Atmosphera sky multiple scattering", MS_SIZE, MS_SIZE, false);
        skyView = lut("Atmosphera sky view", VIEW_W, VIEW_H, true);
        fbo = GpuResources.framebuffer("Atmosphera sky look-up tables");
        staticLuts = false;
        lutSun = Float.NaN;
        return true;
    }

    /** Frees programs, tables and the framebuffer; the next frame rebuilds them. */
    public static void release() {
        for (int p : new int[] {transmittanceProgram, multiScatterProgram, skyViewProgram, skyProgram}) {
            if (p != 0) {
                GpuResources.deleteProgram(p);
            }
        }
        transmittanceProgram = multiScatterProgram = skyViewProgram = skyProgram = 0;
        for (int t : new int[] {transmittance, multiScatter, skyView}) {
            if (t != 0) {
                GpuResources.deleteTexture(t);
            }
        }
        transmittance = multiScatter = skyView = 0;
        releaseSkyMap();
        NebulaImages.release();
        MoonMap.release();
        NightSky.release();
        if (fbo != 0) {
            GpuResources.deleteFramebuffer(fbo);
            fbo = 0;
        }
        staticLuts = false;
        lutSun = Float.NaN;
        // 0.15.1: the shader reload tries again (compile() sets the flag after its own release).
        compileFailed = false;
    }
}
