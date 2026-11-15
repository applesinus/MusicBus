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
    private final String oggPath;
    private final byte[] oggData;
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
        this(
                resourceId,
                null,
                null,
                durationSeconds,
                initialPositionSeconds,
                volume,
                pitch,
                looping
        );
    }

    public PlaybackSession(
            String resourceId,
            String oggPath,
            byte[] oggData,
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

        if (oggPath != null && oggPath.isBlank()) {
            throw new IllegalArgumentException(
                    "oggPath cannot be blank"
            );
        }

        if (oggData != null && oggData.length == 0) {
            throw new IllegalArgumentException(
                    "oggData cannot be empty"
            );
        }

        PlaybackMath.validateDuration(durationSeconds);
        PlaybackMath.validatePosition(initialPositionSeconds);
        PlaybackMath.validateVolume(volume);
        PlaybackMath.validatePitch(pitch);

        this.oggPath = oggPath;
        this.oggData = oggData;
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
    // Identity & resource payload
    // ------------------------------------------

    public UUID getId() {
        return id;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getOggPath() {
        return oggPath;
    }

    public byte[] getOggData() {
        return oggData;
    }

    public boolean hasOggData() {
        return oggData != null && oggData.length > 0;
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

        PlaybackMath.validatePosition(positionSeconds);

        this.positionSeconds = normalizePosition(
                positionSeconds
        );
    }

    /**
     * Synchronizes position only from the active AudioPlayer
     * without altering logical session state.
     */
    public synchronized void syncPosition(double positionSeconds) {

        PlaybackMath.validatePosition(positionSeconds);

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
        return PlaybackMath.normalizePosition(
                position,
                durationSeconds,
                looping
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

        PlaybackMath.validateVolume(volume);

        this.volume = volume;
    }

    public synchronized float getPitch() {
        return pitch;
    }

    public synchronized void setPitch(float pitch) {

        PlaybackMath.validatePitch(pitch);

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
                oggPath,
                oggData,
                positionSeconds,
                durationSeconds,
                volume,
                pitch,
                looping,
                state
        );
    }

    // ------------------------------------------
    // Snapshot
    // ------------------------------------------

    public record Snapshot(
            UUID id,
            String resourceId,
            String oggPath,
            byte[] oggData,
            double positionSeconds,
            double durationSeconds,
            float volume,
            float pitch,
            boolean looping,
            State state
    ) {

        public boolean hasOggData() {
            return oggData != null && oggData.length > 0;
        }

        public Snapshot withState(State newState) {
            return new Snapshot(
                    id,
                    resourceId,
                    oggPath,
                    oggData,
                    positionSeconds,
                    durationSeconds,
                    volume,
                    pitch,
                    looping,
                    newState
            );
        }

        public Snapshot withPosition(double newPositionSeconds) {
            return new Snapshot(
                    id,
                    resourceId,
                    oggPath,
                    oggData,
                    newPositionSeconds,
                    durationSeconds,
                    volume,
                    pitch,
                    looping,
                    state
            );
        }

        public Snapshot withPositionAndState(
                double newPositionSeconds,
                State newState
        ) {
            return new Snapshot(
                    id,
                    resourceId,
                    oggPath,
                    oggData,
                    newPositionSeconds,
                    durationSeconds,
                    volume,
                    pitch,
                    looping,
                    newState
            );
        }

        public Snapshot withVolume(float newVolume) {
            return new Snapshot(
                    id,
                    resourceId,
                    oggPath,
                    oggData,
                    positionSeconds,
                    durationSeconds,
                    newVolume,
                    pitch,
                    looping,
                    state
            );
        }

        public Snapshot withPositionAndPitch(
                double newPositionSeconds,
                float newPitch
        ) {
            return new Snapshot(
                    id,
                    resourceId,
                    oggPath,
                    oggData,
                    newPositionSeconds,
                    durationSeconds,
                    volume,
                    newPitch,
                    looping,
                    state
            );
        }

        public Snapshot withPositionAndLooping(
                double newPositionSeconds,
                boolean newLooping
        ) {
            return new Snapshot(
                    id,
                    resourceId,
                    oggPath,
                    oggData,
                    newPositionSeconds,
                    durationSeconds,
                    volume,
                    pitch,
                    newLooping,
                    state
            );
        }
    }
}