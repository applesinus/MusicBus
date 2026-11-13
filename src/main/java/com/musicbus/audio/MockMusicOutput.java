package com.musicbus.audio;

public final class MockMusicOutput {

    private static Object currentInstance;

    private MockMusicOutput() {}

    /**
     * Accepts a music instance into the mock output.
     *
     * @return true if the instance changed.
     */
    public static synchronized boolean play(Object instance) {

        if (instance == null) {
            return false;
        }

        if (currentInstance == instance) {
            return false;
        }

        currentInstance = instance;

        log("Mock output accepted music instance: "
                + instance.getClass().getSimpleName());

        return true;
    }

    /**
     * Returns whether the given instance is active
     * in the mock output.
     */
    public static synchronized boolean isActive(Object instance) {
        return currentInstance == instance && instance != null;
    }

    /**
     * Stops the given instance.
     *
     * @return true if an active instance was stopped.
     */
    public static synchronized boolean stop(Object instance) {

        if (currentInstance != instance || instance == null) {
            return false;
        }

        currentInstance = null;

        log("Mock output stopped.");

        return true;
    }

    public static synchronized Object getCurrentInstance() {
        return currentInstance;
    }

    private static void log(String message) {
        System.out.println("[MusicBus] " + message);
    }
}
