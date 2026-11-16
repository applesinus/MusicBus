package com.musicbus.audio;

import com.musicbus.mappings.McField;
import com.musicbus.mappings.McMappings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks exact OpenAL media playback positions for Minecraft
 * {@code com.mojang.blaze3d.audio.Channel} instances.
 *
 * Hooks are invoked from {@code Channel} on the SoundEngine thread.
 * Queries should also run on the SoundEngine thread whenever possible
 * so OpenAL source handles cannot be deleted concurrently.
 */
public final class VanillaChannelTracker {

    // OpenAL 1.0 / 1.1 constants
    private static final int AL_FREQUENCY = 0x2001;
    private static final int AL_BITS = 0x2002;
    private static final int AL_CHANNELS = 0x2003;
    private static final int AL_SIZE = 0x2004;

    private static final int AL_SOURCE_STATE = 0x1010;
    private static final int AL_INITIAL = 0x1011;
    private static final int AL_PLAYING = 0x1012;
    private static final int AL_PAUSED = 0x1013;
    private static final int AL_STOPPED = 0x1014;

    private static final int AL_SEC_OFFSET = 0x1024;

    private static final Object MAP_LOCK = new Object();
    private static final Map<Object, ChannelState> CHANNELS =
            new WeakHashMap<>();

    private static volatile ReflectionCache reflection;

    private VanillaChannelTracker() {}

    // --------------------------------------------------
    // ASM hooks (called from Channel)
    // --------------------------------------------------

    public static void onBufferQueued(Object channel, int bufferId) {
        if (channel == null || bufferId <= 0) {
            return;
        }

        double duration = queryBufferDurationSeconds(channel, bufferId);
        ChannelState state = getOrCreateState(channel);

        synchronized (state) {
            state.streaming = true;
            state.queuedBufferDurations.addLast(duration);
            state.queuedTotalSeconds += duration;
        }
    }

    public static void onStaticBufferAttached(
            Object channel,
            int bufferId
    ) {
        if (channel == null || bufferId <= 0) {
            return;
        }

        double duration = queryBufferDurationSeconds(channel, bufferId);
        ChannelState state = getOrCreateState(channel);

        synchronized (state) {
            state.streaming = false;
            state.staticDurationSeconds = duration;
        }
    }

    public static void onBuffersUnqueued(Object channel, int count) {
        if (channel == null || count <= 0) {
            return;
        }

        ChannelState state = getOrCreateState(channel);

        synchronized (state) {
            for (int i = 0; i < count; i++) {
                Double removed = state.queuedBufferDurations.pollFirst();
                if (removed == null) {
                    break;
                }
                state.unqueuedSeconds += removed;
            }
            state.lastObservedSeconds = state.unqueuedSeconds;
        }
    }

    public static void onChannelPlay(Object channel) {
        if (channel == null) {
            return;
        }

        ChannelState state = getOrCreateState(channel);

        synchronized (state) {
            state.playInvoked = true;
            state.stopped = false;
            state.frozenPositionSeconds = -1.0;
        }
    }

    public static void onChannelStopping(Object channel) {
        markChannelStopped(channel, false);
    }

    public static void onChannelDestroying(Object channel) {
        markChannelStopped(channel, true);
    }

    private static void markChannelStopped(
            Object channel,
            boolean destroying
    ) {
        if (channel == null) {
            return;
        }

        ChannelState state = getState(channel);
        if (state == null) {
            return;
        }

        synchronized (state) {
            if (!state.destroyed && !state.stopped) {
                double live = readLiveOffsetLocked(channel, state);
                if (live >= 0.0) {
                    state.frozenPositionSeconds = live;
                    state.lastObservedSeconds = live;
                }
            }
            state.stopped = true;
            if (destroying) {
                state.destroyed = true;
            }
        }
    }

    // --------------------------------------------------
    // Status querying
    // --------------------------------------------------

    public enum ChannelPlaybackState {
        INITIAL,
        PLAYING,
        PAUSED,
        STOPPED
    }

    public record ChannelSnapshot(
            ChannelPlaybackState state,
            double positionSeconds
    ) {}

    /**
     * Fast non-blocking check when the channel is already
     * known to be stopped or destroyed.
     */
    public static ChannelSnapshot getTerminalSnapshotIfStopped(
            Object channel
    ) {
        if (channel == null) {
            return null;
        }

        ChannelState state = getState(channel);
        if (state == null) {
            return null;
        }

        synchronized (state) {
            if (!state.destroyed && !state.stopped) {
                return null;
            }

            return new ChannelSnapshot(
                    ChannelPlaybackState.STOPPED,
                    resolveStoppedPositionLocked(state)
            );
        }
    }

    /**
     * Returns last known position without querying OpenAL directly
     * (used as fallback if SoundEngineExecutor cannot respond in time).
     */
    public static double getFallbackPosition(Object channel) {
        if (channel == null) {
            return 0.0;
        }

        ChannelState state = getState(channel);
        if (state == null) {
            return 0.0;
        }

        synchronized (state) {
            return resolveStoppedPositionLocked(state);
        }
    }

    /**
     * Queries exact OpenAL state and playback offset.
     * Should be executed on SoundEngineExecutor.
     */
    public static ChannelSnapshot sampleOnSoundThread(Object channel) {
        if (channel == null) {
            return new ChannelSnapshot(
                    ChannelPlaybackState.INITIAL,
                    0.0
            );
        }

        ChannelState state = getOrCreateState(channel);

        synchronized (state) {
            if (state.destroyed || state.stopped) {
                return new ChannelSnapshot(
                        ChannelPlaybackState.STOPPED,
                        resolveStoppedPositionLocked(state)
                );
            }

            try {
                OpenAlSample sample = queryOpenAlSampleLocked(channel, state);
                if (sample == null) {
                    return new ChannelSnapshot(
                            ChannelPlaybackState.STOPPED,
                            resolveStoppedPositionLocked(state)
                    );
                }

                if (sample.alState() == AL_PLAYING
                        || sample.alState() == AL_PAUSED) {
                    state.lastObservedSeconds = sample.positionSeconds();
                    return new ChannelSnapshot(
                            sample.alState() == AL_PLAYING
                                    ? ChannelPlaybackState.PLAYING
                                    : ChannelPlaybackState.PAUSED,
                            sample.positionSeconds()
                    );
                }

                if (sample.alState() == AL_INITIAL) {
                    return new ChannelSnapshot(
                            ChannelPlaybackState.INITIAL,
                            0.0
                    );
                }

                // AL_STOPPED: either underran / finished naturally, or stopped
                double pos;
                if (state.frozenPositionSeconds >= 0.0) {
                    pos = state.frozenPositionSeconds;
                } else if (sample.positionSeconds() >= 0.0) {
                    pos = sample.positionSeconds();
                } else {
                    pos = state.lastObservedSeconds;
                }

                pos = Math.max(0.0, pos);
                state.lastObservedSeconds = pos;

                return new ChannelSnapshot(
                        ChannelPlaybackState.STOPPED,
                        pos
                );

            } catch (Exception ignored) {
                return new ChannelSnapshot(
                        ChannelPlaybackState.PLAYING,
                        Math.max(0.0, state.lastObservedSeconds)
                );
            }
        }
    }

    // --------------------------------------------------
    // Internal helpers
    // --------------------------------------------------

    private static double readLiveOffsetLocked(
            Object channel,
            ChannelState state
    ) {
        try {
            OpenAlSample sample = queryOpenAlSampleLocked(channel, state);
            return sample != null ? sample.positionSeconds() : -1.0;
        } catch (Exception ignored) {
            return -1.0;
        }
    }

    private static OpenAlSample queryOpenAlSampleLocked(
            Object channel,
            ChannelState state
    ) throws Exception {
        ReflectionCache cache = ensureReflection(channel);
        AtomicBoolean initialized =
                (AtomicBoolean) cache.initializedField.get(channel);

        if (initialized == null || !initialized.get()) {
            return null;
        }

        int source = cache.sourceField.getInt(channel);
        int alState = (int) cache.alGetSourcei.invoke(
                null,
                source,
                AL_SOURCE_STATE
        );

        if (alState == AL_PLAYING || alState == AL_PAUSED) {
            float secOffset = (float) cache.alGetSourcef.invoke(
                    null,
                    source,
                    AL_SEC_OFFSET
            );
            return new OpenAlSample(alState, combineOffset(state, secOffset));
        }

        if (alState == AL_INITIAL) {
            return new OpenAlSample(alState, 0.0);
        }

        if (alState == AL_STOPPED) {
            double endedPos = -1.0;
            if (state.streaming) {
                endedPos = state.queuedTotalSeconds;
            } else if (state.staticDurationSeconds > 0.0) {
                endedPos = state.staticDurationSeconds;
            }
            return new OpenAlSample(alState, endedPos);
        }

        return new OpenAlSample(alState, -1.0);
    }

    private static double resolveStoppedPositionLocked(ChannelState state) {
        double pos = state.frozenPositionSeconds >= 0.0
                ? state.frozenPositionSeconds
                : state.lastObservedSeconds;
        return Math.max(0.0, pos);
    }

    private record OpenAlSample(int alState, double positionSeconds) {}

    private static double combineOffset(
            ChannelState state,
            float secOffset
    ) {
        double offset = Float.isFinite(secOffset) && secOffset > 0.0f
                ? secOffset
                : 0.0;

        if (state.streaming) {
            return Math.max(0.0, state.unqueuedSeconds + offset);
        }

        return Math.max(0.0, offset);
    }

    private static double queryBufferDurationSeconds(
            Object channel,
            int bufferId
    ) {
        try {
            ReflectionCache cache = ensureReflection(channel);
            int sizeBytes = (int) cache.alGetBufferi.invoke(
                    null,
                    bufferId,
                    AL_SIZE
            );
            int frequency = (int) cache.alGetBufferi.invoke(
                    null,
                    bufferId,
                    AL_FREQUENCY
            );
            int channels = (int) cache.alGetBufferi.invoke(
                    null,
                    bufferId,
                    AL_CHANNELS
            );
            int bits = (int) cache.alGetBufferi.invoke(
                    null,
                    bufferId,
                    AL_BITS
            );

            if (sizeBytes <= 0 || frequency <= 0
                    || channels <= 0 || bits <= 0) {
                return 0.0;
            }

            double bytesPerSecond =
                    (double) frequency * channels * (bits / 8.0);

            if (bytesPerSecond <= 0.0) {
                return 0.0;
            }

            return sizeBytes / bytesPerSecond;

        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private static ChannelState getOrCreateState(Object channel) {
        synchronized (MAP_LOCK) {
            return CHANNELS.computeIfAbsent(
                    channel,
                    ignored -> new ChannelState()
            );
        }
    }

    private static ChannelState getState(Object channel) {
        synchronized (MAP_LOCK) {
            return CHANNELS.get(channel);
        }
    }

    private static ReflectionCache ensureReflection(Object channel)
            throws Exception {

        ReflectionCache current = reflection;
        if (current != null) {
            return current;
        }

        ClassLoader loader = channel.getClass().getClassLoader();
        McMappings.ensureInitialized(loader);

        Class<?> al10Class = Class.forName(
                "org.lwjgl.openal.AL10",
                true,
                loader
        );

        Field sourceField = McMappings.resolveField(
                McField.AUDIO_CHANNEL_SOURCE
        );
        Field initializedField = McMappings.resolveField(
                McField.AUDIO_CHANNEL_INITIALIZED
        );

        Method alGetBufferi = al10Class.getMethod(
                "alGetBufferi",
                int.class,
                int.class
        );
        Method alGetSourcei = al10Class.getMethod(
                "alGetSourcei",
                int.class,
                int.class
        );
        Method alGetSourcef = al10Class.getMethod(
                "alGetSourcef",
                int.class,
                int.class
        );

        ReflectionCache created = new ReflectionCache(
                sourceField,
                initializedField,
                alGetBufferi,
                alGetSourcei,
                alGetSourcef
        );

        reflection = created;
        return created;
    }

    private record ReflectionCache(
            Field sourceField,
            Field initializedField,
            Method alGetBufferi,
            Method alGetSourcei,
            Method alGetSourcef
    ) {}

    private static final class ChannelState {
        final Deque<Double> queuedBufferDurations = new ArrayDeque<>();
        boolean streaming;
        boolean playInvoked;
        boolean stopped;
        boolean destroyed;

        double queuedTotalSeconds;
        double unqueuedSeconds;
        double staticDurationSeconds;
        double lastObservedSeconds;
        double frozenPositionSeconds = -1.0;
    }
}