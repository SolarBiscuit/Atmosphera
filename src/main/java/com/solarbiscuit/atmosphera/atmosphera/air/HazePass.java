package com.solarbiscuit.atmosphera.atmosphera.air;

import com.mojang.blaze3d.systems.RenderSystem;
import com.solarbiscuit.atmosphera.lib.client.AtmosClient;
import com.solarbiscuit.atmosphera.lib.compat.dh.DhBridge;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.gpu.GlStateSnapshot;
import com.solarbiscuit.atmosphera.lib.gpu.GlslPrograms;
import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.lib.gpu.PostPasses;
import com.solarbiscuit.atmosphera.lib.gpu.RenderTargets;
import com.solarbiscuit.atmosphera.atmosphera.Atmosphera;
import com.solarbiscuit.atmosphera.atmosphera.AtmospheraConfig;
import com.solarbiscuit.atmosphera.atmosphera.cloud.CloudWeather;
import com.solarbiscuit.atmosphera.atmosphera.sky.SkyPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.world.level.material.FogType;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.system.MemoryStack;

/**
 * Distant haze (0.6.0 "Air"): aerial perspective over the terrain, LOD terrain (NeoVoxy/Voxy in the scene depth,
 * Distant Horizons through the lib's DhBridge) and everything else that is not sky. One full-resolution pass at the
 * lib's CLOUDS stage, before the clouds: {@code scene * T + haze * (1 - T)}, with T falling with distance beyond 96
 * blocks (half at a couple of kilometres in clear weather, much nearer in rain), denser low down. The haze colour is
 * the drawn sky's horizon in the view's direction (Atmosphera's sky-view table, so it glows towards the sun) mixed with
 * the fog colour (the biome's air); without Atmosphera's sky, the fog colour. Vanilla's fog still ends the render
 * distance; the haze reaches the far terrain vanilla fog never touches.
 */
public final class HazePass {
    private static AtmosFeature feature;
    private static int program;
    private static int[] u;
    private static boolean failed;

    private HazePass() {
    }

    static void register(AtmosFeature air) {
        feature = air;
        PostPasses.register(air, PostPasses.Stage.CLOUDS, -10, "atmosphera_haze", HazePass::render);
    }

    private static void render(PostPasses.Context ctx) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        float amount = AtmospheraConfig.haze.get().floatValue();
        if (level == null || failed || amount <= 0f || Minecraft.useShaderTransparency()
                || level.effects().skyType() != DimensionSpecialEffects.SkyType.NORMAL
                || mc.gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }
        RenderTargets.Scene scene = RenderTargets.scene();
        if (!scene.usable()) {
            return;
        }
        if (program == 0 && !compile()) {
            return;
        }
        long frame = AtmosClient.frame();
        FrameUniforms.update(frame);
        float partial = ctx.partialTick();
        float thunder = level.getThunderLevel(partial);
        float rain = CloudWeather.wetness(level.getRainLevel(partial), thunder);
        // Distance (beyond the start) at which half the light is lost.
        float half = 2600f / Math.max(0.05f, amount * AirFog.hazeScale() * AirFog.stormHazeScale(rain))
                * (1f - 0.55f * rain - 0.15f * thunder);
        int dh = AtmospheraConfig.dhDepth.get() ? DhBridge.depthTexture(frame) : 0;
        GlStateSnapshot saved = GlStateSnapshot.save();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!RenderTargets.bindColorOnly(scene)) {
                return;
            }
            bind(12, scene.depthTexture());
            bind(11, dh);
            int lut = SkyPass.horizonLut();
            bind(13, lut);
            GL20C.glUseProgram(program);
            GL20C.glUniform1i(u[0], 12);
            GL20C.glUniform1i(u[1], 11);
            GL20C.glUniform1i(u[2], 13);
            GL20C.glUniformMatrix4fv(u[3], false, ctx.invViewProjection().get(stack.mallocFloat(16)));
            GL20C.glUniform4f(u[4], half, 96f, lut != 0 ? 1f : 0f, SkyPass.horizonExposure());
            GL20C.glUniform4f(u[5], SkyPass.sunAzimuth(), SkyPass.horizonAngle(), (float) ctx.camera().y, dh != 0 ? 1f : 0f);
            float[] fog = RenderSystem.getShaderFogColor();
            GL20C.glUniform4f(u[6], fog[0], fog[1], fog[2], 0f);
            var look = mc.gameRenderer.getMainCamera().getLookVector();
            GL20C.glUniform4f(u[7], look.x(), look.y(), look.z(), 0f);
            GL11C.glEnable(GL11C.GL_BLEND);
            GL14C.glBlendFuncSeparate(GL11C.GL_ONE, GL11C.GL_SRC_ALPHA, GL11C.GL_ZERO, GL11C.GL_ONE);
            GlslPrograms.drawFullscreen();
            GL11C.glDisable(GL11C.GL_BLEND);
        } finally {
            for (int unit = 11; unit <= 13; unit++) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            }
            saved.restore();
        }
    }

    private static void bind(int unit, int texture) {
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
    }

    private static boolean compile() {
        program = GlslPrograms.compile("Atmosphera haze", GlslPrograms.FULLSCREEN_VERTEX,
                GlslPrograms.header() + DhBridge.GLSL + HAZE);
        if (program == 0) {
            failed = true;
            Atmosphera.LOGGER.error("[Atmosphera] Atmosphera haze is off: its program did not compile (see above)");
            return false;
        }
        String[] names = {"u_Depth", "atmos_DhDepth", "u_SkyView", "u_InvViewProj", "u_Haze", "u_Sky", "u_FogCol",
                "u_Forward"};
        u = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            u[i] = GL20C.glGetUniformLocation(program, names[i]);
        }
        return true;
    }

    /** Frees the program (the lib's shader reload); the next frame rebuilds it. */
    public static void release() {
        if (program != 0) {
            GpuResources.deleteProgram(program);
            program = 0;
        }
        failed = false;
    }

    static final String HAZE = """
            uniform sampler2D u_Depth;
            uniform sampler2D u_SkyView;
            uniform mat4 u_InvViewProj;
            uniform vec4 u_Haze;     // x half distance, y start, z 1 = sky table bound, w exposure
            uniform vec4 u_Sky;      // x sun azimuth, y horizon elevation, z camera y, w 1 = DH depth bound
            uniform vec4 u_FogCol;   // rgb fog colour (the biome's air)
            uniform vec4 u_Forward;  // xyz camera look direction
            in vec2 v_Uv;
            out vec4 fragColor;

            void main() {
                float depth = texture(u_Depth, v_Uv).r;
                vec4 far = u_InvViewProj * vec4(v_Uv * 2.0 - 1.0, 1.0, 1.0);
                vec3 dir = normalize(far.xyz / far.w);
                float dist = 1e9;
                if (depth < 1.0) {
                    vec4 rel = u_InvViewProj * vec4(v_Uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                    dist = length(rel.xyz / rel.w);
                }
                if (u_Sky.w > 0.5) {
                    float dh = atmos_dhDistance(v_Uv);
                    if (dh < 1e8) {
                        dist = min(dist, dh / max(dot(dir, u_Forward.xyz), 1e-3));
                    }
                }
                if (dist > 1e8 || dist < u_Haze.y) {
                    discard;
                }
                // Denser low down: the average height along the ray, a 900-block scale height above the sea.
                float midY = u_Sky.z + dir.y * dist * 0.5;
                float density = exp(-max(midY - 63.0, 0.0) / 900.0);
                float T = exp(-0.6931472 * (dist - u_Haze.y) / u_Haze.x * density);
                vec3 haze = u_FogCol.rgb;
                if (u_Haze.z > 0.5) {
                    // The drawn sky just above the horizon, in this azimuth (the sky-view table, tonemapped as drawn).
                    float az = atan(dir.z, dir.x) - u_Sky.x;
                    float lat = clamp((0.035 - u_Sky.y) / 1.5707963, 0.0, 1.0);
                    vec3 lum = textureLod(u_SkyView, vec2(fract(az / 6.2831853), 0.5 + 0.5 * sqrt(lat)), 0.0).rgb;
                    vec3 sky = pow(vec3(1.0) - exp(-lum * u_Haze.w), vec3(1.0 / 2.2));
                    haze = mix(u_FogCol.rgb, sky, 0.5);
                }
                fragColor = vec4(haze * (1.0 - T), T);
            }
            """;
}
