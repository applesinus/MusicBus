package com.musicbus.audio;

import com.musicbus.core.MinecraftReflection;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Resolves the concrete OGG resource bytes and duration for a resolved
 * Minecraft SoundInstance via SoundBufferLibrary's ResourceProvider.
 */
public final class OggResourceResolver {

    private static final String RESOURCE_PROVIDER_CLASS =
            "net.minecraft.server.packs.resources.ResourceProvider";

    private static final String IDENTIFIER_CLASS =
            "net.minecraft.resources.Identifier";

    private static final String SOUND_MANAGER_CLASS =
            "net.minecraft.client.sounds.SoundManager";

    private OggResourceResolver() {}

    public record ResolvedOgg(
            String soundEventId,
            String oggPath,
            byte[] oggData,
            double durationSeconds
    ) {

        public ResolvedOgg {

            if (soundEventId == null || soundEventId.isBlank()) {
                throw new IllegalArgumentException(
                        "soundEventId cannot be blank"
                );
            }

            if (oggPath == null || oggPath.isBlank()) {
                throw new IllegalArgumentException(
                        "oggPath cannot be blank"
                );
            }

            if (oggData == null || oggData.length == 0) {
                throw new IllegalArgumentException(
                        "oggData cannot be empty"
                );
            }

            if (!Double.isFinite(durationSeconds)
                    || durationSeconds < 0.0) {
                throw new IllegalArgumentException(
                        "Invalid durationSeconds"
                );
            }
        }
    }

    /**
     * Resolves and reads the OGG resource for the given SoundInstance.
     *
     * Expects SoundInstance.resolve(SoundManager) to have already run.
     */
    public static Optional<ResolvedOgg> resolve(Object soundInstance) {

        if (soundInstance == null) {
            return Optional.empty();
        }

        try {

            Object eventIdentifier = MinecraftReflection.invokeNoArgs(
                    soundInstance,
                    "getIdentifier"
            );

            if (eventIdentifier == null) {
                return Optional.empty();
            }

            Object sound = MinecraftReflection.invokeNoArgs(
                    soundInstance,
                    "getSound"
            );
            if (sound == null) {
                return Optional.empty();
            }

            ClassLoader mcLoader = soundInstance.getClass().getClassLoader();
            if (isEmptySound(sound, mcLoader)) {
                return Optional.empty();
            }

            Object pathIdentifier = MinecraftReflection.invokeNoArgs(
                    sound,
                    "getPath"
            );
            if (pathIdentifier == null) {
                return Optional.empty();
            }

            Object resourceProvider = findResourceProvider(mcLoader);
            if (resourceProvider == null) {
                return Optional.empty();
            }

            byte[] oggData = readResourceBytes(
                    resourceProvider,
                    pathIdentifier,
                    mcLoader
            );

            if (oggData.length == 0) {
                return Optional.empty();
            }

            double durationSeconds = parseOggVorbisDurationSeconds(oggData);

            return Optional.of(
                    new ResolvedOgg(
                            eventIdentifier.toString(),
                            pathIdentifier.toString(),
                            oggData,
                            durationSeconds
                    )
            );

        } catch (Exception error) {

            log("WARNING: Failed to resolve OGG resource: " + error);
            return Optional.empty();
        }
    }

    // --------------------------------------------------
    // Ogg Vorbis duration parsing
    // --------------------------------------------------

    /**
     * Extracts exact playback duration in seconds from an Ogg Vorbis
     * byte array without decoding PCM samples.
     *
     * Returns 0.0 if the payload is not a valid Ogg Vorbis stream.
     */
    public static double parseOggVorbisDurationSeconds(byte[] data) {

        if (data == null || data.length < 58) {
            return 0.0;
        }

        if (!isOggPageMagic(data, 0) || data[4] != 0) {
            return 0.0;
        }

        int bosSegments = data[26] & 0xFF;
        int bosHeaderEnd = 27 + bosSegments;
        if (bosHeaderEnd + 16 > data.length) {
            return 0.0;
        }

        int streamSerial = readIntLE(data, 14);
        long sampleRate = readVorbisSampleRate(data, bosHeaderEnd);
        if (sampleRate <= 0L) {
            return 0.0;
        }

        long totalSamples = findLastGranulePosition(data, streamSerial);
        if (totalSamples <= 0L) {
            return 0.0;
        }

        return (double) totalSamples / (double) sampleRate;
    }

    private static long readVorbisSampleRate(
            byte[] data,
            int payloadOffset
    ) {

        // Vorbis identification header: 0x01 "vorbis" + version(4) + channels(1) + sampleRate(4)
        if (payloadOffset + 16 > data.length) {
            return 0L;
        }

        if (data[payloadOffset] != 0x01
                || data[payloadOffset + 1] != 'v'
                || data[payloadOffset + 2] != 'o'
                || data[payloadOffset + 3] != 'r'
                || data[payloadOffset + 4] != 'b'
                || data[payloadOffset + 5] != 'i'
                || data[payloadOffset + 6] != 's') {
            return 0L;
        }

        int channels = data[payloadOffset + 11] & 0xFF;
        if (channels <= 0) {
            return 0L;
        }

        long sampleRate = Integer.toUnsignedLong(
                readIntLE(data, payloadOffset + 12)
        );

        if (sampleRate <= 0L || sampleRate > 1_000_000L) {
            return 0L;
        }

        return sampleRate;
    }

    private static long findLastGranulePosition(
            byte[] data,
            int streamSerial
    ) {

        // Fast path: scan backwards for the final Ogg page belonging to this stream.
        for (int i = data.length - 27; i >= 0; i--) {

            if (!isOggPageMagic(data, i) || data[i + 4] != 0) {
                continue;
            }

            if (readIntLE(data, i + 14) != streamSerial) {
                continue;
            }

            int segments = data[i + 26] & 0xFF;
            int headerEnd = i + 27 + segments;
            if (headerEnd > data.length) {
                continue;
            }

            int bodySize = 0;
            for (int s = 0; s < segments; s++) {
                bodySize += data[i + 27 + s] & 0xFF;
            }

            if (headerEnd + bodySize > data.length) {
                continue;
            }

            long granule = readLongLE(data, i + 6);
            if (granule > 0L) {
                return granule;
            }
        }

        // Fallback: walk pages forward from the beginning.
        int offset = 0;
        long lastGranule = 0L;

        while (offset + 27 <= data.length) {

            if (!isOggPageMagic(data, offset) || data[offset + 4] != 0) {
                break;
            }

            int segments = data[offset + 26] & 0xFF;
            int headerEnd = offset + 27 + segments;
            if (headerEnd > data.length) {
                break;
            }

            int bodySize = 0;
            for (int s = 0; s < segments; s++) {
                bodySize += data[offset + 27 + s] & 0xFF;
            }

            int nextOffset = headerEnd + bodySize;
            if (nextOffset > data.length) {
                break;
            }

            if (readIntLE(data, offset + 14) == streamSerial) {
                long granule = readLongLE(data, offset + 6);
                if (granule > 0L) {
                    lastGranule = granule;
                }
            }

            offset = nextOffset;
        }

        return lastGranule;
    }

    private static boolean isOggPageMagic(byte[] data, int offset) {
        return offset >= 0
                && offset + 4 <= data.length
                && data[offset] == 'O'
                && data[offset + 1] == 'g'
                && data[offset + 2] == 'g'
                && data[offset + 3] == 'S';
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static long readLongLE(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24)
                | ((data[offset + 4] & 0xFFL) << 32)
                | ((data[offset + 5] & 0xFFL) << 40)
                | ((data[offset + 6] & 0xFFL) << 48)
                | ((data[offset + 7] & 0xFFL) << 56);
    }

    // --------------------------------------------------
    // Minecraft ResourceProvider reflection
    // --------------------------------------------------

    private static boolean isEmptySound(
            Object sound,
            ClassLoader loader
    ) {

        try {

            Class<?> soundManagerClass = Class.forName(
                    SOUND_MANAGER_CLASS,
                    false,
                    loader
            );

            Field emptyField = soundManagerClass.getField("EMPTY_SOUND");
            if (sound == emptyField.get(null)) {
                return true;
            }

            Field intentionallyEmptyField =
                    soundManagerClass.getField("INTENTIONALLY_EMPTY_SOUND");

            if (sound == intentionallyEmptyField.get(null)) {
                return true;
            }

        } catch (Exception ignored) {
        }

        return false;
    }

    private static Object findResourceProvider(
            ClassLoader loader
    ) throws Exception {

        Object minecraft = MinecraftReflection.getMinecraftInstance(loader);
        if (minecraft == null) {
            return null;
        }

        Object soundManager = MinecraftReflection.findObjectByType(
                minecraft,
                "SoundManager"
        );
        if (soundManager == null) {
            return null;
        }

        Object soundEngine = MinecraftReflection.findObjectByType(
                soundManager,
                "SoundEngine"
        );
        if (soundEngine == null) {
            return null;
        }

        Object soundBuffers = MinecraftReflection.findObjectByType(
                soundEngine,
                "SoundBufferLibrary"
        );

        if (soundBuffers == null) {
            return null;
        }

        return MinecraftReflection.findObjectByType(
                soundBuffers,
                "ResourceProvider"
        );
    }

    private static byte[] readResourceBytes(
            Object resourceProvider,
            Object pathIdentifier,
            ClassLoader loader
    ) throws Exception {

        Class<?> providerInterface = Class.forName(
                RESOURCE_PROVIDER_CLASS,
                false,
                loader
        );

        Class<?> identifierClass = Class.forName(
                IDENTIFIER_CLASS,
                false,
                loader
        );

        Method openMethod = providerInterface.getMethod(
                "open",
                identifierClass
        );

        try (InputStream input = (InputStream) openMethod.invoke(
                resourceProvider,
                pathIdentifier
        )) {

            if (input == null) {
                throw new IOException(
                        "ResourceProvider.open returned null for "
                                + pathIdentifier
                );
            }

            return input.readAllBytes();
        }
    }

    private static void log(String message) {
        System.out.println("[MusicBus] [OggResourceResolver] " + message);
    }
}