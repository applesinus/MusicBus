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

        double finalPosition = calculatePosition(
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

        double currentPosition = calculatePosition(
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

        if (!Double.isFinite(positionSeconds)
                || positionSeconds < 0.0) {

            throw new IllegalArgumentException(
                    "Invalid playback position"
            );
        }

        PlaybackSession.Snapshot session = currentSession;
        double normalized = normalizePosition(
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

        validateNonNegative(volume, "volume");

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

        validateNonNegative(pitch, "pitch");

        long now = System.nanoTime();
        PlaybackSession.Snapshot session = currentSession;
        double currentPosition = calculatePosition(
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
        double currentPosition = normalizePosition(
                calculatePosition(session, anchorNanos, now),
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
        double position = calculatePosition(
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

    private static double calculatePosition(
            PlaybackSession.Snapshot session,
            long anchorNanos,
            long nowNanos
    ) {

        if (session.state() != PlaybackSession.State.PLAYING) {
            return session.positionSeconds();
        }

        double elapsedSeconds =
                Math.max(0L, nowNanos - anchorNanos)
                        / 1_000_000_000.0;

        double currentPosition =
                session.positionSeconds()
                        + elapsedSeconds * session.pitch();

        return normalizePosition(
                currentPosition,
                session.durationSeconds(),
                session.looping()
        );
    }

    private static double normalizePosition(
            double position,
            double durationSeconds,
            boolean looping
    ) {

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