package com.solarbiscuit.atmosphera.atmosphera;

import com.solarbiscuit.atmosphera.atmosphera.cloud.CloudPass;
import com.solarbiscuit.atmosphera.atmosphera.cloud.CloudStyle;
import com.solarbiscuit.atmosphera.atmosphera.sky.SkyPass;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Atmosphera: the sky and clouds for the Atmosphera suite. Everything goes through the AtmospheraLib API: the config and
 * options page, a cloud feature drawn as a post pass at the lib's cloud stage, a sky feature at its sky stage (0.3.0),
 * and the lib's cloud cover and sky services, through which Lumis and other mods learn where the clouds are and what
 * the sky looks like. Client-only; on a dedicated server it does nothing.
 * <p>
 * Copyright (C) 2026 SolarBiscuit. All Rights Reserved (see LICENSE).
 */
@Mod(Atmosphera.MOD_ID)
public final class Atmosphera {
    public static final String MOD_ID = "atmosphera";
    public static final Logger LOGGER = LoggerFactory.getLogger("Atmosphera");

    public Atmosphera() {
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            LOGGER.info("Atmosphera is client-only; inactive on this dedicated server.");
            return;
        }
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        convertStarAmount();
        AtmospheraConfig.register();
        CloudPass.register();
        SkyPass.register();
        com.solarbiscuit.atmosphera.atmosphera.air.AirFog.register();
        // 0.9.0: the blood moon on skybox moons; 0.7.0: /atmosphera skyeffect (a client command).
        com.solarbiscuit.atmosphera.atmosphera.sky.MoonOverlay.register();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.client.event.RegisterClientCommandsEvent e) -> AtmospheraCommand.register(e.getDispatcher()));
        // 0.6.0: /atmosphera reload shaders (and the reload key) rebuilds Atmosphera's programs and tables too.
        com.solarbiscuit.atmosphera.lib.options.AtmosReload.register(MOD_ID, () -> {
            CloudPass.release();
            SkyPass.release();
            com.solarbiscuit.atmosphera.atmosphera.air.HazePass.release();
            com.solarbiscuit.atmosphera.atmosphera.sky.BowPass.release();
            com.solarbiscuit.atmosphera.atmosphera.sky.MoonOverlay.release();
        });
        modBus.addListener((RegisterClientReloadListenersEvent e) -> {
            e.registerReloadListener(new CloudStyle.Loader());
            e.registerReloadListener(new com.solarbiscuit.atmosphera.atmosphera.air.AirData.Loader());
            e.registerReloadListener(new com.solarbiscuit.atmosphera.atmosphera.sky.NebulaImages.Loader());
            e.registerReloadListener(new com.solarbiscuit.atmosphera.atmosphera.sky.MoonMap.Loader());
        });
    }

    /**
     * 0.12.0: the star amount's 100% is what 30% was before. A file written before 0.12.0 (no {@code star_scale} key)
     * keeps the look it had: a changed amount v becomes v / 0.3 (at most 300%); the default 100% stays 100%, which is
     * the new, sparser default. Runs once, before Forge reads the file; Forge then adds {@code star_scale = 2}.
     */
    private static void convertStarAmount() {
        java.nio.file.Path file = com.solarbiscuit.atmosphera.lib.options.AtmosPaths.file(MOD_ID + "-client.toml");
        try {
            if (!java.nio.file.Files.exists(file)) {
                return;
            }
            String text = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
            if (text.contains("star_scale")) {
                return;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?m)^(\\s*star_amount\\s*=\\s*)([0-9.eE+-]+)").matcher(text);
            if (!m.find()) {
                return;
            }
            double old = Double.parseDouble(m.group(2));
            if (Math.abs(old - 1.0) < 1e-6) {
                return;
            }
            double now = Math.min(3.0, old / 0.3);
            String converted = text.substring(0, m.start(2)) + String.format(java.util.Locale.ROOT, "%.3f", now)
                    + text.substring(m.end(2));
            java.nio.file.Files.writeString(file, converted, java.nio.charset.StandardCharsets.UTF_8);
            LOGGER.info("[Atmosphera] Atmosphera 0.12.0 rescaled the star amount: {} is now {} (same number of stars).",
                    old, now);
        } catch (java.io.IOException | RuntimeException e) {
            LOGGER.warn("[Atmosphera] Could not convert the star amount in {}: {}", file, e.toString());
        }
    }
}
