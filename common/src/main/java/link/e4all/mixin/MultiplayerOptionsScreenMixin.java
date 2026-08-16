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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;
@Mixin(targets = "net.minecraft.client.gui.screens.MultiplayerOptionsScreen")
public abstract class MultiplayerOptionsScreenMixin extends Screen {
    protected MultiplayerOptionsScreenMixin(Component component) {
        super(component);
    }
    @Inject(method = "/^(init|method_25426|m_7856_|initLayout|m_280264_|method_48413)$/", at = @At("TAIL"), require = 0)
    private void e4all$addOfflineModeButton(CallbackInfo ci) {
        E4allClient.LOGGER.warn("[e4all] MultiplayerOptionsScreen.init() TAIL reached: injecting Online Mode toggle button");
        try {
            boolean currentValue = Config.INSTANCE.offlineMode.value();
            Component buttonText = e4all$getButtonText(currentValue);
            int buttonW = 150;
            int buttonH = 20;
            int[] pos = e4all$findBestPosition(buttonW, buttonH);
            int x = pos[0];
            int y = pos[1];
            E4allClient.LOGGER.warn("[e4all] Computed button position: x={}, y={} (screen {}x{})", x, y, this.width, this.height);
            Object button = e4all$createButton(x, y, buttonW, buttonH, buttonText);
            if (button == null) {
                E4allClient.LOGGER.warn("[e4all] Could not construct Online Mode toggle button on this MC version");
                return;
            }
            E4allClient.LOGGER.warn("[e4all] Button instance created: {}", button.getClass().getName());
            if (!e4all$addWidgetReflectively(button)) {
                E4allClient.LOGGER.warn("[e4all] Could not add Online Mode toggle button to the LAN screen: reflective addRenderableWidget failed");
                e4all$logAvailableScreenMethods();
                return;
            }
            E4allClient.LOGGER.warn("[e4all] Online Mode toggle button added successfully at ({}, {})", x, y);
        } catch (Throwable e) {
            E4allClient.LOGGER.warn("[e4all] Failed to add Online Mode toggle button to LAN screen", e);
        }
    }
    @Unique
    private int[] e4all$findBestPosition(int buttonW, int buttonH) {
        int leftX = this.width / 2 - 155;
        int centerX = this.width / 2 - buttonW / 2;
        int rightX = this.width / 2 + 5;
        int defaultY = Math.min(128, this.height - 56);

        try {
            List<Object> widgets = e4all$getWidgetList();
            if (widgets == null || widgets.isEmpty()) {
                return new int[]{leftX, defaultY};
            }

            int bottomThreshold = this.height - 40;
            for (Object widget : widgets) {
                int wy = e4all$getWidgetY(widget);
                if (wy >= this.height - 45 && wy < this.height) {
                    if (wy < bottomThreshold) {
                        bottomThreshold = wy;
                    }
                }
            }

            int maxContentBottom = 0;
            Object lowestWidget = null;
            for (Object widget : widgets) {
                int wy = e4all$getWidgetY(widget);
                if (wy >= 0 && wy < bottomThreshold) {
                    int wh = e4all$getWidgetHeight(widget);
                    int bottom = wy + (wh > 0 ? wh : 20);
                    if (bottom > maxContentBottom) {
                        maxContentBottom = bottom;
                        lowestWidget = widget;
                    }
                }
            }

            if (maxContentBottom > 0 && lowestWidget != null) {
                int lowestY = e4all$getWidgetY(lowestWidget);
                int lowestH = e4all$getWidgetHeight(lowestWidget);
                int lowestX = e4all$getWidgetX(lowestWidget);
                int lowestW = e4all$getWidgetWidth(lowestWidget);

                boolean leftOccupied = false;
                boolean rightOccupied = false;
                int widgetsInLowestRow = 0;
                for (Object widget : widgets) {
                    int wy = e4all$getWidgetY(widget);
                    if (Math.abs(wy - lowestY) <= 6) {
                        widgetsInLowestRow++;
                        int wx = e4all$getWidgetX(widget);
                        int ww = e4all$getWidgetWidth(widget);
                        if (wx < this.width / 2) {
                            leftOccupied = true;
                        }
                        if (wx + ww > this.width / 2) {
                            rightOccupied = true;
                        }
                    }
                }

                if (leftOccupied && !rightOccupied && lowestH == buttonH && lowestW <= 160) {
                    return new int[]{rightX, lowestY};
                }
                if (!leftOccupied && rightOccupied && lowestH == buttonH && lowestW <= 160) {
                    return new int[]{leftX, lowestY};
                }

                boolean isCenteredWidget = (widgetsInLowestRow == 1) && 
                    (Math.abs((lowestX + lowestW / 2) - (this.width / 2)) <= 30 || lowestW > 160 || lowestWidget.getClass().getSimpleName().contains("EditBox"));

                int gap = 6;
                int candidateY = maxContentBottom + gap;

                if (isCenteredWidget) {
                    if (candidateY + buttonH <= bottomThreshold - 2) {
                        return new int[]{centerX, candidateY};
                    }
                } else {
                    if (candidateY + buttonH <= bottomThreshold - 2) {
                        return new int[]{leftX, candidateY};
                    }
                }
            }
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("[e4all] Could not scan widgets for positioning, using fallback", t);
        }

        return new int[]{leftX, defaultY};
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
            "field_22764",
            "f_169369_",      
            "f_96541_",
            "drawables",
            "buttons",
            "field_22761",
            "f_96540_"
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
        String[] methodNames = {"getX", "method_46427", "method_25364", "m_252754_", "getLeft", "x"};
        for (String name : methodNames) {
            try {
                Method m = widget.getClass().getMethod(name);
                if (m.getReturnType() == int.class || m.getReturnType() == Integer.class) {
                    return (int) m.invoke(widget);
                }
            } catch (Throwable ignored) {}
        }
        String[] fieldNames = {"x", "field_22758", "f_93902_", "field_22560", "f_93618_"};
        for (String name : fieldNames) {
            Class<?> c = widget.getClass();
            while (c != null && c != Object.class) {
                try {
                    Field f = c.getDeclaredField(name);
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        return (int) f.get(widget);
                    }
                } catch (Throwable ignored) {}
                c = c.getSuperclass();
            }
        }
        return -1;
    }
    @Unique
    private static int e4all$getWidgetY(Object widget) {
        String[] methodNames = {"getY", "method_46426", "method_25331", "m_252907_", "getTop", "y"};
        for (String name : methodNames) {
            try {
                Method m = widget.getClass().getMethod(name);
                if (m.getReturnType() == int.class || m.getReturnType() == Integer.class) {
                    return (int) m.invoke(widget);
                }
            } catch (Throwable ignored) {}
        }
        String[] fieldNames = {"y", "field_22561", "field_22760", "f_93903_", "f_93619_"};
        for (String name : fieldNames) {
            Class<?> c = widget.getClass();
            while (c != null && c != Object.class) {
                try {
                    Field f = c.getDeclaredField(name);
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        return (int) f.get(widget);
                    }
                } catch (Throwable ignored) {}
                c = c.getSuperclass();
            }
        }
        return -1;
    }
    @Unique
    private static int e4all$getWidgetWidth(Object widget) {
        String[] methodNames = {"getWidth", "method_25368", "method_25365", "m_93699_", "width"};
        for (String name : methodNames) {
            try {
                Method m = widget.getClass().getMethod(name);
                if (m.getReturnType() == int.class || m.getReturnType() == Integer.class) {
                    return (int) m.invoke(widget);
                }
            } catch (Throwable ignored) {}
        }
        String[] fieldNames = {"width", "field_22759", "f_93904_", "f_93620_"};
        for (String name : fieldNames) {
            Class<?> c = widget.getClass();
            while (c != null && c != Object.class) {
                try {
                    Field f = c.getDeclaredField(name);
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        return (int) f.get(widget);
                    }
                } catch (Throwable ignored) {}
                c = c.getSuperclass();
            }
        }
        return 150;
    }
    @Unique
    private static int e4all$getWidgetHeight(Object widget) {
        String[] methodNames = {"getHeight", "method_25364", "method_25368", "m_93694_", "getBottom", "height"};
        for (String name : methodNames) {
            try {
                Method m = widget.getClass().getMethod(name);
                if (m.getReturnType() == int.class || m.getReturnType() == Integer.class) {
                    return (int) m.invoke(widget);
                }
            } catch (Throwable ignored) {}
        }
        String[] fieldNames = {"height", "field_22760", "f_93905_", "f_93621_"};
        for (String name : fieldNames) {
            Class<?> c = widget.getClass();
            while (c != null && c != Object.class) {
                try {
                    Field f = c.getDeclaredField(name);
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        return (int) f.get(widget);
                    }
                } catch (Throwable ignored) {}
                c = c.getSuperclass();
            }
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
            if (method.getReturnType() == int.class) return 0;
            return null;
        };
        try {
            Class<?> callbackClass = null;
            for (Class<?> c : Button.class.getDeclaredClasses()) {
                if (c.getName().endsWith("OnPress")) {
                    callbackClass = c;
                    break;
                }
            }
            if (callbackClass == null) {
                for (Method m : Button.class.getMethods()) {
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                            && m.getParameterCount() == 2
                            && m.getParameterTypes()[0].equals(Component.class)
                            && m.getParameterTypes()[1].isInterface()) {
                        callbackClass = m.getParameterTypes()[1];
                        break;
                    }
                }
            }
            if (callbackClass == null) {
                for (Method m : Button.class.getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                            && m.getParameterCount() == 2
                            && m.getParameterTypes()[0].equals(Component.class)
                            && m.getParameterTypes()[1].isInterface()) {
                        callbackClass = m.getParameterTypes()[1];
                        break;
                    }
                }
            }
            if (callbackClass != null) {
                Object onPress = Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass},
                    handler
                );
                return Mirror.createButton(x, y, w, h, text, onPress);
            }
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("[e4all] Failed to create proxy for Button callback", t);
        }
        return null;
    }
    @Unique
    private boolean e4all$addWidgetReflectively(Object widget) {
        Class<?> widgetClass = widget.getClass();
        String[] candidates = {
            "addRenderableWidget", 
            "method_37063",        
            "m_142416_",           
            "addDrawableChild"     
        };
        Method best = e4all$findScreenSingleArgMethod(widgetClass, candidates);
        if (best == null) {
            best = e4all$findScreenSingleArgMethod(widgetClass, null);
        }
        if (best == null) {
            E4allClient.LOGGER.warn("[e4all] No addRenderableWidget-like method found on Screen hierarchy for widget type {}", widgetClass.getName());
            return false;
        }
        try {
            best.setAccessible(true);
            E4allClient.LOGGER.debug("[e4all] Invoking {} on Screen with widget {}", best.getName(), widgetClass.getSimpleName());
            best.invoke(this, widget);
            return true;
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("[e4all] Failed to invoke {} reflectively", best.getName(), t);
            return false;
        }
    }
    @Unique
    private static Method e4all$findScreenSingleArgMethod(Class<?> widgetClass, String[] nameFilter) {
        Class<?> c = Screen.class;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 1) continue;
                Class<?> p = m.getParameterTypes()[0];
                if (!p.isAssignableFrom(widgetClass)) continue;
                if (nameFilter != null) {
                    boolean match = false;
                    for (String n : nameFilter) {
                        if (m.getName().equals(n)) {
                            match = true;
                            break;
                        }
                    }
                    if (!match) continue;
                }
                if (nameFilter == null) {
                    String pn = p.getSimpleName();
                    if (!pn.contains("Widget") && !pn.contains("GuiEventListener") && !pn.contains("Renderable")) {
                        continue;
                    }
                }
                return m;
            }
            c = c.getSuperclass();
        }
        return null;
    }
    @Unique
    private void e4all$logAvailableScreenMethods() {
        try {
            Class<?> c = this.getClass();
            while (c != null && c != Object.class) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && !Modifier.isStatic(m.getModifiers())) {
                        E4allClient.LOGGER.debug("[e4all-diag] {}.{}({})", c.getSimpleName(), m.getName(), m.getParameterTypes()[0].getSimpleName());
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
    }
}
