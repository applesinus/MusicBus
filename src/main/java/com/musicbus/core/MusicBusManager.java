
package com.musicbus.core;

import com.musicbus.audio.AudioPlayer;
import com.musicbus.audio.MockAudioPlayer;
import com.musicbus.audio.PlaybackSession;
import com.musicbus.audio.VanillaAudioPlayer;
import com.musicbus.config.MusicBusConfig;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

public final class MusicBusManager {

    private static volatile boolean enabled;
    private static volatile boolean initialized;

    private static final Object STATE_LOCK = new Object();
    private static final Object TRANSITION_LOCK = new Object();

    private static final ThreadLocal<Boolean> BACKEND_TRANSITION =
            ThreadLocal.withInitial(() -> false);

    private static final Map<Object, PlaybackSession> SESSIONS =
            new IdentityHashMap<>();

    private static final Set<Object> MOCK_ROUTED_INSTANCES =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private static AudioPlayer mockPlayer;
    private static VanillaAudioPlayer vanillaPlayer;

    private static PlaybackSession currentSession;
    private static Object currentSoundInstance;

    private MusicBusManager() {}

    // --------------------------------------------------
    // Initialization
    // --------------------------------------------------

    public static void initialize(
            Instrumentation instrumentation
    ) {

        synchronized (TRANSITION_LOCK) {

            if (initialized) {
                return;
            }

            if (instrumentation == null) {
                throw new IllegalArgumentException(
                        "Instrumentation cannot be null"
                );
            }

            log("Initializing MusicBus Manager...");

            try {

                vanillaPlayer = new VanillaAudioPlayer();
                mockPlayer = createMockPlayer();

                if (!vanillaPlayer.isAvailable()) {
                    log("WARNING: Vanilla backend unavailable.");
                }

                if (!mockPlayer.isAvailable()) {
                    throw new IllegalStateException(
                            "Mock backend unavailable"
                    );
                }

                enabled = MusicBusConfig.load();

                instrumentation.addTransformer(
                        new MusicBusTransformer(),
                        false
                );

                initialized = true;

                log("Vanilla backend initialized.");
                log("Mock backend initialized.");
                log("Configuration loaded: enabled=" + enabled);
                log("Configuration path: "
                        + MusicBusConfig.getLocation());

                log("MusicBus Manager initialized.");

            } catch (Exception error) {

                vanillaPlayer = null;
                mockPlayer = null;
                enabled = false;

                throw new IllegalStateException(
                        "MusicBus initialization failed",
                        error
                );
            }
        }
    }

    private static MockAudioPlayer createMockPlayer() {
        return new MockAudioPlayer();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static PlaybackSession getCurrentSession() {

        synchronized (STATE_LOCK) {
            return currentSession;
        }
    }

    public static AudioPlayer getMockPlayer() {
        return mockPlayer;
    }

    public static AudioPlayer getVanillaPlayer() {
        return vanillaPlayer;
    }

    // --------------------------------------------------
    // Configuration
    // --------------------------------------------------

    public static void setEnabled(boolean value) {

        synchronized (TRANSITION_LOCK) {

            if (!initialized) {
                log("WARNING: Manager is not initialized.");
                return;
            }

            if (enabled == value) {
                return;
            }

            boolean successful = value
                    ? enableMusicBus()
                    : disableMusicBus();

            if (!successful) {
                log("WARNING: Backend transition failed.");
                return;
            }

            try {

                MusicBusConfig.save(enabled);

            } catch (Exception error) {

                log("WARNING: Failed to save configuration: "
                        + error);
            }

            log("MusicBus state changed: " + enabled);
        }
    }

    // --------------------------------------------------
    // Backend switching
    // --------------------------------------------------

    private static boolean enableMusicBus() {

        Object instance;
        PlaybackSession session = null;

        try {

            instance = getCurrentMusic();

            if (instance == null || !isMusicInstance(instance)) {

                enabled = true;

                log("MusicBus enabled. Waiting for next music event.");

                return true;
            }

            boolean vanillaActive =
                    invokeSoundManagerBoolean(
                            "isActive",
                            instance
                    );

            if (!vanillaActive) {

                enabled = true;

                log("MusicBus enabled. No active vanilla music.");

                return true;
            }

            session = getOrCreateSession(instance);

            if (session == null) {
                throw new IllegalStateException(
                        "Could not adopt current music"
                );
            }

            /*
             * Stop vanilla playback.
             *
             * The transition guard prevents SoundEngine.stop()
             * from destroying the logical PlaybackSession.
             */
            PlaybackSession targetSession = session;

            duringBackendTransition(
                    () -> vanillaPlayer.stop(targetSession.getId())
            );

            PlaybackSession.Snapshot snapshot =
                    session.snapshot();

            if (!mockPlayer.supports(snapshot)) {
                throw new IllegalStateException(
                        "Mock backend does not support session"
                );
            }

            mockPlayer.play(snapshot);

            if (!mockPlayer.isActive(session.getId())) {
                throw new IllegalStateException(
                        "Mock backend did not activate session"
                );
            }

            synchronized (STATE_LOCK) {
                MOCK_ROUTED_INSTANCES.add(instance);
            }

            enabled = true;

            log("Vanilla -> Mock transition successful.");
            log("Session: " + session.getId());

            return true;

        } catch (Exception error) {

            log("WARNING: Could not enable MusicBus: " + error);

            /*
             * Attempt to restore vanilla playback.
             */
            if (session != null) {

                try {

                    Object vanillaInstance = findInstanceForSession(session);

                    if (vanillaInstance != null) {

                        session.seek(0.0);

                        PlaybackSession.Snapshot snapshot =
                                session.snapshot();

                        PlaybackSession targetSession = session;

                        duringBackendTransition(
                                () -> vanillaPlayer.play(
                                        targetSession.snapshot()
                                )
                        );

                        synchronized (STATE_LOCK) {
                            MOCK_ROUTED_INSTANCES.remove(vanillaInstance);
                        }
                    }

                } catch (Exception rollbackError) {

                    log("CRITICAL: Vanilla rollback failed: "
                            + rollbackError);
                }
            }

            enabled = false;

            return false;
        }
    }

    private static boolean disableMusicBus() {

        PlaybackSession session;
        Object instance;

        synchronized (STATE_LOCK) {

            session = currentSession;
            instance = currentSoundInstance;
        }

        if (session == null || instance == null) {

            enabled = false;

            log("No active mock session. Vanilla backend restored.");

            return true;
        }

        PlaybackSession.Snapshot previousState =
                session.snapshot();

        try {

            /*
             * Detach MockBackend without stopping the logical session.
             */
            mockPlayer.stop(session.getId());

            synchronized (STATE_LOCK) {
                MOCK_ROUTED_INSTANCES.remove(instance);
            }

            /*
             * Disable interception before invoking vanilla play.
             */
            enabled = false;

            /*
             * Vanilla cannot restore arbitrary playback positions.
             * Restart the session from the beginning.
             */
            session.seek(0.0);

            duringBackendTransition(
                    () -> vanillaPlayer.play(session.snapshot())
            );

            log("Mock -> Vanilla transition successful.");
            log("Session: " + session.getId());

            return true;

        } catch (Exception error) {

            log("WARNING: Could not restore vanilla playback: "
                    + error);

            /*
             * Restore MockBackend if vanilla handoff failed.
             */
            try {

                mockPlayer.play(previousState);

                synchronized (STATE_LOCK) {
                    MOCK_ROUTED_INSTANCES.add(instance);
                }

                enabled = true;

                log("Mock backend successfully restored.");

            } catch (Exception rollbackError) {

                log("CRITICAL: Mock rollback failed: "
                        + rollbackError);

                enabled = false;
            }

            return false;
        }
    }

    // --------------------------------------------------
    // SoundEngine interception
    // --------------------------------------------------

    /**
     * Called from SoundEngine.play().
     *
     * true  = suppress original vanilla playback.
     * false = allow original vanilla playback.
     */
    public static boolean interceptMusicPlay(Object instance) {

        if (!isMusicInstance(instance)) {
            return false;
        }

        if (isBackendTransition()) {
            return false;
        }

        PlaybackSession session = getOrCreateSession(instance);

        if (session == null) {
            return false;
        }

        /*
         * Vanilla owns playback while MusicBus is disabled.
         */
        if (!enabled) {
            return false;
        }

        try {

            PlaybackSession.Snapshot snapshot =
                    session.snapshot();

            if (!mockPlayer.supports(snapshot)) {
                return false;
            }

            /*
             * Stop a previous mock session if a new track
             * replaces it.
             */
            PlaybackSession previous =
                    mockPlayerSession();

            if (previous != null && previous != session) {
                mockPlayer.stop(previous.getId());
            }

            mockPlayer.play(snapshot);

            if (!mockPlayer.isActive(session.getId())) {
                throw new IllegalStateException(
                        "Mock playback failed"
                );
            }

            synchronized (STATE_LOCK) {
                MOCK_ROUTED_INSTANCES.add(instance);
            }

            log("Music intercepted: " + session.getResourceId());

            return true;

        } catch (Exception error) {

            log("WARNING: Mock playback failed: " + error);

            synchronized (STATE_LOCK) {
                MOCK_ROUTED_INSTANCES.remove(instance);
            }

            /*
             * Allow vanilla playback as a fallback.
             */
            return false;
        }
    }

    /**
     * Called from SoundEngine.isActive().
     *
     * Returns true only when MockBackend owns this instance
     * and confirms that playback is active.
     */
    public static boolean isMockActive(Object instance) {

        if (!enabled || instance == null) {
            return false;
        }

        PlaybackSession session;

        synchronized (STATE_LOCK) {

            if (!MOCK_ROUTED_INSTANCES.contains(instance)) {
                return false;
            }

            session = SESSIONS.get(instance);
        }

        return session != null
                && mockPlayer != null
                && mockPlayer.isActive(session.getId());
    }

    /**
     * Called from SoundEngine.stop().
     *
     * true  = suppress vanilla stop.
     * false = execute original vanilla stop.
     */
    public static boolean interceptMusicStop(Object instance) {

        if (!isMusicInstance(instance)) {
            return false;
        }

        if (isBackendTransition()) {
            return false;
        }

        PlaybackSession session;
        boolean mockRouted;

        synchronized (STATE_LOCK) {

            session = SESSIONS.get(instance);

            mockRouted =
                    MOCK_ROUTED_INSTANCES.contains(instance);
        }

        if (session == null) {
            return false;
        }

        if (mockRouted) {

            mockPlayer.stop(session.getId());

            retireSession(instance, session);

            return true;
        }

        /*
         * Vanilla playback is stopped by the original method.
         * We only update our logical state.
         */
        session.stop();

        retireSession(instance, session);

        return false;
    }

    // --------------------------------------------------
    // Session management
    // --------------------------------------------------

    private static PlaybackSession getOrCreateSession(
            Object instance
    ) {

        synchronized (STATE_LOCK) {

            PlaybackSession existing = SESSIONS.get(instance);

            if (existing != null
                    && existing.getState()
                    != PlaybackSession.State.STOPPED) {

                return existing;
            }
        }

        PlaybackSession created;

        try {

            created = createSession(instance);

        } catch (Exception error) {

            log("WARNING: Session creation failed: " + error);

            return null;
        }

        PlaybackSession previous;
        Object previousInstance;
        boolean previousMock;

        synchronized (STATE_LOCK) {

            PlaybackSession existing = SESSIONS.get(instance);

            if (existing != null
                    && existing.getState()
                    != PlaybackSession.State.STOPPED) {

                return existing;
            }

            previous = currentSession;
            previousInstance = currentSoundInstance;

            previousMock = previousInstance != null
                    && MOCK_ROUTED_INSTANCES.contains(previousInstance);

            SESSIONS.put(instance, created);

            currentSession = created;
            currentSoundInstance = instance;

            MOCK_ROUTED_INSTANCES.remove(instance);
        }

        /*
         * Register the SoundInstance with the vanilla adapter.
         * This association is required for future handoffs.
         */
        vanillaPlayer.registerSoundInstance(
                created.getId(),
                instance
        );

        /*
         * Retire the previous logical session.
         */
        if (previous != null && previous != created) {

            if (previousMock) {
                mockPlayer.stop(previous.getId());
            }

            previous.stop();

            if (previousInstance != null) {

                vanillaPlayer.unregisterSoundInstance(
                        previous.getId()
                );

                synchronized (STATE_LOCK) {

                    SESSIONS.remove(previousInstance);

                    MOCK_ROUTED_INSTANCES.remove(
                            previousInstance
                    );
                }
            }
        }

        log("Session created: " + created.getId());
        log("Resource: " + created.getResourceId());

        return created;
    }

    private static PlaybackSession createSession(
            Object instance
    ) throws Exception {

        Object location = invokeNoArgs(
                instance,
                "getLocation"
        );

        if (location == null) {
            throw new IllegalStateException(
                    "SoundInstance location is null"
            );
        }

        String resourceId = location.toString();

        float volume = readFloat(
                instance,
                "getVolume",
                1.0f
        );

        float pitch = readFloat(
                instance,
                "getPitch",
                1.0f
        );

        boolean looping = readBoolean(
                instance,
                "isLooping",
                false
        );

        if (!Float.isFinite(volume) || volume < 0.0f) {
            volume = 1.0f;
        }

        if (!Float.isFinite(pitch) || pitch < 0.0f) {
            pitch = 1.0f;
        }

        return new PlaybackSession(
                resourceId,
                0.0,
                0.0,
                volume,
                pitch,
                looping
        );
    }

    private static PlaybackSession mockPlayerSession() {

        synchronized (STATE_LOCK) {
            return currentSession;
        }
    }

    private static void retireSession(
            Object instance,
            PlaybackSession session
    ) {

        synchronized (STATE_LOCK) {

            if (SESSIONS.get(instance) == session) {
                SESSIONS.remove(instance);
            }

            MOCK_ROUTED_INSTANCES.remove(instance);

            if (currentSession == session) {
                currentSession = null;
                currentSoundInstance = null;
            }
        }

        vanillaPlayer.unregisterSoundInstance(
                session.getId()
        );
    }

    private static Object findInstanceForSession(
            PlaybackSession session
    ) {

        synchronized (STATE_LOCK) {

            for (Map.Entry<Object, PlaybackSession> entry
                    : SESSIONS.entrySet()) {

                if (entry.getValue() == session) {
                    return entry.getKey();
                }
            }
        }

        return null;
    }

    // --------------------------------------------------
    // Backend transition guard
    // --------------------------------------------------

    private static boolean isBackendTransition() {
        return Boolean.TRUE.equals(BACKEND_TRANSITION.get());
    }

    private static void duringBackendTransition(
            CheckedRunnable action
    ) throws Exception {

        boolean previous = BACKEND_TRANSITION.get();

        BACKEND_TRANSITION.set(true);

        try {

            action.run();

        } finally {

            BACKEND_TRANSITION.set(previous);
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    // --------------------------------------------------
    // Minecraft reflection
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

        if (loader == null) {
            loader = MusicBusManager.class.getClassLoader();
        }

        Class<?> minecraftClass = Class.forName(
                "net.minecraft.client.Minecraft",
                true,
                loader
        );

        Method method = minecraftClass.getDeclaredMethod(
                "getInstance"
        );

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

        return Boolean.TRUE.equals(
                invokeSoundManager(methodName, instance)
        );
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

    // --------------------------------------------------
    // SoundInstance inspection
    // --------------------------------------------------

    private static boolean isMusicInstance(Object instance) {

        if (instance == null) {
            return false;
        }

        try {

            Object source = invokeNoArgs(
                    instance,
                    "getSource"
            );

            return source instanceof Enum<?> category
                    && "MUSIC".equals(category.name());

        } catch (Exception ignored) {

            return false;
        }
    }

    private static Object invokeNoArgs(
            Object target,
            String methodName
    ) throws Exception {

        Method method = findNoArgMethod(
                target.getClass(),
                methodName
        );

        if (method == null) {
            throw new NoSuchMethodException(methodName);
        }

        method.setAccessible(true);

        return method.invoke(target);
    }

    private static Method findNoArgMethod(
            Class<?> type,
            String name
    ) {

        Class<?> current = type;

        while (current != null) {

            try {

                return current.getDeclaredMethod(name);

            } catch (NoSuchMethodException ignored) {

                current = current.getSuperclass();
            }
        }

        return null;
    }

    private static float readFloat(
            Object target,
            String methodName,
            float fallback
    ) {

        try {

            Object value = invokeNoArgs(target, methodName);

            if (value instanceof Number number) {
                return number.floatValue();
            }

        } catch (Exception ignored) {
        }

        return fallback;
    }

    private static boolean readBoolean(
            Object target,
            String methodName,
            boolean fallback
    ) {

        try {

            Object value = invokeNoArgs(target, methodName);

            if (value instanceof Boolean result) {
                return result;
            }

        } catch (Exception ignored) {
        }

        return fallback;
    }

    // --------------------------------------------------
    // Logging
    // --------------------------------------------------

    private static void log(String message) {
        System.out.println("[MusicBus] " + message);
    }
}
