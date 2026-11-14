package com.musicbus;

import com.musicbus.core.MusicBusManager;

import java.lang.instrument.Instrumentation;

public final class MusicBusAgent {

    private static Instrumentation instrumentation;

    private MusicBusAgent() {}

    public static void premain(
            String agentArgs,
            Instrumentation inst
    ) {

        instrumentation = inst;

        String version = MusicBusAgent.class.getPackage().getImplementationVersion();
        if (version == null) {
            version = "DEV-MODE";
        }

        log("=================================");
        log("MusicBus Java Agent loaded!");
        log("Version: " + version);
        log("Java: " + System.getProperty("java.version"));
        log("OS: " + System.getProperty("os.name"));
        log("=================================");

        // Initialize the central MusicBus manager.
        MusicBusManager.initialize(inst);

        Runtime.getRuntime().addShutdownHook(
                new Thread(
                        () -> log("MusicBus shutting down."),
                        "MusicBus-Shutdown"
                )
        );
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    private static void log(String message) {
        System.out.println("[MusicBus] " + message);
    }
}
