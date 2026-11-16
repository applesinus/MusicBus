package com.musicbus.core;

import com.musicbus.mappings.McClass;
import com.musicbus.mappings.McFeature;
import com.musicbus.mappings.McMappings;
import com.musicbus.mappings.McMethod;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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

        ClassLoader loader = screen.getClass().getClassLoader();
        McMappings.ensureInitialized(loader);

        if (!McMappings.hasFeature(McFeature.HAS_OPTION_INSTANCE)
                || !McMappings.hasClass(McClass.OPTION_INSTANCE)
                || !McMappings.hasClass(McClass.OPTIONS_LIST)
                || !McMappings.hasMethod(McMethod.OPTION_INSTANCE_CREATE_BOOLEAN)
                || !McMappings.hasMethod(McMethod.OPTIONS_LIST_ADD_SMALL)) {
            return;
        }

        log("onSoundOptionsScreen invoked. Screen loader: " + loader);

        try {
            Class<?> optionClass = McMappings.resolveClass(McClass.OPTION_INSTANCE);

            // Locate OptionsList in SoundOptionsScreen
            Object list = MinecraftReflection.findObjectByType(
                    screen,
                    McClass.OPTIONS_LIST
            );

            if (list == null) {
                log("ERROR: OptionsList not found.");
                return;
            }

            // Create a native Minecraft OptionInstance<Boolean>
            Method createBoolean = McMappings.resolveMethod(
                    McMethod.OPTION_INSTANCE_CREATE_BOOLEAN
            );
            Object listener = createBooleanListener(
                    createBoolean.getParameterTypes()[2],
                    loader
            );

            Object option = createBoolean.invoke(
                    null,
                    "MusicBus",
                    Boolean.valueOf(MusicBusManager.isEnabled()),
                    listener
            );

            // Invoke OptionsList.addSmall(...)
            Method addSmall = McMappings.resolveMethod(
                    McMethod.OPTIONS_LIST_ADD_SMALL
            );
            if (McMappings.hasFeature(McFeature.OPTIONS_LIST_ADD_SMALL_ARRAY)) {
                Object optionsArray = Array.newInstance(optionClass, 1);
                Array.set(optionsArray, 0, option);
                addSmall.invoke(list, (Object) optionsArray);
            } else if (addSmall.getParameterCount() == 2) {
                addSmall.invoke(list, option, null);
            } else {
                addSmall.invoke(list, option);
            }

            INJECTED.put(screen, Boolean.TRUE);

            log("MusicBus toggle injected successfully.");

        } catch (Throwable error) {
            log("Failed to inject toggle: " + error);
            error.printStackTrace(System.err);
        }
    }

    private static Object createBooleanListener(
            Class<?> listenerType,
            ClassLoader loader
    ) {
        return Proxy.newProxyInstance(
                loader,
                new Class<?>[] { listenerType },
                new InvocationHandler() {
                    @Override
                    public Object invoke(
                            Object proxy,
                            Method method,
                            Object[] args
                    ) {
                        if (method.getDeclaringClass() == Object.class) {
                            String name = method.getName();
                            if ("equals".equals(name)) {
                                return Boolean.valueOf(
                                        args != null && args.length == 1 && proxy == args[0]
                                );
                            }
                            if ("hashCode".equals(name)) {
                                return Integer.valueOf(System.identityHashCode(proxy));
                            }
                            if ("toString".equals(name)) {
                                return "MusicBusOptionListener";
                            }
                        }
                        if (args != null && args.length == 1 && args[0] instanceof Boolean) {
                            MusicBusManager.setEnabled(((Boolean) args[0]).booleanValue());
                        }
                        return null;
                    }
                }
        );
    }

    private static void log(String message) {
        System.out.println("[MusicBus] " + message);
    }
}
