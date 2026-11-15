package com.musicbus.audio;

import java.util.Optional;
import java.util.UUID;

/**
 * Mock implementation of AudioPlayer.
 *
 * Simulates audio playback using a monotonic wall-clock timer
 * without producing actual sound.
 */
public final class MockAudioPlayer implements BusedAudioPlayer {

    private PlaybackSession.Snapshot currentSession;
    private long anchorNanos;

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
        anchorNanos = System.nanoTime();

        log("Playback started: " + session.id());
        log("Resource: " + session.resourceId());
        if (session.oggPath() != null) {
            log("OGG: " + session.oggPath()
                    + " (" + String.format(
                            java.util.Locale.ROOT,
                            "%.2fs",
                            session.durationSeconds()
                    ) + ")");
        }
        log("Position: " + session.positionSeconds() + "s");
    }

    @Override
    public synchronized void stop(UUID sessionId) {

        if (!isCurrentSession(sessionId)) {
            return;
        }

        double finalPosition = PlaybackMath.estimatePosition(
                currentSession,
                anchorNanos,
                System.nanoTime()
        );

        log("Playback stopped: " + sessionId
                + " at " + finalPosition + "s");

        currentSession = null;
        anchorNanos = 0L;
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

        double currentPosition = PlaybackMath.estimatePosition(
                session,
                anchorNanos,
                System.nanoTime()
        );

        currentSession = session.withPositionAndState(
                currentPosition,
                PlaybackSession.State.PAUSED
        );

        log("Playback paused: " + sessionId
                + " at " + currentPosition + "s");
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
        anchorNanos = System.nanoTime();

        log("Playback resumed: " + session.id()
                + " at " + session.positionSeconds() + "s");
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

        PlaybackMath.validatePosition(positionSeconds);

        PlaybackSession.Snapshot session = currentSession;
        double normalized = PlaybackMath.normalizePosition(
                positionSeconds,
                session.durationSeconds(),
                session.looping()
        );

        currentSession = session.withPosition(normalized);
        anchorNanos = System.nanoTime();

        log("Playback seek: " + normalized + "s");
    }

    @Override
    public synchronized void setVolume(
            UUID sessionId,
            float volume
    ) {

        if (!isCurrentSession(sessionId)) {
            return;
        }

        PlaybackMath.validateVolume(volume);

        PlaybackSession.Snapshot session = currentSession;

        currentSession = session.withVolume(volume);

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

        PlaybackMath.validatePitch(pitch);

        long now = System.nanoTime();
        PlaybackSession.Snapshot session = currentSession;
        double currentPosition = PlaybackMath.estimatePosition(
                session,
                anchorNanos,
                now
        );

        currentSession = session.withPositionAndPitch(
                currentPosition,
                pitch
        );
        anchorNanos = now;

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

        long now = System.nanoTime();
        PlaybackSession.Snapshot session = currentSession;
        double currentPosition = PlaybackMath.normalizePosition(
                PlaybackMath.estimatePosition(session, anchorNanos, now),
                session.durationSeconds(),
                looping
        );

        currentSession = session.withPositionAndLooping(
                currentPosition,
                looping
        );
        anchorNanos = now;

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

        long now = System.nanoTime();
        PlaybackSession.Snapshot session = currentSession;
        double position = PlaybackMath.estimatePosition(
                session,
                anchorNanos,
                now
        );

        if (session.state() == PlaybackSession.State.PLAYING
                && session.durationSeconds() > 0.0
                && !session.looping()
                && position >= session.durationSeconds()) {

            double endPosition = session.durationSeconds();

            currentSession = session.withPositionAndState(
                    endPosition,
                    PlaybackSession.State.STOPPED
            );

            return Optional.of(
                    new PlaybackStatus(
                            PlaybackSession.State.STOPPED,
                            endPosition
                    )
            );
        }

        return Optional.of(
                new PlaybackStatus(
                        session.state(),
                        position
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

    private static void log(String message) {
        System.out.println(
                "[MusicBus] [MockAudioPlayer] " + message
        );
    }

    
}