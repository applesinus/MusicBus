package com.musicbus.audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Resolves or extracts the bundled musicbus-player.exe into a deterministic
 * content-addressed cache directory so OBS always sees a stable executable path
 * and temporary directories are not polluted across launches.
 */
final class ExternalPlayerBinary {

    static final String RESOURCE_PATH = "/native/windows-x64/musicbus-player.exe";
    private static final String EXECUTABLE_NAME = "musicbus-player.exe";

    private ExternalPlayerBinary() {}

    static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    static Path resolveExecutable(Path customExecutablePath, Path cacheRoot) throws IOException {
        if (!isWindows()) {
            throw new IOException("External native player is currently supported on Windows only");
        }

        if (customExecutablePath != null) {
            Path normalized = customExecutablePath.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized)) {
                throw new IOException("Configured external player binary not found: " + normalized);
            }
            return normalized;
        }

        byte[] bundledBytes = readBundledBinary();
        String hashPrefix = sha256Hex(bundledBytes).substring(0, 16);

        Path effectiveRoot = resolveCacheRoot(cacheRoot);
        Path targetDir = effectiveRoot.resolve("bin").resolve(hashPrefix);
        Path targetExe = targetDir.resolve(EXECUTABLE_NAME);

        if (isSameBinaryOnDisk(targetExe, bundledBytes.length, bundledBytes)) {
            return targetExe;
        }

        Files.createDirectories(targetDir);
        Path tempExe = targetDir.resolve(EXECUTABLE_NAME + "." + ProcessHandle.current().pid() + ".part");
        try {
            Files.write(tempExe, bundledBytes);
            try {
                Files.move(tempExe, targetExe, StandardCopyOption.REPLACE_EXISTING);
            } catch (AccessDeniedException runningOrLocked) {
                // Another instance on the same machine may already be running this exact version.
                if (isSameBinaryOnDisk(targetExe, bundledBytes.length, bundledBytes)) {
                    return targetExe;
                }
                throw runningOrLocked;
            }
        } finally {
            Files.deleteIfExists(tempExe);
        }

        return targetExe;
    }

    private static Path resolveCacheRoot(Path explicitRoot) {
        if (explicitRoot != null) {
            return explicitRoot.toAbsolutePath().normalize();
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "MusicBus");
        }
        return Path.of(System.getProperty("java.io.tmpdir", "."), "MusicBus");
    }

    private static byte[] readBundledBinary() throws IOException {
        try (InputStream in = ExternalPlayerBinary.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IOException("Bundled native binary not found in JAR: " + RESOURCE_PATH);
            }
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0) {
                throw new IOException("Bundled native binary is empty: " + RESOURCE_PATH);
            }
            return bytes;
        }
    }

    private static boolean isSameBinaryOnDisk(Path path, int expectedLength, byte[] expectedBytes) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) != expectedLength) {
                return false;
            }
            byte[] diskBytes = Files.readAllBytes(path);
            return MessageDigest.isEqual(diskBytes, expectedBytes);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}