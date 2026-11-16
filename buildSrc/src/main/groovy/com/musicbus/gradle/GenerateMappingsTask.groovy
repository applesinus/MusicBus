package com.musicbus.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
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
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

abstract class GenerateMappingsTask extends DefaultTask {

    private static final String PISTON_MANIFEST_URL =
            'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
    private static final String FABRIC_INTERMEDIARY_API =
            'https://meta.fabricmc.net/v2/versions/intermediary/'
    private static final String FABRIC_MAVEN =
            'https://maven.fabricmc.net/'

    @Input
    abstract Property<String> getMinVersion()

    @Optional
    @Input
    abstract Property<String> getMaxVersion()

    @Input
    abstract Property<Boolean> getIncludeSnapshots()

    @Input
    abstract Property<Boolean> getRefreshManifest()

    @Internal
    abstract Property<String> getCacheRootPath()

    @Optional
    @Input
    abstract Property<String> getLocalMinecraftDir()

    @OutputFile
    abstract RegularFileProperty getOutputMappingsFile()

    @OutputFile
    abstract RegularFileProperty getOutputReportFile()

    @TaskAction
    void generate() {
        Path cacheRoot = Path.of(cacheRootPath.get())
        Files.createDirectories(cacheRoot)

        Map manifest = loadManifest(cacheRoot)
        List<Map> selectedVersions = selectVersions(manifest)
        if (selectedVersions.isEmpty()) {
            throw new GradleException("No Minecraft versions matched minVersion=${minVersion.get()}")
        }

        logger.lifecycle("[MusicBus] Generating mappings for ${selectedVersions.size()} versions " +
                "(${selectedVersions.first().id} -> ${selectedVersions.last().id})...")

        Map<String, Map<String, Integer>> classPoolIndex = new LinkedHashMap<>()
        Map<String, Map<String, Map>> classPool = new LinkedHashMap<>()
        Map<String, Map<String, Integer>> fieldPoolIndex = new LinkedHashMap<>()
        Map<String, Map<String, Map>> fieldPool = new LinkedHashMap<>()
        Map<String, Map<String, Integer>> methodPoolIndex = new LinkedHashMap<>()
        Map<String, Map<String, Map>> methodPool = new LinkedHashMap<>()
        Map<String, Integer> featurePoolIndex = new LinkedHashMap<>()
        Map<String, Map> featurePool = new LinkedHashMap<>()

        Map<String, Map> versionEntries = new LinkedHashMap<>()
        List<Map> reportRows = new ArrayList<>()

        for (Map verMeta : selectedVersions) {
            String versionId = verMeta.id as String
            Path verCache = cacheRoot.resolve('versions').resolve(sanitizeId(versionId))
            Files.createDirectories(verCache)

            Map verJson = loadVersionJson(verCache, verMeta.url as String)
            Map downloads = (verJson.downloads ?: [:]) as Map
            Map clientMappingsDownload = downloads.client_mappings as Map
            boolean unobfuscated = (clientMappingsDownload == null)

            VersionSymbolSource symbolSource
            if (unobfuscated) {
                Path jarPath = resolveClientJar(versionId, verCache, downloads.client as Map)
                symbolSource = readUnobfuscatedJar(versionId, jarPath)
            } else {
                Path clientTxt = ensureDownload(
                        clientMappingsDownload.url as String,
                        verCache.resolve('client.txt'),
                        clientMappingsDownload.sha1 as String
                )
                TinyMappings tiny = loadIntermediaryTiny(versionId, verCache)
                symbolSource = parseObfuscatedMappings(clientTxt, tiny)
            }

            ResolvedVersion resolved = resolveVersionSymbols(versionId, symbolSource)
            reportRows.add(resolved.reportRow)

            Map<String, String> clsIds = new LinkedHashMap<>()
            for (Map.Entry<String, Map> entry : resolved.classes.entrySet()) {
                clsIds[entry.key] = internEntry(classPoolIndex, classPool, entry.key, entry.value)
            }

            Map<String, String> fldIds = new LinkedHashMap<>()
            for (Map.Entry<String, Map> entry : resolved.fields.entrySet()) {
                fldIds[entry.key] = internEntry(fieldPoolIndex, fieldPool, entry.key, entry.value)
            }

            Map<String, String> mthIds = new LinkedHashMap<>()
            for (Map.Entry<String, Map> entry : resolved.methods.entrySet()) {
                mthIds[entry.key] = internEntry(methodPoolIndex, methodPool, entry.key, entry.value)
            }

            String featJson = JsonOutput.toJson(resolved.features)
            Integer featIdNum = featurePoolIndex.get(featJson)
            if (featIdNum == null) {
                featIdNum = featurePoolIndex.size()
                featurePoolIndex[featJson] = featIdNum
                featurePool[String.valueOf(featIdNum)] = resolved.features
            }

            Map verRecord = new LinkedHashMap()
            verRecord['unobfuscated'] = unobfuscated
            verRecord['hasIntermediary'] = symbolSource.hasIntermediary
            verRecord['features'] = String.valueOf(featIdNum)
            verRecord['classes'] = clsIds
            verRecord['fields'] = fldIds
            verRecord['methods'] = mthIds
            versionEntries[versionId] = verRecord
        }

        Map outputRoot = new LinkedHashMap()
        outputRoot['schemaVersion'] = 1
        outputRoot['minVersion'] = selectedVersions.first().id
        outputRoot['maxVersion'] = selectedVersions.last().id
        outputRoot['classes'] = classPool
        outputRoot['fields'] = fieldPool
        outputRoot['methods'] = methodPool
        outputRoot['features'] = featurePool
        outputRoot['versions'] = versionEntries

        Path mappingsOut = outputMappingsFile.get().asFile.toPath()
        Files.createDirectories(mappingsOut.parent)
        String prettyMappings = JsonOutput.prettyPrint(JsonOutput.toJson(outputRoot)) + '\n'
        Files.writeString(mappingsOut, prettyMappings, StandardCharsets.UTF_8)

        String reportText = buildReport(selectedVersions, reportRows)
        Path reportOut = outputReportFile.get().asFile.toPath()
        Files.createDirectories(reportOut.parent)
        Files.writeString(reportOut, reportText, StandardCharsets.UTF_8)

        logger.lifecycle("[MusicBus] Wrote ${mappingsOut} (${Files.size(mappingsOut)} bytes)")
        logger.lifecycle("[MusicBus] Wrote ${reportOut}")
        printConsoleSummary(reportRows)
    }

    protected static String internEntry(
            Map<String, Map<String, Integer>> poolIndex,
            Map<String, Map<String, Map>> pool,
            String key,
            Map value
    ) {
        Map<String, Integer> keyIndex = poolIndex.computeIfAbsent(key) { new LinkedHashMap<String, Integer>() }
        Map<String, Map> keyPool = pool.computeIfAbsent(key) { new LinkedHashMap<String, Map>() }
        String serialized = JsonOutput.toJson(value)
        Integer id = keyIndex.get(serialized)
        if (id == null) {
            id = keyIndex.size()
            keyIndex[serialized] = id
            keyPool[String.valueOf(id)] = value
        }
        return String.valueOf(id)
    }

    // -------------------------------------------------------------------------
    // Manifest & downloads
    // -------------------------------------------------------------------------

    private Map loadManifest(Path cacheRoot) {
        Path manifestFile = cacheRoot.resolve('version_manifest_v2.json')
        boolean stale = !Files.isRegularFile(manifestFile) || refreshManifest.get()
        if (!stale) {
            long ageMs = System.currentTimeMillis() - Files.getLastModifiedTime(manifestFile).toMillis()
            if (ageMs > 24L * 60L * 60L * 1000L) {
                stale = true
            }
        }
        if (stale) {
            downloadFile(PISTON_MANIFEST_URL, manifestFile)
        }
        return new JsonSlurper().parse(manifestFile.toFile()) as Map
    }

    private List<Map> selectVersions(Map manifest) {
        List<Map> all = (manifest.versions ?: []) as List<Map>
        String minId = minVersion.get()
        String maxId = maxVersion.getOrNull()
        boolean snapshots = includeSnapshots.get()

        Map minEntry = all.find { (it.id as String) == minId }
        if (minEntry == null) {
            throw new GradleException("minVersion '${minId}' not found in Piston Meta manifest")
        }
        String minTime = minEntry.releaseTime as String

        String maxTime = null
        if (maxId != null && !maxId.trim().isEmpty()) {
            Map maxEntry = all.find { (it.id as String) == maxId }
            if (maxEntry == null) {
                throw new GradleException("maxVersion '${maxId}' not found in Piston Meta manifest")
            }
            maxTime = maxEntry.releaseTime as String
        }

        List<Map> filtered = all.findAll { Map v ->
            String type = v.type as String
            if (!snapshots && type != 'release') {
                return false
            }
            if (snapshots && type != 'release' && type != 'snapshot') {
                return false
            }
            String time = v.releaseTime as String
            if (time < minTime) {
                return false
            }
            if (maxTime != null && time > maxTime) {
                return false
            }
            return true
        }
        filtered.sort { Map a, Map b -> (a.releaseTime as String) <=> (b.releaseTime as String) }
        return filtered
    }

    private Map loadVersionJson(Path verCache, String url) {
        Path file = verCache.resolve('version.json')
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            downloadFile(url, file)
        }
        return new JsonSlurper().parse(file.toFile()) as Map
    }

    private Path resolveClientJar(String versionId, Path verCache, Map clientDownload) {
        String localMc = localMinecraftDir.getOrNull()
        if (localMc != null && !localMc.trim().isEmpty()) {
            Path localJar = Path.of(localMc, 'versions', versionId, "${versionId}.jar")
            if (Files.isRegularFile(localJar) && Files.size(localJar) > 100_000L) {
                return localJar
            }
        }
        if (clientDownload == null || clientDownload.url == null) {
            throw new GradleException("No client jar download URL for unobfuscated version ${versionId}")
        }
        return ensureDownload(
                clientDownload.url as String,
                verCache.resolve('client.jar'),
                clientDownload.sha1 as String
        )
    }

    private TinyMappings loadIntermediaryTiny(String versionId, Path verCache) {
        Path tinyFile = verCache.resolve('intermediary.tiny')
        Path noMarker = verCache.resolve('.no-intermediary')
        if (Files.isRegularFile(noMarker)) {
            return null
        }
        if (!Files.isRegularFile(tinyFile)) {
            Path metaFile = verCache.resolve('intermediary-meta.json')
            if (!Files.isRegularFile(metaFile)) {
                downloadFile(FABRIC_INTERMEDIARY_API + URLEncoder.encode(versionId, 'UTF-8'), metaFile)
            }
            Object parsed = new JsonSlurper().parse(metaFile.toFile())
            List list = (parsed instanceof List) ? (parsed as List) : []
            if (list.isEmpty()) {
                Files.writeString(noMarker, 'none\n', StandardCharsets.UTF_8)
                return null
            }
            String maven = (list[0] as Map).maven as String
            String[] parts = maven.split(':')
            String groupPath = parts[0].replace('.', '/')
            String artifact = parts[1]
            String ver = parts[2]
            Path jarPath = verCache.resolve('intermediary.jar')

            String v2Url = "${FABRIC_MAVEN}${groupPath}/${artifact}/${ver}/${artifact}-${ver}-v2.jar"
            String v1Url = "${FABRIC_MAVEN}${groupPath}/${artifact}/${ver}/${artifact}-${ver}.jar"
            try {
                downloadFile(v2Url, jarPath)
            } catch (Exception ignored) {
                downloadFile(v1Url, jarPath)
            }

            try (ZipFile zf = new ZipFile(jarPath.toFile())) {
                ZipEntry entry = zf.getEntry('mappings/mappings.tiny')
                if (entry == null) {
                    throw new GradleException("mappings/mappings.tiny not found in ${jarPath}")
                }
                try (InputStream is = zf.getInputStream(entry)) {
                    Files.copy(is, tinyFile, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        return parseTiny(tinyFile)
    }

    private Path ensureDownload(String url, Path target, String expectedSha1) {
        if (Files.isRegularFile(target) && Files.size(target) > 0L) {
            if (expectedSha1 == null || expectedSha1.isEmpty() || computeSha1(target) == expectedSha1.toLowerCase(Locale.ROOT)) {
                return target
            }
            Files.deleteIfExists(target)
        }
        downloadFile(url, target)
        if (expectedSha1 != null && !expectedSha1.isEmpty()) {
            String actual = computeSha1(target)
            if (actual != expectedSha1.toLowerCase(Locale.ROOT)) {
                Files.deleteIfExists(target)
                throw new GradleException("SHA-1 mismatch for ${url}: expected ${expectedSha1}, got ${actual}")
            }
        }
        return target
    }

    private void downloadFile(String urlString, Path target) {
        Files.createDirectories(target.parent)
        Path part = target.resolveSibling(target.fileName.toString() + '.part')
        logger.lifecycle("[MusicBus] Fetching ${urlString}")

        Exception lastError = null
        for (int attempt = 1; attempt <= 3; attempt++) {
            Files.deleteIfExists(part)
            HttpURLConnection conn = null
            try {
                conn = openConnection(urlString)
                int code = conn.responseCode
                if (code == 404) {
                    throw new FileNotFoundException("HTTP 404 for ${urlString}")
                }
                if (code != 200) {
                    throw new IOException("HTTP ${code} for ${urlString}")
                }
                try (InputStream input = conn.inputStream;
                     OutputStream output = Files.newOutputStream(part)) {
                    byte[] buf = new byte[65536]
                    int r
                    while ((r = input.read(buf)) != -1) {
                        output.write(buf, 0, r)
                    }
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (FileNotFoundException e) {
                Files.deleteIfExists(part)
                throw e
            } catch (Exception e) {
                Files.deleteIfExists(part)
                lastError = e
                if (attempt < 3) {
                    logger.warn("[MusicBus] Download attempt ${attempt}/3 failed (${e.class.simpleName}: ${e.message}), retrying...")
                    Thread.sleep(1500L * attempt)
                }
            } finally {
                if (conn != null) {
                    conn.disconnect()
                }
            }
        }
        throw lastError
    }

    private static HttpURLConnection openConnection(String urlString) {
        String current = urlString
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = (HttpURLConnection) URI.create(current).toURL().openConnection()
            conn.setInstanceFollowRedirects(false)
            conn.setConnectTimeout(15_000)
            conn.setReadTimeout(60_000)
            conn.setRequestProperty('User-Agent', 'MusicBus-Mappings/1.0')
            conn.connect()
            int code = conn.responseCode
            if (code in [301, 302, 303, 307, 308]) {
                String loc = conn.getHeaderField('Location')
                conn.disconnect()
                if (loc == null || loc.trim().isEmpty()) {
                    throw new IOException("Redirect without Location for ${current}")
                }
                current = URI.create(current).resolve(loc).toString()
                continue
            }
            return conn
        }
        throw new IOException("Too many redirects for ${urlString}")
    }

    private static String computeSha1(Path file) {
        MessageDigest md = MessageDigest.getInstance('SHA-1')
        byte[] buf = new byte[65536]
        try (InputStream is = Files.newInputStream(file)) {
            int r
            while ((r = is.read(buf)) != -1) {
                md.update(buf, 0, r)
            }
        }
        byte[] d = md.digest()
        StringBuilder sb = new StringBuilder(d.length * 2)
        for (byte b : d) {
            sb.append(String.format(Locale.ROOT, '%02x', b & 0xFF))
        }
        return sb.toString()
    }

    private static String sanitizeId(String id) {
        return id.replaceAll('[^a-zA-Z0-9._-]', '_')
    }

    // -------------------------------------------------------------------------
    // Tiny v1 / v2 parser
    // -------------------------------------------------------------------------

    private static TinyMappings parseTiny(Path tinyFile) {
        TinyMappings tm = new TinyMappings()
        List<String> lines = Files.readAllLines(tinyFile, StandardCharsets.UTF_8)
        if (lines.isEmpty()) {
            return tm
        }
        String header = lines[0]
        String[] hParts = header.split('\t')
        boolean v2 = (hParts[0] == 'tiny')
        int offIdx = -1
        int intIdx = -1
        if (v2) {
            for (int i = 3; i < hParts.length; i++) {
                if (hParts[i] == 'official') offIdx = i - 3
                if (hParts[i] == 'intermediary') intIdx = i - 3
            }
            String curOff = null
            for (int i = 1; i < lines.size(); i++) {
                String line = lines[i]
                if (line.isEmpty()) continue
                if (line.startsWith('c\t')) {
                    String[] p = line.split('\t', -1)
                    curOff = p[1 + offIdx]
                    String curInt = p[1 + intIdx]
                    tm.classOffToInt[curOff] = curInt
                } else if (line.startsWith('\tf\t') && curOff != null) {
                    String[] p = line.split('\t', -1)
                    String descOff = p[2]
                    String nameOff = p[3 + offIdx]
                    String nameInt = p[3 + intIdx]
                    tm.fieldOffToInt["${curOff}#${nameOff}:${descOff}"] = nameInt
                } else if (line.startsWith('\tm\t') && curOff != null) {
                    String[] p = line.split('\t', -1)
                    String descOff = p[2]
                    String nameOff = p[3 + offIdx]
                    String nameInt = p[3 + intIdx]
                    tm.methodOffToInt["${curOff}#${nameOff}${descOff}"] = nameInt
                }
            }
        } else if (hParts[0] == 'v1') {
            for (int i = 1; i < hParts.length; i++) {
                if (hParts[i] == 'official') offIdx = i - 1
                if (hParts[i] == 'intermediary') intIdx = i - 1
            }
            for (int i = 1; i < lines.size(); i++) {
                String line = lines[i]
                if (line.isEmpty() || line.startsWith('#')) continue
                String[] p = line.split('\t', -1)
                if (p[0] == 'CLASS') {
                    tm.classOffToInt[p[1 + offIdx]] = p[1 + intIdx]
                } else if (p[0] == 'FIELD') {
                    String ownerOff = p[1]
                    String descOff = p[2]
                    String nameOff = p[3 + offIdx]
                    String nameInt = p[3 + intIdx]
                    tm.fieldOffToInt["${ownerOff}#${nameOff}:${descOff}"] = nameInt
                } else if (p[0] == 'METHOD') {
                    String ownerOff = p[1]
                    String descOff = p[2]
                    String nameOff = p[3 + offIdx]
                    String nameInt = p[3 + intIdx]
                    tm.methodOffToInt["${ownerOff}#${nameOff}${descOff}"] = nameInt
                }
            }
        }
        return tm
    }

    // -------------------------------------------------------------------------
    // ProGuard client.txt + Tiny merge
    // -------------------------------------------------------------------------

    private static VersionSymbolSource parseObfuscatedMappings(Path clientTxt, TinyMappings tiny) {
        VersionSymbolSource src = new VersionSymbolSource()
        src.unobfuscated = false
        src.hasIntermediary = (tiny != null)

        List<String> lines = Files.readAllLines(clientTxt, StandardCharsets.UTF_8)

        // Pass 1: collect all Mojang <-> Obf class names (internal '/' format)
        for (String line : lines) {
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && line.endsWith(':')) {
                int arrow = line.indexOf(' -> ')
                if (arrow > 0) {
                    String mojang = line.substring(0, arrow).trim().replace('.', '/')
                    String obf = line.substring(arrow + 4, line.length() - 1).trim().replace('.', '/')
                    src.classMojangToObf[mojang] = obf
                    if (tiny != null) {
                        String inter = tiny.classOffToInt.get(obf)
                        if (inter != null) {
                            src.classMojangToInt[mojang] = inter
                        }
                    }
                }
            }
        }

        // Pass 2: parse fields and methods
        String currentMojangClass = null
        String currentObfClass = null
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith('#')) continue
            if (!Character.isWhitespace(line.charAt(0))) {
                if (line.endsWith(':')) {
                    int arrow = line.indexOf(' -> ')
                    currentMojangClass = line.substring(0, arrow).trim().replace('.', '/')
                    currentObfClass = line.substring(arrow + 4, line.length() - 1).trim().replace('.', '/')
                } else {
                    currentMojangClass = null
                    currentObfClass = null
                }
                continue
            }
            if (currentMojangClass == null) continue

            String trimmed = line.trim()
            int arrow = trimmed.indexOf(' -> ')
            if (arrow <= 0) continue
            String left = trimmed.substring(0, arrow).trim()
            String obfMember = trimmed.substring(arrow + 4).trim()

            // Strip optional line numbers "12:34:"
            int openParen = left.indexOf('(')
            if (openParen > 0) {
                // Method
                String beforeParen = left.substring(0, openParen)
                int closeParen = left.indexOf(')', openParen)
                if (closeParen < 0) continue
                String argsRaw = left.substring(openParen + 1, closeParen).trim()

                // Strip leading line numbers if present
                int firstSpace = beforeParen.indexOf(' ')
                if (firstSpace < 0) continue
                String retRaw = beforeParen.substring(0, firstSpace)
                int lastColon = retRaw.lastIndexOf(':')
                if (lastColon >= 0) {
                    retRaw = retRaw.substring(lastColon + 1)
                }
                String mojangMethodName = beforeParen.substring(firstSpace + 1).trim()
                if (mojangMethodName == '<init>' || mojangMethodName == '<clinit>') continue

                List<String> argTypes = argsRaw.isEmpty()
                        ? Collections.emptyList()
                        : argsRaw.split(',').collect { it.trim() }

                String descMojang = buildMethodDescriptor(retRaw, argTypes, null)
                String descObf = buildMethodDescriptor(retRaw, argTypes, src.classMojangToObf)
                String descInt = tiny != null
                        ? buildMethodDescriptor(retRaw, argTypes, src.classMojangToInt)
                        : null

                String interName = null
                if (tiny != null && currentObfClass != null) {
                    interName = tiny.methodOffToInt.get("${currentObfClass}#${obfMember}${descObf}")
                    if (interName == null) {
                        interName = mojangMethodName // e.g. inherited JDK / interface method not renamed
                    }
                }

                MethodEntry me = new MethodEntry(
                        mojangName: mojangMethodName,
                        obfName: obfMember,
                        interName: interName,
                        descMojang: descMojang,
                        descObf: descObf,
                        descInt: descInt
                )
                src.methods.computeIfAbsent(currentMojangClass) { new ArrayList<MethodEntry>() }.add(me)
            } else {
                // Field
                int space = left.indexOf(' ')
                if (space <= 0) continue
                String typeRaw = left.substring(0, space).trim()
                String mojangFieldName = left.substring(space + 1).trim()

                String descMojang = javaTypeToDescriptor(typeRaw, null)
                String descObf = javaTypeToDescriptor(typeRaw, src.classMojangToObf)
                String descInt = tiny != null ? javaTypeToDescriptor(typeRaw, src.classMojangToInt) : null

                String interName = null
                if (tiny != null && currentObfClass != null) {
                    interName = tiny.fieldOffToInt.get("${currentObfClass}#${obfMember}:${descObf}")
                    if (interName == null) {
                        interName = mojangFieldName
                    }
                }

                FieldEntry fe = new FieldEntry(
                        mojangName: mojangFieldName,
                        obfName: obfMember,
                        interName: interName,
                        descMojang: descMojang,
                        descObf: descObf,
                        descInt: descInt
                )
                src.fields.computeIfAbsent(currentMojangClass) { new ArrayList<FieldEntry>() }.add(fe)
            }
        }
        return src
    }

    private static String buildMethodDescriptor(
            String returnType,
            List<String> argTypes,
            Map<String, String> classRemap
    ) {
        StringBuilder sb = new StringBuilder('(')
        for (String arg : argTypes) {
            sb.append(javaTypeToDescriptor(arg, classRemap))
        }
        sb.append(')')
        sb.append(javaTypeToDescriptor(returnType, classRemap))
        return sb.toString()
    }

    private static String javaTypeToDescriptor(String rawType, Map<String, String> classRemap) {
        String t = rawType.trim()
        int dims = 0
        while (t.endsWith('[]')) {
            dims++
            t = t.substring(0, t.length() - 2).trim()
        }
        StringBuilder sb = new StringBuilder()
        for (int i = 0; i < dims; i++) sb.append('[')
        switch (t) {
            case 'void': sb.append('V'); break
            case 'boolean': sb.append('Z'); break
            case 'byte': sb.append('B'); break
            case 'short': sb.append('S'); break
            case 'char': sb.append('C'); break
            case 'int': sb.append('I'); break
            case 'long': sb.append('J'); break
            case 'float': sb.append('F'); break
            case 'double': sb.append('D'); break
            default:
                String internal = t.replace('.', '/')
                if (classRemap != null) {
                    String mapped = classRemap.get(internal)
                    if (mapped != null) {
                        internal = mapped
                    }
                }
                sb.append('L').append(internal).append(';')
                break
        }
        return sb.toString()
    }

    // -------------------------------------------------------------------------
    // Unobfuscated client.jar inspector (26.1+)
    // -------------------------------------------------------------------------

    private static final List<String> TARGET_CLASS_CANDIDATES = [
            'net/minecraft/client/Minecraft',
            'net/minecraft/util/thread/BlockableEventLoop',
            'net/minecraft/client/sounds/SoundManager',
            'net/minecraft/client/sounds/SoundEngine',
            'net/minecraft/client/sounds/SoundEngine$PlayResult',
            'net/minecraft/client/sounds/SoundEngineExecutor',
            'net/minecraft/client/sounds/MusicManager',
            'net/minecraft/client/sounds/SoundBufferLibrary',
            'net/minecraft/client/sounds/WeighedSoundEvents',
            'net/minecraft/client/sounds/AudioStream',
            'net/minecraft/client/resources/sounds/Sound',
            'net/minecraft/client/resources/sounds/SoundInstance',
            'net/minecraft/client/resources/sounds/AbstractSoundInstance',
            'com/mojang/blaze3d/audio/Channel',
            'net/minecraft/client/sounds/ChannelAccess$ChannelHandle',
            'net/minecraft/sounds/SoundSource',
            'net/minecraft/resources/Identifier',
            'net/minecraft/resources/ResourceLocation',
            'net/minecraft/server/packs/resources/ResourceProvider',
            'net/minecraft/server/packs/resources/ResourceManager',
            'net/minecraft/client/gui/screens/options/SoundOptionsScreen',
            'net/minecraft/client/gui/screens/SoundOptionsScreen',
            'net/minecraft/client/gui/components/OptionsList',
            'net/minecraft/client/OptionInstance'
    ]

    private static VersionSymbolSource readUnobfuscatedJar(String versionId, Path jarPath) {
        VersionSymbolSource src = new VersionSymbolSource()
        src.unobfuscated = true
        src.hasIntermediary = false

        try (ZipFile zf = new ZipFile(jarPath.toFile())) {
            for (String internalName : TARGET_CLASS_CANDIDATES) {
                ZipEntry entry = zf.getEntry(internalName + '.class')
                if (entry == null) continue
                src.classMojangToObf[internalName] = internalName

                try (InputStream is = zf.getInputStream(entry)) {
                    readClassFile(is, internalName, src)
                }
            }
        }
        if (src.classMojangToObf.isEmpty()) {
            throw new GradleException("Failed to inspect unobfuscated jar for ${versionId}: ${jarPath}")
        }
        return src
    }

    private static void readClassFile(InputStream rawIn, String internalName, VersionSymbolSource src) {
        DataInputStream din = new DataInputStream(new BufferedInputStream(rawIn))
        int magic = din.readInt()
        if (magic != -889275714) { // 0xCAFEBABE
            throw new IOException("Invalid class magic for ${internalName}: 0x${Integer.toHexString(magic)}")
        }
        din.readUnsignedShort() // minor
        din.readUnsignedShort() // major
        int cpCount = din.readUnsignedShort()
        String[] utf8 = new String[cpCount]
        for (int i = 1; i < cpCount; i++) {
            int tag = din.readUnsignedByte()
            switch (tag) {
                case 1: // Utf8
                    utf8[i] = din.readUTF()
                    break
                case 3: // Integer
                case 4: // Float
                    din.skipBytes(4)
                    break
                case 5: // Long
                case 6: // Double
                    din.skipBytes(8)
                    i++
                    break
                case 7: // Class
                case 8: // String
                case 16: // MethodType
                case 19: // Module
                case 20: // Package
                    din.skipBytes(2)
                    break
                case 9: // Fieldref
                case 10: // Methodref
                case 11: // InterfaceMethodref
                case 12: // NameAndType
                case 17: // Dynamic
                case 18: // InvokeDynamic
                    din.skipBytes(4)
                    break
                case 15: // MethodHandle
                    din.skipBytes(3)
                    break
                default:
                    throw new IOException("Unsupported CP tag ${tag} in ${internalName}")
            }
        }
        din.readUnsignedShort() // accessFlags
        din.readUnsignedShort() // thisClass
        din.readUnsignedShort() // superClass
        int ifaces = din.readUnsignedShort()
        din.skipBytes(ifaces * 2)

        List<FieldEntry> fields = new ArrayList<>()
        int fieldCount = din.readUnsignedShort()
        for (int i = 0; i < fieldCount; i++) {
            din.readUnsignedShort() // access
            String name = utf8[din.readUnsignedShort()]
            String desc = utf8[din.readUnsignedShort()]
            skipAttributes(din)
            fields.add(new FieldEntry(
                    mojangName: name,
                    obfName: name,
                    interName: null,
                    descMojang: desc,
                    descObf: desc,
                    descInt: null
            ))
        }
        src.fields[internalName] = fields

        List<MethodEntry> methods = new ArrayList<>()
        int methodCount = din.readUnsignedShort()
        for (int i = 0; i < methodCount; i++) {
            din.readUnsignedShort() // access
            String name = utf8[din.readUnsignedShort()]
            String desc = utf8[din.readUnsignedShort()]
            skipAttributes(din)
            if (name != '<init>' && name != '<clinit>') {
                methods.add(new MethodEntry(
                        mojangName: name,
                        obfName: name,
                        interName: null,
                        descMojang: desc,
                        descObf: desc,
                        descInt: null
                ))
            }
        }
        src.methods[internalName] = methods
    }

    private static void skipAttributes(DataInputStream din) {
        int attrCount = din.readUnsignedShort()
        for (int i = 0; i < attrCount; i++) {
            din.readUnsignedShort() // attrNameIndex
            long len = din.readInt() & 0xFFFFFFFFL
            long remaining = len
            while (remaining > 0) {
                int skipped = din.skipBytes((int) Math.min(remaining, Integer.MAX_VALUE))
                if (skipped <= 0) {
                    if (din.read() < 0) throw new EOFException()
                    remaining--
                } else {
                    remaining -= skipped
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Canonical 66-key resolution & feature flags
    // -------------------------------------------------------------------------

    private static ResolvedVersion resolveVersionSymbols(String versionId, VersionSymbolSource src) {
        Map<String, Map> outClasses = new LinkedHashMap<>()
        Map<String, String> resolvedMojangClass = new LinkedHashMap<>()
        List<String> missing = new ArrayList<>()
        Map<String, String> notes = new LinkedHashMap<>()

        def resolveClass = { String key, List<String> candidates, boolean optional ->
            String found = candidates.find { src.classMojangToObf.containsKey(it) }
            if (found == null) {
                if (!optional) missing.add("CLASS:${key}")
                return null
            }
            resolvedMojangClass[key] = found
            Map entry = new LinkedHashMap()
            entry['mojang'] = found
            entry['obf'] = src.classMojangToObf.get(found)
            if (src.hasIntermediary) {
                entry['intermediary'] = src.classMojangToInt.get(found)
            }
            outClasses[key] = entry
            return found
        }

        // 21 Classes
        resolveClass('MINECRAFT_CLIENT', ['net/minecraft/client/Minecraft'], false)
        resolveClass('EVENT_LOOP', ['net/minecraft/util/thread/BlockableEventLoop'], false)
        resolveClass('SOUND_MANAGER', ['net/minecraft/client/sounds/SoundManager'], false)
        resolveClass('SOUND_ENGINE', ['net/minecraft/client/sounds/SoundEngine'], false)
        String playResultClass = resolveClass('PLAY_RESULT', ['net/minecraft/client/sounds/SoundEngine$PlayResult'], true)
        resolveClass('SOUND_ENGINE_EXECUTOR', ['net/minecraft/client/sounds/SoundEngineExecutor'], false)
        resolveClass('MUSIC_MANAGER', ['net/minecraft/client/sounds/MusicManager'], false)
        resolveClass('SOUND_BUFFER_LIBRARY', ['net/minecraft/client/sounds/SoundBufferLibrary'], false)
        resolveClass('WEIGHED_SOUND_EVENTS', ['net/minecraft/client/sounds/WeighedSoundEvents'], false)
        resolveClass('AUDIO_STREAM', ['net/minecraft/client/sounds/AudioStream'], false)
        resolveClass('SOUND_RESOURCE', ['net/minecraft/client/resources/sounds/Sound'], false)
        resolveClass('SOUND_INSTANCE', ['net/minecraft/client/resources/sounds/SoundInstance'], false)
        resolveClass('ABSTRACT_SOUND_INSTANCE', ['net/minecraft/client/resources/sounds/AbstractSoundInstance'], false)
        resolveClass('AUDIO_CHANNEL', ['com/mojang/blaze3d/audio/Channel'], false)
        resolveClass('CHANNEL_HANDLE', ['net/minecraft/client/sounds/ChannelAccess$ChannelHandle'], false)
        resolveClass('SOUND_CATEGORY', ['net/minecraft/sounds/SoundSource'], false)
        String resourceIdClass = resolveClass('RESOURCE_ID', [
                'net/minecraft/resources/Identifier',
                'net/minecraft/resources/ResourceLocation'
        ], false)
        String resourceProviderClass = resolveClass('RESOURCE_PROVIDER', [
                'net/minecraft/server/packs/resources/ResourceProvider',
                'net/minecraft/server/packs/resources/ResourceManager'
        ], false)
        String soundOptionsClass = resolveClass('SOUND_OPTIONS_SCREEN', [
                'net/minecraft/client/gui/screens/options/SoundOptionsScreen',
                'net/minecraft/client/gui/screens/SoundOptionsScreen'
        ], false)
        resolveClass('OPTIONS_LIST', ['net/minecraft/client/gui/components/OptionsList'], false)
        String optionInstanceClass = resolveClass('OPTION_INSTANCE', ['net/minecraft/client/OptionInstance'], true)

        // 11 Fields
        Map<String, Map> outFields = new LinkedHashMap<>()
        def resolveField = { String key, String ownerKey, String fieldName, boolean optional ->
            String ownerMojang = resolvedMojangClass.get(ownerKey)
            if (ownerMojang == null) {
                if (!optional) missing.add("FIELD:${key}")
                return null
            }
            FieldEntry found = (src.fields.get(ownerMojang) ?: []).find { it.mojangName == fieldName }
            if (found == null) {
                if (!optional) missing.add("FIELD:${key}")
                return null
            }
            Map entry = new LinkedHashMap()
            entry['owner'] = ownerKey
            entry['mojang'] = [found.mojangName, found.descMojang]
            entry['obf'] = [found.obfName, found.descObf]
            if (src.hasIntermediary) {
                entry['intermediary'] = [found.interName, found.descInt]
            }
            outFields[key] = entry
            return found
        }

        resolveField('SOUND_CATEGORY_MUSIC', 'SOUND_CATEGORY', 'MUSIC', false)
        resolveField('SOUND_CATEGORY_MASTER', 'SOUND_CATEGORY', 'MASTER', false)
        resolveField('PLAY_RESULT_STARTED', 'PLAY_RESULT', 'STARTED', true)
        resolveField('SOUND_MANAGER_EMPTY_SOUND', 'SOUND_MANAGER', 'EMPTY_SOUND', false)
        FieldEntry intEmptySound = resolveField('SOUND_MANAGER_INTENTIONALLY_EMPTY_SOUND', 'SOUND_MANAGER', 'INTENTIONALLY_EMPTY_SOUND', true)
        resolveField('ABSTRACT_SOUND_INSTANCE_SOUND', 'ABSTRACT_SOUND_INSTANCE', 'sound', false)
        resolveField('MUSIC_MANAGER_CURRENT_MUSIC', 'MUSIC_MANAGER', 'currentMusic', false)
        resolveField('SOUND_ENGINE_INSTANCE_TO_CHANNEL', 'SOUND_ENGINE', 'instanceToChannel', false)
        resolveField('CHANNEL_HANDLE_CHANNEL', 'CHANNEL_HANDLE', 'channel', false)
        resolveField('AUDIO_CHANNEL_SOURCE', 'AUDIO_CHANNEL', 'source', false)
        resolveField('AUDIO_CHANNEL_INITIALIZED', 'AUDIO_CHANNEL', 'initialized', false)

        // Helper for parameter descriptors
        def clsDesc = { String classKey, String arrayPrefix ->
            String m = resolvedMojangClass.get(classKey)
            if (m == null) return null
            return "${arrayPrefix}L${m};"
        }

        // 34 Methods
        Map<String, Map> outMethods = new LinkedHashMap<>()
        def resolveMethod = { String key, List<Map> candidates, boolean optional ->
            for (Map cand : candidates) {
                String ownerKey = cand.owner as String
                String ownerMojang = resolvedMojangClass.get(ownerKey)
                if (ownerMojang == null) continue
                List<String> paramDescs = cand.params as List<String>
                if (paramDescs.any { it == null }) continue
                String paramPrefix = '(' + paramDescs.join('') + ')'
                String methodName = cand.name as String

                MethodEntry found = (src.methods.get(ownerMojang) ?: []).find {
                    it.mojangName == methodName && it.descMojang.startsWith(paramPrefix)
                }
                if (found != null) {
                    Map entry = new LinkedHashMap()
                    entry['owner'] = ownerKey
                    entry['mojang'] = [found.mojangName, found.descMojang]
                    entry['obf'] = [found.obfName, found.descObf]
                    if (src.hasIntermediary) {
                        entry['intermediary'] = [found.interName, found.descInt]
                    }
                    outMethods[key] = entry
                    notes[key] = "${ownerMojang}.${found.mojangName}${found.descMojang}"
                    return found
                }
            }
            if (!optional) missing.add("METHOD:${key}")
            return null
        }

        String soundInstDesc = clsDesc('SOUND_INSTANCE', '')
        String soundMgrDesc = clsDesc('SOUND_MANAGER', '')
        String resIdDesc = clsDesc('RESOURCE_ID', '')
        String soundCatDesc = clsDesc('SOUND_CATEGORY', '')
        String soundCatArrDesc = clsDesc('SOUND_CATEGORY', '[')
        String optInstDesc = clsDesc('OPTION_INSTANCE', '')
        String optInstArrDesc = clsDesc('OPTION_INSTANCE', '[')

        resolveMethod('MINECRAFT_GET_INSTANCE', [[owner: 'MINECRAFT_CLIENT', name: 'getInstance', params: []]], false)
        resolveMethod('EVENT_LOOP_IS_SAME_THREAD', [[owner: 'EVENT_LOOP', name: 'isSameThread', params: []]], false)

        MethodEntry smPlay = resolveMethod('SOUND_MANAGER_PLAY', [[owner: 'SOUND_MANAGER', name: 'play', params: [soundInstDesc]]], false)
        resolveMethod('SOUND_MANAGER_STOP', [[owner: 'SOUND_MANAGER', name: 'stop', params: [soundInstDesc]]], false)
        resolveMethod('SOUND_MANAGER_IS_ACTIVE', [[owner: 'SOUND_MANAGER', name: 'isActive', params: [soundInstDesc]]], false)

        MethodEntry sePlay = resolveMethod('SOUND_ENGINE_PLAY', [[owner: 'SOUND_ENGINE', name: 'play', params: [soundInstDesc]]], false)
        resolveMethod('SOUND_ENGINE_IS_ACTIVE', [[owner: 'SOUND_ENGINE', name: 'isActive', params: [soundInstDesc]]], false)
        resolveMethod('SOUND_ENGINE_STOP_INSTANCE', [[owner: 'SOUND_ENGINE', name: 'stop', params: [soundInstDesc]]], false)
        resolveMethod('SOUND_ENGINE_STOP_BY_ID', [[owner: 'SOUND_ENGINE', name: 'stop', params: [resIdDesc, soundCatDesc]]], false)
        resolveMethod('SOUND_ENGINE_STOP_ALL', [[owner: 'SOUND_ENGINE', name: 'stopAll', params: []]], false)

        MethodEntry updateCatVol = resolveMethod('SOUND_ENGINE_UPDATE_CATEGORY_VOLUME', [
                [owner: 'SOUND_ENGINE', name: 'refreshCategoryVolume', params: [soundCatDesc]],
                [owner: 'SOUND_ENGINE', name: 'updateCategoryVolume', params: [soundCatDesc, 'F']],
                [owner: 'SOUND_ENGINE', name: 'updateCategoryVolume', params: [soundCatDesc]]
        ], false)
        MethodEntry pauseMethod = resolveMethod('SOUND_ENGINE_PAUSE', [
                [owner: 'SOUND_ENGINE', name: 'pauseAllExcept', params: [soundCatArrDesc]],
                [owner: 'SOUND_ENGINE', name: 'pause', params: []]
        ], false)
        resolveMethod('SOUND_ENGINE_RESUME', [[owner: 'SOUND_ENGINE', name: 'resume', params: []]], false)
        MethodEntry calcVol = resolveMethod('SOUND_ENGINE_CALCULATE_VOLUME', [
                [owner: 'SOUND_ENGINE', name: 'calculateVolume', params: ['F', soundCatDesc]],
                [owner: 'SOUND_ENGINE', name: 'calculateVolume', params: [soundInstDesc]]
        ], false)

        resolveMethod('SOUND_INSTANCE_GET_CATEGORY', [[owner: 'SOUND_INSTANCE', name: 'getSource', params: []]], false)
        MethodEntry getIdMethod = resolveMethod('SOUND_INSTANCE_GET_ID', [
                [owner: 'SOUND_INSTANCE', name: 'getIdentifier', params: []],
                [owner: 'SOUND_INSTANCE', name: 'getLocation', params: []]
        ], false)
        resolveMethod('SOUND_INSTANCE_GET_VOLUME', [[owner: 'SOUND_INSTANCE', name: 'getVolume', params: []]], false)
        resolveMethod('SOUND_INSTANCE_GET_PITCH', [[owner: 'SOUND_INSTANCE', name: 'getPitch', params: []]], false)
        resolveMethod('SOUND_INSTANCE_IS_LOOPING', [[owner: 'SOUND_INSTANCE', name: 'isLooping', params: []]], false)
        resolveMethod('SOUND_INSTANCE_GET_SOUND', [[owner: 'SOUND_INSTANCE', name: 'getSound', params: []]], false)
        resolveMethod('SOUND_INSTANCE_RESOLVE', [
                [owner: 'SOUND_INSTANCE', name: 'resolve', params: [soundMgrDesc]],
                [owner: 'ABSTRACT_SOUND_INSTANCE', name: 'resolve', params: [soundMgrDesc]],
                [owner: 'SOUND_INSTANCE', name: 'getOrResolve', params: [soundMgrDesc]],
                [owner: 'ABSTRACT_SOUND_INSTANCE', name: 'getOrResolve', params: [soundMgrDesc]]
        ], false)

        resolveMethod('SOUND_RESOURCE_GET_LOCATION', [[owner: 'SOUND_RESOURCE', name: 'getLocation', params: []]], false)
        resolveMethod('SOUND_RESOURCE_GET_PATH', [[owner: 'SOUND_RESOURCE', name: 'getPath', params: []]], false)

        MethodEntry getStreamMethod = resolveMethod('SOUND_BUFFER_LIBRARY_GET_STREAM', [
                [owner: 'SOUND_BUFFER_LIBRARY', name: 'getStream', params: [resIdDesc, 'Z']],
                [owner: 'SOUND_BUFFER_LIBRARY', name: 'getStream', params: [resIdDesc]]
        ], false)
        resolveMethod('AUDIO_STREAM_GET_FORMAT', [[owner: 'AUDIO_STREAM', name: 'getFormat', params: []]], false)
        resolveMethod('AUDIO_STREAM_READ', [[owner: 'AUDIO_STREAM', name: 'read', params: ['I']]], false)
        MethodEntry resOpen = resolveMethod('RESOURCE_PROVIDER_OPEN', [
                [owner: 'RESOURCE_PROVIDER', name: 'open', params: [resIdDesc]],
                [owner: 'RESOURCE_PROVIDER', name: 'getResource', params: [resIdDesc]]
        ], false)

        resolveMethod('AUDIO_CHANNEL_PLAY', [[owner: 'AUDIO_CHANNEL', name: 'play', params: []]], false)
        resolveMethod('AUDIO_CHANNEL_STOP', [[owner: 'AUDIO_CHANNEL', name: 'stop', params: []]], false)
        resolveMethod('AUDIO_CHANNEL_DESTROY', [[owner: 'AUDIO_CHANNEL', name: 'destroy', params: []]], false)
        resolveMethod('AUDIO_CHANNEL_REMOVE_PROCESSED_BUFFERS', [[owner: 'AUDIO_CHANNEL', name: 'removeProcessedBuffers', params: []]], false)

        MethodEntry optPopulate = resolveMethod('SOUND_OPTIONS_SCREEN_POPULATE', [
                [owner: 'SOUND_OPTIONS_SCREEN', name: 'addOptions', params: []],
                [owner: 'SOUND_OPTIONS_SCREEN', name: 'init', params: []]
        ], false)
        MethodEntry createBooleanMethod = resolveMethod('OPTION_INSTANCE_CREATE_BOOLEAN', [
                [owner: 'OPTION_INSTANCE', name: 'createBoolean', params: ['Ljava/lang/String;', 'Z', 'Ljava/util/function/Consumer;']],
                [owner: 'OPTION_INSTANCE', name: 'createBoolean', params: ['Ljava/lang/String;', 'Z', 'Lnet/minecraft/client/OptionInstance$ValueUpdateListener;']]
        ], true)
        MethodEntry addSmall = resolveMethod('OPTIONS_LIST_ADD_SMALL', [
                [owner: 'OPTIONS_LIST', name: 'addSmall', params: [optInstArrDesc]],
                [owner: 'OPTIONS_LIST', name: 'addSmall', params: [optInstDesc, optInstDesc]]
        ], true)

        Map<String, Object> features = new LinkedHashMap<>()
        features['playReturnsResult'] = (sePlay != null && !sePlay.descMojang.endsWith(')V') && smPlay != null)
        features['hasPlayResult'] = (playResultClass != null)
        features['soundInstanceGetIdentifier'] = (getIdMethod != null && getIdMethod.mojangName == 'getIdentifier')
        features['resourceIdIsIdentifier'] = (resourceIdClass == 'net/minecraft/resources/Identifier')
        features['resourceProviderOpen'] = (resOpen != null && resOpen.mojangName == 'open')
        features['hasResourceProvider'] = (resourceProviderClass == 'net/minecraft/server/packs/resources/ResourceProvider')
        features['soundOptionsInOptionsPkg'] = (soundOptionsClass == 'net/minecraft/client/gui/screens/options/SoundOptionsScreen')
        features['soundOptionsAddOptions'] = (optPopulate != null && optPopulate.mojangName == 'addOptions')
        features['hasOptionInstance'] = (optionInstanceClass != null)
        features['optionsListAddSmallArray'] = (addSmall != null && addSmall.descMojang.startsWith('(['))
        features['hasIntentionallyEmptySound'] = (intEmptySound != null)
        features['soundEnginePauseAllExcept'] = (pauseMethod != null && pauseMethod.mojangName == 'pauseAllExcept')
        features['soundEngineRefreshCategoryVolume'] = (updateCatVol != null && updateCatVol.mojangName == 'refreshCategoryVolume')
        features['soundEngineUpdateCategoryVolumeHasFloat'] = (updateCatVol != null && updateCatVol.descMojang.contains('F)'))
        features['soundEngineCalculateVolumeFloatSource'] = (calcVol != null && calcVol.descMojang.startsWith('(F'))
        features['soundBufferGetStreamHasFlag'] = (getStreamMethod != null && getStreamMethod.descMojang.contains(';Z)'))
        features['optionInstanceUsesValueUpdateListener'] = (createBooleanMethod != null && createBooleanMethod.descMojang.contains('ValueUpdateListener'))

        Map row = [
                version: versionId,
                unobfuscated: src.unobfuscated,
                hasIntermediary: src.hasIntermediary,
                classesResolved: outClasses.size(),
                fieldsResolved: outFields.size(),
                methodsResolved: outMethods.size(),
                missing: missing,
                features: features,
                notes: notes,
                classes: resolvedMojangClass
        ]

        return new ResolvedVersion(
                classes: outClasses,
                fields: outFields,
                methods: outMethods,
                features: features,
                reportRow: row
        )
    }

    // -------------------------------------------------------------------------
    // Report generation
    // -------------------------------------------------------------------------

    private static String buildReport(List<Map> versions, List<Map> rows) {
        StringBuilder sb = new StringBuilder()
        sb.append("=== MusicBus Mappings & API Differences Report ===\n")
        sb.append("Versions processed: ${rows.size()} (${versions.first().id} -> ${versions.last().id})\n\n")

        sb.append("--- 1. Version Coverage & Missing Keys ---\n")
        for (Map row : rows) {
            String miss = (row.missing as List).isEmpty() ? 'OK' : "MISSING: ${(row.missing as List).join(', ')}"
            sb.append(String.format(Locale.ROOT,
                    "%-10s | unobf=%-5s | inter=%-5s | C=%2d/21 F=%2d/11 M=%2d/34 | %s\n",
                    row.version, row.unobfuscated, row.hasIntermediary,
                    row.classesResolved, row.fieldsResolved, row.methodsResolved, miss))
        }

        sb.append("\n--- 2. Feature Flag Transitions ---\n")
        Map prevFeat = null
        for (Map row : rows) {
            Map curFeat = row.features as Map
            if (prevFeat == null) {
                sb.append("${row.version} (baseline): ${curFeat}\n")
            } else {
                List<String> diffs = new ArrayList<>()
                curFeat.each { k, v ->
                    if (prevFeat[k] != v) {
                        diffs.add("${k}: ${prevFeat[k]} -> ${v}")
                    }
                }
                if (!diffs.isEmpty()) {
                    sb.append("${row.version}: ${diffs.join('; ')}\n")
                }
            }
            prevFeat = curFeat
        }

        sb.append("\n--- 3. Signature Changes Across Versions ---\n")
        Set<String> allKeys = new LinkedHashSet<>()
        rows.each { allKeys.addAll((it.notes as Map).keySet()) }
        for (String key : allKeys) {
            String lastSig = null
            List<String> timeline = new ArrayList<>()
            for (Map row : rows) {
                String sig = (row.notes as Map).get(key) ?: '<ABSENT>'
                if (sig != lastSig) {
                    timeline.add("${row.version}: ${sig}")
                    lastSig = sig
                }
            }
            if (timeline.size() > 1) {
                sb.append("${key}:\n")
                timeline.each { sb.append("  - ${it}\n") }
            }
        }
        return sb.toString()
    }

    private void printConsoleSummary(List<Map> rows) {
        Map prevFeat = null
        for (Map row : rows) {
            List missing = row.missing as List
            if (!missing.isEmpty()) {
                logger.warn("[MusicBus] ${row.version}: missing keys -> ${missing.join(', ')}")
            }
            Map curFeat = row.features as Map
            if (prevFeat != null) {
                List<String> diffs = new ArrayList<>()
                curFeat.each { k, v ->
                    if (prevFeat[k] != v) diffs.add("${k}=${v}")
                }
                if (!diffs.isEmpty()) {
                    logger.lifecycle("[MusicBus] API shift at ${row.version}: ${diffs.join(', ')}")
                }
            }
            prevFeat = curFeat
        }
    }

    // -------------------------------------------------------------------------
    // Data structs
    // -------------------------------------------------------------------------

    private static class TinyMappings {
        final Map<String, String> classOffToInt = new LinkedHashMap<>()
        final Map<String, String> fieldOffToInt = new LinkedHashMap<>()
        final Map<String, String> methodOffToInt = new LinkedHashMap<>()
    }

    private static class VersionSymbolSource {
        boolean unobfuscated
        boolean hasIntermediary
        final Map<String, String> classMojangToObf = new LinkedHashMap<>()
        final Map<String, String> classMojangToInt = new LinkedHashMap<>()
        final Map<String, List<FieldEntry>> fields = new LinkedHashMap<>()
        final Map<String, List<MethodEntry>> methods = new LinkedHashMap<>()
    }

    private static class FieldEntry {
        String mojangName
        String obfName
        String interName
        String descMojang
        String descObf
        String descInt
    }

    private static class MethodEntry {
        String mojangName
        String obfName
        String interName
        String descMojang
        String descObf
        String descInt
    }

    private static class ResolvedVersion {
        Map<String, Map> classes
        Map<String, Map> fields
        Map<String, Map> methods
        Map<String, Object> features
        Map reportRow
    }
}