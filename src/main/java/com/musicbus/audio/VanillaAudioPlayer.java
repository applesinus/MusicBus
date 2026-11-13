
package com.musicbus.audio;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Vanilla Minecraft implementation of AudioPlayer.
 *
 * Uses Minecraft's native SoundManager and SoundEngine.
 *
 * This implementation does not own logical playback state.
 * PlaybackSession remains managed by MusicBus.
 *
 * Important limitations:
 * - Individual SoundInstance pause is not exposed by SoundManager.
 * - Arbitrary seeking is not supported by vanilla playback.
 * - Playback parameters cannot be changed on an already
 *   created vanilla audio channel through SoundManager.
 *
 * Minecraft classes are accessed through reflection to keep
 * the Java Agent independent of Minecraft compile-time classes.
 */
public final class VanillaAudioPlayer implements AudioPlayer {

    private final Map<UUID, Entry> sessions = new HashMap<>();

    private static final String MUSIC_SOURCE = "MUSIC";

    // --------------------------------------------------
    // Backend identity
    // --------------------------------------------------

    @Override
    public String getId() {
        return "vanilla";
    }

    @Override
    public boolean isAvailable() {

        try {
            return getSoundManager() != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public synchronized boolean supports(
            PlaybackSession.Snapshot session
    ) {

        if (session == null) {
            return false;
        }

        Entry entry = sessions.get(session.id());

        return entry != null
                && isMusicInstance(entry.soundInstance);
    }

    // --------------------------------------------------
    // SoundInstance registration
    // --------------------------------------------------

    /**
     * Associates a Minecraft SoundInstance with a logical
     * MusicBus session.
     *
     * Must be called when MusicBus creates or adopts
     * a PlaybackSession from a Minecraft SoundInstance.
     */
    public synchronized void registerSoundInstance(
            UUID sessionId,
            Object soundInstance
    ) {

        if (sessionId == null || soundInstance == null) {
            throw new IllegalArgumentException(
                    "Session ID and SoundInstance cannot be null"
            );
        }

        if (!isMusicInstance(soundInstance)) {
            throw new IllegalArgumentException(
                    "Only SoundSource.MUSIC instances are supported"
            );
        }

        sessions.put(
                sessionId,
                new Entry(soundInstance)
        );

        log("SoundInstance registered: " + sessionId);
    }

    /**
     * Removes the association between a logical session
     * and its Minecraft SoundInstance.
     */
    public synchronized void unregisterSoundInstance(
            UUID sessionId
    ) {

        sessions.remove(sessionId);

        log("SoundInstance unregistered: " + sessionId);
    }

    // --------------------------------------------------
    // Playback lifecycle
    // --------------------------------------------------

    @Override
    public void play(PlaybackSession.Snapshot session) {

        if (session == null) {
            throw new IllegalArgumentException(
                    "Session cannot be null"
            );
        }

        if (session.state() == PlaybackSession.State.STOPPED) {
            stop(session.id());
            return;
        }

        Entry entry = getEntry(session.id());

        if (entry == null) {
            throw new IllegalStateException(
                    "No SoundInstance registered for session: "
                            + session.id()
            );
        }

        ensureClientThread();

        try {

            /*
             * Vanilla SoundManager cannot restore arbitrary
             * media positions. Playback starts from the beginning.
             */
            if (session.positionSeconds() > 0.0) {

                log("WARNING: Vanilla playback cannot restore "
                        + "position. Restarting from zero.");
            }

            invokeSoundManager(
                    "play",
                    entry.soundInstance
            );

            synchronized (entry) {

                entry.state = PlaybackSession.State.PLAYING;
                entry.positionSeconds = 0.0;
                entry.durationSeconds = session.durationSeconds();
                entry.volume = session.volume();
                entry.pitch = session.pitch();
                entry.looping = session.looping();
                entry.anchorNanos = System.nanoTime();
            }

            log("Vanilla playback started: " + session.id());

        } catch (Exception error) {

            throw new IllegalStateException(
                    "Failed to start vanilla playback",
                    error
            );
        }
    }

    @Override
    public void stop(UUID sessionId) {

        Entry entry = getEntry(sessionId);

        if (entry == null) {
            return;
        }

        ensureClientThread();

        try {

            invokeSoundManager(
                    "stop",
                    entry.soundInstance
            );

            synchronized (entry) {

                entry.positionSeconds = calculatePosition(
                        entry,
                        System.nanoTime()
                );

                entry.state = PlaybackSession.State.STOPPED;
            }

            log("Vanilla playback stopped: " + sessionId);

        } catch (Exception error) {

            throw new IllegalStateException(
                    "Failed to stop vanilla playback",
                    error
            );
        }
    }

    @Override
    public void pause(UUID sessionId) {

        throw unsupported(
                "Individual vanilla SoundInstance pause"
        );
    }

    @Override
    public void resume(PlaybackSession.Snapshot session) {

        throw unsupported(
                "Individual vanilla SoundInstance resume"
        );
    }

    // --------------------------------------------------
    // Playback controls
    // --------------------------------------------------

    @Override
    public void seek(
            UUID sessionId,
            double positionSeconds
    ) {

        throw unsupported(
                "Vanilla playback seeking"
        );
    }

    @Override
    public void setVolume(
            UUID sessionId,
            float volume
    ) {

        throw unsupported(
                "Individual vanilla channel volume control"
        );
    }

    @Override
    public void setPitch(
            UUID sessionId,
            float pitch
    ) {

        throw unsupported(
                "Individual vanilla channel pitch control"
        );
    }

    @Override
    public void setLooping(
            UUID sessionId,
            boolean looping
    ) {

        throw unsupported(
                "Individual vanilla channel looping control"
        );
    }

    // --------------------------------------------------
    // Playback state
    // --------------------------------------------------

    @Override
    public Optional<PlaybackStatus> getStatus(UUID sessionId) {

        Entry entry = getEntry(sessionId);

        if (entry == null) {
            return Optional.empty();
        }

        ensureClientThread();

        try {

            boolean active = invokeSoundManagerBoolean(
                    "isActive",
                    entry.soundInstance
            );

            synchronized (entry) {

                long now = System.nanoTime();

                if (active) {

                    entry.state = PlaybackSession.State.PLAYING;

                    return Optional.of(
                            new PlaybackStatus(
                                    entry.state,
                                    calculatePosition(entry, now)
                            )
                    );
                }

                entry.positionSeconds = calculatePosition(
                        entry,
                        now
                );

                entry.state = PlaybackSession.State.STOPPED;

                return Optional.of(
                        new PlaybackStatus(
                                entry.state,
                                entry.positionSeconds
                        )
                );
            }

        } catch (Exception error) {

            throw new IllegalStateException(
                    "Failed to query vanilla playback status",
                    error
            );
        }
    }

    // --------------------------------------------------
    // Position estimation
    // --------------------------------------------------

    /**
     * Estimates playback position from monotonic elapsed time.
     *
     * This is an estimate, not an actual SoundEngine position.
     */
    private static double calculatePosition(
            Entry entry,
            long nowNanos
    ) {

        if (entry.state != PlaybackSession.State.PLAYING) {
            return entry.positionSeconds;
        }

        double elapsed =
                Math.max(0L, nowNanos - entry.anchorNanos)
                        / 1_000_000_000.0;

        double position =
                entry.positionSeconds + elapsed * entry.pitch;

        if (entry.durationSeconds > 0.0) {

            if (entry.looping) {
                position %= entry.durationSeconds;
            } else {
                position = Math.min(
                        position,
                        entry.durationSeconds
                );
            }
        }

        return Math.max(0.0, position);
    }

    // --------------------------------------------------
    // Internal session management
    // --------------------------------------------------

    private synchronized Entry getEntry(UUID sessionId) {

        return sessions.get(sessionId);
    }

    private static final class Entry {

        private final Object soundInstance;

        private PlaybackSession.State state =
                PlaybackSession.State.STOPPED;

        private double positionSeconds;
        private double durationSeconds;

        private float volume = 1.0f;
        private float pitch = 1.0f;

        private boolean looping;

        private long anchorNanos;

        private Entry(Object soundInstance) {
            this.soundInstance = soundInstance;
        }
    }

    // --------------------------------------------------
    // Minecraft reflection adapter
    // --------------------------------------------------

    private static Object getMinecraftInstance()
            throws Exception {

        ClassLoader loader =
                Thread.currentThread().getContextClassLoader();

        if (loader == null) {
            loader = VanillaAudioPlayer.class.getClassLoader();
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

    private static Object getSoundManager()
            throws Exception {

        Object minecraft = getMinecraftInstance();

        Class<?> current = minecraft.getClass();

        while (current != null) {

            for (Field field : current.getDeclaredFields()) {

                if (field.getType().getSimpleName()
                        .equals("SoundManager")) {

                    field.setAccessible(true);

                    return field.get(minecraft);
                }
            }

            current = current.getSuperclass();
        }

        throw new IllegalStateException(
                "Minecraft SoundManager not found"
        );
    }

    private static Object invokeSoundManager(
            String methodName,
            Object instance
    ) throws Exception {

        Object soundManager = getSoundManager();

        Method method = findSoundManagerMethod(
                soundManager.getClass(),
                methodName
        );

        if (method == null) {
            throw new NoSuchMethodException(
                    "SoundManager." + methodName
            );
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

    // --------------------------------------------------
    // SoundInstance inspection
    // --------------------------------------------------

    private static boolean isMusicInstance(Object instance) {

        if (instance == null) {
            return false;
        }

        try {

            Method method = findNoArgMethod(
                    instance.getClass(),
                    "getSource"
            );

            if (method == null) {
                return false;
            }

            method.setAccessible(true);

            Object source = method.invoke(instance);

            return source instanceof Enum<?> category
                    && MUSIC_SOURCE.equals(category.name());

        } catch (Exception ignored) {

            return false;
        }
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

    // --------------------------------------------------
    // Thread safety
    // --------------------------------------------------

    /**
     * SoundManager operations must run on the Minecraft
     * client thread.
     */
    private static void ensureClientThread() {

        try {

            Object minecraft = getMinecraftInstance();

            Method method = findNoArgMethod(
                    minecraft.getClass(),
                    "isSameThread"
            );

            if (method == null) {
                throw new IllegalStateException(
                        "Minecraft thread check is unavailable"
                );
            }

            method.setAccessible(true);

            boolean correctThread = Boolean.TRUE.equals(
                    method.invoke(minecraft)
            );

            if (!correctThread) {
                throw new IllegalStateException(
                        "VanillaAudioPlayer must be called "
                                + "from the Minecraft client thread"
                );
            }

        } catch (RuntimeException error) {

            throw error;

        } catch (Exception error) {

            throw new IllegalStateException(
                    "Could not verify Minecraft client thread",
                    error
            );
        }
    }

    // --------------------------------------------------
    // Unsupported operations
    // --------------------------------------------------

    private static UnsupportedOperationException unsupported(
            String operation
    ) {

        return new UnsupportedOperationException(
                operation + " is not supported by VanillaAudioPlayer"
        );
    }

    // --------------------------------------------------
    // Lifecycle
    // --------------------------------------------------

    @Override
    public void close() {

        Map<UUID, Entry> snapshot;

        synchronized (this) {

            snapshot = new HashMap<>(sessions);
        }

        for (UUID sessionId : snapshot.keySet()) {

            try {

                stop(sessionId);

            } catch (Exception error) {

                log("WARNING: Failed to stop session "
                        + sessionId + ": " + error);
            }
        }

        synchronized (this) {

            sessions.clear();
        }

        log("VanillaAudioPlayer closed.");
    }

    // --------------------------------------------------
    // Logging
    // --------------------------------------------------

    private static void log(String message) {

        System.out.println(
                "[MusicBus] [VanillaBackend] " + message
        );
    }
}
