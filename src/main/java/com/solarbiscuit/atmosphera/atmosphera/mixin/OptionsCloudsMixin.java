package com.solarbiscuit.atmosphera.atmosphera.mixin;

import com.solarbiscuit.atmosphera.atmosphera.cloud.CloudPass;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hides vanilla's (and Xenon's) clouds while Atmosphera draws its own: the effective cloud setting reads OFF, so
 * {@code LevelRenderer} never calls {@code renderClouds}, whichever renderer would draw them. The option itself (what
 * Video Settings shows and saves) is untouched, and so is anything a shaderpack sets: Atmosphera is off under one.
 */
@Mixin(Options.class)
public abstract class OptionsCloudsMixin {
    @Inject(method = "getCloudsType", at = @At("HEAD"), cancellable = true)
    private void atmosphera$hideVanillaClouds(CallbackInfoReturnable<CloudStatus> cir) {
        if (CloudPass.drawing()) {
            cir.setReturnValue(CloudStatus.OFF);
        }
    }
}
