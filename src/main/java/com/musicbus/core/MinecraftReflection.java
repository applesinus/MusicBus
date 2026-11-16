package com.musicbus.core;

import com.musicbus.mappings.McClass;
import com.musicbus.mappings.McMappings;
import com.musicbus.mappings.McMethod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Shared reflection utilities for inspecting and invoking
 * Minecraft client classes without compile-time dependencies.
 */
public final class MinecraftReflection {

    private MinecraftReflection() {}

    // --------------------------------------------------
    // Core Minecraft singletons & subsystems
    // --------------------------------------------------

    public static Object getMinecraftInstance() throws Exception {
        return getMinecraftInstance(null);
    }

    public static Object getMinecraftInstance(
            ClassLoader preferredLoader
    ) throws Exception {

        ClassLoader loader = preferredLoader;
        if (loader == null) {
            loader = Thread.currentThread().getContextClassLoader();
        }
        if (loader == null) {
            loader = MinecraftReflection.class.getClassLoader();
        }

        McMappings.ensureInitialized(loader);
        return McMappings.invokeMethod(McMethod.MINECRAFT_GET_INSTANCE, null);
    }

    public static Object getSoundManager() throws Exception {

        Object minecraft = getMinecraftInstance();
        if (minecraft == null) {
            return null;
        }

        return findObjectByType(minecraft, McClass.SOUND_MANAGER);
    }

    public static Object getSoundEngine() throws Exception {

        Object soundManager = getSoundManager();
        if (soundManager == null) {
            return null;
        }

        return findObjectByType(soundManager, McClass.SOUND_ENGINE);
    }

    // --------------------------------------------------
    // SoundManager invocation helpers
    // --------------------------------------------------

    public static Object invokeSoundManager(
            McMethod method,
            Object soundInstance
    ) throws Exception {

        Object soundManager = getSoundManager();
        if (soundManager == null) {
            throw new IllegalStateException("SoundManager not found");
        }

        return McMappings.invokeMethod(method, soundManager, soundInstance);
    }

    public static boolean invokeSoundManagerBoolean(
            McMethod method,
            Object soundInstance
    ) throws Exception {

        return Boolean.TRUE.equals(
                invokeSoundManager(method, soundInstance)
        );
    }

    public static Object invokeSoundManager(
            String methodName,
            Object soundInstance
    ) throws Exception {

        Object soundManager = getSoundManager();
        if (soundManager == null) {
            throw new IllegalStateException("SoundManager not found");
        }

        Method method = findSoundManagerMethod(
                soundManager.getClass(),
                methodName
        );

        if (method == null) {
            throw new NoSuchMethodException(methodName);
        }

        method.setAccessible(true);
        return method.invoke(soundManager, soundInstance);
    }

    public static boolean invokeSoundManagerBoolean(
            String methodName,
            Object soundInstance
    ) throws Exception {

        return Boolean.TRUE.equals(
                invokeSoundManager(methodName, soundInstance)
        );
    }

    public static Method findSoundManagerMethod(
            Class<?> type,
            String name
    ) {

        Class<?> current = type;

        while (current != null) {

            for (Method method : current.getDeclaredMethods()) {

                if (method.getName().equals(name)
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0]
                                .getSimpleName().equals("SoundInstance")) {
                    return method;
                }
            }

            current = current.getSuperclass();
        }

        return null;
    }

    // --------------------------------------------------
    // Generic field & method lookup
    // --------------------------------------------------

    public static Object findObjectByType(
            Object owner,
            McClass expectedType
    ) throws IllegalAccessException {

        if (owner == null || expectedType == null || !McMappings.hasClass(expectedType)) {
            return null;
        }

        return findObjectByClass(owner, McMappings.resolveClass(expectedType));
    }

    public static Object findObjectByClass(
            Object owner,
            Class<?> expectedClass
    ) throws IllegalAccessException {

        if (owner == null || expectedClass == null) {
            return null;
        }

        Class<?> current = owner.getClass();

        while (current != null) {

            for (Field field : current.getDeclaredFields()) {

                if (expectedClass.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return field.get(owner);
                }
            }

            current = current.getSuperclass();
        }

        return null;
    }

    public static Object findObjectByType(
            Object owner,
            String simpleName
    ) throws IllegalAccessException {

        if (owner == null || simpleName == null) {
            return null;
        }

        Class<?> current = owner.getClass();

        while (current != null) {

            for (Field field : current.getDeclaredFields()) {

                if (field.getType().getSimpleName().equals(simpleName)) {
                    field.setAccessible(true);
                    return field.get(owner);
                }
            }

            current = current.getSuperclass();
        }

        return null;
    }

    public static Field findField(
            Class<?> type,
            String name
    ) {

        Class<?> current = type;

        while (current != null) {

            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }

        return null;
    }

    public static Object getFieldValue(
            Object target,
            String name
    ) throws IllegalAccessException {

        if (target == null) {
            return null;
        }

        Field field = findField(target.getClass(), name);

        if (field == null) {
            return null;
        }

        field.setAccessible(true);
        return field.get(target);
    }

    public static Object invokeNoArgs(
            Object target,
            String methodName
    ) throws Exception {

        if (target == null) {
            throw new IllegalArgumentException("target cannot be null");
        }

        Method method = findNoArgMethod(target.getClass(), methodName);
        if (method == null) {
            throw new NoSuchMethodException(methodName);
        }

        method.setAccessible(true);
        return method.invoke(target);
    }

    public static Method findNoArgMethod(
            Class<?> type,
            String name
    ) {

        Class<?> current = type;

        while (current != null) {

            try {
                return current.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }

        return null;
    }

    public static float readFloat(
            Object target,
            McMethod method,
            float fallback
    ) {

        if (target == null || method == null || !McMappings.hasMethod(method)) {
            return fallback;
        }

        try {
            Object value = McMappings.invokeMethod(method, target);
            if (value instanceof Number) {
                return ((Number) value).floatValue();
            }
        } catch (Exception ignored) {
        }

        return fallback;
    }

    public static boolean readBoolean(
            Object target,
            McMethod method,
            boolean fallback
    ) {

        if (target == null || method == null || !McMappings.hasMethod(method)) {
            return fallback;
        }

        try {
            Object value = McMappings.invokeMethod(method, target);
            if (value instanceof Boolean) {
                return ((Boolean) value).booleanValue();
            }
        } catch (Exception ignored) {
        }

        return fallback;
    }

    public static float readFloat(
            Object target,
            String methodName,
            float fallback
    ) {

        try {

            Object value = invokeNoArgs(target, methodName);
            if (value instanceof Number number) {
                return number.floatValue();
            }

        } catch (Exception ignored) {
        }

        return fallback;
    }

    public static boolean readBoolean(
            Object target,
            String methodName,
            boolean fallback
    ) {

        try {

            Object value = invokeNoArgs(target, methodName);
            if (value instanceof Boolean result) {
                return result;
            }

        } catch (Exception ignored) {
        }

        return fallback;
    }
}