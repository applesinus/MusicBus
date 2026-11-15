package com.musicbus.core;

import com.musicbus.audio.AudioPlayer;
import com.musicbus.audio.BusedAudioPlayer;
import com.musicbus.audio.ExternalAudioPlayer;
import com.musicbus.audio.MockAudioPlayer;
import com.musicbus.audio.OggResourceResolver;
import com.musicbus.audio.PlaybackSession;
import com.musicbus.audio.VanillaAudioPlayer;
import com.musicbus.config.MusicBusConfig;

import javax.sound.sampled.AudioFormat;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Central runtime coordinator and instrumentation hook target for MusicBus.
 *
 * Owns agent initialization, configuration state, and live position-preserving
 * transitions (with rollback) between {@link VanillaAudioPlayer} and
 * {@link BusedAudioPlayer}, using {@link PlaybackSession} as a neutral state
 * mediator.
 *
 * Handles {@code SoundEngine}, {@code AbstractSoundInstance}, and
 * {@code SoundBufferLibrary} hooks to route {@code SoundSource.MUSIC} playback,
 * synchronize volume and pause/stop lifecycle events, lock resolved random
 * sound selections across handoffs, and seek vanilla {@code AudioStream}
 * futures when returning playback to Minecraft.
 */
public final class MusicBusManager {

    private static volatile boolean enabled;
    private static volatile boolean initialized;

    private static final Object STATE_LOCK = new Object();
    private static final Object TRANSITION_LOCK = new Object();

    private static final ThreadLocal<Boolean> AUDIO_PLAYER_TRANSITION =
            ThreadLocal.withInitial(() -> false);

    private static final ThreadLocal<Double> PENDING_RESUME_SECONDS =
            new ThreadLocal<>();

    private static final Map<Object, PlaybackSession> SESSIONS =
            new IdentityHashMap<>();

    private static final Map<Object, Object> LOCKED_SOUNDS =
            new IdentityHashMap<>();

    private static final Map<Object, Float> BASE_VOLUMES =
            new IdentityHashMap<>();

    private static final Set<Object> BUS_ROUTED_INSTANCES =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private static volatile Method calculateVolumeMethod;
    private static volatile Object musicSoundSource;

    private static BusedAudioPlayer busedPlayer;
    private static VanillaAudioPlayer vanillaPlayer;

    private static PlaybackSession currentSession;
    private static Object currentSoundInstance;

    private MusicBusManager() {}

    // --------------------------------------------------
    // Initialization
    // --------------------------------------------------

    public static void initialize(
            Instrumentation instrumentation
    ) {

        synchronized (TRANSITION_LOCK) {

            if (initialized) {
                return;
            }

            if (instrumentation == null) {
                throw new IllegalArgumentException(
                        "Instrumentation cannot be null"
                );
            }

            log("Initializing MusicBus Manager...");

            try {

                enabled = MusicBusConfig.load();

                vanillaPlayer = new VanillaAudioPlayer();
                busedPlayer = createBusedPlayer();

                if (!vanillaPlayer.isAvailable()) {
                    log("WARNING: Vanilla audio player unavailable.");
                }

                if (!busedPlayer.isAvailable()) {
                    throw new IllegalStateException(
                            "Bused audio player unavailable"
                    );
                }

                instrumentation.addTransformer(
                        new MusicBusTransformer(),
                        false
                );

                initialized = true;

                log("Vanilla audio player initialized.");
                log("Bused audio player initialized (" + busedPlayer.getId() + ").");
                log("Configuration loaded: enabled=" + enabled);
                log("Configuration path: "
                        + MusicBusConfig.getLocation());

                log("MusicBus Manager initialized.");

            } catch (Exception error) {

                if (busedPlayer != null) {
                    try {
                        busedPlayer.close();
                    } catch (Exception ignored) {
                    }
                }
                if (vanillaPlayer != null) {
                    try {
                        vanillaPlayer.close();
                    } catch (Exception ignored) {
                    }
                }

                vanillaPlayer = null;
                busedPlayer = null;
                enabled = false;

                throw new IllegalStateException(
                        "MusicBus initialization failed",
                        error
                );
            }
        }
    }

    private static BusedAudioPlayer createBusedPlayer() {
        if ("mock".equalsIgnoreCase(System.getProperty("musicbus.player"))) {
            return new MockAudioPlayer();
        }
        return new ExternalAudioPlayer(
                MusicBusConfig.getPlayerExecutablePath(),
                null,
                MusicBusConfig.getOutputDevice(),
                MusicBusConfig.getWindowMode()
        );
    }

    public static void shutdown() {
        synchronized (TRANSITION_LOCK) {
            if (!initialized) {
                return;
            }
            if (busedPlayer != null) {
                try {
                    busedPlayer.close();
                } catch (Exception ignored) {
                }
            }
            if (vanillaPlayer != null) {
                try {
                    vanillaPlayer.close();
                } catch (Exception ignored) {
                }
            }
            initialized = false;
            enabled = false;
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static PlaybackSession getCurrentSession() {

        synchronized (STATE_LOCK) {
            return currentSession;
        }
    }

    public static BusedAudioPlayer getBusedPlayer() {
        return busedPlayer;
    }

    public static AudioPlayer getVanillaPlayer() {
        return vanillaPlayer;
    }

    // --------------------------------------------------
    // Configuration
    // --------------------------------------------------

    public static void setEnabled(boolean value) {

        synchronized (TRANSITION_LOCK) {

            if (!initialized) {
                log("WARNING: Manager is not initialized.");
                return;
            }

            if (enabled == value) {
                return;
            }

            boolean successful = value
                    ? enableMusicBus()
                    : disableMusicBus();

            if (!successful) {
                log("WARNING: Audio Player transition failed.");
                return;
            }

            try {

                MusicBusConfig.save(enabled);

            } catch (Exception error) {

                log("WARNING: Failed to save configuration: "
                        + error);
            }

            log("MusicBus state changed: " + enabled);
        }
    }

    // --------------------------------------------------
    // Audio Player switching
    // --------------------------------------------------

    private static boolean enableMusicBus() {

        Object instance;
        PlaybackSession session = null;

        try {

            instance = getCurrentMusic();

            if (instance == null || !isMusicInstance(instance)) {

                enabled = true;

                log("MusicBus enabled. Waiting for next music event.");

                return true;
            }

            boolean vanillaActive =
                    MinecraftReflection.invokeSoundManagerBoolean(
                            "isActive",
                            instance
                    );

            if (!vanillaActive) {

                enabled = true;

                log("MusicBus enabled. No active vanilla music.");

                return true;
            }

            session = getOrCreateSession(instance);

            if (session == null) {
                throw new IllegalStateException(
                        "Could not adopt current music"
                );
            }

            /*
             * Query exact vanilla OpenAL position before stopping
             * and store it in the blind PlaybackSession mediator.
             */
            PlaybackSession targetSession = session;

            Optional<AudioPlayer.PlaybackStatus> vanillaStatus =
                    vanillaPlayer.getStatus(targetSession.getId());

            vanillaStatus.ifPresent(targetSession::sync);

            duringAudioPlayerTransition(
                    () -> vanillaPlayer.stop(targetSession.getId())
            );

            float baseVolume;
            synchronized (STATE_LOCK) {
                baseVolume = BASE_VOLUMES.getOrDefault(instance, 1.0f);
            }
            session.setVolume(
                    calculateEffectiveMusicVolume(null, baseVolume)
            );

            PlaybackSession.Snapshot snapshot =
                    session.snapshot();

            if (snapshot.state() == PlaybackSession.State.STOPPED) {
                enabled = true;
                log("MusicBus enabled. Vanilla track already finished.");
                return true;
            }

            if (!busedPlayer.supports(snapshot)) {
                throw new IllegalStateException(
                        "Bused audio player does not support session"
                );
            }

            busedPlayer.play(snapshot);

            if (!busedPlayer.isActive(session.getId())) {
                throw new IllegalStateException(
                        "Bused audio player did not activate session"
                );
            }

            synchronized (STATE_LOCK) {
                BUS_ROUTED_INSTANCES.add(instance);
            }

            enabled = true;

            log("Vanilla -> Bused transition successful.");
            log("Session: " + session.getId());

            return true;

        } catch (Exception error) {

            log("WARNING: Could not enable MusicBus: " + error);

            /*
             * Attempt to restore vanilla playback.
             */
            if (session != null) {

                try {

                    Object vanillaInstance = findInstanceForSession(session);

                    if (vanillaInstance != null) {
                        PlaybackSession targetSession = session;

                        duringAudioPlayerTransition(
                                () -> vanillaPlayer.resume(
                                        targetSession.snapshot()
                                )
                        );

                        synchronized (STATE_LOCK) {
                            BUS_ROUTED_INSTANCES.remove(vanillaInstance);
                        }
                    }

                } catch (Exception rollbackError) {

                    log("CRITICAL: Vanilla rollback failed: "
                            + rollbackError);
                }
            }

            enabled = false;

            return false;
        }
    }

    private static boolean disableMusicBus() {

        PlaybackSession session;
        Object instance;

        synchronized (STATE_LOCK) {

            session = currentSession;
            instance = currentSoundInstance;
        }

        if (session == null || instance == null) {

            enabled = false;

            log("No active bused session. Vanilla audio player restored.");

            return true;
        }

        busedPlayer.getStatus(session.getId())
                .ifPresent(session::sync);

        PlaybackSession.Snapshot previousState =
                session.snapshot();

        try {

            /*
             * Detach bused AudioPlayer without stopping the logical session.
             */
            busedPlayer.stop(session.getId());

            synchronized (STATE_LOCK) {
                BUS_ROUTED_INSTANCES.remove(instance);
            }

            /*
             * Disable interception before invoking vanilla play.
             */
            enabled = false;

            if (previousState.state() != PlaybackSession.State.STOPPED) {
                if (previousState.state() == PlaybackSession.State.PAUSED) {
                    session.resume();
                }
                final PlaybackSession.Snapshot handoffSnapshot =
                        session.snapshot();
                duringAudioPlayerTransition(
                        () -> vanillaPlayer.resume(handoffSnapshot)
                );
            } else {
                retireSession(instance, session);
            }

            log("Bused -> Vanilla transition successful.");
            log("Session: " + session.getId());

            return true;

        } catch (Exception error) {

            log("WARNING: Could not restore vanilla playback: "
                    + error);

            /*
             * Restore BusedAudioPlayer if vanilla handoff failed.
             */
            try {

                busedPlayer.play(previousState);

                synchronized (STATE_LOCK) {
                    BUS_ROUTED_INSTANCES.add(instance);
                }

                enabled = true;

                log("Bused audio player successfully restored.");

            } catch (Exception rollbackError) {

                log("CRITICAL: Bused rollback failed: "
                        + rollbackError);

                enabled = false;
            }

            return false;
        }
    }

    // --------------------------------------------------
    // SoundEngine interception
    // --------------------------------------------------

    /**
     * Called from SoundEngine.play().
     *
     * true  = suppress original vanilla playback.
     * false = allow original vanilla playback.
     */
    public static boolean interceptMusicPlay(Object instance) {

        if (!isMusicInstance(instance)) {
            return false;
        }

        if (isAudioPlayerTransition()) {
            return false;
        }

        PlaybackSession session = getOrCreateSession(instance);

        if (session == null) {
            return false;
        }

        /*
         * Vanilla owns playback while MusicBus is disabled.
         */
        if (!enabled) {
            return false;
        }

        try {

            PlaybackSession.Snapshot snapshot =
                    session.snapshot();

            if (!busedPlayer.supports(snapshot)) {
                return false;
            }

            /*
             * Stop a previous bused session if a new track
             * replaces it.
             */
            PlaybackSession previous =
                    busedPlayerSession();

            if (previous != null && previous != session) {
                busedPlayer.stop(previous.getId());
            }

            busedPlayer.play(snapshot);

            if (!busedPlayer.isActive(session.getId())) {
                throw new IllegalStateException(
                        "Bused playback failed"
                );
            }

            synchronized (STATE_LOCK) {
                BUS_ROUTED_INSTANCES.add(instance);
            }

            log("Music intercepted: " + session.getResourceId());

            return true;

        } catch (Exception error) {

            log("WARNING: Bused playback failed: " + error);

            synchronized (STATE_LOCK) {
                BUS_ROUTED_INSTANCES.remove(instance);
            }

            /*
             * Allow vanilla playback as a fallback.
             */
            return false;
        }
    }

    /**
     * Called from SoundEngine.isActive().
     *
     * Returns true only when BusedAudioPlayer owns this instance
     * and confirms that playback is active.
     */
    public static boolean isBusActive(Object instance) {

        if (!enabled || instance == null) {
            return false;
        }

        PlaybackSession session;

        synchronized (STATE_LOCK) {

            if (!BUS_ROUTED_INSTANCES.contains(instance)) {
                return false;
            }

            session = SESSIONS.get(instance);
        }

        if (session == null || busedPlayer == null) {
            return false;
        }

        Optional<AudioPlayer.PlaybackStatus> status =
                busedPlayer.getStatus(session.getId());

        if (status.isPresent()
                && status.get().state()
                        != PlaybackSession.State.STOPPED) {
            return true;
        }

        session.stop();
        retireSession(instance, session);

        return false;
    }

    /**
     * Called from SoundEngine.stop().
     *
     * true  = suppress vanilla stop.
     * false = execute original vanilla stop.
     */
    public static boolean interceptMusicStop(Object instance) {

        if (!isMusicInstance(instance)) {
            return false;
        }

        if (isAudioPlayerTransition()) {
            return false;
        }

        PlaybackSession session;
        boolean busedPlayerRouted;

        synchronized (STATE_LOCK) {

            session = SESSIONS.get(instance);

            busedPlayerRouted =
                    BUS_ROUTED_INSTANCES.contains(instance);
        }

        if (session == null) {
            return false;
        }

        if (busedPlayerRouted) {

            busedPlayer.stop(session.getId());

            retireSession(instance, session);

            return true;
        }

        /*
         * Vanilla playback is stopped by the original method.
         * We only update our logical state.
         */
        session.stop();

        retireSession(instance, session);

        return false;
    }

    /**
     * Called from SoundEngine.refreshCategoryVolume(SoundSource).
     *
     * Handles both Options slider changes (MASTER / MUSIC) and
     * MusicManager.fadePlaying() updates via updateCategoryVolume().
     */
    public static void onCategoryVolumeRefreshed(
            Object soundEngine,
            Object soundSource
    ) {

        if (!initialized || soundSource == null) {
            return;
        }

        if (!(soundSource instanceof Enum<?> category)) {
            return;
        }

        String categoryName = category.name();
        if (!"MUSIC".equals(categoryName)
                && !"MASTER".equals(categoryName)) {
            return;
        }

        PlaybackSession session;
        Object instance;
        float baseVolume;
        boolean busedPlayerRouted;

        synchronized (STATE_LOCK) {
            session = currentSession;
            instance = currentSoundInstance;
            baseVolume = (instance != null)
                    ? BASE_VOLUMES.getOrDefault(instance, 1.0f)
                    : 1.0f;
            busedPlayerRouted = instance != null
                    && BUS_ROUTED_INSTANCES.contains(instance);
        }

        if (session == null
                || instance == null
                || session.getState() == PlaybackSession.State.STOPPED) {
            return;
        }

        float effectiveVolume = calculateEffectiveMusicVolume(
                soundEngine,
                baseVolume
        );

        if (Float.compare(session.getVolume(), effectiveVolume) == 0) {
            return;
        }

        session.setVolume(effectiveVolume);

        if (enabled && busedPlayerRouted && busedPlayer != null) {
            try {
                busedPlayer.setVolume(session.getId(), effectiveVolume);
            } catch (Exception error) {
                log("WARNING: Failed to update bused volume: " + error);
            }
        }
    }

    private static float calculateEffectiveMusicVolume(
            Object soundEngineObj,
            float baseVolume
    ) {

        float clampedBase = Math.max(0.0f, Math.min(1.0f, baseVolume));

        try {

            Object soundEngine = (soundEngineObj != null)
                    ? soundEngineObj
                    : MinecraftReflection.getSoundEngine();

            if (soundEngine == null) {
                return clampedBase;
            }

            Method method = calculateVolumeMethod;
            Object musicSource = musicSoundSource;

            if (method == null || musicSource == null) {

                ClassLoader loader = soundEngine.getClass().getClassLoader();
                Class<?> soundSourceClass = Class.forName(
                        "net.minecraft.sounds.SoundSource",
                        false,
                        loader
                );

                @SuppressWarnings({"unchecked", "rawtypes"})
                Object resolvedSource = Enum.valueOf(
                        (Class<? extends Enum>) soundSourceClass,
                        "MUSIC"
                );

                Method resolvedMethod = soundEngine.getClass().getDeclaredMethod(
                        "calculateVolume",
                        float.class,
                        soundSourceClass
                );
                resolvedMethod.setAccessible(true);

                musicSoundSource = resolvedSource;
                calculateVolumeMethod = resolvedMethod;

                musicSource = resolvedSource;
                method = resolvedMethod;
            }

            Object result = method.invoke(
                    soundEngine,
                    clampedBase,
                    musicSource
            );

            if (result instanceof Number number) {
                float value = number.floatValue();
                if (Float.isFinite(value)) {
                    return Math.max(0.0f, Math.min(1.0f, value));
                }
            }

        } catch (Exception error) {

            log("WARNING: Failed to calculate effective music volume: "
                    + error);
        }

        return clampedBase;
    }

    // --------------------------------------------------
    // SoundEngine lifecycle hooks
    // --------------------------------------------------

    /**
     * Called from SoundEngine.pauseAllExcept(SoundSource...).
     */
    public static void onPauseAllExcept(Object[] exceptedSources) {

        if (!initialized || !enabled || isAudioPlayerTransition()) {
            return;
        }

        if (exceptedSources != null) {
            for (Object source : exceptedSources) {
                if (source instanceof Enum<?> category
                        && "MUSIC".equals(category.name())) {
                    return;
                }
            }
        }

        PlaybackSession session;
        Object instance;
        boolean busedPlayerRouted;

        synchronized (STATE_LOCK) {
            session = currentSession;
            instance = currentSoundInstance;
            busedPlayerRouted = instance != null
                    && BUS_ROUTED_INSTANCES.contains(instance);
        }

        if (session == null
                || !busedPlayerRouted
                || busedPlayer == null
                || session.getState() != PlaybackSession.State.PLAYING) {
            return;
        }

        try {
            busedPlayer.getStatus(session.getId())
                    .ifPresent(session::sync);
        } catch (Exception ignored) {
        }

        if (session.getState() == PlaybackSession.State.STOPPED) {
            retireSession(instance, session);
            return;
        }

        if (session.getState() != PlaybackSession.State.PLAYING) {
            return;
        }

        session.pause();

        try {
            busedPlayer.pause(session.getId());
        } catch (Exception error) {
            log("WARNING: Failed to pause bused player: " + error);
        }
    }

    /**
     * Called from SoundEngine.resume().
     */
    public static void onSoundEngineResume() {

        if (!initialized || !enabled || isAudioPlayerTransition()) {
            return;
        }

        PlaybackSession session;
        Object instance;
        boolean busedPlayerRouted;

        synchronized (STATE_LOCK) {
            session = currentSession;
            instance = currentSoundInstance;
            busedPlayerRouted = instance != null
                    && BUS_ROUTED_INSTANCES.contains(instance);
        }

        if (session == null
                || !busedPlayerRouted
                || busedPlayer == null
                || session.getState() != PlaybackSession.State.PAUSED) {
            return;
        }

        session.resume();

        try {
            busedPlayer.resume(session.snapshot());
        } catch (Exception error) {
            log("WARNING: Failed to resume bused player: " + error);
        }
    }

    /**
     * Called from SoundEngine.stop(Identifier, SoundSource).
     */
    public static void onStopByIdAndSource(
            Object identifier,
            Object soundSource
    ) {

        if (!initialized || isAudioPlayerTransition()) {
            return;
        }

        if (soundSource != null) {
            if (!(soundSource instanceof Enum<?> category)
                    || !"MUSIC".equals(category.name())) {
                return;
            }
        }

        PlaybackSession session;
        Object instance;
        boolean busedPlayerRouted;

        synchronized (STATE_LOCK) {
            session = currentSession;
            instance = currentSoundInstance;
            busedPlayerRouted = instance != null
                    && BUS_ROUTED_INSTANCES.contains(instance);
        }

        if (session == null || instance == null) {
            return;
        }

        if (identifier != null
                && !identifier.toString().equals(session.getResourceId())) {
            return;
        }

        if (busedPlayerRouted && busedPlayer != null) {
            try {
                busedPlayer.stop(session.getId());
            } catch (Exception error) {
                log("WARNING: Failed to stop bused player: " + error);
            }
        }

        session.stop();
        retireSession(instance, session);
    }

    /**
     * Called from SoundEngine.stopAll().
     */
    public static void onStopAll() {

        if (!initialized || isAudioPlayerTransition()) {
            return;
        }

        PlaybackSession session;
        Object instance;
        boolean busedPlayerRouted;

        synchronized (STATE_LOCK) {
            session = currentSession;
            instance = currentSoundInstance;
            busedPlayerRouted = instance != null
                    && BUS_ROUTED_INSTANCES.contains(instance);
        }

        if (session == null) {
            return;
        }

        if (busedPlayerRouted && busedPlayer != null) {
            try {
                busedPlayer.stop(session.getId());
            } catch (Exception error) {
                log("WARNING: Failed to stop bused player on stopAll: "
                        + error);
            }
        }

        session.stop();
        if (instance != null) {
            retireSession(instance, session);
        }
    }

    // --------------------------------------------------
    // Session management
    // --------------------------------------------------

    private static PlaybackSession getOrCreateSession(
            Object instance
    ) {

        synchronized (STATE_LOCK) {

            PlaybackSession existing = SESSIONS.get(instance);

            if (existing != null
                    && existing.getState()
                    != PlaybackSession.State.STOPPED) {

                return existing;
            }
        }

        ensureSoundResolved(instance);

        PlaybackSession created;

        try {

            created = createSession(instance);

        } catch (Exception error) {

            log("WARNING: Session creation failed: " + error);

            return null;
        }

        PlaybackSession previous;
        Object previousInstance;
        boolean previousBusedPlayer;

        synchronized (STATE_LOCK) {

            PlaybackSession existing = SESSIONS.get(instance);

            if (existing != null
                    && existing.getState()
                    != PlaybackSession.State.STOPPED) {

                return existing;
            }

            previous = currentSession;
            previousInstance = currentSoundInstance;

            previousBusedPlayer = previousInstance != null
                    && BUS_ROUTED_INSTANCES.contains(previousInstance);

            SESSIONS.put(instance, created);

            currentSession = created;
            currentSoundInstance = instance;

            BUS_ROUTED_INSTANCES.remove(instance);
        }

        /*
         * Register the SoundInstance with the vanilla adapter.
         * This association is required for future handoffs.
         */
        vanillaPlayer.registerSoundInstance(
                created.getId(),
                instance
        );

        /*
         * Retire the previous logical session.
         */
        if (previous != null && previous != created) {

            if (previousBusedPlayer) {
                busedPlayer.stop(previous.getId());
            }

            previous.stop();

            if (previousInstance != null) {

                vanillaPlayer.unregisterSoundInstance(
                        previous.getId()
                );

                synchronized (STATE_LOCK) {

                    SESSIONS.remove(previousInstance);
                    LOCKED_SOUNDS.remove(previousInstance);
                    BASE_VOLUMES.remove(previousInstance);

                    BUS_ROUTED_INSTANCES.remove(
                            previousInstance
                    );
                }
            }
        }

        onSoundResolved(instance);

        log("Session created: " + created.getId());
        log("Resource: " + created.getResourceId());
        if (created.getOggPath() != null) {
            log("OGG: " + created.getOggPath()
                    + " (" + String.format(
                            Locale.ROOT,
                            "%.2fs",
                            created.getDurationSeconds()
                    ) + ")");
        }

        return created;
    }

    private static PlaybackSession createSession(
            Object instance
    ) throws Exception {

        Object identifier = MinecraftReflection.invokeNoArgs(
                instance,
                "getIdentifier"
        );

        if (identifier == null) {
            throw new IllegalStateException(
                    "SoundInstance identifier is null"
            );
        }

        String resourceId = identifier.toString();

        float baseVolume = MinecraftReflection.readFloat(
                instance,
                "getVolume",
                1.0f
        );

        float pitch = MinecraftReflection.readFloat(
                instance,
                "getPitch",
                1.0f
        );

        boolean looping = MinecraftReflection.readBoolean(
                instance,
                "isLooping",
                false
        );

        if (!Float.isFinite(baseVolume) || baseVolume < 0.0f) {
            baseVolume = 1.0f;
        }

        synchronized (STATE_LOCK) {
            BASE_VOLUMES.put(instance, baseVolume);
        }

        float effectiveVolume = calculateEffectiveMusicVolume(
                null,
                baseVolume
        );

        if (!Float.isFinite(pitch) || pitch < 0.0f) {
            pitch = 1.0f;
        }

        OggResourceResolver.ResolvedOgg resolved =
                OggResourceResolver.resolve(instance).orElse(null);

        String oggPath = resolved != null ? resolved.oggPath() : null;
        byte[] oggData = resolved != null ? resolved.oggData() : null;
        double durationSeconds = resolved != null
                ? resolved.durationSeconds()
                : 0.0;

        return new PlaybackSession(
                resourceId,
                oggPath,
                oggData,
                durationSeconds,
                0.0,
                effectiveVolume,
                pitch,
                looping
        );
    }

    private static PlaybackSession busedPlayerSession() {

        synchronized (STATE_LOCK) {
            return currentSession;
        }
    }

    private static void retireSession(
            Object instance,
            PlaybackSession session
    ) {

        synchronized (STATE_LOCK) {

            if (SESSIONS.get(instance) == session) {
                SESSIONS.remove(instance);
                LOCKED_SOUNDS.remove(instance);
                BASE_VOLUMES.remove(instance);
            }

            BUS_ROUTED_INSTANCES.remove(instance);

            if (currentSession == session) {
                currentSession = null;
                currentSoundInstance = null;
            }
        }

        vanillaPlayer.unregisterSoundInstance(
                session.getId()
        );
    }

    private static Object findInstanceForSession(
            PlaybackSession session
    ) {

        synchronized (STATE_LOCK) {

            for (Map.Entry<Object, PlaybackSession> entry
                    : SESSIONS.entrySet()) {

                if (entry.getValue() == session) {
                    return entry.getKey();
                }
            }
        }

        return null;
    }

    /**
     * Called from AbstractSoundInstance.resolve(SoundManager).
     *
     * Locks the first resolved Sound for an active music session
     * and restores it on subsequent resolve() calls so vanilla
     * playback does not switch to a different random track.
     */
    public static void onSoundResolved(Object instance) {

        if (instance == null) {
            return;
        }

        boolean tracked;
        Object lockedSound;

        synchronized (STATE_LOCK) {
            tracked = SESSIONS.containsKey(instance);
            lockedSound = tracked ? LOCKED_SOUNDS.get(instance) : null;
        }

        if (!tracked) {
            return;
        }

        try {

            if (lockedSound == null) {

                Object resolved = MinecraftReflection.invokeNoArgs(
                        instance,
                        "getSound"
                );

                if (resolved == null) {
                    return;
                }

                synchronized (STATE_LOCK) {
                    if (SESSIONS.containsKey(instance)
                            && !LOCKED_SOUNDS.containsKey(instance)) {
                        LOCKED_SOUNDS.put(instance, resolved);
                    }
                }

                log("Sound locked: " + describeSound(resolved));
                return;
            }

            Object current = MinecraftReflection.invokeNoArgs(
                    instance,
                    "getSound"
            );

            if (current != lockedSound) {
                setSoundField(instance, lockedSound);
                log("Restored locked sound: "
                        + describeSound(lockedSound));
            }

        } catch (Exception error) {

            log("WARNING: Failed to process resolved sound: "
                    + error);
        }
    }

    private static void ensureSoundResolved(Object instance) {

        if (instance == null) {
            return;
        }

        try {

            Object existing = MinecraftReflection.invokeNoArgs(
                    instance,
                    "getSound"
            );

            if (existing != null) {
                onSoundResolved(instance);
                return;
            }

            Object minecraft = MinecraftReflection.getMinecraftInstance();

            Object soundManager = MinecraftReflection.findObjectByType(
                    minecraft,
                    "SoundManager"
            );

            if (soundManager == null) {
                return;
            }

            Method resolveMethod = findResolveMethod(
                    instance.getClass()
            );

            if (resolveMethod == null) {
                return;
            }

            resolveMethod.setAccessible(true);
            resolveMethod.invoke(instance, soundManager);

        } catch (Exception error) {

            log("WARNING: Pre-resolve failed: " + error);
        }
    }

    private static Method findResolveMethod(Class<?> type) {

        Class<?> current = type;

        while (current != null) {

            for (Method method : current.getDeclaredMethods()) {

                if (method.getName().equals("resolve")
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0]
                                .getSimpleName().equals("SoundManager")) {
                    return method;
                }
            }

            current = current.getSuperclass();
        }

        return null;
    }

    private static void setSoundField(
            Object instance,
            Object sound
    ) throws Exception {

        Field field = MinecraftReflection.findField(
                instance.getClass(),
                "sound"
        );

        if (field == null) {
            throw new NoSuchFieldException("sound");
        }

        field.setAccessible(true);
        field.set(instance, sound);
    }

    private static String describeSound(Object sound) {

        if (sound == null) {
            return "null";
        }

        try {
            Object location = MinecraftReflection.invokeNoArgs(
                    sound,
                    "getLocation"
            );
            if (location != null) {
                return location.toString();
            }
        } catch (Exception ignored) {
        }

        return sound.toString();
    }

    // --------------------------------------------------
    // Vanilla stream resume / seek
    // --------------------------------------------------

    public static void setPendingResumeSeconds(double seconds) {

        if (Double.isFinite(seconds) && seconds > 0.0) {
            PENDING_RESUME_SECONDS.set(seconds);
        } else {
            PENDING_RESUME_SECONDS.remove();
        }
    }

    public static void clearPendingResumeSeconds() {
        PENDING_RESUME_SECONDS.remove();
    }

    private static Double consumePendingResumeSeconds() {

        Double seconds = PENDING_RESUME_SECONDS.get();
        PENDING_RESUME_SECONDS.remove();
        return seconds;
    }

    /**
     * Called from SoundBufferLibrary.getStream(Identifier, boolean).
     */
    public static CompletableFuture<?> wrapAudioStreamFuture(
            CompletableFuture<?> future
    ) {

        Double targetSeconds = consumePendingResumeSeconds();

        if (future == null
                || targetSeconds == null
                || !Double.isFinite(targetSeconds)
                || targetSeconds <= 0.0) {
            return future;
        }

        final double seconds = targetSeconds;

        return future.thenApply(
                stream -> seekAudioStream(stream, seconds)
        );
    }

    private static Object seekAudioStream(
            Object stream,
            double targetSeconds
    ) {

        if (stream == null || targetSeconds <= 0.0) {
            return stream;
        }

        try {

            Class<?> audioStreamClass = Class.forName(
                    "net.minecraft.client.sounds.AudioStream",
                    false,
                    stream.getClass().getClassLoader()
            );

            Method getFormatMethod = audioStreamClass.getMethod("getFormat");
            Method readMethod = audioStreamClass.getMethod("read", int.class);

            AudioFormat format = (AudioFormat) getFormatMethod.invoke(stream);

            if (format == null) {
                return stream;
            }

            int frameSize = format.getFrameSize();
            float frameRate = format.getFrameRate();
            double bytesPerSecond;

            if (frameSize > 0 && frameRate > 0.0f) {
                bytesPerSecond = (double) frameSize * (double) frameRate;
            } else {
                float sampleRate = format.getSampleRate();
                int channels = format.getChannels();
                int sampleBits = format.getSampleSizeInBits();

                if (sampleRate <= 0.0f || channels <= 0 || sampleBits <= 0) {
                    return stream;
                }

                int computedFrameSize = (channels * sampleBits) / 8;
                if (computedFrameSize <= 0) {
                    return stream;
                }

                frameSize = computedFrameSize;
                bytesPerSecond = (double) sampleRate * (double) frameSize;
            }

            long bytesToSkip = Math.round(targetSeconds * bytesPerSecond);
            if (frameSize > 1) {
                bytesToSkip -= bytesToSkip % frameSize;
            }

            if (bytesToSkip <= 0L) {
                return stream;
            }

            long remaining = bytesToSkip;
            ByteBuffer leftover = null;
            final int chunkSize = Math.max(frameSize * 4096, 16384);

            while (remaining > 0L) {

                int request = (int) Math.min(
                        chunkSize,
                        Math.max(remaining, frameSize)
                );

                ByteBuffer buffer = (ByteBuffer) readMethod.invoke(
                        stream,
                        request
                );

                if (buffer == null || !buffer.hasRemaining()) {
                    break;
                }

                int available = buffer.remaining();
                if (available <= remaining) {
                    remaining -= available;
                } else {
                    buffer.position(buffer.position() + (int) remaining);
                    leftover = buffer;
                    remaining = 0L;
                }
            }

            log("AudioStream skipped to "
                    + String.format(Locale.ROOT, "%.2fs", targetSeconds)
                    + " (bytes skipped: "
                    + (bytesToSkip - remaining)
                    + ")");

            if (leftover == null || !leftover.hasRemaining()) {
                return stream;
            }

            return wrapRemainingStream(
                    audioStreamClass,
                    stream,
                    readMethod,
                    leftover
            );

        } catch (Exception error) {

            log("WARNING: Failed to seek AudioStream to "
                    + targetSeconds + "s: " + error);

            return stream;
        }
    }

    private static Object wrapRemainingStream(
            Class<?> audioStreamClass,
            Object delegate,
            Method readMethod,
            ByteBuffer leftover
    ) {

        final ByteBuffer[] pendingRef = new ByteBuffer[] { leftover };

        return Proxy.newProxyInstance(
                audioStreamClass.getClassLoader(),
                new Class<?>[] { audioStreamClass },
                (proxy, method, args) -> {

                    String name = method.getName();

                    if (method.getDeclaringClass() == Object.class) {
                        return switch (name) {
                            case "equals" -> args != null
                                    && args.length == 1
                                    && proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> "SeekingAudioStream[" + delegate + "]";
                            default -> method.invoke(delegate, args);
                        };
                    }

                    if ("read".equals(name)
                            && args != null
                            && args.length == 1
                            && args[0] instanceof Integer requested) {

                        ByteBuffer pending = pendingRef[0];
                        if (pending != null && pending.hasRemaining()) {

                            if (requested <= 0) {
                                return ByteBuffer.allocate(0);
                            }

                            if (pending.remaining() <= requested) {
                                pendingRef[0] = null;
                                return pending;
                            }

                            ByteBuffer slice = pending.slice();
                            slice.order(pending.order());
                            slice.limit(requested);
                            pending.position(pending.position() + requested);
                            return slice;
                        }

                        try {
                            return readMethod.invoke(delegate, requested);
                        } catch (InvocationTargetException error) {
                            Throwable cause = error.getCause();
                            throw (cause != null) ? cause : error;
                        }
                    }

                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException error) {
                        Throwable cause = error.getCause();
                        throw (cause != null) ? cause : error;
                    }
                }
        );
    }

    // --------------------------------------------------
    // Audio Player transition guard
    // --------------------------------------------------

    private static boolean isAudioPlayerTransition() {
        return Boolean.TRUE.equals(AUDIO_PLAYER_TRANSITION.get());
    }

    private static void duringAudioPlayerTransition(
            CheckedRunnable action
    ) throws Exception {

        boolean previous = AUDIO_PLAYER_TRANSITION.get();

        AUDIO_PLAYER_TRANSITION.set(true);

        try {

            action.run();

        } finally {

            AUDIO_PLAYER_TRANSITION.set(previous);
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    // --------------------------------------------------
    // Minecraft reflection
    // --------------------------------------------------

    private static Object getCurrentMusic() throws Exception {

        Object minecraft = MinecraftReflection.getMinecraftInstance();

        Object musicManager = MinecraftReflection.findObjectByType(
                minecraft,
                "MusicManager"
        );

        if (musicManager == null) {
            return null;
        }

        Field field = MinecraftReflection.findField(
                musicManager.getClass(),
                "currentMusic"
        );

        if (field == null) {
            return null;
        }

        field.setAccessible(true);

        return field.get(musicManager);
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
                    && "MUSIC".equals(category.name());

        } catch (Exception ignored) {

            return false;
        }
    }

    // --------------------------------------------------
    // Logging
    // --------------------------------------------------

    private static void log(String message) {
        System.out.println("[MusicBus] " + message);
    }
}
