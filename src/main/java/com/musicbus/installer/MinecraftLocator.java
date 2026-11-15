package com.musicbus.installer;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class MinecraftLocator {

    public record BaseVersion(
            String id,
            String type,
            String mainClass,
            Instant releaseTime,
            boolean legacyArguments
    ) {
        @Override
        public String toString() {
            if (type != null && !type.isBlank() && !"release".equalsIgnoreCase(type)) {
                return id + " (" + type + ")";
            }
            return id;
        }
    }

    public record InstalledMusicBusVersion(
            String id,
            String inheritsFrom,
            String modVersion,
            Path versionDir
    ) {
        @Override
        public String toString() {
            return id;
        }
    }

    private MinecraftLocator() {}

    public static Path findDefaultMinecraftDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String userHome = System.getProperty("user.home", "");

        if (os.contains("win")) {
            List<Path> candidates = new ArrayList<>();
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                candidates.add(Paths.get(appData, ".minecraft"));
            }
            if (!userHome.isBlank()) {
                candidates.add(Paths.get(userHome, "AppData", "Roaming", ".minecraft"));
            }
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                candidates.add(Paths.get(
                        localAppData,
                        "Packages",
                        "Microsoft.4297127D64EC6_8wekyb3d8bbwe",
                        "LocalCache",
                        "Local",
                        "minecraft"
                ));
            }
            return firstExistingOrFirst(candidates, Paths.get(userHome, ".minecraft"));
        }

        if (os.contains("mac")) {
            return Paths.get(userHome, "Library", "Application Support", "minecraft");
        }

        List<Path> linuxCandidates = new ArrayList<>();
        linuxCandidates.add(Paths.get(userHome, ".minecraft"));
        linuxCandidates.add(Paths.get(userHome, ".var", "app", "com.mojang.Minecraft", ".minecraft"));
        return firstExistingOrFirst(linuxCandidates, Paths.get(userHome, ".minecraft"));
    }

    public static List<BaseVersion> findBaseVersions(Path mcDir) {
        List<BaseVersion> result = new ArrayList<>();
        if (mcDir == null) {
            return result;
        }

        Path versionsDir = mcDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) {
            return result;
        }

        try (Stream<Path> dirs = Files.list(versionsDir)) {
            for (Path dir : dirs.toList()) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                String id = dir.getFileName().toString();
                if (id.contains("-MusicBus-")) {
                    continue;
                }

                Path jsonFile = dir.resolve(id + ".json");
                if (!Files.isRegularFile(jsonFile)) {
                    continue;
                }

                JsonObject json = readJsonObject(jsonFile);
                if (json == null) {
                    continue;
                }

                // Skip versions that already inherit from another version or belong to MusicBus.
                if (json.get("inheritsFrom") != null || json.get("_musicbus") != null) {
                    continue;
                }

                String mainClass = json.getString("mainClass", null);
                if (mainClass == null || mainClass.isBlank()) {
                    continue;
                }

                boolean hasModernArgs = json.get("arguments") != null && json.get("arguments").isObject();
                boolean hasLegacyArgs = json.get("minecraftArguments") != null;
                if (!hasModernArgs && !hasLegacyArgs) {
                    continue;
                }

                String type = json.getString("type", "release");
                Instant releaseTime = parseInstant(json.getString("releaseTime", null));
                result.add(new BaseVersion(id, type, mainClass, releaseTime, !hasModernArgs));
            }
        } catch (IOException ignored) {
            // Return whatever was discovered cleanly.
        }

        result.sort(
                Comparator.comparing(BaseVersion::releaseTime).reversed()
                        .thenComparing(BaseVersion::id, Comparator.reverseOrder())
        );
        return result;
    }

    public static List<InstalledMusicBusVersion> findInstalledMusicBusVersions(Path mcDir) {
        List<InstalledMusicBusVersion> result = new ArrayList<>();
        if (mcDir == null) {
            return result;
        }

        Path versionsDir = mcDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) {
            return result;
        }

        try (Stream<Path> dirs = Files.list(versionsDir)) {
            for (Path dir : dirs.toList()) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                String id = dir.getFileName().toString();
                Path jsonFile = dir.resolve(id + ".json");
                if (!Files.isRegularFile(jsonFile)) {
                    continue;
                }

                JsonObject json = readJsonObject(jsonFile);
                if (json == null) {
                    continue;
                }

                JsonValue metaVal = json.get("_musicbus");
                boolean isMusicBus = (metaVal != null && metaVal.isObject()) || id.contains("-MusicBus-");
                if (!isMusicBus) {
                    continue;
                }

                String inheritsFrom = json.getString("inheritsFrom", "");
                String modVersion = "";
                if (metaVal != null && metaVal.isObject()) {
                    modVersion = metaVal.asObject().getString("version", "");
                }
                result.add(new InstalledMusicBusVersion(id, inheritsFrom, modVersion, dir));
            }
        } catch (IOException ignored) {
            // Ignore unreadable directories.
        }

        result.sort(Comparator.comparing(InstalledMusicBusVersion::id, Comparator.reverseOrder()));
        return result;
    }

    private static Path firstExistingOrFirst(List<Path> candidates, Path fallback) {
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return candidates.isEmpty() ? fallback : candidates.getFirst();
    }

    static JsonObject readJsonObject(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonValue parsed = Json.parse(reader);
            return parsed != null && parsed.isObject() ? parsed.asObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Instant parseInstant(String isoTime) {
        if (isoTime == null || isoTime.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return OffsetDateTime.parse(isoTime).toInstant();
        } catch (Exception ignored) {
            try {
                return Instant.parse(isoTime);
            } catch (Exception ignored2) {
                return Instant.EPOCH;
            }
        }
    }
}