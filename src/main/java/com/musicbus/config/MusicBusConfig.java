package com.musicbus.config;

import com.musicbus.audio.ExternalAudioPlayer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Properties;

public final class MusicBusConfig {

    private static final String CONFIG_DIRECTORY = "config";
    private static final String CONFIG_FILE = "musicbus.properties";

    private static volatile String outputDevice = "";
    private static volatile ExternalAudioPlayer.WindowMode windowMode =
            ExternalAudioPlayer.WindowMode.MINIMIZED;
    private static volatile String playerExecutable = "";

    private MusicBusConfig() {}

    private static Path getConfigPath() {
        return Paths.get(
                System.getProperty("user.dir", ".")
        ).toAbsolutePath()
         .normalize()
         .resolve(CONFIG_DIRECTORY)
         .resolve(CONFIG_FILE);
    }

    public static boolean load() {

        Path path = getConfigPath();

        if (!Files.exists(path)) {
            outputDevice = "";
            windowMode = ExternalAudioPlayer.WindowMode.MINIMIZED;
            playerExecutable = "";
            return false;
        }

        Properties properties = new Properties();

        try (Reader reader = Files.newBufferedReader(
                path,
                StandardCharsets.UTF_8
        )) {
            properties.load(reader);

            outputDevice = properties.getProperty("outputDevice", "").trim();
            playerExecutable = properties.getProperty("playerExecutable", "").trim();

            String rawWindowMode = properties.getProperty("windowMode", "minimized")
                    .trim()
                    .toLowerCase(Locale.ROOT);
            windowMode = "hidden".equals(rawWindowMode)
                    ? ExternalAudioPlayer.WindowMode.HIDDEN
                    : ExternalAudioPlayer.WindowMode.MINIMIZED;

            return Boolean.parseBoolean(
                    properties.getProperty("enabled", "false")
            );

        } catch (IOException error) {
            System.err.println(
                    "[MusicBus] Failed to load config: " + error
            );

            return false;
        }
    }

    public static void save(boolean enabled) throws IOException {

        Path path = getConfigPath();

        Files.createDirectories(path.getParent());

        Properties properties = new Properties();

        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(
                    path,
                    StandardCharsets.UTF_8
            )) {
                properties.load(reader);
            } catch (IOException ignored) {
            }
        }

        properties.setProperty(
                "enabled",
                Boolean.toString(enabled)
        );
        if (!outputDevice.isEmpty()) {
            properties.setProperty("outputDevice", outputDevice);
        }
        if (!playerExecutable.isEmpty()) {
            properties.setProperty("playerExecutable", playerExecutable);
        }
        properties.setProperty(
                "windowMode",
                windowMode == ExternalAudioPlayer.WindowMode.HIDDEN
                        ? "hidden"
                        : "minimized"
        );

        try (Writer writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8
        )) {
            properties.store(writer, "MusicBus Configuration");
        }
    }

    public static String getOutputDevice() {
        return outputDevice;
    }

    public static ExternalAudioPlayer.WindowMode getWindowMode() {
        return windowMode;
    }

    public static Path getPlayerExecutablePath() {
        String raw = playerExecutable;
        return (raw == null || raw.isBlank()) ? null : Paths.get(raw);
    }

    public static Path getLocation() {
        return getConfigPath();
    }
}