package com.musicbus.audio;

import com.musicbus.core.MinecraftReflection;
import com.musicbus.core.MusicBusManager;
import com.musicbus.mappings.McClass;
import com.musicbus.mappings.McField;
import com.musicbus.mappings.McMappings;
import com.musicbus.mappings.McMethod;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Vanilla Minecraft implementation of AudioPlayer.
 *
 * Uses Minecraft's native SoundManager and SoundEngine and queries
 * exact OpenAL playback positions via VanillaChannelTracker.
 *
 * Minecraft classes are accessed through reflection to keep
 * the Java Agent independent of Minecraft compile-time classes.
 */
public final class VanillaAudioPlayer implements AudioPlayer {

    private final Map<UUID, Entry> sessions = new HashMap<>();

    private static final long QUERY_TIMEOUT_MS = 50L;

    // --------------------------------------------------
    // Audio Player identity
    // --------------------------------------------------

    @Override
    public String getId() {
        return "vanilla";
    }

    @Override
    public boolean isAvailable() {

        try {
            return MinecraftReflection.getSoundManager() != null;
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

        Entry entry = requireEntryForPlayback(session);
        if (entry == null) {
            return;
        }

        try {

            if (session.positionSeconds() > 0.0) {

                log("WARNING: Vanilla play() called with non-zero "
                        + "position. Restarting from zero.");
            }

            initializeEntryForPlayback(entry, session, 0.0);

            MinecraftReflection.invokeSoundManager(
                    McMethod.SOUND_MANAGER_PLAY,
                    entry.soundInstance
            );

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

            VanillaChannelTracker.ChannelSnapshot snap =
                    queryChannelSnapshot(entry);

            if (snap != null) {
                synchronized (entry) {
                    entry.lastKnownPositionSeconds = PlaybackMath.normalizePosition(
                            entry.initialOffsetSeconds
                                    + snap.positionSeconds(),
                            entry.durationSeconds,
                            entry.looping
                    );
                }
            }

            MinecraftReflection.invokeSoundManager(
                    McMethod.SOUND_MANAGER_STOP,
                    entry.soundInstance
            );

            synchronized (entry) {
                entry.state = PlaybackSession.State.STOPPED;
            }

            log("Vanilla playback stopped: " + sessionId
                    + " at "
                    + String.format(
                            Locale.ROOT,
                            "%.2fs",
                            entry.lastKnownPositionSeconds
                    ));

        } catch (Exception error) {

            throw new IllegalStateException(
                    "Failed to stop vanilla playback",
                    error
            );
        }
    }

    @Override
    public void resume(PlaybackSession.Snapshot session) {

        Entry entry = requireEntryForPlayback(session);
        if (entry == null) {
            return;
        }

        double targetPosition = Math.max(0.0, session.positionSeconds());

        try {

            initializeEntryForPlayback(entry, session, targetPosition);

            MusicBusManager.setPendingResumeSeconds(targetPosition);

            try {
                MinecraftReflection.invokeSoundManager(
                        McMethod.SOUND_MANAGER_PLAY,
                        entry.soundInstance
                );
            } finally {
                MusicBusManager.clearPendingResumeSeconds();
            }

            log("Vanilla playback resumed: "
                    + session.id()
                    + " at "
                    + String.format(Locale.ROOT, "%.2fs", targetPosition));

        } catch (Exception error) {

            throw new IllegalStateException(
                    "Failed to resume vanilla playback",
                    error
            );
        }
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

            boolean active = MinecraftReflection.invokeSoundManagerBoolean(
                    McMethod.SOUND_MANAGER_IS_ACTIVE,
                    entry.soundInstance
            );

            VanillaChannelTracker.ChannelSnapshot snap =
                    queryChannelSnapshot(entry);

            synchronized (entry) {

                if (snap != null) {
                    entry.lastKnownPositionSeconds = PlaybackMath.normalizePosition(
                            entry.initialOffsetSeconds
                                    + snap.positionSeconds(),
                            entry.durationSeconds,
                            entry.looping
                    );
                }

                PlaybackSession.State nextState;
                if (!active) {
                    nextState = PlaybackSession.State.STOPPED;
                } else if (snap != null
                        && snap.state()
                        == VanillaChannelTracker.ChannelPlaybackState.PAUSED) {
                    nextState = PlaybackSession.State.PAUSED;
                } else {
                    nextState = PlaybackSession.State.PLAYING;
                }

                entry.state = nextState;

                return Optional.of(
                        new PlaybackStatus(
                                nextState,
                                entry.lastKnownPositionSeconds
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
    // Exact OpenAL Channel position query
    // --------------------------------------------------

    private static VanillaChannelTracker.ChannelSnapshot queryChannelSnapshot(
            Entry entry
    ) {

        try {

            Object soundEngine = MinecraftReflection.getSoundEngine();

            if (soundEngine == null) {
                return null;
            }

            Object channel = findChannelForInstance(
                    soundEngine,
                    entry
            );

            if (channel == null) {
                return null;
            }

            VanillaChannelTracker.ChannelSnapshot terminal =
                    VanillaChannelTracker.getTerminalSnapshotIfStopped(
                            channel
                    );

            if (terminal != null) {
                return terminal;
            }

            Executor soundExecutor = getSoundEngineExecutor(soundEngine);

            if (soundExecutor == null) {
                return new VanillaChannelTracker.ChannelSnapshot(
                        VanillaChannelTracker.ChannelPlaybackState.PLAYING,
                        VanillaChannelTracker.getFallbackPosition(channel)
                );
            }

            CompletableFuture<VanillaChannelTracker.ChannelSnapshot> future =
                    new CompletableFuture<>();

            soundExecutor.execute(() -> {
                try {
                    future.complete(
                            VanillaChannelTracker.sampleOnSoundThread(channel)
                    );
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });

            return future.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        } catch (Exception ignored) {

            Object channel;
            synchronized (entry) {
                channel = entry.lastChannel;
            }

            if (channel != null) {
                return new VanillaChannelTracker.ChannelSnapshot(
                        VanillaChannelTracker.ChannelPlaybackState.PLAYING,
                        VanillaChannelTracker.getFallbackPosition(channel)
                );
            }

            return null;
        }
    }

    private static Object findChannelForInstance(
            Object soundEngine,
            Entry entry
    ) throws Exception {

        Map<?, ?> instanceToChannel =
                getInstanceToChannel(soundEngine);

        if (instanceToChannel != null) {
            Object handle = instanceToChannel.get(entry.soundInstance);

            if (handle != null) {
                Object channel = getChannelFromHandle(handle);

                if (channel != null) {
                    synchronized (entry) {
                        entry.lastChannel = channel;
                    }
                    return channel;
                }
            }
        }

        synchronized (entry) {
            return entry.lastChannel;
        }
    }

    // --------------------------------------------------
    // Internal session management
    // --------------------------------------------------

    private synchronized Entry getEntry(UUID sessionId) {

        return sessions.get(sessionId);
    }

    private Entry requireEntryForPlayback(PlaybackSession.Snapshot session) {

        if (session == null) {
            throw new IllegalArgumentException(
                    "Session cannot be null"
            );
        }

        if (session.state() == PlaybackSession.State.STOPPED) {
            stop(session.id());
            return null;
        }

        Entry entry = getEntry(session.id());

        if (entry == null) {
            throw new IllegalStateException(
                    "No SoundInstance registered for session: "
                            + session.id()
            );
        }

        ensureClientThread();
        return entry;
    }

    private static void initializeEntryForPlayback(
            Entry entry,
            PlaybackSession.Snapshot session,
            double startPosition
    ) {

        synchronized (entry) {
            entry.state = PlaybackSession.State.PLAYING;
            entry.initialOffsetSeconds = startPosition;
            entry.lastKnownPositionSeconds = startPosition;
            entry.durationSeconds = session.durationSeconds();
            entry.volume = session.volume();
            entry.pitch = session.pitch();
            entry.looping = session.looping();
            entry.lastChannel = null;
        }
    }

    private static final class Entry {

        private final Object soundInstance;

        private PlaybackSession.State state =
                PlaybackSession.State.STOPPED;

        private double initialOffsetSeconds;
        private double lastKnownPositionSeconds;
        private double durationSeconds;

        private float volume = 1.0f;
        private float pitch = 1.0f;

        private boolean looping;

        private Object lastChannel;

        private Entry(Object soundInstance) {
            this.soundInstance = soundInstance;
        }
    }

        // --------------------------------------------------
    // Minecraft object access
    // --------------------------------------------------

    private static Executor getSoundEngineExecutor(Object soundEngine)
            throws Exception {

        if (soundEngine == null) {
            return null;
        }

        Object value = MinecraftReflection.findObjectByType(
                soundEngine,
                McClass.SOUND_ENGINE_EXECUTOR
        );

        return value instanceof Executor ? (Executor) value : null;
    }

    private static Map<?, ?> getInstanceToChannel(Object soundEngine)
            throws Exception {

        if (soundEngine == null) {
            return null;
        }

        Object value = McMappings.getFieldValue(
                McField.SOUND_ENGINE_INSTANCE_TO_CHANNEL,
                soundEngine
        );

        return value instanceof Map<?, ?> ? (Map<?, ?>) value : null;
    }

    private static Object getChannelFromHandle(Object handle)
            throws Exception {

        if (handle == null) {
            return null;
        }

        return McMappings.getFieldValue(
                McField.CHANNEL_HANDLE_CHANNEL,
                handle
        );
    }

    // --------------------------------------------------
    // SoundInstance inspection
    // --------------------------------------------------

    private static boolean isMusicInstance(Object instance) {

        if (instance == null) {
            return false;
        }

        try {

            McMappings.ensureInitialized(instance.getClass().getClassLoader());

            Object source = McMappings.invokeMethod(
                    McMethod.SOUND_INSTANCE_GET_CATEGORY,
                    instance
            );
            Object musicCategory = McMappings.getFieldValue(
                    McField.SOUND_CATEGORY_MUSIC,
                    null
            );

            return musicCategory != null && musicCategory.equals(source);

        } catch (Exception ignored) {

            return false;
        }
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

            Object minecraft = MinecraftReflection.getMinecraftInstance();

            if (!McMappings.hasMethod(McMethod.EVENT_LOOP_IS_SAME_THREAD)) {

                throw new IllegalStateException(
                        "Minecraft thread check is unavailable"
                );
            }

            boolean correctThread = Boolean.TRUE.equals(
                    McMappings.invokeMethod(
                            McMethod.EVENT_LOOP_IS_SAME_THREAD,
                            minecraft
                    )
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
                "[MusicBus] [VanillaAudioPlayer] " + message
        );
    }
}