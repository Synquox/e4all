package link.e4all.mixin;

import link.e4all.Config;
import link.e4all.E4allClient;
import link.e4all.Mirror;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;

@Mixin(targets = {
        "net.minecraft.client.gui.screens.ShareToLanScreen",
        "net.minecraft.class_527"
}, remap = false)
public abstract class ShareToLanScreenMixin extends Screen {
    @Unique
    private Object e4all$button = null;

    protected ShareToLanScreenMixin(Component component) {
        super(component);
    }

    @Inject(method = {
            "init", "method_25426", "m_7856_",
            "initLayout", "method_48413", "m_280264_",
            "repositionElements"
    }, at = @At("TAIL"), require = 0, remap = false)
    private void e4all$onInit(CallbackInfo ci) {
        E4allClient.LOGGER.info("[e4all] >>> e4all$onInit FIRED on {}", this.getClass().getName());
        e4all$updateOrCreateButton();
    }

    @Inject(method = {
            "repositionElements", "method_48413", "m_280264_"
    }, at = @At("TAIL"), require = 0, remap = false)
    private void e4all$onReposition(CallbackInfo ci) {
        E4allClient.LOGGER.info("[e4all] >>> e4all$onReposition FIRED on {}", this.getClass().getName());
        e4all$updateOrCreateButton();
    }

    @Unique
    private void e4all$updateOrCreateButton() {
        try {
            E4allClient.LOGGER.info("[e4all] e4all$updateOrCreateButton called, screen size={}x{}", this.width, this.height);
            int buttonW = 150;
            int buttonH = 20;
            int[] pos = e4all$findBestPosition(buttonW, buttonH);
            int x = pos[0];
            int y = pos[1];
            E4allClient.LOGGER.info("[e4all] Button position: ({}, {})", x, y);

            if (this.e4all$button != null && e4all$isWidgetInScreen(this.e4all$button)) {
                e4all$setWidgetPosition(this.e4all$button, x, y);
                e4all$ensureInScreenLists(this.e4all$button);
                E4allClient.LOGGER.info("[e4all] Existing button repositioned to ({}, {})", x, y);
                return;
            }

            boolean currentValue = Config.INSTANCE.offlineMode.value();
            Component buttonText = e4all$getButtonText(currentValue);
            E4allClient.LOGGER.info("[e4all] Creating button with text: {}", buttonText);
            this.e4all$button = e4all$createButton(x, y, buttonW, buttonH, buttonText);
            if (this.e4all$button != null) {
                E4allClient.LOGGER.info("[e4all] Button created: {}", this.e4all$button.getClass().getName());
                if (e4all$addWidgetReflectively(this.e4all$button)) {
                    E4allClient.LOGGER.info("[e4all] Online Mode toggle button added at ({}, {})", x, y);
                } else {
                    E4allClient.LOGGER.warn("[e4all] addWidgetReflectively returned false! Button NOT added.");
                }
            } else {
                E4allClient.LOGGER.warn("[e4all] e4all$createButton returned null! Button creation FAILED.");
            }
        } catch (Throwable e) {
            E4allClient.LOGGER.warn("[e4all] Failed to update Online Mode toggle button", e);
        }
    }

    @Unique
    private boolean e4all$isWidgetInScreen(Object widget) {
        if (widget == null) return false;
        List<Object> widgets = e4all$getWidgetList();
        return widgets != null && widgets.contains(widget);
    }

    @Unique
    private static void e4all$setWidgetPosition(Object widget, int x, int y) {
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

    @Unique
    private int[] e4all$findBestPosition(int buttonW, int buttonH) {
        int centerX = this.width / 2 - buttonW / 2;
        int maxY = this.height - buttonH - 5;
        int defaultY = Math.min(156, maxY);

        try {
            List<Object> widgets = e4all$getWidgetList();
            if (widgets == null || widgets.isEmpty()) {
                return new int[]{centerX, defaultY};
            }

            int bottomThreshold = this.height - 35;
            for (Object widget : widgets) {
                if (widget == this.e4all$button) continue;
                int wy = e4all$getWidgetY(widget);
                if (wy >= this.height - 48 && wy < this.height) {
                    if (wy < bottomThreshold) {
                        bottomThreshold = wy;
                    }
                }
            }

            int maxContentBottom = 0;
            for (Object widget : widgets) {
                if (widget == this.e4all$button) continue;
                int wy = e4all$getWidgetY(widget);
                if (wy >= 0 && wy < bottomThreshold) {
                    int wh = e4all$getWidgetHeight(widget);
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

    @Unique
    private List<Object> e4all$getWidgetList() {
        List<Object> allWidgets = new java.util.ArrayList<>();
        String[] methodNames = {"children", "method_25396", "m_6702_"};
        for (String name : methodNames) {
            try {
                Method m = Screen.class.getMethod(name);
                if (List.class.isAssignableFrom(m.getReturnType())) {
                    List<?> list = (List<?>) m.invoke(this);
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
                            List<?> list = (List<?>) f.get(this);
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

    @Unique
    private static int e4all$getWidgetX(Object widget) {
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

    @Unique
    private static int e4all$getWidgetY(Object widget) {
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

    @Unique
    private static int e4all$getWidgetHeight(Object widget) {
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

    @Unique
    private Component e4all$getButtonText(boolean offlineMode) {
        Component label = Mirror.translatable("text.e4all_minecraft.onlineMode");
        Component value = Mirror.translatable(offlineMode ? "text.e4all_minecraft.onlineModeFalse" : "text.e4all_minecraft.onlineModeTrue");
        return Mirror.append(
                Mirror.literal(""),
                Mirror.append(label, value)
        );
    }

    @Unique
    private void e4all$onToggle(Object buttonObj) {
        boolean newValue = !Config.INSTANCE.offlineMode.value();
        Config.INSTANCE.offlineMode.setValue(newValue, true);
        Component newText = e4all$getButtonText(newValue);
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

    @Unique
    private Object e4all$createButton(int x, int y, int w, int h, Component text) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (args != null && args.length == 1) {
                e4all$onToggle(args[0]);
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

    @Unique
    private static final String[] E4ALL$RENDERABLE_FIELDS = {
            "renderables", "drawables", "field_33814", "field_22765", "f_169369_"
    };

    @Unique
    private static final String[] E4ALL$CHILDREN_FIELDS = {
            "children", "field_33815", "field_22764", "f_96541_"
    };

    @Unique
    private static final String[] E4ALL$NARRATABLE_FIELDS = {
            "narratables", "selectables", "field_33816", "f_169370_"
    };

    @Unique
    private void e4all$ensureInScreenLists(Object widget) {
        if (widget == null) return;
        e4all$ensureInList(E4ALL$RENDERABLE_FIELDS, widget);
        e4all$ensureInList(E4ALL$CHILDREN_FIELDS, widget);
        e4all$ensureInList(E4ALL$NARRATABLE_FIELDS, widget);
    }

    @Unique
    private void e4all$ensureInList(String[] fieldNames, Object widget) {
        if (widget == null) return;
        Class<?> c = Screen.class;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                for (String name : fieldNames) {
                    if (f.getName().equals(name) && List.class.isAssignableFrom(f.getType())) {
                        try {
                            f.setAccessible(true);
                            @SuppressWarnings("unchecked")
                            List<Object> list = (List<Object>) f.get(this);
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

    @Unique
    private boolean e4all$addWidgetReflectively(Object widget) {
        if (widget == null) return false;
        Class<?> widgetClass = widget.getClass();

        String[] preferredNames = {
                "addRenderableWidget",
                "addDrawableChild",
                "method_37063",
                "m_142416_",
                "addButton",
                "method_25411",
                "m_96587_",
                "m_7787_"
        };

        boolean invoked = false;
        Method best = e4all$findScreenMethodByName(widgetClass, preferredNames);
        if (best != null) {
            try {
                best.setAccessible(true);
                best.invoke(this, widget);
                invoked = true;
                E4allClient.LOGGER.info("[e4all] Added widget via method: {}", best.getName());
            } catch (Throwable t) {
                E4allClient.LOGGER.warn("[e4all] Failed to invoke {} reflectively", best.getName(), t);
            }
        }

        e4all$ensureInScreenLists(widget);

        return invoked || e4all$isWidgetInScreen(widget);
    }

    @Unique
    private static Method e4all$findScreenMethodByName(Class<?> widgetClass, String[] priorityNames) {
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