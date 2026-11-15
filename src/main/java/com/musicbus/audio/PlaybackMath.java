package com.musicbus.audio;

import java.util.Objects;

/**
 * Shared playback math and parameter validation helpers
 * for audio backends and session state.
 */
final class PlaybackMath {

    private PlaybackMath() {}

    // --------------------------------------------------
    // Position math
    // --------------------------------------------------

    static double normalizePosition(
            double position,
            double durationSeconds,
            boolean looping
    ) {
        if (!Double.isFinite(position) || position < 0.0) {
            return 0.0;
        }
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0.0) {
            return position;
        }
        if (looping) {
            return position % durationSeconds;
        }
        return Math.min(position, durationSeconds);
    }

    static double estimatePosition(
            PlaybackSession.Snapshot session,
            long anchorNanos,
            long nowNanos
    ) {
        Objects.requireNonNull(session, "session cannot be null");
        return estimatePosition(
                session.state(),
                session.positionSeconds(),
                session.durationSeconds(),
                session.pitch(),
                session.looping(),
                anchorNanos,
                nowNanos
        );
    }

    static double estimatePosition(
            PlaybackSession.State state,
            double positionSeconds,
            double durationSeconds,
            float pitch,
            boolean looping,
            long anchorNanos,
            long nowNanos
    ) {
        if (state != PlaybackSession.State.PLAYING) {
            return positionSeconds;
        }
        double elapsedSeconds =
                Math.max(0L, nowNanos - anchorNanos) / 1_000_000_000.0;
        double estimated = positionSeconds + elapsedSeconds * pitch;
        return normalizePosition(estimated, durationSeconds, looping);
    }

    // --------------------------------------------------
    // Validation
    // --------------------------------------------------

    static double validatePosition(double position) {
        return requireNonNegative(position, "playback position");
    }

    static double validateDuration(double durationSeconds) {
        return requireNonNegative(durationSeconds, "duration");
    }

    static float validateVolume(float volume) {
        return requireNonNegative(volume, "volume");
    }

    static float validatePitch(float pitch) {
        return requireNonNegative(pitch, "pitch");
    }

    static double requireNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }

    static float requireNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }
}