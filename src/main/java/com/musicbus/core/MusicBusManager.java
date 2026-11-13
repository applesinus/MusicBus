package com.musicbus.core;

import com.musicbus.config.MusicBusConfig;
import com.musicbus.audio.MockMusicOutput;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class MusicBusManager {

    private static volatile boolean enabled = false;
    private static boolean initialized = false;

    private MusicBusManager() {}

    public static synchronized void initialize(
            Instrumentation instrumentation
    ) {
        if (initialized) {
            return;
        }

        if (instrumentation == null) {
            throw new IllegalArgumentException(
                    "Instrumentation cannot be null"
            );
        }

        log("Initializing MusicBus Manager...");

        enabled = MusicBusConfig.load();

        log("Configuration loaded: enabled=" + enabled);
        log("Configuration path: " + MusicBusConfig.getLocation());

        instrumentation.addTransformer(
                new MusicBusTransformer(),
                false
        );

        initialized = true;

        log("MusicBus Manager initialized.");
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Handles a runtime state transition.
     *
     * The transition is performed on the Minecraft
     * client thread through the UI callback.
     */
    public static synchronized void setEnabled(boolean value) {

        if (enabled == value) {
            return;
        }

        if (value) {
            enableMusicBus();
        } else {
            disableMusicBus();
        }

        try {
            MusicBusConfig.save(enabled);
        } catch (Exception error) {
            log("WARNING: Failed to save configuration: " + error);
        }

        log("MusicBus state changed: " + enabled);
    }

    private static void enableMusicBus() {

        Object currentMusic = null;
        boolean wasPlaying = false;

        try {
            currentMusic = getCurrentMusic();

            if (currentMusic != null) {

                wasPlaying = invokeSoundManagerBoolean(
                        "isActive",
                        currentMusic
                );

                if (wasPlaying) {
                    // Stop the vanilla channel BEFORE enabling interception.
                    invokeSoundManager(
                            "stop",
                            currentMusic
                    );
                }
            }

        } catch (Exception error) {
            log("WARNING: Could not adopt current music: " + error);
        }

        enabled = true;

        if (wasPlaying && currentMusic != null) {
            MockMusicOutput.play(currentMusic);

            log("Current vanilla music transferred to mock output.");
        } else {
            log("MusicBus enabled. Waiting for next music event.");
        }
    }

    private static void disableMusicBus() {

        Object currentMusic =
                MockMusicOutput.getCurrentInstance();

        if (currentMusic != null) {

            MockMusicOutput.stop(currentMusic);

            // Disable interception BEFORE sending playback
            // back to the vanilla SoundManager.
            enabled = false;

            try {
                invokeSoundManager(
                        "play",
                        currentMusic
                );

                log("Current music returned to vanilla output.");

            } catch (Exception error) {
                log("WARNING: Could not restore vanilla music: "
                        + error);
            }

        } else {
            enabled = false;

            log("MusicBus disabled. Vanilla playback restored.");
        }
    }

    /**
     * Called from SoundEngine.play().
     *
     * True means the original method must not execute.
     */
    public static boolean interceptMusicPlay(Object instance) {

        if (!enabled) {
            return false;
        }

        MockMusicOutput.play(instance);

        return true;
    }

    /**
     * Called from SoundEngine.isActive().
     */
    public static boolean isMockActive(Object instance) {
        return MockMusicOutput.isActive(instance);
    }

    /**
     * Called from SoundEngine.stop().
     */
    public static boolean interceptMusicStop(Object instance) {

        if (!enabled) {
            return false;
        }

        MockMusicOutput.stop(instance);

        return true;
    }

    // --------------------------------------------------
    // Minecraft reflection adapter
    // --------------------------------------------------

    private static Object getCurrentMusic() throws Exception {

        Object minecraft = getMinecraftInstance();

        Object musicManager = findObjectByType(
                minecraft,
                "MusicManager"
        );

        if (musicManager == null) {
            return null;
        }

        Field field = findField(
                musicManager.getClass(),
                "currentMusic"
        );

        if (field == null) {
            return null;
        }

        field.setAccessible(true);

        return field.get(musicManager);
    }

    private static Object getMinecraftInstance()
            throws Exception {

        ClassLoader loader =
                Thread.currentThread().getContextClassLoader();

        Class<?> minecraftClass = Class.forName(
                "net.minecraft.client.Minecraft",
                true,
                loader
        );

        Method method =
                minecraftClass.getDeclaredMethod("getInstance");

        method.setAccessible(true);

        return method.invoke(null);
    }

    private static Object findObjectByType(
            Object owner,
            String simpleName
    ) throws IllegalAccessException {

        Class<?> current = owner.getClass();

        while (current != null) {

            for (Field field : current.getDeclaredFields()) {

                if (field.getType().getSimpleName()
                        .equals(simpleName)) {

                    field.setAccessible(true);

                    return field.get(owner);
                }
            }

            current = current.getSuperclass();
        }

        return null;
    }

    private static Field findField(
            Class<?> type,
            String name
    ) {

        Class<?> current = type;

        while (current != null) {

            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }

        return null;
    }

    private static Object invokeSoundManager(
            String methodName,
            Object instance
    ) throws Exception {

        Object minecraft = getMinecraftInstance();

        Object soundManager = findObjectByType(
                minecraft,
                "SoundManager"
        );

        if (soundManager == null) {
            throw new IllegalStateException(
                    "SoundManager not found"
            );
        }

        Method method = findSoundManagerMethod(
                soundManager.getClass(),
                methodName
        );

        if (method == null) {
            throw new NoSuchMethodException(methodName);
        }

        method.setAccessible(true);

        return method.invoke(soundManager, instance);
    }

    private static boolean invokeSoundManagerBoolean(
            String methodName,
            Object instance
    ) throws Exception {

        Object result = invokeSoundManager(
                methodName,
                instance
        );

        return Boolean.TRUE.equals(result);
    }

    private static Method findSoundManagerMethod(
            Class<?> type,
            String name
    ) {

        Class<?> current = type;

        while (current != null) {

            for (Method method : current.getDeclaredMethods()) {

                if (method.getName().equals(name)
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0]
                            .getSimpleName().equals("SoundInstance")) {

                    return method;
                }
            }

            current = current.getSuperclass();
        }

        return null;
    }

    private static void log(String message) {
        System.out.println("[MusicBus] " + message);
    }
}
