package com.musicbus.installer;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.eclipsesource.json.WriterConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class MusicBusInstallService {

    public static final String PROFILE_KEY = "MusicBus";
    public static final String PROFILE_NAME = "MusicBus";
    private static final String ICON_RESOURCE = "/assets/musicbus/icon.png";

    private static final List<String> LAUNCHER_PROFILE_FILES = List.of(
            "launcher_profiles.json",
            "launcher_profiles_microsoft_store.json"
    );

    public record InstallResult(
            String versionId,
            Path versionJsonFile,
            Path libraryJarFile,
            List<Path> updatedProfileFiles
    ) {}

    public record UninstallResult(
            String removedVersionId,
            boolean removedLibraryJar,
            List<Path> updatedProfileFiles
    ) {}

    private MusicBusInstallService() {}

    public static String getModVersion() {
        String version = MusicBusInstallService.class.getPackage().getImplementationVersion();
        return (version == null || version.isBlank()) ? "DEV" : version.trim();
    }

    public static Path getSelfJarPath() throws IOException {
        CodeSource codeSource = MusicBusInstallService.class.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            throw new IOException("Cannot locate running MusicBus JAR.");
        }
        try {
            Path path = Paths.get(codeSource.getLocation().toURI());
            if (!Files.isRegularFile(path)) {
                throw new IOException("Installer must be run from a packaged .jar file (current location: " + path + ").");
            }
            return path;
        } catch (URISyntaxException e) {
            throw new IOException("Invalid JAR location URI.", e);
        }
    }

    public static boolean isMinecraftLauncherRunning() {
        try {
            return ProcessHandle.allProcesses()
                    .map(handle -> handle.info().command().orElse(""))
                    .map(cmd -> cmd.toLowerCase(Locale.ROOT))
                    .anyMatch(cmd ->
                            cmd.endsWith("minecraftlauncher.exe")
                                    || cmd.endsWith("\\minecraft.exe")
                                    || cmd.endsWith("/minecraft.exe")
                                    || cmd.endsWith("/minecraft-launcher")
                    );
        } catch (Exception ignored) {
            return false;
        }
    }

    public static InstallResult install(
            Path mcDir,
            MinecraftLocator.BaseVersion baseVersion,
            boolean createOrUpdateProfile
    ) throws IOException {
        if (mcDir == null) {
            throw new IOException("Minecraft directory is not specified.");
        }
        if (baseVersion == null) {
            throw new IOException("Base Minecraft version is not selected.");
        }

        Path selfJar = getSelfJarPath();
        String modVersion = getModVersion();
        String versionId = baseVersion.id() + "-MusicBus-" + modVersion;

        String relativeLibraryPath = "com/musicbus/musicbus/" + modVersion + "/musicbus-" + modVersion + ".jar";
        Path targetJar = mcDir.resolve("libraries").resolve(relativeLibraryPath.replace("/", mcDir.getFileSystem().getSeparator()));
        Files.createDirectories(targetJar.getParent());

        if (!Files.exists(targetJar) || !Files.isSameFile(selfJar, targetJar)) {
            Files.copy(selfJar, targetJar, StandardCopyOption.REPLACE_EXISTING);
        }

        Path versionDir = mcDir.resolve("versions").resolve(versionId);
        Files.createDirectories(versionDir);
        Path versionJsonFile = versionDir.resolve(versionId + ".json");

        String nowIso = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toString();
        String versionType = (baseVersion.type() == null || baseVersion.type().isBlank()) ? "release" : baseVersion.type();

        JsonArray jvmArgs = Json.array()
                .add("-javaagent:${library_directory}/" + relativeLibraryPath);

        JsonObject arguments = Json.object()
                .set("game", Json.array())
                .set("jvm", jvmArgs);

        JsonObject musicBusMeta = Json.object()
                .set("version", modVersion)
                .set("library", relativeLibraryPath);

        JsonObject versionJson = Json.object()
                .set("id", versionId)
                .set("inheritsFrom", baseVersion.id())
                .set("releaseTime", nowIso)
                .set("time", nowIso)
                .set("type", versionType)
                .set("mainClass", baseVersion.mainClass())
                .set("arguments", arguments)
                .set("_musicbus", musicBusMeta);

        writeJsonObject(versionJsonFile, versionJson);

        List<Path> updatedProfiles = new ArrayList<>();
        if (createOrUpdateProfile) {
            updatedProfiles = upsertLauncherProfile(mcDir, versionId, nowIso);
        }

        return new InstallResult(versionId, versionJsonFile, targetJar, updatedProfiles);
    }

    public static UninstallResult uninstall(
            Path mcDir,
            MinecraftLocator.InstalledMusicBusVersion installed
    ) throws IOException {
        if (mcDir == null || installed == null) {
            throw new IOException("Nothing selected to uninstall.");
        }

        String libraryRelPath = null;
        Path versionJsonFile = installed.versionDir().resolve(installed.id() + ".json");
        if (Files.isRegularFile(versionJsonFile)) {
            JsonObject json = MinecraftLocator.readJsonObject(versionJsonFile);
            if (json != null) {
                JsonValue meta = json.get("_musicbus");
                if (meta != null && meta.isObject()) {
                    libraryRelPath = meta.asObject().getString("library", null);
                }
            }
        }
        if (libraryRelPath == null && !installed.modVersion().isBlank()) {
            libraryRelPath = "com/musicbus/musicbus/" + installed.modVersion()
                    + "/musicbus-" + installed.modVersion() + ".jar";
        }

        deleteRecursively(installed.versionDir());

        boolean removedLibraryJar = false;
        if (libraryRelPath != null && !isLibraryReferencedElsewhere(mcDir, libraryRelPath)) {
            Path jarPath = mcDir.resolve("libraries").resolve(libraryRelPath.replace("/", mcDir.getFileSystem().getSeparator()));
            if (Files.isRegularFile(jarPath)) {
                try {
                    Path selfJar = getSelfJarPath();
                    if (!Files.isSameFile(selfJar, jarPath)) {
                        Files.deleteIfExists(jarPath);
                        removedLibraryJar = true;
                        cleanupEmptyParents(jarPath.getParent(), mcDir.resolve("libraries"));
                    }
                } catch (IOException ignored) {
                    Files.deleteIfExists(jarPath);
                    removedLibraryJar = true;
                    cleanupEmptyParents(jarPath.getParent(), mcDir.resolve("libraries"));
                }
            }
        }

        List<Path> updatedProfiles = removeLauncherProfileReference(mcDir, installed.id());
        return new UninstallResult(installed.id(), removedLibraryJar, updatedProfiles);
    }

    private static List<Path> upsertLauncherProfile(Path mcDir, String versionId, String nowIso) throws IOException {
        List<Path> existingFiles = findExistingProfileFiles(mcDir);
        if (existingFiles.isEmpty()) {
            Path defaultFile = mcDir.resolve("launcher_profiles.json");
            Files.createDirectories(mcDir);
            JsonObject root = Json.object()
                    .set("profiles", Json.object())
                    .set("version", 3);
            writeJsonObject(defaultFile, root);
            existingFiles.add(defaultFile);
        }

        String iconValue = loadProfileIcon();
        List<Path> updated = new ArrayList<>();

        for (Path profileFile : existingFiles) {
            JsonObject root = MinecraftLocator.readJsonObject(profileFile);
            if (root == null) {
                continue;
            }

            JsonValue profilesVal = root.get("profiles");
            JsonObject profiles = (profilesVal != null && profilesVal.isObject())
                    ? profilesVal.asObject()
                    : Json.object();

            JsonValue existingProfileVal = profiles.get(PROFILE_KEY);
            JsonObject profile = (existingProfileVal != null && existingProfileVal.isObject())
                    ? existingProfileVal.asObject()
                    : Json.object();

            if (profile.get("created") == null) {
                profile.set("created", nowIso);
            }
            profile.set("name", PROFILE_NAME);
            profile.set("type", "custom");
            profile.set("lastUsed", nowIso);
            profile.set("lastVersionId", versionId);
            profile.set("icon", iconValue);

            profiles.set(PROFILE_KEY, profile);
            root.set("profiles", profiles);

            writeJsonObject(profileFile, root);
            updated.add(profileFile);
        }

        return updated;
    }

    private static List<Path> removeLauncherProfileReference(Path mcDir, String removedVersionId) throws IOException {
        List<Path> updated = new ArrayList<>();
        for (Path profileFile : findExistingProfileFiles(mcDir)) {
            JsonObject root = MinecraftLocator.readJsonObject(profileFile);
            if (root == null) {
                continue;
            }
            JsonValue profilesVal = root.get("profiles");
            if (profilesVal == null || !profilesVal.isObject()) {
                continue;
            }
            JsonObject profiles = profilesVal.asObject();
            JsonValue musicBusProfile = profiles.get(PROFILE_KEY);
            if (musicBusProfile != null && musicBusProfile.isObject()) {
                String lastVersionId = musicBusProfile.asObject().getString("lastVersionId", "");
                if (removedVersionId.equals(lastVersionId)) {
                    profiles.remove(PROFILE_KEY);
                    writeJsonObject(profileFile, root);
                    updated.add(profileFile);
                }
            }
        }
        return updated;
    }

    private static List<Path> findExistingProfileFiles(Path mcDir) {
        List<Path> found = new ArrayList<>();
        for (String fileName : LAUNCHER_PROFILE_FILES) {
            Path file = mcDir.resolve(fileName);
            if (Files.isRegularFile(file)) {
                found.add(file);
            }
        }
        return found;
    }

    private static boolean isLibraryReferencedElsewhere(Path mcDir, String libraryRelPath) {
        for (MinecraftLocator.InstalledMusicBusVersion version : MinecraftLocator.findInstalledMusicBusVersions(mcDir)) {
            Path jsonFile = version.versionDir().resolve(version.id() + ".json");
            JsonObject json = MinecraftLocator.readJsonObject(jsonFile);
            if (json == null) {
                continue;
            }
            JsonValue meta = json.get("_musicbus");
            if (meta != null && meta.isObject()) {
                if (libraryRelPath.equals(meta.asObject().getString("library", null))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String loadProfileIcon() {
        try (InputStream in = MusicBusInstallService.class.getResourceAsStream(ICON_RESOURCE)) {
            if (in != null) {
                byte[] bytes = in.readAllBytes();
                if (bytes.length > 0) {
                    return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
                }
            }
        } catch (IOException ignored) {
            // Fall back to a built-in launcher icon.
        }
        return "Jukebox";
    }

    private static void writeJsonObject(Path file, JsonObject json) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            json.writeTo(writer, WriterConfig.PRETTY_PRINT);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void cleanupEmptyParents(Path dir, Path stopAt) {
        Path current = dir;
        while (current != null && !current.equals(stopAt) && current.startsWith(stopAt)) {
            try (Stream<Path> entries = Files.list(current)) {
                if (entries.findAny().isPresent()) {
                    break;
                }
            } catch (IOException e) {
                break;
            }
            try {
                Files.deleteIfExists(current);
            } catch (IOException e) {
                break;
            }
            current = current.getParent();
        }
    }
}