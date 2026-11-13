package com.solarbiscuit.atmosphera.atmosphera.cloud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.solarbiscuit.atmosphera.lib.client.AtmosClient;
import com.solarbiscuit.atmosphera.lib.compat.dh.DhBridge;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.gpu.GlStateSnapshot;
import com.solarbiscuit.atmosphera.lib.gpu.GlslPrograms;
import com.solarbiscuit.atmosphera.lib.gpu.GpuTimers;
import com.solarbiscuit.atmosphera.lib.gpu.PostPasses;
import com.solarbiscuit.atmosphera.lib.gpu.RenderTargets;
import com.solarbiscuit.atmosphera.lib.world.CloudCover;
import com.solarbiscuit.atmosphera.lib.world.SkySource;
import com.solarbiscuit.atmosphera.lib.world.SunSource;
import com.solarbiscuit.atmosphera.atmosphera.Atmosphera;
import com.solarbiscuit.atmosphera.atmosphera.AtmospheraConfig;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

/**
 * Atmosphera's clouds: one post pass at the lib's {@code CLOUDS} stage (vanilla's cloud slot, after particles, before
 * weather), in four steps:
 * <ol>
 *   <li>march at 1/divisor resolution into two targets: in-scattered light plus transmittance, and the cloud distance
 *       plus the march limit (scene depth, which holds NeoVoxy/Voxy LODs, and Distant Horizons' depth);</li>
 *   <li>temporal resolve with last frame's result, reprojected by cloud distance (MEDIUM and HIGH);</li>
 *   <li>a depth-aware upsample composited over the scene ({@code scene * T + light});</li>
 *   <li>a 2x1 probe of the transmittance towards the sun and moon, read back a few frames later without stalling,
 *       for {@link CloudCover#sunCover()} (Lumis dims its rays and lens flare by it).</li>
 * </ol>
 * The resolved low-resolution texture (alpha = transmittance) is what {@link CloudCover#transmittanceTexture()} hands
 * to other mods.
 */
public final class CloudPass {
    private static final int CAP_LINES = 360;
    private static final int CAP_LINES_HIGH = 720;

    private static AtmosFeature feature;
    private static AtmosFeature shadowFeature;
    private static Program shadow;
    private static final float[] SHADOW_REL = new float[2];
    private static Program march;
    private static Program resolve;
    private static Program composite;
    private static Program probe;
    /** 0.4.0 "Mirror": the clouds of the sky-view map (128x64, RGBA16F: premultiplied light, transmittance). */
    private static Program map;
    static final int MAP_W = 128;
    static final int MAP_H = 64;
    private static final int MAP_ROWS = 16;
    private static int mapTexture;
    private static int mapFbo;
    private static int mapRow;
    private static long mapFrame = -1;
    private static int[] resolveReproject;
    private static boolean compileFailed;

    private static RenderTargets.Target low;
    private static RenderTargets.Target lowDepth;
    /** 0.2.7: altocumulus and cirrus, outside the temporal history (RGBA16F: rgb light, a transmittance). */
    private static RenderTargets.Target thinTarget;
    private static RenderTargets.History history;
    private static int lowDivisor;
    private static int mrtFbo;

    private static int output;
    private static long outputFrame = -1;
    private static boolean fabulousLogged;

    private static final Vector3f LOOK = new Vector3f();
    private static final float[] SKY = new float[3];
    private static final int[] DRAW_BUFFERS = {GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1,
            GL30C.GL_COLOR_ATTACHMENT2};

    private CloudPass() {
    }

    public static void register() {
        feature = AtmosFeature.builder(Atmosphera.MOD_ID, "clouds")
                .provides(Capability.CLOUDS)
                .uses(AtmosFeature.Uses.WORLD_PASS)
                .switchedBy(AtmospheraConfig.clouds)
                .register();
        PostPasses.register(feature, PostPasses.Stage.CLOUDS, 0, "atmosphera_clouds", CloudPass::render);
        // Cloud shadows (0.2.0): drawn in the cloud pass; a feature of their own so they can be switched and so the
        // overlap guard can see them.
        shadowFeature = AtmosFeature.builder(Atmosphera.MOD_ID, "cloud_shadows")
                .provides(Capability.CLOUD_SHADOWS)
                .uses(AtmosFeature.Uses.WORLD_PASS)
                .switchedBy(AtmospheraConfig.shadows)
                .register();
        CloudCover.register(feature, new CloudCover.Provider() {
            @Override
            public float sunCover() {
                return drawing() ? SunProbe.sunCover() : 0f;
            }

            @Override
            public float moonCover() {
                return drawing() ? SunProbe.moonCover() : 0f;
            }

            @Override
            public float deckBase() {
                return CloudWeather.base;
            }

            @Override
            public float flash() {
                return drawing() && AtmospheraConfig.lightning.get() ? Lightning.intensity() : 0f;
            }

            @Override
            public int transmittanceTexture() {
                // Only this frame's or last frame's result: never a texture from a frame long gone.
                return AtmosClient.frame() - outputFrame <= 1 ? output : 0;
            }

            @Override
            public int shadowTexture() {
                return shadowFeature.isActive() && drawing() ? CloudShadow.texture(AtmosClient.frame()) : 0;
            }

            @Override
            public boolean shadowParams(float[] out) {
                Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                return CloudShadow.params(out, camera.x, camera.z);
            }
        });
    }

    public static AtmosFeature feature() {
        return feature;
    }

    /** The top of the low deck (world y) while the clouds are drawn, else NaN (0.6.0: the sky's horizon above it). */
    public static float deckTop() {
        return drawing() ? CloudWeather.base + CloudWeather.thickness : Float.NaN;
    }

    /** How much of the sun the clouds cover now, 0..1 (the sun probe), for the bows. */
    public static float sunCover() {
        return SunProbe.sunCover();
    }

    /** The cloud map for the sky view (0.4.0), or 0 when the clouds were not drawn lately. Render thread. */
    public static int cloudMap() {
        return mapTexture != 0 && AtmosClient.frame() - mapFrame <= 2 ? mapTexture : 0;
    }

    /**
     * Whether Atmosphera draws clouds right now (feature active, not Fabulous, vanilla option followed if asked). Vanilla's
     * clouds are hidden while this is true ({@code OptionsCloudsMixin}). Any thread.
     */
    public static boolean drawing() {
        if (feature == null || !feature.isActive()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (Minecraft.useShaderTransparency()) {
            return false;
        }
        return !AtmospheraConfig.followVanilla.get() || mc.options.cloudStatus().get() != CloudStatus.OFF;
    }

    private static void render(PostPasses.Context ctx) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        if (Minecraft.useShaderTransparency()) {
            if (!fabulousLogged) {
                fabulousLogged = true;
                Atmosphera.LOGGER.warn("[Atmosphera] Atmosphera clouds are off under Fabulous graphics (its separate cloud "
                        + "target is not supported yet). Use Fancy graphics for Atmosphera clouds.");
            }
            return;
        }
        fabulousLogged = false;
        if (!drawing() || Float.isNaN(level.effects().getCloudHeight())) {
            return; // no clouds in this dimension (the Nether, the End)
        }
        if (!CloudNoise.ready() || compileFailed) {
            return;
        }
        RenderTargets.Scene scene = RenderTargets.scene();
        if (!scene.usable()) {
            return;
        }
        if (march == null && !compile()) {
            return;
        }

        Vec3 camera = ctx.camera();
        CloudWeather.update(level, ctx.partialTick(), camera.x, camera.y, camera.z);
        com.solarbiscuit.atmosphera.atmosphera.sky.SkyEvents.ensure(level, ctx.partialTick());
        Lightning.update(level, ctx.partialTick(), camera.x, camera.y, camera.z, CloudWeather.thunder,
                CloudWeather.base, CloudWeather.thickness,
                (float) Math.min(0.25, Math.max(0.0, com.solarbiscuit.atmosphera.lib.wind.AtmosClock.deltaSeconds())));
        if (AtmospheraConfig.regional.get()) {
            RegionMap.update(level, camera.x, camera.z, CloudWeather.dryness(), CloudWeather.coldness(),
                    CloudWeather.windX, CloudWeather.windZ);
        }
        AtmospheraConfig.Quality quality = AtmospheraConfig.quality.get();
        int cap = quality == AtmospheraConfig.Quality.HIGH ? CAP_LINES_HIGH : CAP_LINES;
        int divisor = Math.max(quality.divisor, (scene.height() + cap - 1) / cap);
        boolean temporal = quality.temporal && AtmospheraConfig.debug.get() == 0;
        if (low == null || lowDivisor != divisor) {
            releaseTargets();
            // Linear filtering (0.2.5): the composite's fast path takes one bilinear fetch instead of four.
            low = RenderTargets.create("Atmosphera clouds", divisor, GL30C.GL_RGBA16F, true);
            // RGBA16F since 0.2.7: x low deck distance, y march limit, w all decks' transmittance (for Lumis).
            lowDepth = RenderTargets.create("Atmosphera cloud depth", divisor, GL30C.GL_RGBA16F, false);
            thinTarget = RenderTargets.create("Atmosphera thin clouds", divisor, GL30C.GL_RGBA16F, true);
            lowDivisor = divisor;
        }
        if (!low.ensure(scene) || !lowDepth.ensure(scene) || !thinTarget.ensure(scene)) {
            return;
        }
        if (temporal) {
            if (history == null) {
                history = RenderTargets.history("Atmosphera clouds", divisor, GL30C.GL_RGBA16F);
            }
            if (!history.ensure(scene)) {
                temporal = false;
            } else if (!ctx.continuous()) {
                history.invalidate();
            }
        } else if (history != null) {
            history.release();
            history = null;
        }

        long frame = AtmosClient.frame();
        int dhTexture = AtmospheraConfig.dhDepth.get() ? DhBridge.depthTexture(frame) : 0;
        FrameUniforms.update(frame);
        GlStateSnapshot saved = GlStateSnapshot.save();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. March (MRT: colour + depth targets).
            GpuTimers.begin("atmosphera_march");
            if (!bindMrt()) {
                return;
            }
            bind(11, GL11C.GL_TEXTURE_2D, dhTexture);
            bind(12, GL11C.GL_TEXTURE_2D, scene.depthTexture());
            bind(13, GL12C.GL_TEXTURE_3D, CloudNoise.shape());
            bind(14, GL12C.GL_TEXTURE_3D, CloudNoise.detail());
            bind(15, GL11C.GL_TEXTURE_2D, CloudNoise.weather());
            bind(10, GL11C.GL_TEXTURE_2D, CloudNoise.layers());
            bind(9, GL11C.GL_TEXTURE_2D, RegionMap.texture());
            GL20C.glUseProgram(march.id());
            setCommon(march, ctx, camera, quality, frame, temporal, dhTexture != 0, stack);
            GlslPrograms.drawFullscreen();
            GpuTimers.end("atmosphera_march");

            // 2. Temporal resolve.
            int clouds = low.texture();
            if (temporal) {
                GpuTimers.begin("atmosphera_resolve");
                RenderTargets.Target target = history.current();
                target.bindWrite();
                bind(13, GL11C.GL_TEXTURE_2D, low.texture());
                bind(14, GL11C.GL_TEXTURE_2D, history.previous().texture());
                bind(15, GL11C.GL_TEXTURE_2D, lowDepth.texture());
                Program p = resolve;
                GL20C.glUseProgram(p.id());
                GL20C.glUniform1i(p.u(0), 13);
                GL20C.glUniform1i(p.u(1), 15);
                GL20C.glUniform1i(p.u(2), 14);
                GL20C.glUniformMatrix4fv(p.u(3), false, ctx.invViewProjection().get(stack.mallocFloat(16)));
                GL20C.glUniform4i(p.u(4), history.valid() ? 1 : 0, 0, 0, 0);
                ctx.bindReproject(resolveReproject, stack);
                GlslPrograms.drawFullscreen();
                clouds = target.texture();
                history.swap();
                GpuTimers.end("atmosphera_resolve");
            }
            // Handed to other mods (CloudCover): the depth target's alpha holds every deck's transmittance this frame.
            output = lowDepth.texture();
            outputFrame = frame;

            // 3. Composite over the scene colour (scene * T + light); the scene depth stays sampleable.
            GpuTimers.begin("atmosphera_composite");
            if (RenderTargets.bindColorOnly(scene)) {
                bind(13, GL11C.GL_TEXTURE_2D, clouds);
                bind(14, GL11C.GL_TEXTURE_2D, thinTarget.texture());
                bind(15, GL11C.GL_TEXTURE_2D, lowDepth.texture());
                Program p = composite;
                GL20C.glUseProgram(p.id());
                GL20C.glUniform1i(p.u(0), 13);
                GL20C.glUniform1i(p.u(1), 14);
                GL20C.glUniform1i(p.u(2), 15);
                GL20C.glUniform1i(p.u(3), 12);
                GL20C.glUniformMatrix4fv(p.u(4), false, ctx.invViewProjection().get(stack.mallocFloat(16)));
                // z: from above the altocumulus the thin decks lie in front of the low deck. 0.8.0: with the
                // altocumulus in the low deck's output the thin target holds the cirrus alone.
                int thinFrom = altoVolume(quality) ? AtmospheraConfig.highHeight.get() : AtmospheraConfig.midHeight.get();
                GL20C.glUniform4f(p.u(5), AtmospheraConfig.farDistance.get(), AtmospheraConfig.debug.get() > 0 ? 1f : 0f,
                        camera.y > thinFrom ? 1f : 0f, 0f);
                GL11C.glEnable(GL11C.GL_BLEND);
                GL14C.glBlendFuncSeparate(GL11C.GL_ONE, GL11C.GL_SRC_ALPHA, GL11C.GL_ZERO, GL11C.GL_ONE);
                GlslPrograms.drawFullscreen();
                GL11C.glDisable(GL11C.GL_BLEND);
            }
            GpuTimers.end("atmosphera_composite");

            // 4. Sun and moon cover for other mods (2 pixels; read back later).
            bind(13, GL12C.GL_TEXTURE_3D, CloudNoise.shape());
            bind(14, GL12C.GL_TEXTURE_3D, CloudNoise.detail());
            bind(15, GL11C.GL_TEXTURE_2D, CloudNoise.weather());
            GpuTimers.begin("atmosphera_probe");
            if (SunProbe.begin(quality == AtmospheraConfig.Quality.LOW ? 0.25f : 0.1f)) {
                GL20C.glUseProgram(probe.id());
                setCommon(probe, ctx, camera, quality, frame, false, false, stack);
                GL20C.glUniform4f(probe.u(Program.PROBE0), SunSource.dirX(), SunSource.dirY(), SunSource.dirZ(), 0f);
                GL20C.glUniform4f(probe.u(Program.PROBE1), -SunSource.dirX(), -SunSource.dirY(), -SunSource.dirZ(), 0f);
                GlslPrograms.drawFullscreen();
                SunProbe.end();
            }
            GpuTimers.end("atmosphera_probe");

            // 5. Cloud shadow map (0.2.0), read by terrain, LODs and Lumis through the lib's atmos_cloudShadow.
            if (shadowFeature.isActive()) {
                GpuTimers.begin("atmosphera_shadow");
                int res = quality == AtmospheraConfig.Quality.HIGH ? 512 : 256;
                float planeY = CloudWeather.base + 0.4f * CloudWeather.thickness;
                if (CloudShadow.begin(res, camera.x, camera.z, planeY, SHADOW_REL)) {
                    GL20C.glUseProgram(shadow.id());
                    setCommon(shadow, ctx, camera, quality, frame, false, false, stack);
                    GL20C.glUniform4i(shadow.u(Program.INFO), lowDivisor, (int) (frame & 0xFFFF),
                            quality == AtmospheraConfig.Quality.LOW ? 3 : 6, quality == AtmospheraConfig.Quality.LOW ? 0 : 1);
                    GL20C.glUniform4f(shadow.u(Program.PROBE0), SunSource.dirX(), SunSource.dirY(), SunSource.dirZ(), 0f);
                    GL20C.glUniform4f(shadow.u(Program.PROBE1), SHADOW_REL[0], SHADOW_REL[1], CloudShadow.SIZE_BLOCKS, planeY);
                    GlslPrograms.drawFullscreen();
                    CloudShadow.end(frame);
                }
                GpuTimers.end("atmosphera_shadow");
            }

            // 6. Cloud map for the sky view (0.4.0 "Mirror": clouds in water reflections). 0.6.2: 16 of 64 rows every
            // other frame (same 8-frame refresh); so few pixels cost the march's latency, not their number, so half
            // the draws is about half the cost.
            if (map != null && com.solarbiscuit.atmosphera.lib.world.SkyView.wanted()
                    && (mapTexture == 0 || (frame & 1L) == 0L)) {
                GpuTimers.begin("atmosphera_cloudmap");
                boolean fresh = mapTexture == 0;
                if (!fresh || createMap()) {
                    bind(13, GL12C.GL_TEXTURE_3D, CloudNoise.shape());
                    bind(14, GL12C.GL_TEXTURE_3D, CloudNoise.detail());
                    bind(15, GL11C.GL_TEXTURE_2D, CloudNoise.weather());
                    bind(10, GL11C.GL_TEXTURE_2D, CloudNoise.layers());
                    GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, mapFbo);
                    GL11C.glViewport(0, 0, MAP_W, MAP_H);
                    if (!fresh) {
                        GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
                        GL11C.glScissor(0, mapRow, MAP_W, MAP_ROWS);
                    }
                    GL20C.glUseProgram(map.id());
                    setCommon(map, ctx, camera, quality, frame, false, false, stack);
                    GL20C.glUniform4i(map.u(Program.INFO), lowDivisor, (int) (frame & 0xFFFF),
                            quality == AtmospheraConfig.Quality.LOW ? 4 : 6, 1);
                    // w: the angle one map texel covers (for the thin layers' mip level).
                    GL20C.glUniform4f(map.u(Program.FORWARD), LOOK.x, LOOK.y, LOOK.z, 0.05f);
                    GL20C.glUniform4f(map.u(Program.PROBE0), camera.y > AtmospheraConfig.midHeight.get() ? 1f : 0f, 0f, 0f, 0f);
                    GlslPrograms.drawFullscreen();
                    GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
                    mapRow = (mapRow + MAP_ROWS) % MAP_H;
                    mapFrame = frame;
                }
                GpuTimers.end("atmosphera_cloudmap");
            }
        } finally {
            for (int unit = 8; unit <= 15; unit++) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
                GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, 0);
            }
            saved.restore();
        }
    }

    /** The cloud map's texture and framebuffer (0.4.0); the first draw fills every row. */
    private static boolean createMap() {
        mapTexture = com.solarbiscuit.atmosphera.lib.gpu.GpuResources.texture("Atmosphera cloud map");
        GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, mapTexture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA16F, MAP_W, MAP_H, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT,
                (java.nio.ByteBuffer) null);
        com.solarbiscuit.atmosphera.lib.gpu.GpuResources.textureBytes(mapTexture, (long) MAP_W * MAP_H * 8);
        mapFbo = com.solarbiscuit.atmosphera.lib.gpu.GpuResources.framebuffer("Atmosphera cloud map");
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, mapFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, mapTexture, 0);
        GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
        if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            Atmosphera.LOGGER.error("Atmosphera cloud map framebuffer incomplete; no clouds in reflections");
            releaseMap();
            map = null;
            return false;
        }
        return true;
    }

    private static void releaseMap() {
        if (mapFbo != 0) {
            com.solarbiscuit.atmosphera.lib.gpu.GpuResources.deleteFramebuffer(mapFbo);
            mapFbo = 0;
        }
        if (mapTexture != 0) {
            com.solarbiscuit.atmosphera.lib.gpu.GpuResources.deleteTexture(mapTexture);
            mapTexture = 0;
        }
        mapFrame = -1;
    }

    private static void bind(int unit, int target, int texture) {
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        GL11C.glBindTexture(target, texture);
    }

    /** Binds an own framebuffer with the colour and depth targets as attachments 0 and 1. */
    private static boolean bindMrt() {
        if (mrtFbo == 0) {
            mrtFbo = com.solarbiscuit.atmosphera.lib.gpu.GpuResources.framebuffer("Atmosphera cloud march");
        }
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, mrtFbo);
        // Attached every frame: the targets are recreated on resize, often with the same ids.
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, low.texture(), 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT1, GL11C.GL_TEXTURE_2D,
                lowDepth.texture(), 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT2, GL11C.GL_TEXTURE_2D,
                thinTarget.texture(), 0);
        GL20C.glDrawBuffers(DRAW_BUFFERS);
        if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            Atmosphera.LOGGER.error("Atmosphera cloud framebuffer incomplete; clouds off");
            compileFailed = true;
            return false;
        }
        GL11C.glViewport(0, 0, low.width(), low.height());
        return true;
    }

    /** The per-frame uniform values (0.2.0: computed once per frame, then only uploaded per program). */
    private static final float[] V = new float[88];
    private static final float[] LOW_SUN = new float[3];
    private static final float[] HIGH_SUN = new float[3];
    private static final int V_CAMERA = 0, V_FORWARD = 4, V_DECK = 8, V_TYPE = 12, V_LAYERS = 16, V_SHAPE_OFF = 20,
            V_WEATHER_OFF = 24, V_SCALE = 28, V_BLOCK = 32, V_LIGHT_DIR = 36, V_LIGHT_COL = 40, V_AMB_TOP = 44,
            V_AMB_BOTTOM = 48, V_HAZE = 52, V_TINT = 56, V_MARCH = 60, V_STORM = 64, V_FLASH = 68, V_REGION = 72,
            V_LOCAL = 76, V_LIGHT_HIGH = 80, V_AMB_SUN = 84;
    private static final int[] V_SLOTS = {Program.CAMERA, Program.FORWARD, Program.DECK, Program.TYPE, Program.LAYERS,
            Program.SHAPE_OFF, Program.WEATHER_OFF, Program.SCALE, Program.BLOCK, Program.LIGHT_DIR, Program.LIGHT_COL,
            Program.AMB_TOP, Program.AMB_BOTTOM, Program.HAZE, Program.TINT, Program.MARCH, Program.STORM,
            Program.FLASH, Program.REGION_OFF, Program.LOCAL, Program.LIGHT_HIGH, Program.AMB_SUN};
    private static long preparedFrame = -1;
    private static boolean preparedDetail;

    private static void put(int at, float x, float y, float z, float w) {
        V[at] = x;
        V[at + 1] = y;
        V[at + 2] = z;
        V[at + 3] = w;
    }

    /**
     * Everything the cloud programs share for this frame, computed once (0.2.0). Until 0.1.6 each program (march,
     * probe) recomputed it, including the game's sky and cloud colours (biome blends that allocate), which showed as
     * about 0.17 ms of idle GPU time inside the pass at 1440p.
     */
    private static void prepare(PostPasses.Context ctx, Vec3 camera, AtmospheraConfig.Quality quality, long frame) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        CloudStyle style = CloudStyle.current();
        float partial = ctx.partialTick();
        preparedFrame = frame;
        preparedDetail = quality.detail && style.detail() > 0f;

        int radius = AtmospheraConfig.curvature.get();
        put(V_CAMERA, (float) camera.y, AtmospheraConfig.farDistance.get(), radius > 0 ? 0.5f / radius : 0f,
                CloudWeather.hazeDistance);
        LOOK.set(mc.gameRenderer.getMainCamera().getLookVector());
        // w: the angle one low-resolution pixel covers (for the thin layers' mip level): 2 tan(fovY / 2) / lines.
        float lines = low != null && low.height() > 0 ? low.height() : 360f;
        put(V_FORWARD, LOOK.x, LOOK.y, LOOK.z, 2f / (Math.max(ctx.projection().m11(), 1e-3f) * lines));

        boolean blocky = style.blockCell() > 0f;
        float thickness = blocky ? style.blockDepth() : CloudWeather.thickness;
        put(V_DECK, CloudWeather.base, Math.max(8f, thickness), CloudWeather.coverage,
                style.extinction() * AtmospheraConfig.density.get().floatValue());
        put(V_TYPE, CloudWeather.stratiform, CloudWeather.towers, style.softness(), style.detail());
        put(V_LAYERS, AtmospheraConfig.midHeight.get(), AtmospheraConfig.highHeight.get(),
                blocky ? 0f : CloudWeather.midCover, blocky ? 0f : CloudWeather.highCover);

        // Noise positions: world position minus drift, as a fraction of each tile (double precision here).
        double lowX = camera.x - CloudWeather.lowX;
        double lowZ = camera.z - CloudWeather.lowZ;
        double highX = camera.x - CloudWeather.highX;
        double highZ = camera.z - CloudWeather.highZ;
        float highSize = style.weatherSize() * 0.35f;
        // 0.10.0: the shape and detail noise also drift on their own (CloudWeather.evoX), against the cover map.
        double shapeX = lowX - CloudWeather.evoX;
        double shapeZ = lowZ - CloudWeather.evoZ;
        put(V_SHAPE_OFF, frac(shapeX / style.shapeSize()), frac(shapeZ / style.shapeSize()),
                frac(shapeX / style.detailSize()), frac(shapeZ / style.detailSize()));
        put(V_WEATHER_OFF, frac(lowX / style.weatherSize()), frac(lowZ / style.weatherSize()),
                frac(highX / highSize), frac(highZ / highSize));
        put(V_SCALE, 1f / style.shapeSize(), 1f / style.detailSize(), 1f / style.weatherSize(), 1f / highSize);
        float cell = style.blockCell();
        put(V_BLOCK, cell, style.blockDepth(),
                blocky ? (float) (lowX - Math.floor(lowX / cell) * cell) : 0f,
                blocky ? (float) (lowZ - Math.floor(lowZ / cell) * cell) : 0f);

        // Light: the sun by day, carrying on a little below the horizon (clouds stay lit after sunset), else the moon.
        float sx = SunSource.dirX();
        float sy = SunSource.dirY();
        float sz = SunSource.dirZ();
        float overcast = 1f - 0.55f * CloudWeather.wet - 0.2f * CloudWeather.thunder;
        // 0.12.0: the sunlight's colour from the sky model's transmittance at each deck's height (low deck about 2 km,
        // altocumulus and cirrus about 8 km): red and dim through the long path at sunrise and sunset, and gone once
        // the Earth's shadow reaches that height, so high clouds glow on after the low ones have gone grey. Before the
        // sky tables exist (or without them), the fixed ramp of 0.11.
        boolean modelled = com.solarbiscuit.atmosphera.atmosphera.sky.SkyModel.sunlight(2.0, sy, LOW_SUN)
                && com.solarbiscuit.atmosphera.atmosphera.sky.SkyModel.sunlight(8.0, sy, HIGH_SUN);
        if (!modelled) {
            float sun = smoothstep(-0.14f, 0.04f, sy);
            float warm = smoothstep(-0.06f, 0.42f, sy);
            LOW_SUN[0] = HIGH_SUN[0] = sun;
            LOW_SUN[1] = HIGH_SUN[1] = sun * lerp(0.42f, 0.96f, warm);
            LOW_SUN[2] = HIGH_SUN[2] = sun * lerp(0.2f, 0.9f, warm);
        }
        float sunHigh = Math.max(HIGH_SUN[0], Math.max(HIGH_SUN[1], HIGH_SUN[2]));
        if (sunHigh > 0.02f) {
            float intensity = 1.15f * overcast;
            put(V_LIGHT_DIR, sx, sy, sz, 1f);
            put(V_LIGHT_COL, intensity * LOW_SUN[0], intensity * LOW_SUN[1], intensity * LOW_SUN[2], style.forward());
            put(V_LIGHT_HIGH, intensity * HIGH_SUN[0], intensity * HIGH_SUN[1], intensity * HIGH_SUN[2], 0f);
            // Golden hour: the sunlit horizon bounces warm light onto the sides and bases facing the sun.
            float golden = (1f - smoothstep(0.03f, 0.35f, sy)) * smoothstep(-0.12f, -0.02f, sy) * overcast;
            put(V_AMB_SUN, 0.22f * golden * LOW_SUN[0], 0.22f * golden * LOW_SUN[1], 0.22f * golden * LOW_SUN[2], golden);
        } else {
            // 0.9.0: a lunar eclipse takes most of the moonlight off the clouds.
            float moon = 0.22f * level.getMoonBrightness() * overcast
                    * com.solarbiscuit.atmosphera.atmosphera.sky.SkyEvents.moonLightScale();
            put(V_LIGHT_DIR, -sx, -sy, -sz, 0f);
            put(V_LIGHT_COL, 0.78f * moon, 0.86f * moon, moon, style.forward());
            put(V_LIGHT_HIGH, 0.78f * moon, 0.86f * moon, moon, 0f);
            put(V_AMB_SUN, 0f, 0f, 0f, 0f);
        }
        // 0.3.0: the drawn sky's colour (Atmosphera's own sky through the lib's SkySource), else vanilla's.
        if (!SkySource.skyColor(SKY)) {
            Vec3 sky = level.getSkyColor(camera, partial);
            SKY[0] = (float) sky.x;
            SKY[1] = (float) sky.y;
            SKY[2] = (float) sky.z;
        }
        float[] fog = RenderSystem.getShaderFogColor();
        put(V_AMB_TOP, SKY[0] * 0.9f + 0.015f, SKY[1] * 0.9f + 0.017f, SKY[2] * 0.9f + 0.02f, style.ambient());
        put(V_AMB_BOTTOM, fog[0] * 0.5f, fog[1] * 0.5f, fog[2] * 0.52f, style.powder());
        put(V_HAZE, fog[0], fog[1], fog[2], CloudWeather.darken);

        int rgb = AtmospheraConfig.tint.rgb();
        float tr = ((rgb >> 16) & 0xFF) / 255f;
        float tg = ((rgb >> 8) & 0xFF) / 255f;
        float tb = (rgb & 0xFF) / 255f;
        if (AtmospheraConfig.levelCloudColor.get()) {
            // Only the hue of the game's cloud colour (vanilla darkens it at night and in rain, which the
            // lighting above already does); Atmospherics' per-biome cloud colours come through here.
            Vec3 cloud = level.getCloudColor(partial);
            double max = Math.max(1e-3, Math.max(cloud.x, Math.max(cloud.y, cloud.z)));
            tr *= (float) (cloud.x / max);
            tg *= (float) (cloud.y / max);
            tb *= (float) (cloud.z / max);
        }
        put(V_TINT, tr, tg, tb, AtmospheraConfig.brightness.get().floatValue());
        // 0.8.0: mammatus under the storm deck (thunder, a few minutes after it, or forced), and the low sun that
        // shines in under the deck and lights their sunward sides.
        int pouches = com.solarbiscuit.atmosphera.atmosphera.sky.SkyEvents.mammatusMode();
        float mammatus = blocky || !AtmospheraConfig.mammatus.get() || pouches == com.solarbiscuit.atmosphera.atmosphera.sky.SkyEvents.OFF
                ? 0f : pouches == com.solarbiscuit.atmosphera.atmosphera.sky.SkyEvents.ON ? -1f
                : AtmospheraConfig.stormShapes.get() && CloudWeather.mammatus > 0.1f
                ? (CloudWeather.mammatus - 0.1f) / 0.9f : 0f;
        float underSun = 0.8f * smoothstep(0.34f, 0.08f, sy) * smoothstep(-0.06f, 0f, sy);
        put(V_MARCH, AtmospheraConfig.marchNear.get() ? 1f : 0f, mammatus, AtmospheraConfig.marchThirdOctave.get() ? 1f : 0f,
                underSun);
        // 0.5.0 "Storm": anvils, mammatus and shelf clouds in thunder, rain shafts in rain, lightning, and the regional
        // map (biomes and terrain around). Each switchable.
        boolean storms = AtmospheraConfig.stormShapes.get() && !blocky;
        put(V_STORM, storms ? CloudWeather.towers : 0f, CloudWeather.windX, CloudWeather.windZ,
                AtmospheraConfig.rainShafts.get() && !blocky ? CloudWeather.rain : 0f);
        float flash = AtmospheraConfig.lightning.get() ? Lightning.STATE[3] : 0f;
        put(V_FLASH, Lightning.STATE[0], Lightning.STATE[1], Lightning.STATE[2], flash);
        RegionMap.params(V, V_REGION, camera.x, camera.z);
        if (!AtmospheraConfig.regional.get()) {
            V[V_REGION + 3] = 0f;
        }
        put(V_LOCAL, CloudWeather.dryness(), CloudWeather.coldness(), AtmospheraConfig.regional.get() ? 1f : 0f, 0f);
    }

    /** Whether the altocumulus is marched as a thin volume (0.8.0) instead of drawn as a flat layer. */
    private static boolean altoVolume(AtmospheraConfig.Quality quality) {
        return quality.temporal && AtmospheraConfig.altoVolume.get() && CloudStyle.current().blockCell() <= 0f;
    }

    private static void setCommon(Program p, PostPasses.Context ctx, Vec3 camera, AtmospheraConfig.Quality quality,
                                  long frame, boolean temporal, boolean dh, MemoryStack stack) {
        if (preparedFrame != frame) {
            prepare(ctx, camera, quality, frame);
        }
        GL20C.glUniform1i(p.u(Program.DEPTH), 12);
        GL20C.glUniform1i(p.u(Program.SHAPE), 13);
        GL20C.glUniform1i(p.u(Program.DETAIL), 14);
        GL20C.glUniform1i(p.u(Program.WEATHER), 15);
        GL20C.glUniform1i(p.u(Program.DH), 11);
        GL20C.glUniform1i(p.u(Program.LAYER_TEX), 10);
        GL20C.glUniform1i(p.u(Program.REGION), 9);
        // 0.6.5: the sky's horizon colours, so far clouds fade to the colour the terrain's haze and the sky use.
        // 0.8.0: the altocumulus as a thin volume on the tiers with a temporal resolve (45 blocks deep, 4 steps, 6 on
        // HIGH); LOW and the blocky style keep the flat layer.
        boolean altoVolume = altoVolume(quality);
        GL20C.glUniform4f(p.u(Program.ALTO), altoVolume ? 1f : 0f, 45f, quality == AtmospheraConfig.Quality.HIGH ? 6f : 4f,
                V[V_DECK + 3] * 1.6f);
        int skyTable = com.solarbiscuit.atmosphera.atmosphera.sky.SkyPass.horizonLut();
        bind(8, GL11C.GL_TEXTURE_2D, skyTable);
        GL20C.glUniform1i(p.u(Program.SKY_HZ), 8);
        GL20C.glUniform4f(p.u(Program.SKY_HZ_P), com.solarbiscuit.atmosphera.atmosphera.sky.SkyPass.sunAzimuth(),
                com.solarbiscuit.atmosphera.atmosphera.sky.SkyPass.horizonAngle(), skyTable != 0 ? 1f : 0f,
                com.solarbiscuit.atmosphera.atmosphera.sky.SkyPass.horizonExposure());
        GL20C.glUniformMatrix4fv(p.u(Program.INV_VIEW_PROJ), false, ctx.invViewProjection().get(stack.mallocFloat(16)));
        GL20C.glUniform4i(p.u(Program.INFO), lowDivisor, (int) (frame & 0xFFFF), quality.steps, quality.lightSteps);
        GL20C.glUniform4f(p.u(Program.FLAGS), preparedDetail ? 1f : 0f, temporal ? 1f : 0f, AtmospheraConfig.debug.get(),
                dh ? 1f : 0f);
        for (int i = 0; i < V_SLOTS.length; i++) {
            int at = i * 4;
            GL20C.glUniform4f(p.u(V_SLOTS[i]), V[at], V[at + 1], V[at + 2], V[at + 3]);
        }
    }

    private static boolean compile() {
        String header = GlslPrograms.header() + DhBridge.GLSL;
        march = Program.of("Atmosphera cloud march", header + CloudGlsl.MARCH);
        resolve = Program.of("Atmosphera cloud resolve", GlslPrograms.header()
                + CloudGlsl.RESOLVE.replace("%REPROJECT%", PostPasses.REPROJECT_GLSL),
                "u_Low", "u_LowDepth", "u_History", "u_InvViewProj", "u_Info");
        composite = Program.of("Atmosphera cloud composite", GlslPrograms.header() + CloudGlsl.COMPOSITE,
                "u_Clouds", "u_Thin", "u_LowDepth", "u_Depth", "u_InvViewProj", "u_Params");
        probe = Program.of("Atmosphera sun cover", GlslPrograms.header() + CloudGlsl.PROBE);
        shadow = Program.of("Atmosphera cloud shadow map", GlslPrograms.header() + CloudGlsl.SHADOW);
        map = Program.of("Atmosphera cloud map", header + CloudGlsl.MAP);
        if (march == null || resolve == null || composite == null || probe == null || shadow == null) {
            compileFailed = true;
            Atmosphera.LOGGER.error("[Atmosphera] Atmosphera clouds are off: a cloud program did not compile (see above)");
            release();
            return false;
        }
        resolveReproject = PostPasses.locateReproject(resolve.id());
        return true;
    }

    private static void releaseTargets() {
        if (low != null) {
            low.release();
            lowDepth.release();
            thinTarget.release();
            low = null;
            lowDepth = null;
            thinTarget = null;
        }
        if (history != null) {
            history.release();
            history = null;
        }
        output = 0;
    }

    /** Frees everything (programs, targets, probe); the next frame rebuilds what it needs. */
    public static void release() {
        releaseTargets();
        SunProbe.release();
        CloudShadow.release();
        RegionMap.release();
        releaseMap();
        for (Program p : new Program[] {march, resolve, composite, probe, shadow, map}) {
            if (p != null) {
                com.solarbiscuit.atmosphera.lib.gpu.GpuResources.deleteProgram(p.id());
            }
        }
        march = resolve = composite = probe = shadow = map = null;
        if (mrtFbo != 0) {
            com.solarbiscuit.atmosphera.lib.gpu.GpuResources.deleteFramebuffer(mrtFbo);
            mrtFbo = 0;
        }
    }

    private static float frac(double v) {
        return (float) (v - Math.floor(v));
    }

    private static float smoothstep(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** A program with its uniform locations; the common block's names come first, in this order. */
    private record Program(int id, int[] locations) {
        static final String[] COMMON = {"u_Depth", "u_Shape", "u_Detail", "u_Weather", "atmos_DhDepth", "u_InvViewProj",
                "u_Info", "u_Flags", "u_Camera", "u_Forward", "u_Deck", "u_Type", "u_Layers", "u_ShapeOff",
                "u_WeatherOff", "u_Scale", "u_Block", "u_LightDir", "u_LightCol", "u_AmbTop", "u_AmbBottom", "u_Haze",
                "u_Tint", "u_Probe0", "u_Probe1", "u_LayerTex", "u_March", "u_Storm", "u_Flash", "u_RegionOff",
                "u_Local", "u_Region", "u_SkyHz", "u_SkyHzP", "u_Alto", "u_LightColHigh", "u_AmbSun"};
        static final int DEPTH = 0, SHAPE = 1, DETAIL = 2, WEATHER = 3, DH = 4, INV_VIEW_PROJ = 5, INFO = 6, FLAGS = 7,
                CAMERA = 8, FORWARD = 9, DECK = 10, TYPE = 11, LAYERS = 12, SHAPE_OFF = 13, WEATHER_OFF = 14, SCALE = 15,
                BLOCK = 16, LIGHT_DIR = 17, LIGHT_COL = 18, AMB_TOP = 19, AMB_BOTTOM = 20, HAZE = 21, TINT = 22,
                PROBE0 = 23, PROBE1 = 24, LAYER_TEX = 25, MARCH = 26, STORM = 27, FLASH = 28, REGION_OFF = 29,
                LOCAL = 30, REGION = 31, SKY_HZ = 32, SKY_HZ_P = 33, ALTO = 34, LIGHT_HIGH = 35, AMB_SUN = 36;

        /** Programs built from {@link CloudGlsl#COMMON} locate the common names; others name their own. */
        static Program of(String name, String fragment, String... uniforms) {
            int id = GlslPrograms.compile(name, GlslPrograms.FULLSCREEN_VERTEX, fragment);
            if (id == 0) {
                return null;
            }
            String[] names = uniforms.length > 0 ? uniforms : COMMON;
            int[] locations = new int[names.length];
            for (int i = 0; i < names.length; i++) {
                locations[i] = GL20C.glGetUniformLocation(id, names[i]);
            }
            return new Program(id, locations);
        }

        int u(int index) {
            return locations[index];
        }
    }
}
