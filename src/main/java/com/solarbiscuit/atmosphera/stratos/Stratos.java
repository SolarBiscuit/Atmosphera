package com.solarbiscuit.atmosphera.stratos;

import com.solarbiscuit.atmosphera.stratos.cloud.CloudPass;
import com.solarbiscuit.atmosphera.stratos.cloud.CloudStyle;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stratos: clouds (and, on the roadmap, the sky) for the Atmosphera suite. Everything goes through the AtmospheraLib
 * API: the config and options page, one cloud feature drawn as a post pass at the lib's cloud stage, and the lib's
 * cloud cover service, through which Lumis and other mods learn where the clouds are. Client-only; on a dedicated
 * server it does nothing.
 * <p>
 * Copyright (C) 2026 SolarBiscuit. All Rights Reserved (see LICENSE).
 */
@Mod(Stratos.MOD_ID)
public final class Stratos {
    public static final String MOD_ID = "stratos";
    public static final Logger LOGGER = LoggerFactory.getLogger("Stratos");

    public Stratos() {
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            LOGGER.info("Stratos is client-only; inactive on this dedicated server.");
            return;
        }
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        StratosConfig.register();
        CloudPass.register();
        modBus.addListener((RegisterClientReloadListenersEvent e) -> e.registerReloadListener(new CloudStyle.Loader()));
    }
}
