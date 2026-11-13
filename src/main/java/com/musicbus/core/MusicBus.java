package com.musicbus.core;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

public final class MusicBus {

    private static boolean enabled = false;

    private static final Map<Object, Boolean> INJECTED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private MusicBus() {}

    public static void onSoundOptionsScreen(Object screen) {
        if (screen == null || INJECTED.containsKey(screen)) {
            return;
        }

        MusicBusLog.log(
            "onSoundOptionsScreen invoked. Screen loader: "
            + screen.getClass().getClassLoader()
        );

        try {
            Class<?> optionClass = Class.forName(
                    "net.minecraft.client.OptionInstance",
                    true,
                    screen.getClass().getClassLoader()
            );

            // Находим OptionsList в SoundOptionsScreen
            Object list = findOptionsList(screen);

            if (list == null) {
                log("ERROR: OptionsList not found.");
                return;
            }

            Consumer<Boolean> callback = value -> {
                enabled = value;
                MusicBusLog.log("TOGGLE CLICKED! Enabled: " + enabled);
            };

            // Создаём штатный Minecraft OptionInstance<Boolean>
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
                    "options.musicbus.enabled",
                    enabled,
                    callback
            );

            // Создаём массив OptionInstance[]
            Object optionsArray = Array.newInstance(optionClass, 1);
            Array.set(optionsArray, 0, option);

            // Вызываем OptionsList.addSmall(OptionInstance[])
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
            MusicBusLog.error("Failed to inject toggle.", error);
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

    public static boolean isEnabled() {
        return enabled;
    }

    private static void log(String message) {
        System.out.println("[MusicBus] " + message);
    }
}
