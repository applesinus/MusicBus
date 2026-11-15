package com.musicbus.fabric;

import com.musicbus.core.MusicBusManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class MusicBusFabricEntrypoint implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        if (Boolean.getBoolean("musicbus.agent.active")) {
            log("Java Agent already active; skipping Fabric preLaunch initialization.");
            return;
        }

        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            return;
        }

        log("Initializing MusicBus via Fabric preLaunch entrypoint...");
        MusicBusManager.initialize();
    }

    private static void log(String message) {
        System.out.println("[MusicBus] " + message);
    }
}