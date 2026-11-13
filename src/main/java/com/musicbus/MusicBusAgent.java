package com.musicbus;

import com.musicbus.core.MusicBusLog;
import com.musicbus.core.MusicBusTransformer;

import java.lang.instrument.Instrumentation;

public final class MusicBusAgent {

    private static Instrumentation instrumentation;

    private MusicBusAgent() {}

    public static void premain(
            String agentArgs,
            Instrumentation inst
    ) {
        instrumentation = inst;

        MusicBusLog.log("Agent loaded.");
        MusicBusLog.log("Java: " + System.getProperty("java.version"));
        MusicBusLog.log("System ClassLoader: "
        + ClassLoader.getSystemClassLoader());

        //inst.addTransformer(new MusicBusTransformer(), false);

        MusicBusLog.log("Transformer registered.");

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
