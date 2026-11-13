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
        });
    }
}
