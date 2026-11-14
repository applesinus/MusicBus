package com.musicbus.audio;

import java.util.UUID;

/**
 * Extended contract for MusicBus bused audio players
 * (such as mock or external process players) that support
 * direct transport and parameter control.
 */
public interface BusedAudioPlayer extends AudioPlayer {

    /**
     * Pauses the specified audio player playback.
     */
    void pause(UUID sessionId);

    /**
     * Changes the current playback position.
     */
    void seek(UUID sessionId, double positionSeconds);

    /**
     * Changes playback volume.
     */
    void setVolume(UUID sessionId, float volume);

    /**
     * Changes playback pitch.
     */
    void setPitch(UUID sessionId, float pitch);

    /**
     * Changes looping behavior.
     */
    void setLooping(UUID sessionId, boolean looping);
}