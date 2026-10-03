package link.e4all;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

public final class ScreenWidgetHelper {
    private ScreenWidgetHelper() {}

    private static final String[] ADD_WIDGET_NAMES = {
            "addRenderableWidget",
            "addDrawableChild",
            "method_37063",
            "m_142416_",
            "addButton",
            "method_25411",
            "m_96587_"
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
            int centerX = screen.width / 2 - buttonW / 2;
            int y = Math.min(156, screen.height - 28 - buttonH - 8);
            y = Math.max(y, 100);

            boolean currentValue = Config.INSTANCE.offlineMode.value();
            Component buttonText = getOnlineModeButtonText(currentValue);
            Object button = createButton(centerX, y, buttonW, buttonH, buttonText, ScreenWidgetHelper::onToggleOnlineMode);
            if (button != null) {
                addWidget(screen, button);
                return button;
            }
        } catch (Throwable e) {
            E4allClient.LOGGER.warn("Failed to add Online Mode button to " + screenName, e);
        }
        return currentButton;
    }

    private static void onToggleOnlineMode(Object buttonObj) {
        boolean newValue = !Config.INSTANCE.offlineMode.value();
        Config.INSTANCE.offlineMode.setValue(newValue, true);
        Component newText = getOnlineModeButtonText(newValue);
        try {
            for (Method m : buttonObj.getClass().getMethods()) {
                if (m.getParameterCount() == 1 && Component.class.isAssignableFrom(m.getParameterTypes()[0])
                        && m.getReturnType() == void.class) {
                    m.invoke(buttonObj, newText);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    public static Object createButton(int x, int y, int w, int h, Component text, Consumer<Object> onToggle) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (args != null && args.length == 1 && onToggle != null) {
                onToggle.accept(args[0]);
            }
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
                for (Class<?> nested : Button.class.getDeclaredClasses()) {
                    if (nested.isInterface()) {
                        onPressClass = nested;
                        break;
                    }
                }
            }
            if (onPressClass != null) {
                Object onPressProxy = Proxy.newProxyInstance(
                        onPressClass.getClassLoader(),
                        new Class<?>[]{onPressClass},
                        handler
                );
                return Mirror.createButton(x, y, w, h, text, onPressProxy);
            }
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("Failed to create button", t);
        }
        return null;
    }

    private static boolean addWidget(Screen screen, Object widget) {
        if (screen == null || widget == null) return false;
        Class<?> widgetClass = widget.getClass();
        for (String name : ADD_WIDGET_NAMES) {
            Class<?> c = Screen.class;
            while (c != null && c != Object.class) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1
                            && m.getName().equals(name) && m.getParameterTypes()[0].isAssignableFrom(widgetClass)) {
                        try {
                            m.setAccessible(true);
                            m.invoke(screen, widget);
                            return true;
                        } catch (Throwable ignored) {}
                    }
                }
                c = c.getSuperclass();
            }
        }
        return false;
    }
}
