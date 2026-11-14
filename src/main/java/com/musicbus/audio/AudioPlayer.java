package com.musicbus.audio;

import java.util.Optional;
import java.util.UUID;

/**
 * Common contract for all MusicBus audio audioPlayers.
 *
 * An AudioPlayer is responsible for actual audio playback.
 * It must not own or modify the logical PlaybackSession state.
 *
 * PlaybackSession remains the authoritative source of playback state.
 *
 * Implementations must be thread-safe and must not perform
 * potentially blocking device or IPC operations on the caller thread.
 */
public interface AudioPlayer extends AutoCloseable {

    /**
     * Returns a stable audio player identifier.
     *
     * Examples: "vanilla", "mock", "external".
     */
    String getId();

    /**
     * Indicates whether this audio player is currently available.
     *
     * For example, an external player may be unavailable
     * if its process has not started successfully.
     */
    boolean isAvailable();

    /**
     * Checks whether this audio player can handle the given session.
     */
    boolean supports(PlaybackSession.Snapshot session);

    // --------------------------------------------------
    // Playback lifecycle
    // --------------------------------------------------

    /**
     * Starts playback or replaces the currently playing session.
     *
     * The complete snapshot allows a audio player to restore
     * playback from an arbitrary position.
     */
    void play(PlaybackSession.Snapshot session);

    /**
     * Permanently stops the specified audio player playback.
     *
     * Does not modify the logical PlaybackSession.
     */
    void stop(UUID sessionId);

    /**
     * Resumes playback using the supplied session snapshot.
     */
    void resume(PlaybackSession.Snapshot session);

    // --------------------------------------------------
    // Playback state
    // --------------------------------------------------

    /**
     * Queries the actual audio player playback state.
     *
     * Empty means that the audio player has no information
     * about the specified session.
     */
    Optional<PlaybackStatus> getStatus(UUID sessionId);

    /**
     * Convenience method for checking actual playback activity.
     */
    default boolean isActive(UUID sessionId) {

        return getStatus(sessionId)
                .map(status ->
                        status.state() == PlaybackSession.State.PLAYING
                )
                .orElse(false);
    }

    // --------------------------------------------------
    // AudioPlayer lifecycle
    // --------------------------------------------------

    /**
     * Releases audio player resources.
     *
     * Implementations should stop their playback and
     * release associated resources.
     */
    @Override
    default void close() {
    }

    // --------------------------------------------------
    // AudioPlayer status
    // --------------------------------------------------

    /**
     * Represents the audio player's observed playback state.
     *
     * Unlike PlaybackSession.Snapshot, this describes
     * the actual audio player rather than the logical session.
     */
    record PlaybackStatus(
            PlaybackSession.State state,
            double positionSeconds
    ) {

        public PlaybackStatus {

            if (state == null) {
                throw new IllegalArgumentException(
                        "Playback state cannot be null"
                );
            }

            if (!Double.isFinite(positionSeconds)
                    || positionSeconds < 0.0) {

                throw new IllegalArgumentException(
                        "Invalid playback position"
                );
            }
        }
    }
}
