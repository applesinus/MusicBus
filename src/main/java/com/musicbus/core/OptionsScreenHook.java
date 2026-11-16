package com.musicbus.core;

import com.musicbus.mappings.McClass;
import com.musicbus.mappings.McFeature;
import com.musicbus.mappings.McMappings;
import com.musicbus.mappings.McMethod;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.List;
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

            // Locate OptionsList in SoundOptionsScreen (>= 1.19.3)
            Object list = MinecraftReflection.findObjectByType(
                    screen,
                    McClass.OPTIONS_LIST
            );

            if (list != null) {
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
            } else if (!injectDirectWidget(screen, optionClass, option)) {
                log("ERROR: OptionsList not found and direct widget injection failed.");
                return;
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

    private static boolean injectDirectWidget(
            Object screen,
            Class<?> optionClass,
            Object option
    ) throws Exception {
        Method createButtonMethod = findCreateButton4ArgsMethod(optionClass);
        if (createButtonMethod == null) {
            return false;
        }

        Class<?> optionsClass = createButtonMethod.getParameterTypes()[0];
        Class<?> widgetClass = createButtonMethod.getReturnType();

        Object options = findFieldValueByType(screen, optionsClass);
        if (options == null) {
            options = findFieldValueByType(
                    MinecraftReflection.getMinecraftInstance(),
                    optionsClass
            );
        }
        if (options == null) {
            return false;
        }

        // Probe Screen.addRenderableWidget first (also tells us Screen class)
        Method addWidgetMethod = findAddRenderableWidgetMethod(
                screen.getClass(),
                widgetClass
        );
        if (addWidgetMethod == null) {
            return false;
        }

        Class<?> screenClass = addWidgetMethod.getDeclaringClass();
        int[] screenSize = readFirstTwoPublicInts(screen, screenClass);
        int screenWidth = screenSize != null ? screenSize[0] : 427;
        int screenHeight = screenSize != null ? screenSize[1] : 240;

        int[] placement = computeDirectButtonPlacement(
                screen,
                screenClass,
                widgetClass,
                screenWidth,
                screenHeight
        );

        Object widget = createButtonMethod.invoke(
                option,
                options,
                Integer.valueOf(placement[0]),
                Integer.valueOf(placement[1]),
                Integer.valueOf(placement[2])
        );
        if (widget == null) {
            return false;
        }

        addWidgetMethod.invoke(screen, widget);
        return true;
    }

    private static int[] computeDirectButtonPlacement(
            Object screen,
            Class<?> screenClass,
            Class<?> widgetClass,
            int screenWidth,
            int screenHeight
    ) {
        int defaultX = screenWidth / 2 - 155;
        int defaultY = screenHeight / 6 + 132;
        int width = 150;

        List<?> widgets = findWidgetsList(screen, screenClass, widgetClass);
        if (widgets == null || widgets.isEmpty()) {
            return new int[] { defaultX, defaultY, width };
        }

        Field[] xyFields = findFirstTwoPublicIntFields(widgetClass);
        if (xyFields == null) {
            return new int[] { defaultX, defaultY, width };
        }

        Object doneWidget = null;
        int doneY = Integer.MIN_VALUE;
        for (int i = 0; i < widgets.size(); i++) {
            Object w = widgets.get(i);
            if (!widgetClass.isInstance(w)) {
                continue;
            }
            try {
                int y = xyFields[1].getInt(w);
                if (y > doneY) {
                    doneY = y;
                    doneWidget = w;
                }
            } catch (Exception ignored) {
            }
        }

        int maxOptionY = Integer.MIN_VALUE;
        for (int i = 0; i < widgets.size(); i++) {
            Object w = widgets.get(i);
            if (!widgetClass.isInstance(w) || w == doneWidget) {
                continue;
            }
            try {
                int y = xyFields[1].getInt(w);
                if (y < doneY && y > maxOptionY) {
                    maxOptionY = y;
                }
            } catch (Exception ignored) {
            }
        }

        if (maxOptionY == Integer.MIN_VALUE) {
            return new int[] { defaultX, defaultY, width };
        }

        boolean hasLeftAtMax = false;
        boolean hasRightAtMax = false;
        for (int i = 0; i < widgets.size(); i++) {
            Object w = widgets.get(i);
            if (!widgetClass.isInstance(w) || w == doneWidget) {
                continue;
            }
            try {
                int x = xyFields[0].getInt(w);
                int y = xyFields[1].getInt(w);
                if (y == maxOptionY) {
                    if (x < screenWidth / 2) {
                        hasLeftAtMax = true;
                    } else {
                        hasRightAtMax = true;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        int targetX;
        int targetY;
        if (hasLeftAtMax && !hasRightAtMax) {
            targetX = screenWidth / 2 + 5;
            targetY = maxOptionY;
        } else {
            targetX = screenWidth / 2 - 155;
            targetY = maxOptionY + 24;
        }

        if (doneWidget != null && targetY + 24 > doneY) {
            try {
                xyFields[1].setInt(doneWidget, targetY + 28);
            } catch (Exception ignored) {
            }
        }

        return new int[] { targetX, targetY, width };
    }

    private static Method findCreateButton4ArgsMethod(Class<?> optionClass) {
        Method[] methods = optionClass.getMethods();
        for (int i = 0; i < methods.length; i++) {
            Method m = methods[i];
            if (m.getParameterCount() == 4
                    && m.getParameterTypes()[1] == int.class
                    && m.getParameterTypes()[2] == int.class
                    && m.getParameterTypes()[3] == int.class
                    && m.getReturnType() != void.class) {
                try {
                    m.setAccessible(true);
                } catch (Exception ignored) {
                }
                return m;
            }
        }
        return null;
    }

    private static Method findAddRenderableWidgetMethod(
            Class<?> startClass,
            Class<?> widgetClass
    ) {
        Class<?> current = startClass;
        while (current != null && current != Object.class) {
            Method[] methods = current.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++) {
                Method m = methods[i];
                if (m.getParameterCount() != 1 || m.getReturnType() == void.class) {
                    continue;
                }
                Type[] genericParams = m.getGenericParameterTypes();
                if (!(genericParams[0] instanceof TypeVariable<?>)) {
                    continue;
                }
                Type[] bounds = ((TypeVariable<?>) genericParams[0]).getBounds();
                if (bounds.length != 3) {
                    continue;
                }
                boolean allMatch = true;
                for (int b = 0; b < bounds.length; b++) {
                    if (!(bounds[b] instanceof Class<?>)
                            || !((Class<?>) bounds[b]).isAssignableFrom(widgetClass)) {
                        allMatch = false;
                        break;
                    }
                }
                if (allMatch) {
                    try {
                        m.setAccessible(true);
                    } catch (Exception ignored) {
                    }
                    return m;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Object findFieldValueByType(
            Object owner,
            Class<?> expectedType
    ) {
        if (owner == null || expectedType == null) {
            return null;
        }
        Class<?> current = owner.getClass();
        while (current != null && current != Object.class) {
            Field[] fields = current.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                Field f = fields[i];
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (expectedType.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(owner);
                        if (val != null) {
                            return val;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static List<?> findWidgetsList(
            Object screen,
            Class<?> screenClass,
            Class<?> widgetClass
    ) {
        Field[] fields = screenClass.getDeclaredFields();
        for (int i = 0; i < fields.length; i++) {
            Field f = fields[i];
            if (Modifier.isStatic(f.getModifiers())
                    || !List.class.isAssignableFrom(f.getType())) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object val = f.get(screen);
                if (val instanceof List<?>) {
                    List<?> list = (List<?>) val;
                    for (int j = 0; j < list.size(); j++) {
                        if (widgetClass.isInstance(list.get(j))) {
                            return list;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Field[] findFirstTwoPublicIntFields(Class<?> clazz) {
        Field[] fields = clazz.getDeclaredFields();
        List<Field> ints = new ArrayList<Field>(2);
        for (int i = 0; i < fields.length; i++) {
            Field f = fields[i];
            if (Modifier.isPublic(f.getModifiers())
                    && !Modifier.isStatic(f.getModifiers())
                    && f.getType() == int.class) {
                f.setAccessible(true);
                ints.add(f);
                if (ints.size() == 2) {
                    return new Field[] { ints.get(0), ints.get(1) };
                }
            }
        }
        return null;
    }

    private static int[] readFirstTwoPublicInts(Object target, Class<?> clazz) {
        Field[] pair = findFirstTwoPublicIntFields(clazz);
        if (pair == null) {
            return null;
        }
        try {
            return new int[] { pair[0].getInt(target), pair[1].getInt(target) };
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void log(String message) {
        System.out.println("[MusicBus] " + message);
    }
}
