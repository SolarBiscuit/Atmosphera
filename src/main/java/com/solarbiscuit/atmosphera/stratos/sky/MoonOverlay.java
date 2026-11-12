package com.solarbiscuit.atmosphera.stratos.sky;

import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.gpu.GlStateSnapshot;
import com.solarbiscuit.atmosphera.lib.gpu.GlslPrograms;
import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.lib.gpu.PostPasses;
import com.solarbiscuit.atmosphera.lib.gpu.RenderTargets;
import com.solarbiscuit.atmosphera.lib.world.SunSource;
import com.solarbiscuit.atmosphera.stratos.Stratos;
import com.solarbiscuit.atmosphera.stratos.StratosConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.system.MemoryStack;

/**
 * 0.9.0 "Omens": the blood moon on a skybox's moon (Dramatic Skies and other Nuit skies), for when Stratos's own sky
 * is off and a skybox draws the moon. Stratos cannot reach into the skybox's texture, so it multiplies the sky's
 * colour round the moon's direction by the eclipse's tint: a soft disc, 7 degrees in radius, at the lib's SKY stage
 * (after the skybox, before the terrain). Vanilla's own moon is tinted by {@code LevelRendererMoonMixin} instead, and
 * Stratos's moon in its sky program. A feature of its own, so it runs while the sky feature is off; it draws only
 * during an eclipse.
 */
public final class MoonOverlay {
    private static int program;
    private static int[] u;
    private static boolean failed;
    private static final float[] TINT = new float[3];

    private MoonOverlay() {
    }

    public static void register() {
        AtmosFeature feature = AtmosFeature.builder(Stratos.MOD_ID, "omens")
                .uses(AtmosFeature.Uses.WORLD_PASS)
                .enabledWhen(() -> StratosConfig.bloodMoon.get())
                .register();
        PostPasses.register(feature, PostPasses.Stage.SKY, 10, "stratos_moon_tint", MoonOverlay::render);
    }

    private static void render(PostPasses.Context ctx) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || failed || SkyPass.drawn() || !SunSource.fromSkybox()
                || level.effects().skyType() != DimensionSpecialEffects.SkyType.NORMAL) {
            return;
        }
        SkyEvents.ensure(level, ctx.partialTick());
        if (!SkyEvents.discTint(TINT)) {
            return;
        }
        RenderTargets.Scene scene = RenderTargets.scene();
        if (!scene.usable() || program == 0 && !compile()) {
            return;
        }
        GlStateSnapshot saved = GlStateSnapshot.save();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!RenderTargets.bindColorOnly(scene)) {
                return;
            }
            GL20C.glUseProgram(program);
            GL20C.glUniformMatrix4fv(u[0], false, ctx.invViewProjection().get(stack.mallocFloat(16)));
            GL20C.glUniform4f(u[1], -SunSource.dirX(), -SunSource.dirY(), -SunSource.dirZ(), 0f);
            GL20C.glUniform4f(u[2], TINT[0], TINT[1], TINT[2], 0f);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthMask(false);
            GL11C.glEnable(GL11C.GL_BLEND);
            // scene * tint.
            GL14C.glBlendFuncSeparate(GL11C.GL_DST_COLOR, GL11C.GL_ZERO, GL11C.GL_ZERO, GL11C.GL_ONE);
            GlslPrograms.drawFullscreen();
            GL11C.glDisable(GL11C.GL_BLEND);
        } finally {
            saved.restore();
        }
    }

    private static boolean compile() {
        program = GlslPrograms.compile("Stratos moon tint", GlslPrograms.FULLSCREEN_VERTEX, GlslPrograms.header() + TINT_GLSL);
        if (program == 0) {
            failed = true;
            return false;
        }
        u = new int[] {GL20C.glGetUniformLocation(program, "u_InvViewProj"), GL20C.glGetUniformLocation(program, "u_Moon"),
                GL20C.glGetUniformLocation(program, "u_Tint")};
        return true;
    }

    /** Frees the program (the lib's shader reload). */
    public static void release() {
        if (program != 0) {
            GpuResources.deleteProgram(program);
            program = 0;
        }
        failed = false;
    }

    static final String TINT_GLSL = """
            uniform mat4 u_InvViewProj;
            uniform vec4 u_Moon;  // xyz towards the moon
            uniform vec4 u_Tint;  // rgb the eclipse's tint
            in vec2 v_Uv;
            out vec4 fragColor;

            void main() {
                vec4 far = u_InvViewProj * vec4(v_Uv * 2.0 - 1.0, 1.0, 1.0);
                float c = dot(normalize(far.xyz / far.w), u_Moon.xyz);
                // Full within 7 degrees of the moon's centre, gone by 10.
                float mask = smoothstep(0.9848, 0.9925, c);
                if (mask <= 0.0) {
                    discard;
                }
                fragColor = vec4(mix(vec3(1.0), u_Tint.rgb, mask), 1.0);
            }
            """;
}
