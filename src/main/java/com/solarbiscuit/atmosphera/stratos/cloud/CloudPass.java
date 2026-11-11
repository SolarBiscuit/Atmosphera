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
    private static Program march;
    private static Program resolve;
    private static Program composite;
    private static Program probe;
    private static int[] resolveReproject;
    private static boolean compileFailed;

    private static RenderTargets.Target low;
    private static RenderTargets.Target lowDepth;
    private static RenderTargets.History history;
    private static int lowDivisor;
    private static int mrtFbo;

    private static int output;
    private static long outputFrame = -1;
    private static boolean fabulousLogged;

    private static final Vector3f LOOK = new Vector3f();
    private static final int[] DRAW_BUFFERS = {GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1};

    private CloudPass() {
    }

    public static void register() {
        feature = AtmosFeature.builder(Stratos.MOD_ID, "clouds")
                .provides(Capability.CLOUDS)
                .uses(AtmosFeature.Uses.WORLD_PASS)
                .switchedBy(StratosConfig.clouds)
                .register();
        PostPasses.register(feature, PostPasses.Stage.CLOUDS, 0, "stratos_clouds", CloudPass::render);
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
            low = RenderTargets.create("Stratos clouds", divisor, GL30C.GL_RGBA16F, false);
            lowDepth = RenderTargets.create("Stratos cloud depth", divisor, GL30C.GL_RG16F, false);
            lowDivisor = divisor;
        }
        if (!low.ensure(scene) || !lowDepth.ensure(scene)) {
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
            output = clouds;
            outputFrame = frame;

            // 3. Composite over the scene colour (scene * T + light); the scene depth stays sampleable.
            GpuTimers.begin("stratos_composite");
            if (RenderTargets.bindColorOnly(scene)) {
                bind(13, GL11C.GL_TEXTURE_2D, clouds);
                bind(14, GL12C.GL_TEXTURE_3D, 0);
                bind(15, GL11C.GL_TEXTURE_2D, lowDepth.texture());
                Program p = composite;
                GL20C.glUseProgram(p.id());
                GL20C.glUniform1i(p.u(0), 13);
                GL20C.glUniform1i(p.u(1), 15);
                GL20C.glUniform1i(p.u(2), 12);
                GL20C.glUniformMatrix4fv(p.u(3), false, ctx.invViewProjection().get(stack.mallocFloat(16)));
                GL20C.glUniform4f(p.u(4), StratosConfig.farDistance.get(), StratosConfig.debug.get() > 0 ? 1f : 0f, 0f, 0f);
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
            if (SunProbe.begin()) {
                GL20C.glUseProgram(probe.id());
                setCommon(probe, ctx, camera, quality, frame, false, false, stack);
                GL20C.glUniform4f(probe.u(Program.PROBE0), SunSource.dirX(), SunSource.dirY(), SunSource.dirZ(), 0f);
                GL20C.glUniform4f(probe.u(Program.PROBE1), -SunSource.dirX(), -SunSource.dirY(), -SunSource.dirZ(), 0f);
                GlslPrograms.drawFullscreen();
                SunProbe.end();
            }
        } finally {
            for (int unit = 11; unit <= 15; unit++) {
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
        GL20C.glDrawBuffers(DRAW_BUFFERS);
        if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            Stratos.LOGGER.error("Stratos cloud framebuffer incomplete; clouds off");
            compileFailed = true;
            return false;
        }
        GL11C.glViewport(0, 0, low.width(), low.height());
        return true;
    }

    private static void setCommon(Program p, PostPasses.Context ctx, Vec3 camera, StratosConfig.Quality quality,
                                  long frame, boolean temporal, boolean dh, MemoryStack stack) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        CloudStyle style = CloudStyle.current();
        float partial = ctx.partialTick();

        GL20C.glUniform1i(p.u(Program.DEPTH), 12);
        GL20C.glUniform1i(p.u(Program.SHAPE), 13);
        GL20C.glUniform1i(p.u(Program.DETAIL), 14);
        GL20C.glUniform1i(p.u(Program.WEATHER), 15);
        GL20C.glUniform1i(p.u(Program.DH), 11);
        GL20C.glUniformMatrix4fv(p.u(Program.INV_VIEW_PROJ), false, ctx.invViewProjection().get(stack.mallocFloat(16)));
        GL20C.glUniform4i(p.u(Program.INFO), lowDivisor, (int) (frame & 0xFFFF), quality.steps, quality.lightSteps);
        boolean detail = quality.detail && style.detail() > 0f;
        GL20C.glUniform4f(p.u(Program.FLAGS), detail ? 1f : 0f, temporal ? 1f : 0f, StratosConfig.debug.get(), dh ? 1f : 0f);
        int radius = StratosConfig.curvature.get();
        GL20C.glUniform4f(p.u(Program.CAMERA), (float) camera.y, StratosConfig.farDistance.get(),
                radius > 0 ? 0.5f / radius : 0f, CloudWeather.hazeDistance);
        LOOK.set(mc.gameRenderer.getMainCamera().getLookVector());
        GL20C.glUniform4f(p.u(Program.FORWARD), LOOK.x, LOOK.y, LOOK.z, 0f);

        boolean blocky = style.blockCell() > 0f;
        float thickness = blocky ? style.blockDepth() : CloudWeather.thickness;
        GL20C.glUniform4f(p.u(Program.DECK), CloudWeather.base, Math.max(8f, thickness), CloudWeather.coverage,
                style.extinction() * StratosConfig.density.get().floatValue());
        GL20C.glUniform4f(p.u(Program.TYPE), CloudWeather.stratiform, CloudWeather.towers, style.softness(), style.detail());
        GL20C.glUniform4f(p.u(Program.LAYERS), StratosConfig.midHeight.get(), StratosConfig.highHeight.get(),
                blocky ? 0f : CloudWeather.midCover, blocky ? 0f : CloudWeather.highCover);

        // Noise positions: world position minus drift, as a fraction of each tile (double precision here).
        double lowX = camera.x - CloudWeather.lowX;
        double lowZ = camera.z - CloudWeather.lowZ;
        double highX = camera.x - CloudWeather.highX;
        double highZ = camera.z - CloudWeather.highZ;
        float highSize = style.weatherSize() * 0.35f;
        GL20C.glUniform4f(p.u(Program.SHAPE_OFF), frac(lowX / style.shapeSize()), frac(lowZ / style.shapeSize()),
                frac(lowX / style.detailSize()), frac(lowZ / style.detailSize()));
        GL20C.glUniform4f(p.u(Program.WEATHER_OFF), frac(lowX / style.weatherSize()), frac(lowZ / style.weatherSize()),
                frac(highX / highSize), frac(highZ / highSize));
        GL20C.glUniform4f(p.u(Program.SCALE), 1f / style.shapeSize(), 1f / style.detailSize(), 1f / style.weatherSize(),
                1f / highSize);
        float cell = style.blockCell();
        GL20C.glUniform4f(p.u(Program.BLOCK), cell, style.blockDepth(),
                blocky ? (float) (lowX - Math.floor(lowX / cell) * cell) : 0f,
                blocky ? (float) (lowZ - Math.floor(lowZ / cell) * cell) : 0f);

        // Light: the sun by day, carrying on a little below the horizon (clouds stay lit after sunset), else the moon.
        float sx = SunSource.dirX();
        float sy = SunSource.dirY();
        float sz = SunSource.dirZ();
        float sun = smoothstep(-0.14f, 0.04f, sy);
        float rain = CloudWeather.rain;
        float overcast = 1f - 0.55f * rain - 0.2f * CloudWeather.thunder;
        float lr;
        float lg;
        float lb;
        if (sun > 0.02f) {
            float warm = smoothstep(-0.06f, 0.42f, sy);
            float intensity = 1.15f * sun * overcast;
            lr = intensity;
            lg = intensity * lerp(0.42f, 0.96f, warm);
            lb = intensity * lerp(0.2f, 0.9f, warm);
            GL20C.glUniform4f(p.u(Program.LIGHT_DIR), sx, sy, sz, 1f);
        } else {
            float moon = 0.22f * level.getMoonBrightness() * overcast;
            lr = 0.78f * moon;
            lg = 0.86f * moon;
            lb = 1.0f * moon;
            GL20C.glUniform4f(p.u(Program.LIGHT_DIR), -sx, -sy, -sz, 0f);
        }
        GL20C.glUniform4f(p.u(Program.LIGHT_COL), lr, lg, lb, style.forward());
        Vec3 sky = level.getSkyColor(camera, partial);
        float[] fog = RenderSystem.getShaderFogColor();
        GL20C.glUniform4f(p.u(Program.AMB_TOP), (float) sky.x * 0.9f + 0.015f, (float) sky.y * 0.9f + 0.017f,
                (float) sky.z * 0.9f + 0.02f, style.ambient());
        GL20C.glUniform4f(p.u(Program.AMB_BOTTOM), fog[0] * 0.5f, fog[1] * 0.5f, fog[2] * 0.52f, style.powder());
        GL20C.glUniform4f(p.u(Program.HAZE), fog[0], fog[1], fog[2], CloudWeather.darken);

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
        GL20C.glUniform4f(p.u(Program.TINT), tr, tg, tb, StratosConfig.brightness.get().floatValue());
    }

    private static boolean compile() {
        String header = GlslPrograms.header() + DhBridge.GLSL;
        march = Program.of("Stratos cloud march", header + CloudGlsl.MARCH);
        resolve = Program.of("Stratos cloud resolve", GlslPrograms.header()
                + CloudGlsl.RESOLVE.replace("%REPROJECT%", PostPasses.REPROJECT_GLSL),
                "u_Low", "u_LowDepth", "u_History", "u_InvViewProj", "u_Info");
        composite = Program.of("Stratos cloud composite", GlslPrograms.header() + CloudGlsl.COMPOSITE,
                "u_Clouds", "u_LowDepth", "u_Depth", "u_InvViewProj", "u_Params");
        probe = Program.of("Stratos sun cover", GlslPrograms.header() + CloudGlsl.PROBE);
        if (march == null || resolve == null || composite == null || probe == null) {
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
            low = null;
            lowDepth = null;
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
        for (Program p : new Program[] {march, resolve, composite, probe}) {
            if (p != null) {
                com.solarbiscuit.atmosphera.lib.gpu.GpuResources.deleteProgram(p.id());
            }
        }
        march = resolve = composite = probe = null;
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
                "u_Tint", "u_Probe0", "u_Probe1"};
        static final int DEPTH = 0, SHAPE = 1, DETAIL = 2, WEATHER = 3, DH = 4, INV_VIEW_PROJ = 5, INFO = 6, FLAGS = 7,
                CAMERA = 8, FORWARD = 9, DECK = 10, TYPE = 11, LAYERS = 12, SHAPE_OFF = 13, WEATHER_OFF = 14, SCALE = 15,
                BLOCK = 16, LIGHT_DIR = 17, LIGHT_COL = 18, AMB_TOP = 19, AMB_BOTTOM = 20, HAZE = 21, TINT = 22,
                PROBE0 = 23, PROBE1 = 24;

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
