package com.musicbus.core;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * UI hook entry point for Minecraft's {@code SoundOptionsScreen}.
 *
 * Injects a native {@code OptionInstance<Boolean>} toggle ("MusicBus")
 * into the screen's {@code OptionsList} via reflection so players can
 * switch music routing at runtime without compile-time Minecraft dependencies.
 *
 * Augmented screen instances are tracked in a {@code WeakHashMap} to
 * keep injection idempotent without retaining closed GUI screens.
 */
public final class OptionsScreenHook {
    private static final Map<Object, Boolean> INJECTED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private OptionsScreenHook() {}

    public static void onSoundOptionsScreen(Object screen) {
        if (screen == null || INJECTED.containsKey(screen)) {
            return;
        }

        log(
            "onSoundOptionsScreen invoked. Screen loader: "
            + screen.getClass().getClassLoader()
        );

        try {
            Class<?> optionClass = Class.forName(
                    "net.minecraft.client.OptionInstance",
                    true,
                    screen.getClass().getClassLoader()
            );

            // Locate OptionsList in SoundOptionsScreen
            Object list = findOptionsList(screen);

            if (list == null) {
                log("ERROR: OptionsList not found.");
                return;
            }

            // Create a native Minecraft OptionInstance<Boolean>
            Method createBoolean = null;

            for (Method method : optionClass.getDeclaredMethods()) {
                if (method.getName().equals("createBoolean")
                        && method.getParameterCount() == 3
                        && method.getParameterTypes()[0] == String.class
                        && method.getParameterTypes()[1] == boolean.class) {
                    createBoolean = method;
                    break;
                }
            }

            if (createBoolean == null) {
                throw new NoSuchMethodException(
                        "OptionInstance.createBoolean"
                );
            }

            createBoolean.setAccessible(true);

            Object option = createBoolean.invoke(
                    null,
                    "MusicBus",
                    MusicBusManager.isEnabled(),
                    (java.util.function.Consumer<Boolean>)
                            MusicBusManager::setEnabled
            );

            // Create OptionInstance[] array
            Object optionsArray = Array.newInstance(optionClass, 1);
            Array.set(optionsArray, 0, option);

            // Invoke OptionsList.addSmall(OptionInstance[])
            Method addSmall = null;

            for (Method method : list.getClass().getMethods()) {
                if (method.getName().equals("addSmall")
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isArray()) {
                    addSmall = method;
                    break;
                }
            }

            if (addSmall == null) {
                throw new NoSuchMethodException(
                        "OptionsList.addSmall"
                );
            }

            addSmall.invoke(list, optionsArray);

            INJECTED.put(screen, Boolean.TRUE);

            log("MusicBus toggle injected successfully.");

        } catch (Throwable error) {
            log("Failed to inject toggle: " + error);
            error.printStackTrace(System.err);
        }
    }

    private static Object findOptionsList(Object screen)
            throws IllegalAccessException {

        Class<?> current = screen.getClass();

        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType().getSimpleName()
                        .equals("OptionsList")) {

                    field.setAccessible(true);
                    return field.get(screen);
                }
            }

            current = current.getSuperclass();
        }

        return null;
    }

    private static void log(String message) {
        System.out.println("[MusicBus] " + message);
    }
}
