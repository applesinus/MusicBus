package com.musicbus.audio;

import com.musicbus.core.MusicBusManager;
import com.musicbus.core.MinecraftReflection;

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

    private static final String MUSIC_SOURCE = "MUSIC";
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

            if (session.positionSeconds() > 0.0) {

                log("WARNING: Vanilla play() called with non-zero "
                        + "position. Restarting from zero.");
            }

            synchronized (entry) {

                entry.state = PlaybackSession.State.PLAYING;
                entry.initialOffsetSeconds = 0.0;
                entry.lastKnownPositionSeconds = 0.0;
                entry.durationSeconds = session.durationSeconds();
                entry.volume = session.volume();
                entry.pitch = session.pitch();
                entry.looping = session.looping();
                entry.lastChannel = null;
            }

            MinecraftReflection.invokeSoundManager(
                    "play",
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
                    entry.lastKnownPositionSeconds = normalizePosition(
                            entry,
                            entry.initialOffsetSeconds
                                    + snap.positionSeconds()
                    );
                }
            }

            MinecraftReflection.invokeSoundManager(
                    "stop",
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

        double targetPosition = Math.max(0.0, session.positionSeconds());

        try {

            synchronized (entry) {

                entry.state = PlaybackSession.State.PLAYING;
                entry.initialOffsetSeconds = targetPosition;
                entry.lastKnownPositionSeconds = targetPosition;
                entry.durationSeconds = session.durationSeconds();
                entry.volume = session.volume();
                entry.pitch = session.pitch();
                entry.looping = session.looping();
                entry.lastChannel = null;
            }

            MusicBusManager.setPendingResumeSeconds(targetPosition);

            try {
                MinecraftReflection.invokeSoundManager(
                        "play",
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
                    "isActive",
                    entry.soundInstance
            );

            VanillaChannelTracker.ChannelSnapshot snap =
                    queryChannelSnapshot(entry);

            synchronized (entry) {

                if (snap != null) {
                    entry.lastKnownPositionSeconds = normalizePosition(
                            entry,
                            entry.initialOffsetSeconds
                                    + snap.positionSeconds()
                    );
                }

                if (!active) {
                    entry.state = PlaybackSession.State.STOPPED;

                    return Optional.of(
                            new PlaybackStatus(
                                    PlaybackSession.State.STOPPED,
                                    entry.lastKnownPositionSeconds
                            )
                    );
                }

                if (snap != null
                        && snap.state()
                        == VanillaChannelTracker.ChannelPlaybackState.PAUSED) {
                    entry.state = PlaybackSession.State.PAUSED;

                    return Optional.of(
                            new PlaybackStatus(
                                    PlaybackSession.State.PAUSED,
                                    entry.lastKnownPositionSeconds
                            )
                    );
                }

                entry.state = PlaybackSession.State.PLAYING;

                return Optional.of(
                        new PlaybackStatus(
                                PlaybackSession.State.PLAYING,
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

    private static double normalizePosition(
            Entry entry,
            double position
    ) {

        if (!Double.isFinite(position) || position < 0.0) {
            return 0.0;
        }

        if (entry.durationSeconds > 0.0) {

            if (entry.looping) {
                return position % entry.durationSeconds;
            }

            return Math.min(position, entry.durationSeconds);
        }

        return position;
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
                "SoundEngineExecutor"
        );

        return value instanceof Executor executor ? executor : null;
    }

    private static Map<?, ?> getInstanceToChannel(Object soundEngine)
            throws Exception {

        if (soundEngine == null) {
            return null;
        }

        Object value = MinecraftReflection.getFieldValue(
                soundEngine,
                "instanceToChannel"
        );

        return value instanceof Map<?, ?> map ? map : null;
    }

    private static Object getChannelFromHandle(Object handle)
            throws Exception {

        if (handle == null) {
            return null;
        }

        return MinecraftReflection.getFieldValue(
                handle,
                "channel"
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

            Object source = MinecraftReflection.invokeNoArgs(
                    instance,
                    "getSource"
            );

            return source instanceof Enum<?> category
                    && MUSIC_SOURCE.equals(category.name());

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

            if (MinecraftReflection.findNoArgMethod(
                    minecraft.getClass(),
                    "isSameThread"
            ) == null) {

                throw new IllegalStateException(
                        "Minecraft thread check is unavailable"
                );
            }

            boolean correctThread = Boolean.TRUE.equals(
                    MinecraftReflection.invokeNoArgs(
                            minecraft,
                            "isSameThread"
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
                "[MusicBus] [VanillaAudioPlayer] " + message
        );
    }
}