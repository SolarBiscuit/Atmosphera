package com.solarbiscuit.atmosphera.atmosphera.sky;

import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.gpu.GlStateSnapshot;
import com.solarbiscuit.atmosphera.lib.gpu.GlslPrograms;
import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.lib.gpu.GpuTimers;
import com.solarbiscuit.atmosphera.lib.gpu.PostPasses;
import com.solarbiscuit.atmosphera.lib.gpu.RenderTargets;
import com.solarbiscuit.atmosphera.lib.client.AtmosClient;
import com.solarbiscuit.atmosphera.lib.world.SunSource;
import com.solarbiscuit.atmosphera.atmosphera.Atmosphera;
import com.solarbiscuit.atmosphera.atmosphera.cloud.CloudPass;
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
 * Rainbows, moonbows and fogbows (0.7.0 "Optics"): light bent back by drops in the air between the eye and the scene,
 * so they are drawn over the clouds and the far terrain, not in the sky behind them. One full-screen pass at the lib's
 * CLOUDS stage, after the clouds, and only while a bow shows (rare); it costs nothing otherwise.
 * <p>
 * The primary bow lies 42 degrees from the point opposite the sun, red outside; the fainter secondary at 51 degrees
 * with its colours reversed; the sky is a little brighter inside the primary and darker between the two (Alexander's
 * band). A moonbow is the same by a bright moon, nearly white. A fogbow is broad and white, from the fine drops of
 * mist. Each fades where the scene behind is near, for want of drops in front of it.
 */
public final class BowPass {
    /** 0.14.2: the shared post-program holder (AtmospheraLib 0.11.0). */
    private static final com.solarbiscuit.atmosphera.lib.gpu.PostProgram PROGRAM = new com.solarbiscuit.atmosphera.lib.gpu.PostProgram(Atmosphera.LOGGER, "Atmosphera bows", "Atmosphera rainbows",
            () -> GlslPrograms.FULLSCREEN_VERTEX, () -> GlslPrograms.header() + BowPass.BOWS,
            "u_Depth", "u_InvViewProj", "u_Bow", "u_Bow2");

    private BowPass() {
    }

    static void register(AtmosFeature sky) {
        PostPasses.register(sky, PostPasses.Stage.CLOUDS, 10, "atmosphera_bows", BowPass::render);
    }

    private static void render(PostPasses.Context ctx) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || PROGRAM.failed() || Minecraft.useShaderTransparency()
                || level.effects().skyType() != DimensionSpecialEffects.SkyType.NORMAL
                || mc.gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }
        SkyEvents.ensure(level, ctx.partialTick());
        float rainbow = SkyEvents.strength(SkyEvents.Effect.RAINBOW);
        float moonbow = SkyEvents.strength(SkyEvents.Effect.MOONBOW);
        float fogbow = SkyEvents.strength(SkyEvents.Effect.FOGBOW);
        if (rainbow <= 0f && moonbow <= 0f && fogbow <= 0f) {
            return;
        }
        RenderTargets.Scene scene = RenderTargets.scene();
        if (!scene.usable() || !PROGRAM.ready()) {
            return;
        }
        // A bow needs its light to reach the drops: under a closed deck there is none. A forced bow ignores that.
        boolean byMoon = moonbow > rainbow;
        float cover = CloudPass.drawing() ? CloudPass.sunCover() : 0f;
        boolean forced = SkyEvents.forced(byMoon ? SkyEvents.Effect.MOONBOW : SkyEvents.Effect.RAINBOW) == SkyEvents.ON;
        float lit = forced ? 1f : 1f - 0.85f * cover;
        float sign = byMoon ? 1f : -1f;
        FrameUniforms.update(AtmosClient.frame());
        GlStateSnapshot saved = GlStateSnapshot.save();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!RenderTargets.bindColorOnly(scene)) {
                return;
            }
            GL13C.glActiveTexture(GL13C.GL_TEXTURE12);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, scene.depthTexture());
            GL20C.glUseProgram(PROGRAM.id());
            GL20C.glUniform1i(PROGRAM.u(0), 12);
            GL20C.glUniformMatrix4fv(PROGRAM.u(1), false, ctx.invViewProjection().get(stack.mallocFloat(16)));
            // The bow's axis points away from its light: from the sun by day, from the moon (opposite the sun) by night.
            GL20C.glUniform4f(PROGRAM.u(2), SunSource.dirX() * sign, SunSource.dirY() * sign, SunSource.dirZ() * sign,
                    (byMoon ? moonbow * 0.4f : rainbow) * lit);
            GL20C.glUniform4f(PROGRAM.u(3), byMoon ? 0.25f : 1f, fogbow * (1f - 0.85f * cover), 0f, 0f);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthMask(false);
            GL11C.glEnable(GL11C.GL_BLEND);
            // scene * (1 - a) + light: the bows add light, Alexander's band takes a little away.
            GL14C.glBlendFuncSeparate(GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ZERO, GL11C.GL_ONE);
            GpuTimers.begin("atmosphera_bows");
            GlslPrograms.drawFullscreen();
            GpuTimers.end("atmosphera_bows");
            GL11C.glDisable(GL11C.GL_BLEND);
        } finally {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE12);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            saved.restore();
        }
    }

    /** Frees the program (the lib's shader reload); the next bow rebuilds it. */
    public static void release() {
        PROGRAM.release();
    }

    static final String BOWS = """
            uniform sampler2D u_Depth;
            uniform mat4 u_InvViewProj;
            uniform vec4 u_Bow;   // xyz the bow's axis (away from the sun or moon), w rainbow strength
            uniform vec4 u_Bow2;  // x colour (1 a rainbow, towards 0 a white moonbow), y fogbow strength
            in vec2 v_Uv;
            out vec4 fragColor;

            // Pure colours from violet (0) to red (1).
            vec3 bow_spectrum(float t) {
                float h = 0.75 * (1.0 - t);
                return clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
            }

            void main() {
                vec4 far = u_InvViewProj * vec4(v_Uv * 2.0 - 1.0, 1.0, 1.0);
                vec3 dir = normalize(far.xyz / far.w);
                float c = dot(dir, u_Bow.xyz);
                // Only 33 to 56 degrees from the axis.
                if (c > 0.84 || c < 0.55) {
                    discard;
                }
                float a = degrees(acos(c));
                float depth = texture(u_Depth, v_Uv).r;
                float dist = 1e6;
                if (depth < 1.0) {
                    vec4 rel = u_InvViewProj * vec4(v_Uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                    dist = length(rel.xyz / rel.w);
                }
                // The drops between the eye and what lies behind: none in front of near things.
                float air = smoothstep(48.0, 320.0, dist);
                vec3 col = vec3(0.0);
                float dim = 0.0;
                if (u_Bow.w > 0.0) {
                    float t1 = (a - 40.4) / 2.0;   // primary: violet at 40.4 degrees to red at 42.4
                    float w1 = smoothstep(-0.25, 0.1, t1) * (1.0 - smoothstep(0.9, 1.25, t1));
                    float t2 = (53.6 - a) / 3.2;   // secondary: red at 50.4 to violet at 53.6
                    float w2 = smoothstep(-0.25, 0.1, t2) * (1.0 - smoothstep(0.9, 1.25, t2));
                    vec3 bow = bow_spectrum(clamp(t1, 0.0, 1.0)) * (w1 * 0.26) + bow_spectrum(clamp(t2, 0.0, 1.0)) * (w2 * 0.09);
                    bow = mix(vec3(dot(bow, vec3(0.4))), bow, u_Bow2.x);
                    // Brighter inside the primary; Alexander's dark band between the two.
                    float inner = smoothstep(34.0, 40.4, a) * (1.0 - smoothstep(40.4, 40.9, a)) * 0.04;
                    float band = smoothstep(42.4, 43.4, a) * (1.0 - smoothstep(49.4, 50.4, a));
                    col += (bow + inner) * u_Bow.w;
                    dim += band * 0.1 * u_Bow.w;
                }
                if (u_Bow2.y > 0.0) {
                    // The fogbow: broad and white, a trace of red outside and blue inside.
                    float t = (a - 34.5) / 8.0;
                    float w = smoothstep(0.0, 0.45, t) * (1.0 - smoothstep(0.6, 1.0, t));
                    col += mix(vec3(0.85, 0.92, 1.0), vec3(1.0, 0.93, 0.86), smoothstep(0.3, 0.8, t)) * (w * 0.3 * u_Bow2.y);
                }
                fragColor = vec4(col * air, dim * air);
            }
            """;
}
