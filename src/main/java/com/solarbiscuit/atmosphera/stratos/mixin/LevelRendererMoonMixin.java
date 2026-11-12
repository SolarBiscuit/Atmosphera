package com.solarbiscuit.atmosphera.stratos.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.solarbiscuit.atmosphera.stratos.sky.SkyEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 0.9.0 "Omens": vanilla's moon during a lunar eclipse, for when Stratos's own sky is off. Vanilla reads the moon's
 * phase right before it draws the moon, with the shader colour set to white (alpha fading in rain); this tints that
 * colour copper red. Vanilla resets the colour itself after the stars. Optional ({@code require = 0}): a mod that
 * replaces {@code renderSky} simply loses the tint.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMoonMixin {
    private static final float[] STRATOS$TINT = new float[3];

    @Inject(method = "renderSky", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMoonPhase()I"), require = 0)
    private void stratos$bloodMoon(PoseStack pose, Matrix4f projection, float partialTick, Camera camera, boolean foggy,
                                   Runnable fogSetup, CallbackInfo ci) {
        SkyEvents.ensure(Minecraft.getInstance().level, partialTick);
        if (SkyEvents.discTint(STRATOS$TINT)) {
            float[] colour = RenderSystem.getShaderColor();
            RenderSystem.setShaderColor(colour[0] * STRATOS$TINT[0], colour[1] * STRATOS$TINT[1],
                    colour[2] * STRATOS$TINT[2], colour[3]);
        }
    }
}
