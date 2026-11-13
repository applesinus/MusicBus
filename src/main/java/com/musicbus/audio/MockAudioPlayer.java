package com.musicbus.audio;

import java.util.Optional;
import java.util.UUID;

/**
 * Mock implementation of AudioPlayer.
 *
 * Simulates audio playback without producing actual sound.
 * Useful for testing MusicBus session management and
 * audio player switching.
 */
public final class MockAudioPlayer implements AudioPlayer {

    private PlaybackSession.Snapshot currentSession;

    public MockAudioPlayer() {}

    // --------------------------------------------------
    // AudioPlayer identity
    // --------------------------------------------------

    @Override
    public String getId() {
        return "mock";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean supports(PlaybackSession.Snapshot session) {
        return session != null;
    }

    // --------------------------------------------------
    // Playback lifecycle
    // --------------------------------------------------

    @Override
    public synchronized void play(
            PlaybackSession.Snapshot session
    ) {

        if (session == null) {
            throw new IllegalArgumentException(
                    "Session cannot be null"
            );
        }

        if (session.state() == PlaybackSession.State.STOPPED) {
            return;
        }

        currentSession = session;

        log("Playback started: " + session.id());
        log("Resource: " + session.resourceId());
        log("Position: " + session.positionSeconds() + "s");
    }

    @Override
    public synchronized void stop(UUID sessionId) {

        if (!isCurrentSession(sessionId)) {
            return;
        }

        log("Playback stopped: " + sessionId);

        currentSession = null;
    }

    @Override
    public synchronized void pause(UUID sessionId) {

        if (!isCurrentSession(sessionId)) {
            return;
        }

        PlaybackSession.Snapshot session = currentSession;

        if (session.state() != PlaybackSession.State.PLAYING) {
            return;
        }

        currentSession = new PlaybackSession.Snapshot(
                session.id(),
                session.resourceId(),
                session.positionSeconds(),
                session.durationSeconds(),
                session.volume(),
                session.pitch(),
                session.looping(),
                PlaybackSession.State.PAUSED
        );

        log("Playback paused: " + sessionId);
    }

    @Override
    public synchronized void resume(
            PlaybackSession.Snapshot session
    ) {

        if (session == null) {
            throw new IllegalArgumentException(
                    "Session cannot be null"
            );
        }

        if (session.state() != PlaybackSession.State.PLAYING) {
            return;
        }

        currentSession = session;

        log("Playback resumed: " + session.id());
    }

    // --------------------------------------------------
    // Playback controls
    // --------------------------------------------------

    @Override
    public synchronized void seek(
            UUID sessionId,
            double positionSeconds
    ) {

        if (!isCurrentSession(sessionId)) {
            return;
        }

        if (!Double.isFinite(positionSeconds)
                || positionSeconds < 0.0) {

            throw new IllegalArgumentException(
                    "Invalid playback position"
            );
        }

        PlaybackSession.Snapshot session = currentSession;

        currentSession = new PlaybackSession.Snapshot(
                session.id(),
                session.resourceId(),
                positionSeconds,
                session.durationSeconds(),
                session.volume(),
                session.pitch(),
                session.looping(),
                session.state()
        );

        log("Playback seek: " + positionSeconds + "s");
    }

    @Override
    public synchronized void setVolume(
            UUID sessionId,
            float volume
    ) {

        if (!isCurrentSession(sessionId)) {
            return;
        }

        validateNonNegative(volume, "volume");

        PlaybackSession.Snapshot session = currentSession;

        currentSession = new PlaybackSession.Snapshot(
                session.id(),
                session.resourceId(),
                session.positionSeconds(),
                session.durationSeconds(),
                volume,
                session.pitch(),
                session.looping(),
                session.state()
        );

        log("Volume changed: " + volume);
    }

    @Override
    public synchronized void setPitch(
            UUID sessionId,
            float pitch
    ) {

        if (!isCurrentSession(sessionId)) {
            return;
        }

        validateNonNegative(pitch, "pitch");

        PlaybackSession.Snapshot session = currentSession;

        currentSession = new PlaybackSession.Snapshot(
                session.id(),
                session.resourceId(),
                session.positionSeconds(),
                session.durationSeconds(),
                session.volume(),
                pitch,
                session.looping(),
                session.state()
        );

        log("Pitch changed: " + pitch);
    }

    @Override
    public synchronized void setLooping(
            UUID sessionId,
            boolean looping
    ) {

        if (!isCurrentSession(sessionId)) {
            return;
        }

        PlaybackSession.Snapshot session = currentSession;

        currentSession = new PlaybackSession.Snapshot(
                session.id(),
                session.resourceId(),
                session.positionSeconds(),
                session.durationSeconds(),
                session.volume(),
                session.pitch(),
                looping,
                session.state()
        );

        log("Looping changed: " + looping);
    }

    // --------------------------------------------------
    // Playback state
    // --------------------------------------------------

    @Override
    public synchronized Optional<PlaybackStatus> getStatus(
            UUID sessionId
    ) {

        if (!isCurrentSession(sessionId)) {
            return Optional.empty();
        }

        return Optional.of(
                new PlaybackStatus(
                        currentSession.state(),
                        currentSession.positionSeconds()
                )
        );
    }

    // --------------------------------------------------
    // Internal helpers
    // --------------------------------------------------

    private boolean isCurrentSession(UUID sessionId) {

        return currentSession != null
                && currentSession.id().equals(sessionId);
    }

    private static void validateNonNegative(
            float value,
            String name
    ) {

        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(
                    "Invalid " + name
            );
        }
    }

    private static void log(String message) {
        System.out.println(
                "[MusicBus] [MockAudioPlayer] " + message
        );
    }
}
