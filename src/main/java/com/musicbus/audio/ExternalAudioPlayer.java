package com.musicbus.audio;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Out-of-process BusedAudioPlayer backed by musicbus-player.exe.
 *
 * Completely agnostic to Minecraft. All IPC is asynchronous so caller threads
 * (such as the game tick) never block on pipe writes, process restarts, or audio I/O.
 */
public final class ExternalAudioPlayer implements BusedAudioPlayer {

    public enum WindowMode {
        MINIMIZED,
        HIDDEN
    }

    private static final long STARTUP_READY_TIMEOUT_MS = 2_500L;
    private static final long STATE_OVERRIDE_GRACE_NANOS = 400_000_000L;
    private static final long RESTART_WINDOW_NANOS = 30_000_000_000L;
    private static final int MAX_RESTARTS_IN_WINDOW = 5;
    private static final long[] RESTART_BACKOFF_MS = { 150L, 500L, 1_500L, 3_000L };

    private final Path executablePath;
    private final WindowMode windowMode;

    private final Object stateLock = new Object();
    private final Object queueLock = new Object();
    private final Object processLock = new Object();

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean permanentlyFailed = new AtomicBoolean(false);
    private final boolean supportedPlatform;

    private final ArrayDeque<OutboundCommand> commandQueue = new ArrayDeque<>();
    private final ArrayDeque<Long> recentCrashNanos = new ArrayDeque<>();

    private TrackedSession trackedSession;
    private volatile String desiredDeviceName;
    private volatile List<ExternalPlayerProtocol.AudioDeviceInfo> lastKnownDevices = List.of();

    private Process process;
    private OutputStream processStdin;
    private InputStream processStdout;
    private InputStream processStderr;
    private UUID loadedSessionIdOnProcess;
    private CountDownLatch readyLatch;
    private volatile boolean processReady;

    private final Thread writerThread;
    private Thread readerThread;
    private Thread stderrThread;
    private Thread supervisorThread;
    private final Thread shutdownHook;

    public ExternalAudioPlayer() {
        this(null, null, null, WindowMode.MINIMIZED);
    }

    public ExternalAudioPlayer(
            Path customExecutablePath,
            Path binaryCacheRoot,
            String initialDeviceName,
            WindowMode windowMode
    ) {
        this.desiredDeviceName = (initialDeviceName != null && !initialDeviceName.isBlank())
                ? initialDeviceName
                : null;
        this.windowMode = windowMode != null ? windowMode : WindowMode.MINIMIZED;

        Path resolved = null;
        boolean platformOk = ExternalPlayerBinary.isWindows();
        if (platformOk) {
            try {
                resolved = ExternalPlayerBinary.resolveExecutable(customExecutablePath, binaryCacheRoot);
            } catch (Exception error) {
                log("WARNING: Failed to prepare external player binary: " + error);
                platformOk = false;
            }
        } else {
            log("WARNING: ExternalAudioPlayer is only supported on Windows.");
        }

        this.executablePath = resolved;
        this.supportedPlatform = platformOk;

        if (!this.supportedPlatform) {
            this.permanentlyFailed.set(true);
            this.writerThread = null;
            this.shutdownHook = null;
            return;
        }

        this.shutdownHook = new Thread(this::closeFromShutdownHook, "musicbus-external-shutdown");
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);

        this.writerThread = new Thread(this::writerLoop, "musicbus-ipc-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();

        startProcessAndAwaitReady();
    }

    // --------------------------------------------------
    // AudioPlayer identity & capabilities
    // --------------------------------------------------

    @Override
    public String getId() {
        return "external";
    }

    @Override
    public boolean isAvailable() {
        return supportedPlatform && !closed.get() && !permanentlyFailed.get();
    }

    @Override
    public boolean supports(PlaybackSession.Snapshot session) {
        return isAvailable() && session != null && session.hasOggData();
    }

    // --------------------------------------------------
    // Playback lifecycle (non-blocking)
    // --------------------------------------------------

    @Override
    public void play(PlaybackSession.Snapshot session) {
        if (session == null) {
            throw new IllegalArgumentException("Session cannot be null");
        }
        if (!isAvailable()) {
            throw new IllegalStateException("External audio player is unavailable");
        }
        if (!session.hasOggData()) {
            throw new IllegalArgumentException("ExternalAudioPlayer requires OGG payload in snapshot");
        }
        if (session.state() == PlaybackSession.State.STOPPED) {
            return;
        }

        long now = System.nanoTime();
        synchronized (stateLock) {
            trackedSession = new TrackedSession(session, now);
        }

        enqueue(new OutboundCommand(OutboundCommand.Kind.PLAY, session.id(), session, 0.0, 0.0f, 0.0f, false, null));
    }

    @Override
    public void stop(UUID sessionId) {
        if (sessionId == null || closed.get()) {
            return;
        }

        synchronized (stateLock) {
            if (trackedSession != null && trackedSession.sessionId.equals(sessionId)) {
                trackedSession = null;
            }
        }

        enqueue(new OutboundCommand(OutboundCommand.Kind.STOP, sessionId, null, 0.0, 0.0f, 0.0f, false, null));
    }

    @Override
    public void pause(UUID sessionId) {
        if (sessionId == null || !isAvailable()) {
            return;
        }

        long now = System.nanoTime();
        synchronized (stateLock) {
            if (trackedSession == null
                    || !trackedSession.sessionId.equals(sessionId)
                    || trackedSession.state != PlaybackSession.State.PLAYING) {
                return;
            }
            double currentPos = trackedSession.estimatePosition(now);
            trackedSession.positionSeconds = currentPos;
            trackedSession.state = PlaybackSession.State.PAUSED;
            trackedSession.anchorNanos = now;
            trackedSession.pendingStateOverride = PlaybackSession.State.PAUSED;
            trackedSession.overrideGraceUntilNanos = now + STATE_OVERRIDE_GRACE_NANOS;
            if (trackedSession.snapshot != null) {
                trackedSession.snapshot = trackedSession.snapshot.withPositionAndState(
                        currentPos,
                        PlaybackSession.State.PAUSED
                );
            }
        }

        enqueue(new OutboundCommand(OutboundCommand.Kind.PAUSE, sessionId, null, 0.0, 0.0f, 0.0f, false, null));
    }

    @Override
    public void resume(PlaybackSession.Snapshot session) {
        if (session == null) {
            throw new IllegalArgumentException("Session cannot be null");
        }
        if (!isAvailable() || session.state() != PlaybackSession.State.PLAYING) {
            return;
        }

        long now = System.nanoTime();
        synchronized (stateLock) {
            TrackedSession tracked = new TrackedSession(session, now);
            tracked.pendingStateOverride = PlaybackSession.State.PLAYING;
            tracked.overrideGraceUntilNanos = now + STATE_OVERRIDE_GRACE_NANOS;
            trackedSession = tracked;
        }

        enqueue(new OutboundCommand(OutboundCommand.Kind.RESUME, session.id(), session, 0.0, 0.0f, 0.0f, false, null));
    }

    // --------------------------------------------------
    // Playback controls (non-blocking)
    // --------------------------------------------------

    @Override
    public void seek(UUID sessionId, double positionSeconds) {
        if (sessionId == null || !isAvailable()) {
            return;
        }
        if (!Double.isFinite(positionSeconds) || positionSeconds < 0.0) {
            throw new IllegalArgumentException("Invalid playback position");
        }

        long now = System.nanoTime();
        double normalized;
        synchronized (stateLock) {
            if (trackedSession == null || !trackedSession.sessionId.equals(sessionId)) {
                return;
            }
            normalized = normalizePosition(
                    positionSeconds,
                    trackedSession.durationSeconds,
                    trackedSession.looping
            );
            trackedSession.positionSeconds = normalized;
            trackedSession.anchorNanos = now;
            if (trackedSession.snapshot != null) {
                trackedSession.snapshot = trackedSession.snapshot.withPosition(normalized);
            }
        }

        enqueue(new OutboundCommand(OutboundCommand.Kind.SEEK, sessionId, null, normalized, 0.0f, 0.0f, false, null));
    }

    @Override
    public void setVolume(UUID sessionId, float volume) {
        if (sessionId == null || !isAvailable()) {
            return;
        }
        if (!Float.isFinite(volume) || volume < 0.0f) {
            throw new IllegalArgumentException("Invalid volume");
        }

        synchronized (stateLock) {
            if (trackedSession == null || !trackedSession.sessionId.equals(sessionId)) {
                return;
            }
            if (trackedSession.snapshot != null) {
                trackedSession.snapshot = trackedSession.snapshot.withVolume(volume);
            }
        }

        enqueue(new OutboundCommand(OutboundCommand.Kind.SET_VOLUME, sessionId, null, 0.0, volume, 0.0f, false, null));
    }

    @Override
    public void setPitch(UUID sessionId, float pitch) {
        if (sessionId == null || !isAvailable()) {
            return;
        }
        if (!Float.isFinite(pitch) || pitch < 0.0f) {
            throw new IllegalArgumentException("Invalid pitch");
        }

        long now = System.nanoTime();
        synchronized (stateLock) {
            if (trackedSession == null || !trackedSession.sessionId.equals(sessionId)) {
                return;
            }
            double pos = trackedSession.estimatePosition(now);
            trackedSession.positionSeconds = pos;
            trackedSession.pitch = pitch;
            trackedSession.anchorNanos = now;
            if (trackedSession.snapshot != null) {
                trackedSession.snapshot = trackedSession.snapshot.withPositionAndPitch(pos, pitch);
            }
        }

        enqueue(new OutboundCommand(OutboundCommand.Kind.SET_PITCH, sessionId, null, 0.0, 0.0f, pitch, false, null));
    }

    @Override
    public void setLooping(UUID sessionId, boolean looping) {
        if (sessionId == null || !isAvailable()) {
            return;
        }

        long now = System.nanoTime();
        synchronized (stateLock) {
            if (trackedSession == null || !trackedSession.sessionId.equals(sessionId)) {
                return;
            }
            double pos = normalizePosition(
                    trackedSession.estimatePosition(now),
                    trackedSession.durationSeconds,
                    looping
            );
            trackedSession.positionSeconds = pos;
            trackedSession.looping = looping;
            trackedSession.anchorNanos = now;
            if (trackedSession.snapshot != null) {
                trackedSession.snapshot = trackedSession.snapshot.withPositionAndLooping(pos, looping);
            }
        }

        enqueue(new OutboundCommand(OutboundCommand.Kind.SET_LOOPING, sessionId, null, 0.0, 0.0f, 0.0f, looping, null));
    }

    // --------------------------------------------------
    // Playback status (non-blocking)
    // --------------------------------------------------

    @Override
    public Optional<PlaybackStatus> getStatus(UUID sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }

        long now = System.nanoTime();
        synchronized (stateLock) {
            if (trackedSession == null || !trackedSession.sessionId.equals(sessionId)) {
                return Optional.empty();
            }
            double pos = trackedSession.estimatePosition(now);
            return Optional.of(new PlaybackStatus(trackedSession.state, pos));
        }
    }

    // --------------------------------------------------
    // Device management helpers
    // --------------------------------------------------

    public void setOutputDevice(String deviceName) {
        String normalized = (deviceName != null && !deviceName.isBlank()) ? deviceName : "";
        this.desiredDeviceName = normalized.isEmpty() ? null : normalized;
        if (!isAvailable()) {
            return;
        }
        enqueue(new OutboundCommand(OutboundCommand.Kind.SET_DEVICE, null, null, 0.0, 0.0f, 0.0f, false, normalized));
    }

    public void refreshDeviceListAsync() {
        if (!isAvailable()) {
            return;
        }
        enqueue(new OutboundCommand(OutboundCommand.Kind.LIST_DEVICES, null, null, 0.0, 0.0f, 0.0f, false, null));
    }

    public List<ExternalPlayerProtocol.AudioDeviceInfo> getLastKnownDevices() {
        return lastKnownDevices;
    }

    // --------------------------------------------------
    // Lifecycle & shutdown
    // --------------------------------------------------

    @Override
    public void close() {
        closeInternal(false);
    }

    private void closeFromShutdownHook() {
        closeInternal(true);
    }

    private void closeInternal(boolean fromShutdownHook) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        if (!fromShutdownHook && shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown already in progress
            }
        }

        synchronized (stateLock) {
            trackedSession = null;
        }

        synchronized (queueLock) {
            commandQueue.clear();
            queueLock.notifyAll();
        }

        Process procToStop;
        OutputStream stdinToClose;
        synchronized (processLock) {
            procToStop = this.process;
            stdinToClose = this.processStdin;
        }

        if (stdinToClose != null) {
            try {
                ExternalPlayerProtocol.writeShutdown(stdinToClose);
            } catch (IOException ignored) {
            }
        }

        if (procToStop != null) {
            try {
                if (!procToStop.waitFor(450L, TimeUnit.MILLISECONDS)) {
                    procToStop.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                procToStop.destroyForcibly();
            }
        }

        closeProcessResources();

        joinQuietly(writerThread, 500L);
        joinQuietly(supervisorThread, 500L);
        joinQuietly(readerThread, 500L);
        joinQuietly(stderrThread, 500L);

        log("ExternalAudioPlayer closed.");
    }

    // --------------------------------------------------
    // Process startup, supervision & auto-restart
    // --------------------------------------------------

    private boolean startProcessAndAwaitReady() {
        CountDownLatch latch;
        synchronized (processLock) {
            if (closed.get() || permanentlyFailed.get()) {
                return false;
            }
            if (process != null && process.isAlive()) {
                return true;
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(executablePath.toString());
            cmd.add("--parent-pid");
            cmd.add(Long.toString(ProcessHandle.current().pid()));
            if (windowMode == WindowMode.HIDDEN) {
                cmd.add("--window=hidden");
            }
            String device = desiredDeviceName;
            if (device != null && !device.isBlank()) {
                cmd.add("--device");
                cmd.add(device);
            }

            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(false);
                Process started = pb.start();

                this.process = started;
                this.processStdin = new BufferedOutputStream(started.getOutputStream(), 16384);
                this.processStdout = new BufferedInputStream(started.getInputStream(), 8192);
                this.processStderr = started.getErrorStream();
                this.loadedSessionIdOnProcess = null;
                this.processReady = false;
                latch = new CountDownLatch(1);
                this.readyLatch = latch;

                Thread rThread = new Thread(
                        () -> readerLoop(started, this.processStdout),
                        "musicbus-ipc-reader"
                );
                rThread.setDaemon(true);

                Thread eThread = new Thread(
                        () -> stderrLoop(started, this.processStderr),
                        "musicbus-ipc-stderr"
                );
                eThread.setDaemon(true);

                this.readerThread = rThread;
                this.stderrThread = eThread;

                rThread.start();
                eThread.start();

            } catch (IOException error) {
                log("WARNING: Failed to launch musicbus-player.exe: " + error);
                closeProcessResources();
                return false;
            }
        }

        try {
            if (!latch.await(STARTUP_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                log("WARNING: Timed out waiting for EVT_READY from external player.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        synchronized (processLock) {
            return process != null && process.isAlive();
        }
    }

    private void onProcessDisconnected(Process exitedProcess, String reason) {
        boolean shouldRestart = false;

        synchronized (processLock) {
            if (this.process != exitedProcess) {
                return;
            }
            log("External player disconnected (" + reason + ").");
            closeProcessResourcesLocked();

            if (!closed.get() && !permanentlyFailed.get()) {
                long now = System.nanoTime();
                recentCrashNanos.addLast(now);
                while (!recentCrashNanos.isEmpty()
                        && (now - recentCrashNanos.peekFirst()) > RESTART_WINDOW_NANOS) {
                    recentCrashNanos.removeFirst();
                }

                if (recentCrashNanos.size() > MAX_RESTARTS_IN_WINDOW) {
                    permanentlyFailed.set(true);
                    log("CRITICAL: External player crashed too many times in 30s; disabling auto-restart.");
                } else {
                    shouldRestart = true;
                }
            }
        }

        if (permanentlyFailed.get()) {
            synchronized (stateLock) {
                trackedSession = null;
            }
            return;
        }

        if (shouldRestart) {
            int attemptIndex = Math.max(0, recentCrashNanos.size() - 1);
            long delayMs = RESTART_BACKOFF_MS[Math.min(attemptIndex, RESTART_BACKOFF_MS.length - 1)];
            scheduleRestart(delayMs);
        }
    }

    private void scheduleRestart(long delayMs) {
        synchronized (processLock) {
            if (closed.get() || permanentlyFailed.get()) {
                return;
            }
            if (supervisorThread != null && supervisorThread.isAlive()) {
                return;
            }

            Thread sup = new Thread(() -> {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (closed.get() || permanentlyFailed.get()) {
                    return;
                }

                log("Attempting external player restart...");
                if (startProcessAndAwaitReady()) {
                    restoreActiveSessionAfterRestart();
                } else {
                    log("WARNING: External player restart failed.");
                    Process current;
                    synchronized (processLock) {
                        current = this.process;
                    }
                    if (current == null && !closed.get()) {
                        permanentlyFailed.set(true);
                    }
                }
            }, "musicbus-supervisor");

            sup.setDaemon(true);
            this.supervisorThread = sup;
            sup.start();
        }
    }

    private void restoreActiveSessionAfterRestart() {
        PlaybackSession.Snapshot snapshotToRestore = null;
        long now = System.nanoTime();

        synchronized (stateLock) {
            if (trackedSession != null
                    && trackedSession.state != PlaybackSession.State.STOPPED
                    && trackedSession.snapshot != null
                    && trackedSession.snapshot.hasOggData()) {
                double estimatedPos = trackedSession.estimatePosition(now);
                snapshotToRestore = trackedSession.snapshot
                        .withPositionAndState(estimatedPos, trackedSession.state);
                trackedSession.positionSeconds = estimatedPos;
                trackedSession.anchorNanos = now;
            }
        }

        if (snapshotToRestore != null) {
            log("Restoring active session after player restart: " + snapshotToRestore.id());
            enqueue(new OutboundCommand(
                    OutboundCommand.Kind.PLAY,
                    snapshotToRestore.id(),
                    snapshotToRestore,
                    0.0,
                    0.0f,
                    0.0f,
                    false,
                    null
            ));
        }
    }

    private void closeProcessResources() {
        synchronized (processLock) {
            closeProcessResourcesLocked();
        }
    }

    private void closeProcessResourcesLocked() {
        this.processReady = false;
        this.loadedSessionIdOnProcess = null;
        if (this.readyLatch != null) {
            this.readyLatch.countDown();
        }

        closeQuietly(this.processStdin);
        closeQuietly(this.processStdout);
        closeQuietly(this.processStderr);
        this.processStdin = null;
        this.processStdout = null;
        this.processStderr = null;

        Process p = this.process;
        this.process = null;
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
    }

    // --------------------------------------------------
    // Command queue with coalescing
    // --------------------------------------------------

    private void enqueue(OutboundCommand cmd) {
        synchronized (queueLock) {
            if (closed.get()) {
                return;
            }

            switch (cmd.kind) {
                case PLAY -> {
                    // Drop obsolete queued transport updates before a fresh PLAY
                    commandQueue.removeIf(existing ->
                            existing.kind != OutboundCommand.Kind.SET_DEVICE
                                    && existing.kind != OutboundCommand.Kind.LIST_DEVICES);
                    commandQueue.addLast(cmd);
                }
                case STOP -> {
                    boolean removedUnflushedPlay = commandQueue.removeIf(existing ->
                            existing.kind == OutboundCommand.Kind.PLAY
                                    && cmd.sessionId.equals(existing.sessionId));
                    commandQueue.removeIf(existing ->
                            cmd.sessionId.equals(existing.sessionId)
                                    && existing.kind != OutboundCommand.Kind.STOP);
                    if (!removedUnflushedPlay) {
                        commandQueue.addLast(cmd);
                    }
                }
                case SET_VOLUME -> {
                    OutboundCommand tail = commandQueue.peekLast();
                    if (tail != null
                            && tail.kind == OutboundCommand.Kind.SET_VOLUME
                            && cmd.sessionId.equals(tail.sessionId)) {
                        commandQueue.removeLast();
                    }
                    commandQueue.addLast(cmd);
                }
                default -> commandQueue.addLast(cmd);
            }

            queueLock.notifyAll();
        }
    }

    private void writerLoop() {
        while (!closed.get()) {
            OutboundCommand cmd;
            synchronized (queueLock) {
                while (commandQueue.isEmpty() && !closed.get()) {
                    try {
                        queueLock.wait();
                    } catch (InterruptedException e) {
                        if (closed.get()) {
                            return;
                        }
                    }
                }
                if (closed.get()) {
                    return;
                }
                cmd = commandQueue.pollFirst();
            }

            if (cmd == null) {
                continue;
            }

            OutputStream out;
            Process ownerProc;
            synchronized (processLock) {
                out = this.processStdin;
                ownerProc = this.process;
            }

            if (out == null || ownerProc == null || !ownerProc.isAlive()) {
                continue;
            }

            try {
                writeCommand(out, cmd);
            } catch (IOException error) {
                if (!closed.get()) {
                    onProcessDisconnected(ownerProc, "write failed: " + error.getMessage());
                }
            }
        }
    }

    private void writeCommand(OutputStream out, OutboundCommand cmd) throws IOException {
        switch (cmd.kind) {
            case PLAY -> {
                ExternalPlayerProtocol.writePlay(out, cmd.snapshot);
                synchronized (processLock) {
                    this.loadedSessionIdOnProcess = cmd.sessionId;
                }
            }
            case STOP -> {
                ExternalPlayerProtocol.writeStop(out, cmd.sessionId);
                synchronized (processLock) {
                    if (cmd.sessionId.equals(this.loadedSessionIdOnProcess)) {
                        this.loadedSessionIdOnProcess = null;
                    }
                }
            }
            case PAUSE -> ExternalPlayerProtocol.writePause(out, cmd.sessionId);
            case RESUME -> {
                boolean alreadyLoaded;
                synchronized (processLock) {
                    alreadyLoaded = cmd.sessionId.equals(this.loadedSessionIdOnProcess);
                }
                if (alreadyLoaded) {
                    ExternalPlayerProtocol.writeResume(out, cmd.snapshot);
                } else if (cmd.snapshot != null && cmd.snapshot.hasOggData()) {
                    ExternalPlayerProtocol.writePlay(out, cmd.snapshot);
                    synchronized (processLock) {
                        this.loadedSessionIdOnProcess = cmd.sessionId;
                    }
                }
            }
            case SEEK -> ExternalPlayerProtocol.writeSeek(out, cmd.sessionId, cmd.positionSeconds);
            case SET_VOLUME -> ExternalPlayerProtocol.writeSetVolume(out, cmd.sessionId, cmd.volume);
            case SET_PITCH -> ExternalPlayerProtocol.writeSetPitch(out, cmd.sessionId, cmd.pitch);
            case SET_LOOPING -> ExternalPlayerProtocol.writeSetLooping(out, cmd.sessionId, cmd.looping);
            case SET_DEVICE -> ExternalPlayerProtocol.writeSetDevice(out, cmd.deviceName);
            case LIST_DEVICES -> ExternalPlayerProtocol.writeListDevices(out);
        }
    }

    // --------------------------------------------------
    // Reader & stderr loops
    // --------------------------------------------------

    private void readerLoop(Process ownerProc, InputStream stdout) {
        try {
            while (!closed.get()) {
                ExternalPlayerProtocol.Event event = ExternalPlayerProtocol.readNextEvent(stdout);
                if (event == null) {
                    break;
                }
                handleEvent(event);
            }
        } catch (IOException error) {
            if (!closed.get()) {
                log("IPC reader error: " + error.getMessage());
            }
        } finally {
            if (!closed.get()) {
                onProcessDisconnected(ownerProc, "stdout EOF");
            }
        }
    }

    private void handleEvent(ExternalPlayerProtocol.Event event) {
        long now = System.nanoTime();

        switch (event) {
            case ExternalPlayerProtocol.ReadyEvent ready -> {
                synchronized (processLock) {
                    this.processReady = true;
                    if (this.readyLatch != null) {
                        this.readyLatch.countDown();
                    }
                }
                log("External player ready (pid=" + ready.pid()
                        + ", protocol=" + ready.protocolVersion() + ")");
            }
            case ExternalPlayerProtocol.StatusEvent status -> {
                synchronized (stateLock) {
                    if (trackedSession == null
                            || !trackedSession.sessionId.equals(status.sessionId())) {
                        return;
                    }

                    PlaybackSession.State reportedState = status.state();
                    if (trackedSession.pendingStateOverride != null) {
                        if (reportedState == trackedSession.pendingStateOverride
                                || reportedState == PlaybackSession.State.STOPPED) {
                            trackedSession.pendingStateOverride = null;
                        } else if (now < trackedSession.overrideGraceUntilNanos) {
                            reportedState = trackedSession.state;
                        } else {
                            trackedSession.pendingStateOverride = null;
                        }
                    }

                    trackedSession.state = reportedState;
                    trackedSession.positionSeconds = normalizePosition(
                            status.positionSeconds(),
                            trackedSession.durationSeconds,
                            trackedSession.looping
                    );
                    trackedSession.anchorNanos = now;

                    if (reportedState == PlaybackSession.State.STOPPED) {
                        // Release OGG byte[] immediately once track finishes
                        trackedSession.snapshot = null;
                    }
                }
            }
            case ExternalPlayerProtocol.ErrorEvent err -> {
                log("WARNING: External player error [code=" + err.errorCode()
                        + ", session=" + err.sessionId() + "]: " + err.message());

                if (!ExternalPlayerProtocol.ZERO_UUID.equals(err.sessionId())) {
                    synchronized (stateLock) {
                        if (trackedSession != null
                                && trackedSession.sessionId.equals(err.sessionId())) {
                            trackedSession.state = PlaybackSession.State.STOPPED;
                            trackedSession.snapshot = null;
                        }
                    }
                }
            }
            case ExternalPlayerProtocol.DevicesEvent devices -> {
                this.lastKnownDevices = Collections.unmodifiableList(devices.devices());
            }
            case ExternalPlayerProtocol.PongEvent ignored -> {
            }
        }
    }

    private void stderrLoop(Process ownerProc, InputStream stderr) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    log("[native] " + line);
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (!closed.get() && !ownerProc.isAlive()) {
                onProcessDisconnected(ownerProc, "process exited");
            }
        }
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private static double normalizePosition(
            double position,
            double durationSeconds,
            boolean looping
    ) {
        if (!Double.isFinite(position) || position < 0.0) {
            return 0.0;
        }
        if (durationSeconds <= 0.0) {
            return position;
        }
        if (looping) {
            return position % durationSeconds;
        }
        return Math.min(position, durationSeconds);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void joinQuietly(Thread thread, long timeoutMs) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String message) {
        System.out.println("[MusicBus] [ExternalAudioPlayer] " + message);
    }

    // --------------------------------------------------
    // Internal data structures
    // --------------------------------------------------

    private static final class TrackedSession {
        final UUID sessionId;
        final double durationSeconds;
        PlaybackSession.Snapshot snapshot;
        double positionSeconds;
        float pitch;
        boolean looping;
        PlaybackSession.State state;
        long anchorNanos;
        PlaybackSession.State pendingStateOverride;
        long overrideGraceUntilNanos;

        TrackedSession(PlaybackSession.Snapshot snapshot, long nowNanos) {
            this.sessionId = Objects.requireNonNull(snapshot.id());
            this.durationSeconds = snapshot.durationSeconds();
            this.snapshot = snapshot;
            this.positionSeconds = snapshot.positionSeconds();
            this.pitch = snapshot.pitch();
            this.looping = snapshot.looping();
            this.state = snapshot.state();
            this.anchorNanos = nowNanos;
        }

        double estimatePosition(long nowNanos) {
            if (state != PlaybackSession.State.PLAYING) {
                return positionSeconds;
            }
            double elapsed = Math.max(0L, nowNanos - anchorNanos) / 1_000_000_000.0;
            double estimated = positionSeconds + elapsed * pitch;
            return normalizePosition(estimated, durationSeconds, looping);
        }
    }

    private record OutboundCommand(
            Kind kind,
            UUID sessionId,
            PlaybackSession.Snapshot snapshot,
            double positionSeconds,
            float volume,
            float pitch,
            boolean looping,
            String deviceName
    ) {
        enum Kind {
            PLAY,
            STOP,
            PAUSE,
            RESUME,
            SEEK,
            SET_VOLUME,
            SET_PITCH,
            SET_LOOPING,
            SET_DEVICE,
            LIST_DEVICES
        }
    }
}