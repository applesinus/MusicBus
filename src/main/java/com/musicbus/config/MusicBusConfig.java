package com.musicbus.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public final class MusicBusConfig {

    private static final String CONFIG_DIRECTORY = "config";
    private static final String CONFIG_FILE = "musicbus.properties";

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
            return false;
        }

        Properties properties = new Properties();

        try (Reader reader = Files.newBufferedReader(
                path,
                StandardCharsets.UTF_8
        )) {
            properties.load(reader);

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

        properties.setProperty(
                "enabled",
                Boolean.toString(enabled)
        );

        try (Writer writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8
        )) {
            properties.store(writer, "MusicBus Configuration");
        }
    }

    public static Path getLocation() {
        return getConfigPath();
    }
}
