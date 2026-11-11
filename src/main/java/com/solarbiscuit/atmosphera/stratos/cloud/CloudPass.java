package com.solarbiscuit.atmosphera.stratos.cloud;

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
import com.solarbiscuit.atmosphera.stratos.Stratos;
import com.solarbiscuit.atmosphera.stratos.StratosConfig;
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
 * Stratos's clouds: one post pass at the lib's {@code CLOUDS} stage (vanilla's cloud slot, after particles, before
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
        feature = AtmosFeature.builder(Stratos.MOD_ID, "clouds")
                .provides(Capability.CLOUDS)
                .uses(AtmosFeature.Uses.WORLD_PASS)
                .switchedBy(StratosConfig.clouds)
                .register();
        PostPasses.register(feature, PostPasses.Stage.CLOUDS, 0, "stratos_clouds", CloudPass::render);
        // Cloud shadows (0.2.0): drawn in the cloud pass; a feature of their own so they can be switched and so the
        // overlap guard can see them.
        shadowFeature = AtmosFeature.builder(Stratos.MOD_ID, "cloud_shadows")
                .provides(Capability.CLOUD_SHADOWS)
                .uses(AtmosFeature.Uses.WORLD_PASS)
                .switchedBy(StratosConfig.shadows)
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

    /**
     * Whether Stratos draws clouds right now (feature active, not Fabulous, vanilla option followed if asked). Vanilla's
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
        return !StratosConfig.followVanilla.get() || mc.options.cloudStatus().get() != CloudStatus.OFF;
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
                Stratos.LOGGER.warn("[Atmosphera] Stratos clouds are off under Fabulous graphics (its separate cloud "
                        + "target is not supported yet). Use Fancy graphics for Stratos clouds.");
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
        StratosConfig.Quality quality = StratosConfig.quality.get();
        int cap = quality == StratosConfig.Quality.HIGH ? CAP_LINES_HIGH : CAP_LINES;
        int divisor = Math.max(quality.divisor, (scene.height() + cap - 1) / cap);
        boolean temporal = quality.temporal && StratosConfig.debug.get() == 0;
        if (low == null || lowDivisor != divisor) {
            releaseTargets();
            // Linear filtering (0.2.5): the composite's fast path takes one bilinear fetch instead of four.
            low = RenderTargets.create("Stratos clouds", divisor, GL30C.GL_RGBA16F, true);
            // RGBA16F since 0.2.7: x low deck distance, y march limit, w all decks' transmittance (for Lumis).
            lowDepth = RenderTargets.create("Stratos cloud depth", divisor, GL30C.GL_RGBA16F, false);
            thinTarget = RenderTargets.create("Stratos thin clouds", divisor, GL30C.GL_RGBA16F, true);
            lowDivisor = divisor;
        }
        if (!low.ensure(scene) || !lowDepth.ensure(scene) || !thinTarget.ensure(scene)) {
            return;
        }
        if (temporal) {
            if (history == null) {
                history = RenderTargets.history("Stratos clouds", divisor, GL30C.GL_RGBA16F);
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
        int dhTexture = StratosConfig.dhDepth.get() ? DhBridge.depthTexture(frame) : 0;
        FrameUniforms.update(frame);
        GlStateSnapshot saved = GlStateSnapshot.save();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. March (MRT: colour + depth targets).
            GpuTimers.begin("stratos_march");
            if (!bindMrt()) {
                return;
            }
            bind(11, GL11C.GL_TEXTURE_2D, dhTexture);
            bind(12, GL11C.GL_TEXTURE_2D, scene.depthTexture());
            bind(13, GL12C.GL_TEXTURE_3D, CloudNoise.shape());
            bind(14, GL12C.GL_TEXTURE_3D, CloudNoise.detail());
            bind(15, GL11C.GL_TEXTURE_2D, CloudNoise.weather());
            bind(10, GL11C.GL_TEXTURE_2D, CloudNoise.layers());
            GL20C.glUseProgram(march.id());
            setCommon(march, ctx, camera, quality, frame, temporal, dhTexture != 0, stack);
            GlslPrograms.drawFullscreen();
            GpuTimers.end("stratos_march");

            // 2. Temporal resolve.
            int clouds = low.texture();
            if (temporal) {
                GpuTimers.begin("stratos_resolve");
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
                GpuTimers.end("stratos_resolve");
            }
            // Handed to other mods (CloudCover): the depth target's alpha holds every deck's transmittance this frame.
            output = lowDepth.texture();
            outputFrame = frame;

            // 3. Composite over the scene colour (scene * T + light); the scene depth stays sampleable.
            GpuTimers.begin("stratos_composite");
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
                // z: from above the altocumulus the thin decks lie in front of the low deck.
                GL20C.glUniform4f(p.u(5), StratosConfig.farDistance.get(), StratosConfig.debug.get() > 0 ? 1f : 0f,
                        camera.y > StratosConfig.midHeight.get() ? 1f : 0f, 0f);
                GL11C.glEnable(GL11C.GL_BLEND);
                GL14C.glBlendFuncSeparate(GL11C.GL_ONE, GL11C.GL_SRC_ALPHA, GL11C.GL_ZERO, GL11C.GL_ONE);
                GlslPrograms.drawFullscreen();
                GL11C.glDisable(GL11C.GL_BLEND);
            }
            GpuTimers.end("stratos_composite");

            // 4. Sun and moon cover for other mods (2 pixels; read back later).
            bind(13, GL12C.GL_TEXTURE_3D, CloudNoise.shape());
            bind(14, GL12C.GL_TEXTURE_3D, CloudNoise.detail());
            bind(15, GL11C.GL_TEXTURE_2D, CloudNoise.weather());
            GpuTimers.begin("stratos_probe");
            if (SunProbe.begin(quality == StratosConfig.Quality.LOW ? 0.25f : 0.1f)) {
                GL20C.glUseProgram(probe.id());
                setCommon(probe, ctx, camera, quality, frame, false, false, stack);
                GL20C.glUniform4f(probe.u(Program.PROBE0), SunSource.dirX(), SunSource.dirY(), SunSource.dirZ(), 0f);
                GL20C.glUniform4f(probe.u(Program.PROBE1), -SunSource.dirX(), -SunSource.dirY(), -SunSource.dirZ(), 0f);
                GlslPrograms.drawFullscreen();
                SunProbe.end();
            }
            GpuTimers.end("stratos_probe");

            // 5. Cloud shadow map (0.2.0), read by terrain, LODs and Lumis through the lib's atmos_cloudShadow.
            if (shadowFeature.isActive()) {
                GpuTimers.begin("stratos_shadow");
                int res = quality == StratosConfig.Quality.HIGH ? 512 : 256;
                float planeY = CloudWeather.base + 0.4f * CloudWeather.thickness;
                if (CloudShadow.begin(res, camera.x, camera.z, planeY, SHADOW_REL)) {
                    GL20C.glUseProgram(shadow.id());
                    setCommon(shadow, ctx, camera, quality, frame, false, false, stack);
                    GL20C.glUniform4i(shadow.u(Program.INFO), lowDivisor, (int) (frame & 0xFFFF),
                            quality == StratosConfig.Quality.LOW ? 3 : 6, quality == StratosConfig.Quality.LOW ? 0 : 1);
                    GL20C.glUniform4f(shadow.u(Program.PROBE0), SunSource.dirX(), SunSource.dirY(), SunSource.dirZ(), 0f);
                    GL20C.glUniform4f(shadow.u(Program.PROBE1), SHADOW_REL[0], SHADOW_REL[1], CloudShadow.SIZE_BLOCKS, planeY);
                    GlslPrograms.drawFullscreen();
                    CloudShadow.end(frame);
                }
                GpuTimers.end("stratos_shadow");
            }
        } finally {
            for (int unit = 10; unit <= 15; unit++) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
                GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, 0);
            }
            saved.restore();
        }
    }

    private static void bind(int unit, int target, int texture) {
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        GL11C.glBindTexture(target, texture);
    }

    /** Binds an own framebuffer with the colour and depth targets as attachments 0 and 1. */
    private static boolean bindMrt() {
        if (mrtFbo == 0) {
            mrtFbo = com.solarbiscuit.atmosphera.lib.gpu.GpuResources.framebuffer("Stratos cloud march");
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
            Stratos.LOGGER.error("Stratos cloud framebuffer incomplete; clouds off");
            compileFailed = true;
            return false;
        }
        GL11C.glViewport(0, 0, low.width(), low.height());
        return true;
    }

    /** The per-frame uniform values (0.2.0: computed once per frame, then only uploaded per program). */
    private static final float[] V = new float[64];
    private static final int V_CAMERA = 0, V_FORWARD = 4, V_DECK = 8, V_TYPE = 12, V_LAYERS = 16, V_SHAPE_OFF = 20,
            V_WEATHER_OFF = 24, V_SCALE = 28, V_BLOCK = 32, V_LIGHT_DIR = 36, V_LIGHT_COL = 40, V_AMB_TOP = 44,
            V_AMB_BOTTOM = 48, V_HAZE = 52, V_TINT = 56, V_MARCH = 60;
    private static final int[] V_SLOTS = {Program.CAMERA, Program.FORWARD, Program.DECK, Program.TYPE, Program.LAYERS,
            Program.SHAPE_OFF, Program.WEATHER_OFF, Program.SCALE, Program.BLOCK, Program.LIGHT_DIR, Program.LIGHT_COL,
            Program.AMB_TOP, Program.AMB_BOTTOM, Program.HAZE, Program.TINT, Program.MARCH};
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
    private static void prepare(PostPasses.Context ctx, Vec3 camera, StratosConfig.Quality quality, long frame) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        CloudStyle style = CloudStyle.current();
        float partial = ctx.partialTick();
        preparedFrame = frame;
        preparedDetail = quality.detail && style.detail() > 0f;

        int radius = StratosConfig.curvature.get();
        put(V_CAMERA, (float) camera.y, StratosConfig.farDistance.get(), radius > 0 ? 0.5f / radius : 0f,
                CloudWeather.hazeDistance);
        LOOK.set(mc.gameRenderer.getMainCamera().getLookVector());
        // w: the angle one low-resolution pixel covers (for the thin layers' mip level): 2 tan(fovY / 2) / lines.
        float lines = low != null && low.height() > 0 ? low.height() : 360f;
        put(V_FORWARD, LOOK.x, LOOK.y, LOOK.z, 2f / (Math.max(ctx.projection().m11(), 1e-3f) * lines));

        boolean blocky = style.blockCell() > 0f;
        float thickness = blocky ? style.blockDepth() : CloudWeather.thickness;
        put(V_DECK, CloudWeather.base, Math.max(8f, thickness), CloudWeather.coverage,
                style.extinction() * StratosConfig.density.get().floatValue());
        put(V_TYPE, CloudWeather.stratiform, CloudWeather.towers, style.softness(), style.detail());
        put(V_LAYERS, StratosConfig.midHeight.get(), StratosConfig.highHeight.get(),
                blocky ? 0f : CloudWeather.midCover, blocky ? 0f : CloudWeather.highCover);

        // Noise positions: world position minus drift, as a fraction of each tile (double precision here).
        double lowX = camera.x - CloudWeather.lowX;
        double lowZ = camera.z - CloudWeather.lowZ;
        double highX = camera.x - CloudWeather.highX;
        double highZ = camera.z - CloudWeather.highZ;
        float highSize = style.weatherSize() * 0.35f;
        put(V_SHAPE_OFF, frac(lowX / style.shapeSize()), frac(lowZ / style.shapeSize()),
                frac(lowX / style.detailSize()), frac(lowZ / style.detailSize()));
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
        float sun = smoothstep(-0.14f, 0.04f, sy);
        float overcast = 1f - 0.55f * CloudWeather.rain - 0.2f * CloudWeather.thunder;
        if (sun > 0.02f) {
            float warm = smoothstep(-0.06f, 0.42f, sy);
            float intensity = 1.15f * sun * overcast;
            put(V_LIGHT_DIR, sx, sy, sz, 1f);
            put(V_LIGHT_COL, intensity, intensity * lerp(0.42f, 0.96f, warm), intensity * lerp(0.2f, 0.9f, warm),
                    style.forward());
        } else {
            float moon = 0.22f * level.getMoonBrightness() * overcast;
            put(V_LIGHT_DIR, -sx, -sy, -sz, 0f);
            put(V_LIGHT_COL, 0.78f * moon, 0.86f * moon, moon, style.forward());
        }
        // 0.3.0: the drawn sky's colour (Stratos's own sky through the lib's SkySource), else vanilla's.
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

        int rgb = StratosConfig.tint.rgb();
        float tr = ((rgb >> 16) & 0xFF) / 255f;
        float tg = ((rgb >> 8) & 0xFF) / 255f;
        float tb = (rgb & 0xFF) / 255f;
        if (StratosConfig.levelCloudColor.get()) {
            // Only the hue of the game's cloud colour (vanilla darkens it at night and in rain, which the
            // lighting above already does); Atmospherics' per-biome cloud colours come through here.
            Vec3 cloud = level.getCloudColor(partial);
            double max = Math.max(1e-3, Math.max(cloud.x, Math.max(cloud.y, cloud.z)));
            tr *= (float) (cloud.x / max);
            tg *= (float) (cloud.y / max);
            tb *= (float) (cloud.z / max);
        }
        put(V_TINT, tr, tg, tb, StratosConfig.brightness.get().floatValue());
        put(V_MARCH, StratosConfig.marchNear.get() ? 1f : 0f, 0f, StratosConfig.marchThirdOctave.get() ? 1f : 0f, 0f);
    }

    private static void setCommon(Program p, PostPasses.Context ctx, Vec3 camera, StratosConfig.Quality quality,
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
        GL20C.glUniformMatrix4fv(p.u(Program.INV_VIEW_PROJ), false, ctx.invViewProjection().get(stack.mallocFloat(16)));
        GL20C.glUniform4i(p.u(Program.INFO), lowDivisor, (int) (frame & 0xFFFF), quality.steps, quality.lightSteps);
        GL20C.glUniform4f(p.u(Program.FLAGS), preparedDetail ? 1f : 0f, temporal ? 1f : 0f, StratosConfig.debug.get(),
                dh ? 1f : 0f);
        for (int i = 0; i < V_SLOTS.length; i++) {
            int at = i * 4;
            GL20C.glUniform4f(p.u(V_SLOTS[i]), V[at], V[at + 1], V[at + 2], V[at + 3]);
        }
    }

    private static boolean compile() {
        String header = GlslPrograms.header() + DhBridge.GLSL;
        march = Program.of("Stratos cloud march", header + CloudGlsl.MARCH);
        resolve = Program.of("Stratos cloud resolve", GlslPrograms.header()
                + CloudGlsl.RESOLVE.replace("%REPROJECT%", PostPasses.REPROJECT_GLSL),
                "u_Low", "u_LowDepth", "u_History", "u_InvViewProj", "u_Info");
        composite = Program.of("Stratos cloud composite", GlslPrograms.header() + CloudGlsl.COMPOSITE,
                "u_Clouds", "u_Thin", "u_LowDepth", "u_Depth", "u_InvViewProj", "u_Params");
        probe = Program.of("Stratos sun cover", GlslPrograms.header() + CloudGlsl.PROBE);
        shadow = Program.of("Stratos cloud shadow map", GlslPrograms.header() + CloudGlsl.SHADOW);
        if (march == null || resolve == null || composite == null || probe == null || shadow == null) {
            compileFailed = true;
            Stratos.LOGGER.error("[Atmosphera] Stratos clouds are off: a cloud program did not compile (see above)");
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
        for (Program p : new Program[] {march, resolve, composite, probe, shadow}) {
            if (p != null) {
                com.solarbiscuit.atmosphera.lib.gpu.GpuResources.deleteProgram(p.id());
            }
        }
        march = resolve = composite = probe = shadow = null;
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
                "u_Tint", "u_Probe0", "u_Probe1", "u_LayerTex", "u_March"};
        static final int DEPTH = 0, SHAPE = 1, DETAIL = 2, WEATHER = 3, DH = 4, INV_VIEW_PROJ = 5, INFO = 6, FLAGS = 7,
                CAMERA = 8, FORWARD = 9, DECK = 10, TYPE = 11, LAYERS = 12, SHAPE_OFF = 13, WEATHER_OFF = 14, SCALE = 15,
                BLOCK = 16, LIGHT_DIR = 17, LIGHT_COL = 18, AMB_TOP = 19, AMB_BOTTOM = 20, HAZE = 21, TINT = 22,
                PROBE0 = 23, PROBE1 = 24, LAYER_TEX = 25, MARCH = 26;

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
