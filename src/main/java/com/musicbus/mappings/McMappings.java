package com.musicbus.mappings;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class McMappings {

    public enum Namespace {
        MOJANG,
        OBF,
        INTERMEDIARY
    }

    private static final String MAPPINGS_RESOURCE = "/com/musicbus/mappings/mappings.json";
    private static final String VERSION_JSON_RESOURCE = "version.json";

    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initialized = false;
    private static volatile ClassLoader gameClassLoader;

    private static volatile String detectedMcVersion;
    private static volatile String matchedVersion;
    private static volatile Namespace activeNamespace;
    private static volatile boolean unobfuscatedVersion;

    private static volatile Map<McClass, String> classInternalNames = Collections.emptyMap();
    private static volatile Map<String, McClass> internalNameToClass = Collections.emptyMap();
    private static volatile Map<McField, MemberMapping> fieldMappings = Collections.emptyMap();
    private static volatile Map<McMethod, MemberMapping> methodMappings = Collections.emptyMap();
    private static volatile Map<McFeature, Boolean> featureFlags = Collections.emptyMap();

    private static final Map<McClass, Class<?>> RESOLVED_CLASSES = new ConcurrentHashMap<McClass, Class<?>>();
    private static final Map<McField, Field> RESOLVED_FIELDS = new ConcurrentHashMap<McField, Field>();
    private static final Map<McMethod, Method> RESOLVED_METHODS = new ConcurrentHashMap<McMethod, Method>();

    private McMappings() {
    }

    public static void ensureInitialized() {
        ensureInitialized(resolveDefaultClassLoader());
    }

    public static void ensureInitialized(ClassLoader loader) {
        if (initialized) {
            if (loader != null && gameClassLoader == null) {
                gameClassLoader = loader;
            }
            return;
        }
        synchronized (INIT_LOCK) {
            if (initialized) {
                if (loader != null && gameClassLoader == null) {
                    gameClassLoader = loader;
                }
                return;
            }
            ClassLoader effectiveLoader = loader != null ? loader : resolveDefaultClassLoader();
            doInitialize(effectiveLoader, null, null);
        }
    }

    public static boolean tryInitialize(ClassLoader loader) {
        if (initialized) {
            if (loader != null && gameClassLoader == null) {
                gameClassLoader = loader;
            }
            return true;
        }
        synchronized (INIT_LOCK) {
            if (initialized) {
                return true;
            }
            ClassLoader effectiveLoader = loader != null ? loader : resolveDefaultClassLoader();
            String mcVer = detectMinecraftVersion(effectiveLoader);
            if (mcVer == null) {
                return false;
            }
            doInitialize(effectiveLoader, mcVer, null);
            return true;
        }
    }

    public static void initForTesting(String versionId, Namespace namespace) {
        synchronized (INIT_LOCK) {
            resetCaches();
            doInitialize(resolveDefaultClassLoader(), versionId, namespace);
        }
    }

    public static String getMinecraftVersion() {
        ensureInitialized();
        return detectedMcVersion;
    }

    public static String getMatchedVersion() {
        ensureInitialized();
        return matchedVersion;
    }

    public static Namespace getNamespace() {
        ensureInitialized();
        return activeNamespace;
    }

    public static boolean isUnobfuscated() {
        ensureInitialized();
        return unobfuscatedVersion;
    }

    public static boolean hasFeature(McFeature feature) {
        ensureInitialized();
        Boolean val = featureFlags.get(feature);
        return val != null && val.booleanValue();
    }

    public static boolean hasClass(McClass key) {
        ensureInitialized();
        return classInternalNames.containsKey(key);
    }

    public static String internalName(McClass key) {
        ensureInitialized();
        return classInternalNames.get(key);
    }

    public static String className(McClass key) {
        String internal = internalName(key);
        return internal == null ? null : internal.replace('/', '.');
    }

    public static String descriptor(McClass key) {
        String internal = internalName(key);
        return internal == null ? null : "L" + internal + ";";
    }

    public static McClass findClassByInternalName(String internalName) {
        if (internalName == null || !initialized) {
            return null;
        }
        return internalNameToClass.get(internalName);
    }

    public static boolean hasField(McField key) {
        ensureInitialized();
        return fieldMappings.containsKey(key);
    }

    public static String fieldOwner(McField key) {
        ensureInitialized();
        MemberMapping m = fieldMappings.get(key);
        return m == null ? null : m.ownerInternalName;
    }

    public static String fieldName(McField key) {
        ensureInitialized();
        MemberMapping m = fieldMappings.get(key);
        return m == null ? null : m.name;
    }

    public static String fieldDescriptor(McField key) {
        ensureInitialized();
        MemberMapping m = fieldMappings.get(key);
        return m == null ? null : m.descriptor;
    }

    public static boolean hasMethod(McMethod key) {
        ensureInitialized();
        return methodMappings.containsKey(key);
    }

    public static String methodOwner(McMethod key) {
        ensureInitialized();
        MemberMapping m = methodMappings.get(key);
        return m == null ? null : m.ownerInternalName;
    }

    public static String methodName(McMethod key) {
        ensureInitialized();
        MemberMapping m = methodMappings.get(key);
        return m == null ? null : m.name;
    }

    public static String methodDescriptor(McMethod key) {
        ensureInitialized();
        MemberMapping m = methodMappings.get(key);
        return m == null ? null : m.descriptor;
    }

    public static boolean matchesMethod(McMethod key, String name, String descriptor) {
        ensureInitialized();
        MemberMapping m = methodMappings.get(key);
        return m != null && m.name.equals(name) && m.descriptor.equals(descriptor);
    }

    public static Class<?> resolveClass(McClass key) {
        ensureInitialized();
        Class<?> cached = RESOLVED_CLASSES.get(key);
        if (cached != null) {
            return cached;
        }
        String name = className(key);
        if (name == null) {
            throw new IllegalStateException("[MusicBus] Mapping class not available in MC "
                    + matchedVersion + ": " + key);
        }
        try {
            ClassLoader cl = gameClassLoader != null ? gameClassLoader : resolveDefaultClassLoader();
            Class<?> loaded = Class.forName(name, false, cl);
            RESOLVED_CLASSES.put(key, loaded);
            return loaded;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("[MusicBus] Failed to load class " + key
                    + " (" + name + ") in namespace " + activeNamespace, e);
        }
    }

    public static boolean isInstance(McClass key, Object obj) {
        if (obj == null || !hasClass(key)) {
            return false;
        }
        return resolveClass(key).isInstance(obj);
    }

    public static Field resolveField(McField key) {
        ensureInitialized();
        Field cached = RESOLVED_FIELDS.get(key);
        if (cached != null) {
            return cached;
        }
        MemberMapping mapping = fieldMappings.get(key);
        if (mapping == null) {
            throw new IllegalStateException("[MusicBus] Mapping field not available in MC "
                    + matchedVersion + ": " + key);
        }
        Class<?> owner = resolveClass(mapping.owner);
        Field field = findDeclaredField(owner, mapping.name);
        if (field == null) {
            throw new IllegalStateException("[MusicBus] Field not found: " + key
                    + " (" + owner.getName() + "." + mapping.name + ")");
        }
        field.setAccessible(true);
        RESOLVED_FIELDS.put(key, field);
        return field;
    }

    public static Object getFieldValue(McField key, Object target) {
        try {
            return resolveField(key).get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("[MusicBus] Cannot read field " + key, e);
        }
    }

    public static void setFieldValue(McField key, Object target, Object value) {
        try {
            resolveField(key).set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("[MusicBus] Cannot write field " + key, e);
        }
    }

    public static Method resolveMethod(McMethod key) {
        ensureInitialized();
        Method cached = RESOLVED_METHODS.get(key);
        if (cached != null) {
            return cached;
        }
        MemberMapping mapping = methodMappings.get(key);
        if (mapping == null) {
            throw new IllegalStateException("[MusicBus] Mapping method not available in MC "
                    + matchedVersion + ": " + key);
        }
        Class<?> owner = resolveClass(mapping.owner);
        ClassLoader cl = owner.getClassLoader() != null ? owner.getClassLoader() : gameClassLoader;
        Class<?>[] paramTypes = parseParameterTypes(mapping.descriptor, cl);
        Method method = findDeclaredMethod(owner, mapping.name, paramTypes);
        if (method == null) {
            throw new IllegalStateException("[MusicBus] Method not found: " + key
                    + " (" + owner.getName() + "." + mapping.name + mapping.descriptor + ")");
        }
        method.setAccessible(true);
        RESOLVED_METHODS.put(key, method);
        return method;
    }

    public static Object invokeMethod(McMethod key, Object target, Object... args) {
        try {
            return resolveMethod(key).invoke(target, args);
        } catch (Exception e) {
            throw new IllegalStateException("[MusicBus] Failed to invoke " + key, e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal initialization
    // -------------------------------------------------------------------------

    private static void resetCaches() {
        initialized = false;
        RESOLVED_CLASSES.clear();
        RESOLVED_FIELDS.clear();
        RESOLVED_METHODS.clear();
    }

    private static void doInitialize(
            ClassLoader loader,
            String explicitMcVersion,
            Namespace explicitNamespace
    ) {
        JsonObject root = loadMappingsJson();
        JsonObject versionsObj = root.get("versions").asObject();

        String mcVer = explicitMcVersion != null ? explicitMcVersion : detectMinecraftVersion(loader);
        if (mcVer == null || mcVer.trim().isEmpty()) {
            throw new IllegalStateException(
                    "[MusicBus] Could not detect Minecraft version (version.json missing and -Dmusicbus.mc.version not set)"
            );
        }

        String matched = findClosestVersion(mcVer, versionsObj);
        if (matched == null) {
            throw new IllegalStateException("[MusicBus] No compatible mapping entry for Minecraft " + mcVer);
        }

        JsonObject verObj = versionsObj.get(matched).asObject();
        boolean unobf = verObj.getBoolean("unobfuscated", false);
        boolean hasIntermediary = verObj.getBoolean("hasIntermediary", false);

        JsonObject classPools = root.get("classes").asObject();
        JsonObject fieldPools = root.get("fields").asObject();
        JsonObject methodPools = root.get("methods").asObject();
        JsonObject featurePools = root.get("features").asObject();

        Namespace ns = explicitNamespace != null
                ? explicitNamespace
                : detectNamespace(loader, unobf, hasIntermediary, verObj.get("classes").asObject(), classPools);

        String nsKey = namespaceJsonKey(ns);

        // Build class maps
        EnumMap<McClass, String> clsMap = new EnumMap<McClass, String>(McClass.class);
        Map<String, McClass> revClsMap = new HashMap<String, McClass>();
        JsonObject verClasses = verObj.get("classes").asObject();
        for (McClass key : McClass.values()) {
            String poolId = verClasses.getString(key.name(), null);
            if (poolId == null) {
                continue;
            }
            JsonObject entry = classPools.get(key.name()).asObject().get(poolId).asObject();
            String resolvedName = entry.getString(nsKey, null);
            if (resolvedName == null) {
                resolvedName = entry.getString("mojang", null);
            }
            if (resolvedName != null) {
                clsMap.put(key, resolvedName);
                revClsMap.put(resolvedName, key);
            }
        }

        // Build field maps
        EnumMap<McField, MemberMapping> fldMap = new EnumMap<McField, MemberMapping>(McField.class);
        JsonObject verFields = verObj.get("fields").asObject();
        for (McField key : McField.values()) {
            String poolId = verFields.getString(key.name(), null);
            if (poolId == null) {
                continue;
            }
            JsonObject entry = fieldPools.get(key.name()).asObject().get(poolId).asObject();
            MemberMapping mm = extractMember(entry, nsKey, clsMap);
            if (mm != null) {
                fldMap.put(key, mm);
            }
        }

        // Build method maps
        EnumMap<McMethod, MemberMapping> mthMap = new EnumMap<McMethod, MemberMapping>(McMethod.class);
        JsonObject verMethods = verObj.get("methods").asObject();
        for (McMethod key : McMethod.values()) {
            String poolId = verMethods.getString(key.name(), null);
            if (poolId == null) {
                continue;
            }
            JsonObject entry = methodPools.get(key.name()).asObject().get(poolId).asObject();
            MemberMapping mm = extractMember(entry, nsKey, clsMap);
            if (mm != null) {
                mthMap.put(key, mm);
            }
        }

        // Build feature flags
        EnumMap<McFeature, Boolean> featMap = new EnumMap<McFeature, Boolean>(McFeature.class);
        String featPoolId = verObj.getString("features", null);
        JsonObject featObj = featPoolId != null ? featurePools.get(featPoolId).asObject() : new JsonObject();
        for (McFeature feat : McFeature.values()) {
            featMap.put(feat, Boolean.valueOf(featObj.getBoolean(feat.getJsonKey(), false)));
        }

        gameClassLoader = loader;
        detectedMcVersion = mcVer;
        matchedVersion = matched;
        activeNamespace = ns;
        unobfuscatedVersion = unobf;

        classInternalNames = Collections.unmodifiableMap(clsMap);
        internalNameToClass = Collections.unmodifiableMap(revClsMap);
        fieldMappings = Collections.unmodifiableMap(fldMap);
        methodMappings = Collections.unmodifiableMap(mthMap);
        featureFlags = Collections.unmodifiableMap(featMap);
        initialized = true;

        String suffix = mcVer.equals(matched) ? "" : " (mapped as " + matched + ")";
        System.out.println("[MusicBus] Mappings initialized: mc=" + mcVer + suffix
                + ", namespace=" + ns + ", unobf=" + unobf);
    }

    private static MemberMapping extractMember(
            JsonObject entry,
            String nsKey,
            Map<McClass, String> clsMap
    ) {
        String ownerKeyName = entry.getString("owner", null);
        if (ownerKeyName == null) {
            return null;
        }
        McClass owner = McClass.valueOf(ownerKeyName);
        String ownerInternal = clsMap.get(owner);
        if (ownerInternal == null) {
            return null;
        }
        JsonValue pairVal = entry.get(nsKey);
        if (pairVal == null || pairVal.isNull()) {
            pairVal = entry.get("mojang");
        }
        if (pairVal == null || !pairVal.isArray()) {
            return null;
        }
        JsonArray arr = pairVal.asArray();
        if (arr.size() < 2) {
            return null;
        }
        return new MemberMapping(owner, ownerInternal, arr.get(0).asString(), arr.get(1).asString());
    }

    private static JsonObject loadMappingsJson() {
        InputStream stream = McMappings.class.getResourceAsStream(MAPPINGS_RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("[MusicBus] Missing mappings resource: " + MAPPINGS_RESOURCE);
        }
        try {
            InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
            try {
                return Json.parse(reader).asObject();
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            throw new IllegalStateException("[MusicBus] Failed to parse " + MAPPINGS_RESOURCE, e);
        }
    }

    private static String detectMinecraftVersion(ClassLoader loader) {
        String prop = System.getProperty("musicbus.mc.version");
        if (prop != null && !prop.trim().isEmpty()) {
            return prop.trim();
        }
        String fabricProp = System.getProperty("fabric.gameVersion");
        if (fabricProp != null && !fabricProp.trim().isEmpty()) {
            return fabricProp.trim();
        }

        InputStream stream = openResource(loader, VERSION_JSON_RESOURCE);
        if (stream == null) {
            return null;
        }
        try {
            InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
            try {
                JsonObject obj = Json.parse(reader).asObject();
                String id = obj.getString("id", null);
                if (id != null && !id.trim().isEmpty()) {
                    return id.trim();
                }
                String name = obj.getString("name", null);
                return (name != null && !name.trim().isEmpty()) ? name.trim() : null;
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Namespace detectNamespace(
            ClassLoader loader,
            boolean unobfuscated,
            boolean hasIntermediary,
            JsonObject verClasses,
            JsonObject classPools
    ) {
        String prop = System.getProperty("musicbus.mc.namespace");
        if (prop != null && !prop.trim().isEmpty()) {
            return Namespace.valueOf(prop.trim().toUpperCase(Locale.ROOT));
        }
        if (unobfuscated) {
            return Namespace.MOJANG;
        }

        String poolId = verClasses.getString(McClass.MINECRAFT_CLIENT.name(), null);
        if (poolId != null && loader != null) {
            JsonObject mcEntry = classPools.get(McClass.MINECRAFT_CLIENT.name()).asObject().get(poolId).asObject();
            String mojangName = mcEntry.getString("mojang", null);
            String interName = mcEntry.getString("intermediary", null);
            String obfName = mcEntry.getString("obf", null);

            if (mojangName != null && hasClassResource(loader, mojangName)) {
                return Namespace.MOJANG;
            }
            if (hasIntermediary && interName != null && hasClassResource(loader, interName)) {
                return Namespace.INTERMEDIARY;
            }
            if (obfName != null && hasClassResource(loader, obfName)) {
                return Namespace.OBF;
            }
        }

        if (hasIntermediary && loader != null
                && (hasClassResource(loader, "net/fabricmc/loader/api/FabricLoader")
                || System.getProperty("fabric.gameVersion") != null)) {
            return Namespace.INTERMEDIARY;
        }
        return Namespace.OBF;
    }

    private static boolean hasClassResource(ClassLoader loader, String internalName) {
        return loader.getResource(internalName + ".class") != null;
    }

    private static InputStream openResource(ClassLoader loader, String name) {
        InputStream stream = loader != null ? loader.getResourceAsStream(name) : null;
        if (stream == null) {
            stream = McMappings.class.getResourceAsStream("/" + name);
        }
        if (stream == null) {
            stream = ClassLoader.getSystemResourceAsStream(name);
        }
        return stream;
    }

    private static ClassLoader resolveDefaultClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : McMappings.class.getClassLoader();
    }

    private static String namespaceJsonKey(Namespace ns) {
        switch (ns) {
            case MOJANG:
                return "mojang";
            case INTERMEDIARY:
                return "intermediary";
            case OBF:
            default:
                return "obf";
        }
    }

    // -------------------------------------------------------------------------
    // Version matching
    // -------------------------------------------------------------------------

    private static String findClosestVersion(String target, JsonObject versionsObj) {
        if (versionsObj.get(target) != null) {
            return target;
        }
        int dash = target.indexOf('-');
        if (dash > 0) {
            String base = target.substring(0, dash);
            if (versionsObj.get(base) != null) {
                return base;
            }
        }
        int space = target.indexOf(' ');
        if (space > 0) {
            String base = target.substring(0, space);
            if (versionsObj.get(base) != null) {
                return base;
            }
        }

        int[] targetTuple = parseVersionTuple(target);
        if (targetTuple == null) {
            return null;
        }

        String bestLe = null;
        int[] bestLeTuple = null;
        String latest = null;
        int[] latestTuple = null;

        for (String candidate : versionsObj.names()) {
            int[] candTuple = parseVersionTuple(candidate);
            if (candTuple == null) {
                continue;
            }
            if (latestTuple == null || compareTuples(candTuple, latestTuple) > 0) {
                latestTuple = candTuple;
                latest = candidate;
            }
            if (compareTuples(candTuple, targetTuple) <= 0) {
                if (bestLeTuple == null || compareTuples(candTuple, bestLeTuple) > 0) {
                    bestLeTuple = candTuple;
                    bestLe = candidate;
                }
            }
        }
        return bestLe != null ? bestLe : latest;
    }

    private static int[] parseVersionTuple(String version) {
        String clean = version.trim();
        int cut = clean.length();
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if ((c < '0' || c > '9') && c != '.') {
                cut = i;
                break;
            }
        }
        clean = clean.substring(0, cut);
        if (clean.isEmpty()) {
            return null;
        }
        String[] parts = clean.split("\\.");
        int[] tuple = new int[Math.max(3, parts.length)];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                return null;
            }
            try {
                tuple[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return tuple;
    }

    private static int compareTuples(int[] a, int[] b) {
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) {
                return av < bv ? -1 : 1;
            }
        }
        return 0;
    }

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    private static Field findDeclaredField(Class<?> owner, String name) {
        Class<?> current = owner;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Method findDeclaredMethod(Class<?> owner, String name, Class<?>[] paramTypes) {
        Class<?> current = owner;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        try {
            return owner.getMethod(name, paramTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Class<?>[] parseParameterTypes(String methodDescriptor, ClassLoader loader) {
        if (methodDescriptor == null || !methodDescriptor.startsWith("(")) {
            throw new IllegalArgumentException("Invalid method descriptor: " + methodDescriptor);
        }
        List<Class<?>> types = new ArrayList<Class<?>>();
        int i = 1;
        while (i < methodDescriptor.length()) {
            char c = methodDescriptor.charAt(i);
            if (c == ')') {
                break;
            }
            int dims = 0;
            while (c == '[') {
                dims++;
                i++;
                c = methodDescriptor.charAt(i);
            }
            Class<?> base;
            if (c == 'L') {
                int semi = methodDescriptor.indexOf(';', i);
                if (semi < 0) {
                    throw new IllegalArgumentException("Malformed descriptor: " + methodDescriptor);
                }
                String className = methodDescriptor.substring(i + 1, semi).replace('/', '.');
                try {
                    base = Class.forName(className, false, loader);
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("[MusicBus] Parameter class not found: " + className, e);
                }
                i = semi + 1;
            } else {
                base = primitiveType(c);
                i++;
            }
            for (int d = 0; d < dims; d++) {
                base = Array.newInstance(base, 0).getClass();
            }
            types.add(base);
        }
        return types.toArray(new Class<?>[types.size()]);
    }

    private static Class<?> primitiveType(char code) {
        switch (code) {
            case 'Z': return boolean.class;
            case 'B': return byte.class;
            case 'S': return short.class;
            case 'C': return char.class;
            case 'I': return int.class;
            case 'J': return long.class;
            case 'F': return float.class;
            case 'D': return double.class;
            case 'V': return void.class;
            default:
                throw new IllegalArgumentException("Unknown primitive descriptor: " + code);
        }
    }

    private static final class MemberMapping {
        final McClass owner;
        final String ownerInternalName;
        final String name;
        final String descriptor;

        MemberMapping(McClass owner, String ownerInternalName, String name, String descriptor) {
            this.owner = owner;
            this.ownerInternalName = ownerInternalName;
            this.name = name;
            this.descriptor = descriptor;
        }
    }
}