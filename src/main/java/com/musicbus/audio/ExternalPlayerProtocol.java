package com.musicbus.audio;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Binary Little-Endian IPC protocol shared with musicbus-player.exe.
 *
 * Frame layout:
 *   [uint32 LE: bodyLength = 1 + payloadLength]
 *   [uint8:     messageType]
 *   [byte[]:    payload]
 */
final class ExternalPlayerProtocol {

    static final int PROTOCOL_VERSION = 1;
    static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;

    // Commands (Java -> Player)
    static final int CMD_PLAY = 0x01;
    static final int CMD_STOP = 0x02;
    static final int CMD_PAUSE = 0x03;
    static final int CMD_RESUME = 0x04;
    static final int CMD_SEEK = 0x05;
    static final int CMD_SET_VOLUME = 0x06;
    static final int CMD_SET_PITCH = 0x07;
    static final int CMD_SET_LOOPING = 0x08;
    static final int CMD_SET_DEVICE = 0x09;
    static final int CMD_PING = 0x0A;
    static final int CMD_LIST_DEVICES = 0x0B;
    static final int CMD_SHUTDOWN = 0x0F;

    // Events (Player -> Java)
    static final int EVT_READY = 0x81;
    static final int EVT_STATUS = 0x82;
    static final int EVT_ERROR = 0x83;
    static final int EVT_PONG = 0x84;
    static final int EVT_DEVICES = 0x85;
    static final int EVT_LOG = 0x86;

    static final int STATE_PLAYING = 0;
    static final int STATE_PAUSED = 1;
    static final int STATE_STOPPED = 2;

    static final UUID ZERO_UUID = new UUID(0L, 0L);

    private ExternalPlayerProtocol() {}

    // --------------------------------------------------
    // Decoded events
    // --------------------------------------------------

    sealed interface Event permits ReadyEvent, StatusEvent, ErrorEvent, PongEvent, DevicesEvent, LogEvent {}

    record ReadyEvent(int protocolVersion, long pid) implements Event {}

    record StatusEvent(UUID sessionId, PlaybackSession.State state, double positionSeconds) implements Event {}

    record ErrorEvent(UUID sessionId, int errorCode, String message) implements Event {}

    record PongEvent(long timestamp) implements Event {}

    record AudioDeviceInfo(boolean isDefault, String name) {}

    record DevicesEvent(List<AudioDeviceInfo> devices) implements Event {}

    record LogEvent(String message) implements Event {}

    // --------------------------------------------------
    // Frame writing (Java -> Player)
    // --------------------------------------------------

    static void writePlay(
            OutputStream out,
            PlaybackSession.Snapshot session
    ) throws IOException {
        Objects.requireNonNull(session, "session cannot be null");
        byte[] ogg = session.oggData();
        if (ogg == null || ogg.length == 0) {
            throw new IllegalArgumentException("Snapshot must contain OGG data for CMD_PLAY");
        }

        ByteBuffer buf = allocateFrame(CMD_PLAY, 38 + ogg.length, 38);
        putUuid(buf, session.id());
        buf.putDouble(Math.max(0.0, session.positionSeconds()));
        buf.putFloat(session.volume());
        buf.putFloat(session.pitch());
        buf.put((byte) (session.looping() ? 1 : 0));
        buf.put((byte) (session.state() == PlaybackSession.State.PAUSED ? 1 : 0));
        buf.putInt(ogg.length);

        out.write(buf.array());
        out.write(ogg);
        out.flush();
    }

    static void writeStop(OutputStream out, UUID sessionId) throws IOException {
        writeUuidOnly(out, CMD_STOP, sessionId);
    }

    static void writePause(OutputStream out, UUID sessionId) throws IOException {
        writeUuidOnly(out, CMD_PAUSE, sessionId);
    }

    static void writeResume(
            OutputStream out,
            PlaybackSession.Snapshot session
    ) throws IOException {
        Objects.requireNonNull(session, "session cannot be null");
        ByteBuffer buf = allocateFrame(CMD_RESUME, 33);
        putUuid(buf, session.id());
        buf.putDouble(Math.max(0.0, session.positionSeconds()));
        buf.putFloat(session.volume());
        buf.putFloat(session.pitch());
        buf.put((byte) (session.looping() ? 1 : 0));
        sendFrame(out, buf);
    }

    static void writeSeek(
            OutputStream out,
            UUID sessionId,
            double positionSeconds
    ) throws IOException {
        ByteBuffer buf = allocateFrame(CMD_SEEK, 24);
        putUuid(buf, sessionId);
        buf.putDouble(Math.max(0.0, positionSeconds));
        sendFrame(out, buf);
    }

    static void writeSetVolume(
            OutputStream out,
            UUID sessionId,
            float volume
    ) throws IOException {
        writeUuidAndFloat(out, CMD_SET_VOLUME, sessionId, volume);
    }

    static void writeSetPitch(
            OutputStream out,
            UUID sessionId,
            float pitch
    ) throws IOException {
        writeUuidAndFloat(out, CMD_SET_PITCH, sessionId, pitch);
    }

    static void writeSetLooping(
            OutputStream out,
            UUID sessionId,
            boolean looping
    ) throws IOException {
        ByteBuffer buf = allocateFrame(CMD_SET_LOOPING, 17);
        putUuid(buf, sessionId);
        buf.put((byte) (looping ? 1 : 0));
        sendFrame(out, buf);
    }

    static void writeSetDevice(OutputStream out, String deviceName) throws IOException {
        byte[] utf8 = (deviceName == null ? "" : deviceName).getBytes(StandardCharsets.UTF_8);
        if (utf8.length > 511) {
            throw new IllegalArgumentException("Device name too long");
        }
        ByteBuffer buf = allocateFrame(CMD_SET_DEVICE, 2 + utf8.length);
        buf.putShort((short) utf8.length);
        buf.put(utf8);
        sendFrame(out, buf);
    }

    static void writePing(OutputStream out, long timestamp) throws IOException {
        ByteBuffer buf = allocateFrame(CMD_PING, 8);
        buf.putLong(timestamp);
        sendFrame(out, buf);
    }

    static void writeListDevices(OutputStream out) throws IOException {
        writeEmpty(out, CMD_LIST_DEVICES);
    }

    static void writeShutdown(OutputStream out) throws IOException {
        writeEmpty(out, CMD_SHUTDOWN);
    }

    private static void writeUuidOnly(
            OutputStream out,
            int type,
            UUID sessionId
    ) throws IOException {
        ByteBuffer buf = allocateFrame(type, 16);
        putUuid(buf, sessionId);
        sendFrame(out, buf);
    }

    private static void writeUuidAndFloat(
            OutputStream out,
            int type,
            UUID sessionId,
            float value
    ) throws IOException {
        ByteBuffer buf = allocateFrame(type, 20);
        putUuid(buf, sessionId);
        buf.putFloat(value);
        sendFrame(out, buf);
    }

    private static void writeEmpty(OutputStream out, int type) throws IOException {
        sendFrame(out, allocateFrame(type, 0));
    }

    private static ByteBuffer allocateFrame(int type, int payloadLen) {
        return allocateFrame(type, payloadLen, payloadLen);
    }

    private static ByteBuffer allocateFrame(
            int type,
            int totalPayloadLen,
            int bufferPayloadLen
    ) {
        ByteBuffer buf = ByteBuffer.allocate(5 + bufferPayloadLen).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(1 + totalPayloadLen);
        buf.put((byte) type);
        return buf;
    }

    private static void sendFrame(OutputStream out, ByteBuffer buf) throws IOException {
        out.write(buf.array());
        out.flush();
    }

    // --------------------------------------------------
    // Frame reading (Player -> Java)
    // --------------------------------------------------

    /**
     * Reads and parses the next event frame, or returns null on clean EOF.
     */
    static Event readNextEvent(InputStream in) throws IOException {
        while (true) {
            byte[] header = new byte[5];
            int headerRead = readFully(in, header, true, "Unexpected EOF mid-frame");
            if (headerRead == 0) {
                return null;
            }

            ByteBuffer hBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            long bodyLen = Integer.toUnsignedLong(hBuf.getInt());
            int type = hBuf.get() & 0xFF;

            if (bodyLen < 1L || bodyLen > MAX_FRAME_BYTES) {
                throw new IOException("Invalid IPC frame length: " + bodyLen);
            }

            int payloadLen = (int) (bodyLen - 1L);
            byte[] payload = new byte[payloadLen];
            if (payloadLen > 0) {
                readFully(in, payload, false, "Unexpected EOF reading payload");
            }

            Event parsed = parseEvent(type, payload);
            if (parsed != null) {
                return parsed;
            }
        }
    }

    private static Event parseEvent(int type, byte[] payload) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        return switch (type) {
            case EVT_READY -> {
                if (payload.length < 8) {
                    throw new IOException("Truncated EVT_READY");
                }
                int version = buf.getInt();
                long pid = Integer.toUnsignedLong(buf.getInt());
                yield new ReadyEvent(version, pid);
            }
            case EVT_STATUS -> {
                if (payload.length < 25) {
                    throw new IOException("Truncated EVT_STATUS");
                }
                UUID uuid = getUuid(buf);
                int rawState = buf.get() & 0xFF;
                double position = buf.getDouble();
                if (!Double.isFinite(position) || position < 0.0) {
                    position = 0.0;
                }
                PlaybackSession.State state = switch (rawState) {
                    case STATE_PLAYING -> PlaybackSession.State.PLAYING;
                    case STATE_PAUSED -> PlaybackSession.State.PAUSED;
                    default -> PlaybackSession.State.STOPPED;
                };
                yield new StatusEvent(uuid, state, position);
            }
            case EVT_ERROR -> {
                if (payload.length < 22) {
                    throw new IOException("Truncated EVT_ERROR");
                }
                UUID uuid = getUuid(buf);
                int code = buf.getInt();
                yield new ErrorEvent(uuid, code, readUtf8Clamped(buf));
            }
            case EVT_PONG -> {
                if (payload.length < 8) {
                    throw new IOException("Truncated EVT_PONG");
                }
                yield new PongEvent(buf.getLong());
            }
            case EVT_DEVICES -> {
                if (payload.length < 2) {
                    throw new IOException("Truncated EVT_DEVICES");
                }
                int count = Short.toUnsignedInt(buf.getShort());
                List<AudioDeviceInfo> devices = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    if (buf.remaining() < 3) {
                        break;
                    }
                    boolean isDefault = buf.get() != 0;
                    int nameLen = Short.toUnsignedInt(buf.getShort());
                    if (nameLen > buf.remaining()) {
                        break;
                    }
                    devices.add(new AudioDeviceInfo(isDefault, readUtf8(buf, nameLen)));
                }
                yield new DevicesEvent(List.copyOf(devices));
            }
            case EVT_LOG -> {
                if (payload.length < 2) {
                    throw new IOException("Truncated EVT_LOG");
                }
                yield new LogEvent(readUtf8Clamped(buf));
            }
            default -> null;
        };
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private static void putUuid(ByteBuffer buf, UUID uuid) {
        Objects.requireNonNull(uuid, "sessionId cannot be null");
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
    }

    private static UUID getUuid(ByteBuffer buf) {
        long msb = buf.getLong();
        long lsb = buf.getLong();
        return new UUID(msb, lsb);
    }

    private static String readUtf8Clamped(ByteBuffer buf) {
        int len = Math.min(Short.toUnsignedInt(buf.getShort()), buf.remaining());
        return readUtf8(buf, len);
    }

    private static String readUtf8(ByteBuffer buf, int len) {
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readFully(
            InputStream in,
            byte[] dst,
            boolean allowCleanEof,
            String eofMessage
    ) throws IOException {
        int offset = 0;
        while (offset < dst.length) {
            int n = in.read(dst, offset, dst.length - offset);
            if (n < 0) {
                if (allowCleanEof && offset == 0) {
                    return 0;
                }
                throw new EOFException(eofMessage);
            }
            offset += n;
        }
        return offset;
    }
}