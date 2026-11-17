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
        convertSkyBrightness();
        AtmospheraConfig.register();
        CloudPass.register();
        SkyPass.register();
        // 0.14.1: the left world is let go at once (lib 0.10.3 hook).
        com.solarbiscuit.atmosphera.lib.client.AtmosClient.onLeaveLevel(() -> {
            com.solarbiscuit.atmosphera.atmosphera.cloud.CloudWeather.forgetLevel();
            com.solarbiscuit.atmosphera.atmosphera.sky.SkyEvents.forgetLevel();
        });
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
            e.registerReloadListener(new com.solarbiscuit.atmosphera.atmosphera.sky.NightSky.Loader());
        });
    }

    /**
     * 0.12.1: the sky's exposure at 100% is 14 (was 32). A file written before 0.12.1 (no {@code brightness_scale}
     * key) keeps its look: a changed sky brightness v (in the [sky] section only; the clouds' own "brightness" is in
     * [view]) becomes v * 32 / 14 (at most 400%). The default 100% stays 100%, the new, darker default.
     */
    private static void convertSkyBrightness() {
        // 0.14.2: AtmospheraLib's one-time rewrite (0.11.0).
        com.solarbiscuit.atmosphera.lib.options.ConfigMigration.Change change =
                com.solarbiscuit.atmosphera.lib.options.ConfigMigration.rewrite(MOD_ID + "-client.toml", "brightness_scale", "sky",
                        java.util.regex.Pattern.compile("(?m)^(\\s*brightness\\s*=\\s*)([0-9.eE+-]+)"), value -> {
                            double old = Double.parseDouble(value);
                            if (Math.abs(old - 1.0) < 1e-6) {
                                return null;
                            }
                            double now = Math.min(4.0, old * com.solarbiscuit.atmosphera.atmosphera.sky.SkyPass.OLD_EXPOSURE / 14.0);
                            return String.format(java.util.Locale.ROOT, "%.3f", now);
                        }, LOGGER, "sky brightness");
        if (change != null) {
            LOGGER.info("[Atmosphera] Atmosphera 0.12.1 rescaled the sky brightness: {} is now {} (same look).",
                    change.before(), change.after());
        }
    }

    /**
     * 0.12.0: the star amount's 100% is what 30% was before. A file written before 0.12.0 (no {@code star_scale} key)
     * keeps the look it had: a changed amount v becomes v / 0.3 (at most 300%); the default 100% stays 100%, which is
     * the new, sparser default. Runs once, before Forge reads the file; Forge then adds {@code star_scale = 2}.
     */
    private static void convertStarAmount() {
        // 0.14.2: AtmospheraLib's one-time rewrite (0.11.0).
        com.solarbiscuit.atmosphera.lib.options.ConfigMigration.Change change =
                com.solarbiscuit.atmosphera.lib.options.ConfigMigration.rewrite(MOD_ID + "-client.toml", "star_scale", null,
                        java.util.regex.Pattern.compile("(?m)^(\\s*star_amount\\s*=\\s*)([0-9.eE+-]+)"), value -> {
                            double old = Double.parseDouble(value);
                            if (Math.abs(old - 1.0) < 1e-6) {
                                return null;
                            }
                            return String.format(java.util.Locale.ROOT, "%.3f", Math.min(3.0, old / 0.3));
                        }, LOGGER, "star amount");
        if (change != null) {
            LOGGER.info("[Atmosphera] Atmosphera 0.12.0 rescaled the star amount: {} is now {} (same number of stars).",
                    change.before(), change.after());
        }
    }
}
