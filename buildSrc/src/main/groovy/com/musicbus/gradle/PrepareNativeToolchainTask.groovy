package com.musicbus.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

abstract class PrepareNativeToolchainTask extends DefaultTask {
    @Input
    abstract Property<String> getZigUrl()

    @Input
    abstract Property<String> getZigArchiveName()

    @Input
    abstract Property<String> getMiniaudioUrl()

    @Input
    abstract Property<String> getStbVorbisUrl()

    @Optional
    @Input
    abstract Property<String> getZigSha256()

    @Optional
    @Input
    abstract Property<String> getMiniaudioSha256()

    @Optional
    @Input
    abstract Property<String> getStbVorbisSha256()

    @Internal
    abstract Property<String> getCacheRootPath()

    @OutputFile
    abstract RegularFileProperty getZigExe()

    @OutputFile
    abstract RegularFileProperty getInstallMarker()

    @OutputFile
    abstract RegularFileProperty getMiniaudioHeader()

    @OutputFile
    abstract RegularFileProperty getStbVorbisSource()

    @TaskAction
    void prepare() {
        ensureFile(miniaudioUrl.get(), miniaudioHeader.get().asFile.toPath(), miniaudioSha256.getOrNull(), 15_000)
        ensureFile(stbVorbisUrl.get(), stbVorbisSource.get().asFile.toPath(), stbVorbisSha256.getOrNull(), 15_000)

        Path zigExePath = zigExe.get().asFile.toPath()
        Path zigDir = zigExePath.parent
        Path zigLibDir = zigDir.resolve('lib')
        Path markerPath = installMarker.get().asFile.toPath()
        String expectedZigSha = normalizeSha(zigSha256.getOrNull())

        boolean zigIntact = Files.isRegularFile(zigExePath)
                && Files.isDirectory(zigLibDir)
                && Files.isRegularFile(markerPath)

        if (!zigIntact) {
            Path cacheRoot = Path.of(cacheRootPath.get())
            String zipName = "${zigArchiveName.get()}.zip"
            Path zigZip = cacheRoot.resolve(zipName)

            boolean zipValid = Files.isRegularFile(zigZip)
                    && Files.size(zigZip) >= 10_000_000L
                    && matchesSha256(zigZip, expectedZigSha)

            if (!zipValid) {
                if (Files.isRegularFile(zigZip)) {
                    logger.warn("[MusicBus] Cached ${zipName} failed integrity check, re-downloading ...")
                    Files.deleteIfExists(zigZip)
                }
                downloadZigWithMirrors(zipName, zigZip, expectedZigSha)
            }

            logger.lifecycle("[MusicBus] Extracting ${zipName} ...")
            Files.deleteIfExists(markerPath)
            unzipArchive(zigZip, cacheRoot)
            if (!Files.isRegularFile(zigExePath) || !Files.isDirectory(zigLibDir)) {
                throw new GradleException("Zig installation incomplete after extraction at ${zigDir}")
            }
            Files.writeString(markerPath, "${zigArchiveName.get()}\n", StandardCharsets.UTF_8)
        }
    }

    private void downloadZigWithMirrors(String zipName, Path target, String expectedSha256) {
        List<String> candidates = new ArrayList<>()

        try {
            HttpURLConnection conn = openConnection('https://ziglang.org/download/community-mirrors.txt', 8_000)
            try {
                if (conn.responseCode == 200) {
                    String body = conn.inputStream.getText('UTF-8')
                    List<String> mirrors = body.readLines()
                            .collect { it.trim().replaceAll('/+$', '') }
                            .findAll { it.startsWith('https://') }
                    Collections.shuffle(mirrors)
                    mirrors.each { candidates.add("${it}/${zipName}?source=musicbus") }
                }
            } finally {
                conn.disconnect()
            }
        } catch (Exception e) {
            logger.lifecycle("[MusicBus] Could not fetch community-mirrors.txt (${e.message}), using built-in mirror list.")
        }

        [
            'https://pkg.machengine.org/zig',
            'https://ziglang.freetls.fastly.net',
            'https://zig-mirror.tsimnet.eu/zig',
            'https://zig.linus.dev/zig',
            'https://zig.squirl.dev',
            'https://zigmirror.com',
            'https://zig.chainsafe.dev',
            'https://zig.savalione.com',
            'https://pkg.earth/zig'
        ].each { mirror ->
            String url = "${mirror}/${zipName}?source=musicbus"
            if (!candidates.contains(url)) {
                candidates.add(url)
            }
        }
        candidates.add(zigUrl.get())

        for (String url : candidates) {
            try {
                downloadFile(url, target, expectedSha256, 15_000)
                return
            } catch (Exception e) {
                logger.warn("[MusicBus] Mirror failed (${url}): ${e.class.simpleName}: ${e.message}")
            }
        }
        throw new GradleException("Failed to download ${zipName} from all mirrors.")
    }

    private void ensureFile(String urlString, Path target, String expectedSha256, int readTimeoutMs) {
        String normalizedSha = normalizeSha(expectedSha256)
        if (Files.isRegularFile(target) && Files.size(target) > 0L) {
            if (matchesSha256(target, normalizedSha)) {
                return
            }
            logger.warn("[MusicBus] Checksum mismatch for cached ${target.fileName}, re-downloading ...")
            Files.deleteIfExists(target)
        }
        downloadFile(urlString, target, normalizedSha, readTimeoutMs)
    }

    private void downloadFile(String urlString, Path target, String expectedSha256, int readTimeoutMs) {
        Files.createDirectories(target.parent)
        Path tempFile = target.resolveSibling(target.fileName.toString() + '.part')
        Files.deleteIfExists(tempFile)
        logger.lifecycle("[MusicBus] Downloading ${urlString} ...")

        HttpURLConnection conn = openConnection(urlString, readTimeoutMs)
        try {
            int status = conn.responseCode
            if (status != 200) {
                throw new IOException("HTTP ${status}")
            }

            long totalBytes = conn.contentLengthLong
            long downloaded = 0L
            long nextLogAt = 5L * 1024L * 1024L
            byte[] buffer = new byte[65536]

            try (InputStream input = conn.inputStream;
                 OutputStream output = Files.newOutputStream(tempFile)) {
                int read
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (totalBytes > 0L && downloaded >= nextLogAt) {
                        logger.lifecycle(String.format(Locale.ROOT,
                                "[MusicBus]   %.1f / %.1f MB",
                                downloaded / (1024.0 * 1024.0),
                                totalBytes / (1024.0 * 1024.0)))
                        nextLogAt += 5L * 1024L * 1024L
                    }
                }
            }
        } catch (Exception e) {
            Files.deleteIfExists(tempFile)
            throw e
        } finally {
            conn.disconnect()
        }

        if (Files.size(tempFile) == 0L) {
            Files.deleteIfExists(tempFile)
            throw new IOException("Empty response")
        }
        if (!matchesSha256(tempFile, expectedSha256)) {
            String actual = computeSha256(tempFile)
            Files.deleteIfExists(tempFile)
            throw new IOException("SHA-256 mismatch (expected ${expectedSha256}, got ${actual})")
        }
        Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING)
    }

    private static HttpURLConnection openConnection(String urlString, int readTimeoutMs) {
        String current = urlString
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = (HttpURLConnection) URI.create(current).toURL().openConnection()
            conn.setInstanceFollowRedirects(false)
            conn.setConnectTimeout(10_000)
            conn.setReadTimeout(readTimeoutMs)
            conn.setRequestProperty('User-Agent', 'MusicBus-Gradle/1.0')
            conn.connect()

            int code = conn.responseCode
            if (code in [301, 302, 303, 307, 308]) {
                String location = conn.getHeaderField('Location')
                conn.disconnect()
                if (location == null || location.isBlank()) {
                    throw new IOException("HTTP ${code} redirect without Location")
                }
                current = URI.create(current).resolve(location).toString()
                continue
            }
            return conn
        }
        throw new IOException("Too many redirects for ${urlString}")
    }

    private static String normalizeSha(String sha) {
        return sha == null ? '' : sha.trim().toLowerCase(Locale.ROOT)
    }

    private static boolean matchesSha256(Path file, String expectedSha256) {
        String expected = normalizeSha(expectedSha256)
        if (expected.isEmpty()) {
            return true
        }
        return computeSha256(file) == expected
    }

    private static String computeSha256(Path file) {
        MessageDigest md = MessageDigest.getInstance('SHA-256')
        byte[] buffer = new byte[65536]
        try (InputStream input = Files.newInputStream(file)) {
            int read
            while ((read = input.read(buffer)) != -1) {
                md.update(buffer, 0, read)
            }
        }
        byte[] digest = md.digest()
        StringBuilder sb = new StringBuilder(digest.length * 2)
        for (byte b : digest) {
            sb.append(String.format(Locale.ROOT, '%02x', b & 0xFF))
        }
        return sb.toString()
    }

    private static void unzipArchive(Path zipFile, Path destDir) {
        Path normalizedDest = destDir.toAbsolutePath().normalize()
        Files.createDirectories(normalizedDest)
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            def entry
            while ((entry = zis.nextEntry) != null) {
                Path target = normalizedDest.resolve(entry.name).normalize()
                if (!target.startsWith(normalizedDest)) {
                    throw new GradleException("Unsafe zip entry: ${entry.name}")
                }
                if (entry.directory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING)
                }
                zis.closeEntry()
            }
        }
    }
}