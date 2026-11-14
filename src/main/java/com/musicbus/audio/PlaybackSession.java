package com.musicbus.audio;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents one logical music playback session.
 *
 * Acts as a blind state mediator between AudioPlayer backends:
 * it never estimates elapsed time on its own and only stores
 * explicitly set or synchronized playback state.
 */
public final class PlaybackSession {

    public enum State {
        PLAYING,
        PAUSED,
        STOPPED
    }

    private final UUID id;
    private final String resourceId;
    private final double durationSeconds;

    private double positionSeconds;

    private float volume;
    private float pitch;

    private boolean looping;

    private State state;

    public PlaybackSession(
            String resourceId,
            double durationSeconds,
            double initialPositionSeconds,
            float volume,
            float pitch,
            boolean looping
    ) {

        this.id = UUID.randomUUID();

        this.resourceId = Objects.requireNonNull(
                resourceId,
                "resourceId cannot be null"
        );

        if (resourceId.isBlank()) {
            throw new IllegalArgumentException(
                    "resourceId cannot be blank"
            );
        }

        if (!Double.isFinite(durationSeconds)
                || durationSeconds < 0.0) {
            throw new IllegalArgumentException(
                    "Invalid duration"
            );
        }

        validatePosition(initialPositionSeconds);
        validateVolume(volume);
        validatePitch(pitch);

        this.durationSeconds = durationSeconds;
        this.volume = volume;
        this.pitch = pitch;
        this.looping = looping;

        this.positionSeconds = normalizePosition(
                initialPositionSeconds
        );

        this.state = State.PLAYING;
    }

    // ------------------------------------------
    // Identity
    // ------------------------------------------

    public UUID getId() {
        return id;
    }

    public String getResourceId() {
        return resourceId;
    }

    public double getDurationSeconds() {
        return durationSeconds;
    }

    // ------------------------------------------
    // Playback position & synchronization
    // ------------------------------------------

    public synchronized double getPositionSeconds() {
        return positionSeconds;
    }

    /**
     * Moves playback to a specific media position.
     */
    public synchronized void seek(double positionSeconds) {

        validatePosition(positionSeconds);

        this.positionSeconds = normalizePosition(
                positionSeconds
        );
    }

    /**
     * Synchronizes position only from the active AudioPlayer
     * without altering logical session state.
     */
    public synchronized void syncPosition(double positionSeconds) {

        validatePosition(positionSeconds);

        this.positionSeconds = normalizePosition(
                positionSeconds
        );
    }

    /**
     * Synchronizes both position and state from the active
     * AudioPlayer's observed PlaybackStatus.
     */
    public synchronized void sync(
            AudioPlayer.PlaybackStatus status
    ) {

        Objects.requireNonNull(status, "status cannot be null");

        this.positionSeconds = normalizePosition(
                status.positionSeconds()
        );
        this.state = status.state();
    }

    private double normalizePosition(double position) {

        if (durationSeconds <= 0.0) {
            return Math.max(0.0, position);
        }

        if (looping) {
            return position % durationSeconds;
        }

        return Math.min(
                Math.max(0.0, position),
                durationSeconds
        );
    }

    // ------------------------------------------
    // Playback lifecycle
    // ------------------------------------------

    public synchronized void pause() {

        if (state != State.PLAYING) {
            return;
        }

        state = State.PAUSED;
    }

    public synchronized void resume() {

        if (state == State.STOPPED) {
            throw new IllegalStateException(
                    "A stopped session cannot be resumed"
            );
        }

        if (state == State.PLAYING) {
            return;
        }

        state = State.PLAYING;
    }

    public synchronized void stop() {

        if (state == State.STOPPED) {
            return;
        }

        state = State.STOPPED;
    }

    public synchronized State getState() {
        return state;
    }

    // ------------------------------------------
    // Playback parameters
    // ------------------------------------------

    public synchronized float getVolume() {
        return volume;
    }

    public synchronized void setVolume(float volume) {

        validateVolume(volume);

        this.volume = volume;
    }

    public synchronized float getPitch() {
        return pitch;
    }

    public synchronized void setPitch(float pitch) {

        validatePitch(pitch);

        this.pitch = pitch;
    }

    public synchronized boolean isLooping() {
        return looping;
    }

    public synchronized void setLooping(boolean looping) {

        this.looping = looping;
        this.positionSeconds = normalizePosition(positionSeconds);
    }

    // ------------------------------------------
    // Atomic state snapshot
    // ------------------------------------------

    /**
     * Captures the last synchronized session state.
     *
     * AudioPlayer adapters use this during handoff.
     */
    public synchronized Snapshot snapshot() {

        return new Snapshot(
                id,
                resourceId,
                positionSeconds,
                durationSeconds,
                volume,
                pitch,
                looping,
                state
        );
    }

    // ------------------------------------------
    // Validation
    // ------------------------------------------

    private static void validatePosition(double position) {

        if (!Double.isFinite(position) || position < 0.0) {
            throw new IllegalArgumentException(
                    "Invalid playback position"
            );
        }
    }

    private static void validateVolume(float volume) {

        if (!Float.isFinite(volume) || volume < 0.0f) {
            throw new IllegalArgumentException(
                    "Invalid volume"
            );
        }
    }

    private static void validatePitch(float pitch) {

        if (!Float.isFinite(pitch) || pitch < 0.0f) {
            throw new IllegalArgumentException(
                    "Invalid pitch"
            );
        }
    }

    // ------------------------------------------
    // Snapshot
    // ------------------------------------------

    public record Snapshot(
            UUID id,
            String resourceId,
            double positionSeconds,
            double durationSeconds,
            float volume,
            float pitch,
            boolean looping,
            State state
    ) {}
}