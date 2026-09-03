package link.e4all;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ScreenWidgetHelper {
    private ScreenWidgetHelper() {}

    private static final String[] RENDERABLE_FIELDS = {
            "renderables", "drawables", "field_33814", "field_22765", "f_169369_"
    };

    private static final String[] CHILDREN_FIELDS = {
            "children", "field_33815", "field_22764", "f_96541_"
    };

    private static final String[] NARRATABLE_FIELDS = {
            "narratables", "selectables", "field_33816", "f_169370_"
    };

    private static final String[] ADD_WIDGET_PREFERRED_NAMES = {
            "addRenderableWidget",
            "addDrawableChild",
            "method_37063",
            "m_142416_",
            "addButton",
            "method_25411",
            "m_96587_",
            "m_7787_"
    };

    public static Component getOnlineModeButtonText(boolean offlineMode) {
        Component label = Mirror.translatable("text.e4all_minecraft.onlineMode");
        Component value = Mirror.translatable(offlineMode ? "text.e4all_minecraft.onlineModeFalse" : "text.e4all_minecraft.onlineModeTrue");
        return Mirror.append(
                Mirror.literal(""),
                Mirror.append(label, value)
        );
    }

    public static Object updateOrCreateOnlineModeButton(Screen screen, Object currentButton, String screenName) {
        try {
            int buttonW = 150;
            int buttonH = 20;
            int[] pos = findBestPosition(screen, currentButton, buttonW, buttonH);
            int x = pos[0];
            int y = pos[1];
            E4allClient.LOGGER.debug("[e4all] {} button position: ({}, {})", screenName, x, y);

            if (currentButton != null && isWidgetInScreen(screen, currentButton)) {
                setWidgetPosition(currentButton, x, y);
                ensureInScreenLists(screen, currentButton);
                E4allClient.LOGGER.debug("[e4all] {} existing button repositioned to ({}, {})", screenName, x, y);
                return currentButton;
            }

            boolean currentValue = Config.INSTANCE.offlineMode.value();
            Component buttonText = getOnlineModeButtonText(currentValue);
            E4allClient.LOGGER.debug("[e4all] {} creating button with text: {}", screenName, buttonText);
            Object newButton = createButton(x, y, buttonW, buttonH, buttonText, ScreenWidgetHelper::onToggleOnlineMode);
            if (newButton != null) {
                E4allClient.LOGGER.debug("[e4all] {} button created: {}", screenName, newButton.getClass().getName());
                if (addWidgetReflectively(screen, newButton)) {
                    E4allClient.LOGGER.debug("[e4all] Online Mode toggle button added to {} at ({}, {})", screenName, x, y);
                } else {
                    E4allClient.LOGGER.warn("[e4all] {} addWidgetReflectively returned false! Button NOT added.", screenName);
                }
                return newButton;
            } else {
                E4allClient.LOGGER.warn("[e4all] {} createButton returned null! Button creation FAILED.", screenName);
            }
        } catch (Throwable e) {
            E4allClient.LOGGER.warn("[e4all] Failed to update Online Mode toggle button in " + screenName, e);
        }
        return currentButton;
    }

    private static void onToggleOnlineMode(Object buttonObj) {
        boolean newValue = !Config.INSTANCE.offlineMode.value();
        Config.INSTANCE.offlineMode.setValue(newValue, true);
        Component newText = getOnlineModeButtonText(newValue);
        try {
            for (Method m : buttonObj.getClass().getMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (!Component.class.isAssignableFrom(m.getParameterTypes()[0])) continue;
                if (m.getReturnType() != void.class) continue;
                String n = m.getName();
                if (n.equals("setMessage") || n.equals("method_25355") || n.equals("m_93666_")) {
                    m.invoke(buttonObj, newText);
                    return;
                }
            }
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("[e4all] Could not update button label after toggle", t);
        }
    }

    public static boolean isWidgetInScreen(Screen screen, Object widget) {
        if (widget == null || screen == null) return false;
        List<Object> widgets = getWidgetList(screen);
        return widgets != null && widgets.contains(widget);
    }

    public static void setWidgetPosition(Object widget, int x, int y) {
        if (widget == null) return;
        String[] setPosNames = {"setPosition", "method_46421", "m_253211_"};
        for (String name : setPosNames) {
            try {
                Method m = widget.getClass().getMethod(name, int.class, int.class);
                m.invoke(widget, x, y);
                return;
            } catch (Throwable ignored) {}
        }
        String[] setXNames = {"setX", "method_46419", "m_252865_"};
        for (String name : setXNames) {
            try {
                Method m = widget.getClass().getMethod(name, int.class);
                m.invoke(widget, x);
                break;
            } catch (Throwable ignored) {}
        }
        String[] setYNames = {"setY", "method_46420", "m_253010_"};
        for (String name : setYNames) {
            try {
                Method m = widget.getClass().getMethod(name, int.class);
                m.invoke(widget, y);
                break;
            } catch (Throwable ignored) {}
        }

        String[] xFields = {"x", "field_22758", "f_93618_", "field_22560", "f_93902_"};
        String[] yFields = {"y", "field_22759", "f_93619_", "field_22561", "f_93903_"};
        Class<?> c = widget.getClass();
        while (c != null && c != Object.class) {
            for (String fName : xFields) {
                try {
                    Field f = c.getDeclaredField(fName);
                    f.setAccessible(true);
                    f.setInt(widget, x);
                    break;
                } catch (Throwable ignored) {}
            }
            for (String fName : yFields) {
                try {
                    Field f = c.getDeclaredField(fName);
                    f.setAccessible(true);
                    f.setInt(widget, y);
                    break;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
    }

    public static int[] findBestPosition(Screen screen, Object ignoreWidget, int buttonW, int buttonH) {
        int centerX = screen.width / 2 - buttonW / 2;
        int maxY = screen.height - buttonH - 5;
        int defaultY = Math.min(156, maxY);

        try {
            List<Object> widgets = getWidgetList(screen);
            if (widgets == null || widgets.isEmpty()) {
                return new int[]{centerX, defaultY};
            }

            int bottomThreshold = screen.height - 35;
            for (Object widget : widgets) {
                if (widget == ignoreWidget) continue;
                int wy = getWidgetY(widget);
                if (wy >= screen.height - 48 && wy < screen.height) {
                    if (wy < bottomThreshold) {
                        bottomThreshold = wy;
                    }
                }
            }

            int maxContentBottom = 0;
            for (Object widget : widgets) {
                if (widget == ignoreWidget) continue;
                int wy = getWidgetY(widget);
                if (wy >= 0 && wy < bottomThreshold) {
                    int wh = getWidgetHeight(widget);
                    int bottom = wy + (wh > 0 ? wh : 20);
                    if (bottom > maxContentBottom) {
                        maxContentBottom = bottom;
                    }
                }
            }

            if (maxContentBottom > 0) {
                int candidateY = maxContentBottom + 8;
                if (candidateY + buttonH <= bottomThreshold - 4) {
                    return new int[]{centerX, Math.min(candidateY, maxY)};
                }

                int available = bottomThreshold - maxContentBottom;
                if (available >= buttonH + 2) {
                    int tightGap = Math.max(2, (available - buttonH) / 2);
                    return new int[]{centerX, Math.min(maxContentBottom + tightGap, maxY)};
                }

                // not enough space, place above bottom buttons
                return new int[]{centerX, Math.min(Math.max(bottomThreshold - buttonH - 2, defaultY), maxY)};
            }
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("[e4all] Could not scan widgets for positioning, using fallback", t);
        }

        return new int[]{centerX, defaultY};
    }

    public static List<Object> getWidgetList(Screen screen) {
        List<Object> allWidgets = new ArrayList<>();
        if (screen == null) return allWidgets;

        String[] methodNames = {"children", "method_25396", "m_6702_"};
        for (String name : methodNames) {
            try {
                Method m = Screen.class.getMethod(name);
                if (List.class.isAssignableFrom(m.getReturnType())) {
                    List<?> list = (List<?>) m.invoke(screen);
                    if (list != null) {
                        for (Object o : list) {
                            if (o != null && !allWidgets.contains(o)) {
                                allWidgets.add(o);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        String[] fieldNames = {
                "renderables",
                "children",
                "field_33814",
                "f_169369_",
                "drawables",
                "field_22765",
                "f_96541_"
        };
        Class<?> c = Screen.class;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                for (String name : fieldNames) {
                    if (f.getName().equals(name) && List.class.isAssignableFrom(f.getType())) {
                        try {
                            f.setAccessible(true);
                            List<?> list = (List<?>) f.get(screen);
                            if (list != null) {
                                for (Object o : list) {
                                    if (o != null && !allWidgets.contains(o)) {
                                        allWidgets.add(o);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
            c = c.getSuperclass();
        }

        return allWidgets;
    }

    public static int getWidgetX(Object widget) {
        if (widget == null) return -1;
        String[] methodNames = {"getX", "method_46427", "m_252754_", "getLeft", "x"};
        for (String name : methodNames) {
            try {
                Method m = widget.getClass().getMethod(name);
                if (m.getParameterCount() == 0 && (m.getReturnType() == int.class || m.getReturnType() == Integer.class)) {
                    return (int) m.invoke(widget);
                }
            } catch (Throwable ignored) {}
        }
        String[] fieldNames = {"x", "field_22758", "f_93618_", "field_22560", "f_93902_"};
        Class<?> c = widget.getClass();
        while (c != null && c != Object.class) {
            for (String name : fieldNames) {
                try {
                    Field f = c.getDeclaredField(name);
                    if (f.getType() == int.class || f.getType() == Integer.class) {
                        f.setAccessible(true);
                        return (int) f.get(widget);
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return -1;
    }

    public static int getWidgetY(Object widget) {
        if (widget == null) return -1;
        String[] methodNames = {"getY", "method_46426", "m_252907_", "getTop", "y"};
        for (String name : methodNames) {
            try {
                Method m = widget.getClass().getMethod(name);
                if (m.getParameterCount() == 0 && (m.getReturnType() == int.class || m.getReturnType() == Integer.class)) {
                    return (int) m.invoke(widget);
                }
            } catch (Throwable ignored) {}
        }
        String[] fieldNames = {"y", "field_22759", "f_93619_", "field_22561", "f_93903_"};
        Class<?> c = widget.getClass();
        while (c != null && c != Object.class) {
            for (String name : fieldNames) {
                try {
                    Field f = c.getDeclaredField(name);
                    if (f.getType() == int.class || f.getType() == Integer.class) {
                        f.setAccessible(true);
                        return (int) f.get(widget);
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return -1;
    }

    public static int getWidgetHeight(Object widget) {
        if (widget == null) return 20;
        String[] methodNames = {"getHeight", "method_25368", "m_93694_", "height"};
        for (String name : methodNames) {
            try {
                Method m = widget.getClass().getMethod(name);
                if (m.getParameterCount() == 0 && (m.getReturnType() == int.class || m.getReturnType() == Integer.class)) {
                    return (int) m.invoke(widget);
                }
            } catch (Throwable ignored) {}
        }
        String[] fieldNames = {"height", "field_22760", "f_93620_", "field_22563", "f_93905_"};
        Class<?> c = widget.getClass();
        while (c != null && c != Object.class) {
            for (String name : fieldNames) {
                try {
                    Field f = c.getDeclaredField(name);
                    if (f.getType() == int.class || f.getType() == Integer.class) {
                        f.setAccessible(true);
                        return (int) f.get(widget);
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return 20;
    }

    public static Object createButton(int x, int y, int w, int h, Component text, Consumer<Object> onToggle) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (args != null && args.length == 1 && onToggle != null) {
                onToggle.accept(args[0]);
            }
            if (method.getReturnType() == void.class) return null;
            if (method.getReturnType() == boolean.class) return false;
            return null;
        };

        try {
            Class<?> onPressClass = null;
            for (Method m : Button.class.getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 2
                        && Component.class.isAssignableFrom(m.getParameterTypes()[0])
                        && m.getParameterTypes()[1].isInterface()) {
                    onPressClass = m.getParameterTypes()[1];
                    break;
                }
            }
            if (onPressClass == null) {
                for (Method m : Button.class.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 2
                            && Component.class.isAssignableFrom(m.getParameterTypes()[0])
                            && m.getParameterTypes()[1].isInterface()) {
                        onPressClass = m.getParameterTypes()[1];
                        break;
                    }
                }
            }

            if (onPressClass != null) {
                E4allClient.LOGGER.debug("[e4all] Found OnPress interface from builder method: {}", onPressClass.getName());
                Object onPressProxy = Proxy.newProxyInstance(
                        onPressClass.getClassLoader(),
                        new Class<?>[]{onPressClass},
                        handler
                );
                Object btn = Mirror.createButton(x, y, w, h, text, onPressProxy);
                if (btn != null) return btn;
                E4allClient.LOGGER.warn("[e4all] Mirror.createButton returned null with OnPress={}", onPressClass.getName());
            }
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("[e4all] Failed to create button via builder method strategy", t);
        }

        try {
            for (Class<?> nested : Button.class.getDeclaredClasses()) {
                if (!nested.isInterface()) continue;
                try {
                    Object onPressProxy = Proxy.newProxyInstance(
                            nested.getClassLoader(),
                            new Class<?>[]{nested},
                            handler
                    );
                    Object btn = Mirror.createButton(x, y, w, h, text, onPressProxy);
                    if (btn != null) {
                        E4allClient.LOGGER.debug("[e4all] Button created with nested interface: {}", nested.getName());
                        return btn;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("[e4all] Failed to create button via nested interface scan", t);
        }

        E4allClient.LOGGER.warn("[e4all] All button creation strategies failed");
        return null;
    }

    public static void ensureInScreenLists(Screen screen, Object widget) {
        if (widget == null || screen == null) return;
        ensureInList(screen, RENDERABLE_FIELDS, widget);
        ensureInList(screen, CHILDREN_FIELDS, widget);
        ensureInList(screen, NARRATABLE_FIELDS, widget);
    }

    private static void ensureInList(Screen screen, String[] fieldNames, Object widget) {
        if (widget == null || screen == null) return;
        Class<?> c = Screen.class;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                for (String name : fieldNames) {
                    if (f.getName().equals(name) && List.class.isAssignableFrom(f.getType())) {
                        try {
                            f.setAccessible(true);
                            @SuppressWarnings("unchecked")
                            List<Object> list = (List<Object>) f.get(screen);
                            if (list != null && !list.contains(widget)) {
                                list.add(widget);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
            c = c.getSuperclass();
        }
    }

    public static boolean addWidgetReflectively(Screen screen, Object widget) {
        if (widget == null || screen == null) return false;
        Class<?> widgetClass = widget.getClass();

        boolean invoked = false;
        Method best = findScreenMethodByName(widgetClass, ADD_WIDGET_PREFERRED_NAMES);
        if (best != null) {
            try {
                best.setAccessible(true);
                best.invoke(screen, widget);
                invoked = true;
                E4allClient.LOGGER.debug("[e4all] Added widget via method: {}", best.getName());
            } catch (Throwable t) {
                E4allClient.LOGGER.warn("[e4all] Failed to invoke {} reflectively", best.getName(), t);
            }
        }

        ensureInScreenLists(screen, widget);
        return invoked || isWidgetInScreen(screen, widget);
    }

    private static Method findScreenMethodByName(Class<?> widgetClass, String[] priorityNames) {
        for (String name : priorityNames) {
            Class<?> c = Screen.class;
            while (c != null && c != Object.class) {
                for (Method m : c.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterCount() != 1) continue;
                    if (!m.getName().equals(name)) continue;
                    Class<?> p = m.getParameterTypes()[0];
                    if (p.isAssignableFrom(widgetClass)) {
                        return m;
                    }
                }
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
