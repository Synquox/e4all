package link.e4all.mixin;
import link.e4all.Config;
import link.e4all.E4allClient;
import link.e4all.Mirror;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
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
@Mixin(ShareToLanScreen.class)
public abstract class ShareToLanScreenMixin extends Screen {
    protected ShareToLanScreenMixin(Component component) {
        super(component);
    }
    @Inject(method = "/^(init|method_25426|m_7856_|initLayout|m_280264_|method_48413)$/", at = @At("TAIL"), require = 0)
    private void e4all$addOfflineModeButton(CallbackInfo ci) {
        E4allClient.LOGGER.warn("[e4all] ShareToLanScreen.init() TAIL reached — injecting Online Mode toggle button");
        try {
            boolean currentValue = Config.INSTANCE.offlineMode.value();
            Component buttonText = e4all$getButtonText(currentValue);
            int buttonW = 150;
            int buttonH = 20;
            int x = 10;
            int y = 10;
            E4allClient.LOGGER.warn("[e4all] Computed button position: x={}, y={} (screen {}x{})", x, y, this.width, this.height);
            Object button = e4all$createButton(x, y, buttonW, buttonH, buttonText);
            if (button == null) {
                E4allClient.LOGGER.warn("[e4all] Could not construct Online Mode toggle button on this MC version");
                return;
            }
            E4allClient.LOGGER.warn("[e4all] Button instance created: {}", button.getClass().getName());
            if (!e4all$addWidgetReflectively(button)) {
                E4allClient.LOGGER.warn("[e4all] Could not add Online Mode toggle button to the LAN screen — reflective addRenderableWidget failed");
                e4all$logAvailableScreenMethods();
                return;
            }
            E4allClient.LOGGER.warn("[e4all] Online Mode toggle button added successfully at ({}, {})", x, y);
        } catch (Throwable e) {
            E4allClient.LOGGER.warn("[e4all] Failed to add Online Mode toggle button to LAN screen", e);
        }
    }
    @Unique
    private int e4all$findBestY(int buttonHeight) {
        try {
            List<?> widgets = e4all$getWidgetList();
            if (widgets != null && !widgets.isEmpty()) {
                int minY = Integer.MAX_VALUE;
                for (Object widget : widgets) {
                    int wy = e4all$getWidgetY(widget);
                    if (wy >= 0 && wy < minY) {
                        minY = wy;
                    }
                }
                if (minY != Integer.MAX_VALUE && minY > buttonHeight + 4) {
                    int candidate = minY - buttonHeight - 4;
                    if (candidate >= 10) { 
                        E4allClient.LOGGER.debug("[e4all] Positioned button above topmost widget (minY={})", minY);
                        return candidate;
                    }
                }
            }
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("[e4all] Could not scan widgets for positioning, using fallback", t);
        }
        return this.height - 100;
    }
    @Unique
    private List<?> e4all$getWidgetList() {
        String[] fieldNames = {
            "renderables",    
            "children",       
            "field_33814",    
            "f_169369_",      
            "drawables",      
        };
        Class<?> c = Screen.class;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                for (String name : fieldNames) {
                    if (f.getName().equals(name) && List.class.isAssignableFrom(f.getType())) {
                        try {
                            f.setAccessible(true);
                            return (List<?>) f.get(this);
                        } catch (Throwable ignored) {}
                    }
                }
                if (List.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        List<?> list = (List<?>) f.get(this);
                        if (list != null && !list.isEmpty()) {
                            Object first = list.get(0);
                            if (first != null && e4all$getWidgetY(first) >= 0) {
                                return list;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }
    @Unique
    private static int e4all$getWidgetY(Object widget) {
        String[] methodNames = {"getY", "getTop", "method_25331", "m_252907_", "y"};
        for (String name : methodNames) {
            try {
                Method m = widget.getClass().getMethod(name);
                if (m.getReturnType() == int.class || m.getReturnType() == Integer.class) {
                    return (int) m.invoke(widget);
                }
            } catch (Throwable ignored) {}
        }
        String[] fieldNames = {"y", "field_22561", "f_93903_"};
        for (String name : fieldNames) {
            try {
                Field f = widget.getClass().getField(name);
                if (f.getType() == int.class) {
                    return (int) f.get(widget);
                }
            } catch (Throwable ignored) {}
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
            return null;
        };
        try {
            Class<?> onPressClass = null;
            for (Class<?> c : Button.class.getDeclaredClasses()) {
                if (c.getName().endsWith("OnPress")) {
                    onPressClass = c;
                    break;
                }
            }
            if (onPressClass != null) {
                Object onPress = Proxy.newProxyInstance(
                    onPressClass.getClassLoader(),
                    new Class<?>[]{onPressClass},
                    handler
                );
                return Mirror.createButton(x, y, w, h, text, onPress);
            }
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("[e4all] Failed to create proxy for Button.OnPress", t);
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