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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

/**
 * Mixin that injects the "Online Mode" toggle button into the ShareToLanScreen.
 *
 * Cross-version compatibility notes:
 *   - The mod is compiled against MC 1.20.2 (Mojang names), but is loaded onto
 *     Forge 1.20.1 (SRG runtime), Forge 1.20.2+ (Mojang runtime), Fabric
 *     (intermediary runtime), NeoForge, etc.
 *   - The @Inject `method` value uses a regex that matches every known runtime
 *     name for Screen.init across mappings so the injection actually applies
 *     on Forge 1.20.1's SRG runtime.
 *   - All MC API calls inside the injected method (Button construction,
 *     Screen.addRenderableWidget, Button.OnPress SAM dispatch) are performed
 *     reflectively by signature so they resolve correctly regardless of
 *     whether the runtime uses Mojang, SRG, or intermediary names.
 *
 * Without this, the injected lambda/method references baked in at compile
 * time would reference Mojang-named methods that do not exist on Forge 1.20.1,
 * causing silent NoSuchMethodErrors and a missing button.
 */
@Mixin(ShareToLanScreen.class)
public abstract class ShareToLanScreenMixin extends Screen {

    protected ShareToLanScreenMixin(Component component) {
        super(component);
    }

    @Inject(method = "/^(init|method_25426|m_7856_)$/", at = @At("TAIL"), require = 0)
    private void e4all$addOfflineModeButton(CallbackInfo ci) {
        E4allClient.LOGGER.info("e4all: ShareToLanScreen.init() reached, adding Online Mode toggle button");
        try {
            boolean currentValue = Config.INSTANCE.offlineMode.value();
            Component buttonText = e4all$getButtonText(currentValue);
            // Place above the bottom row (where ShareToLanScreen puts its Start/Cancel
            // buttons). y = height - 56 collides with the existing "Allow Cheats" /
            // gamemode row on some 1.20.x versions, hiding our button behind them.
            // Shifting up to y = height - 80 puts us in a less-contested spot.
            int x = this.width / 2 - 155;
            int y = this.height - 80;
            int w = 150;
            int h = 20;
            Object button = e4all$createButton(x, y, w, h, buttonText);
            if (button == null) {
                E4allClient.LOGGER.warn("e4all: Could not construct Online Mode toggle button on this MC version");
                return;
            }
            if (!e4all$addWidgetReflectively(button)) {
                E4allClient.LOGGER.warn("e4all: Could not add Online Mode toggle button to the LAN screen");
                return;
            }
            E4allClient.LOGGER.info("e4all: Online Mode toggle button added at ({}, {})", x, y);
        } catch (Throwable e) {
            E4allClient.LOGGER.warn("e4all: Failed to add Online Mode toggle button to LAN screen", e);
        }
    }

    @Unique
    private Component e4all$getButtonText(boolean offlineMode) {
        Component label = Mirror.translatable("text.e4mc_minecraft.onlineMode");
        Component value = Mirror.translatable(offlineMode ? "text.e4mc_minecraft.onlineModeFalse" : "text.e4mc_minecraft.onlineModeTrue");
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
        // Button.setMessage(Component) — also SRG-remapped, so call reflectively
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
            E4allClient.LOGGER.debug("e4all: Could not update button label after toggle", t);
        }
    }

    /**
     * Build a Button across MC versions. In 1.19.4+ this is Button.builder(...).
     * In 1.18 - 1.19.3 it was a public constructor. We look both up reflectively
     * so we don't bake in Mojang-named method references that break on SRG runtimes.
     */
    @Unique
    private Object e4all$createButton(int x, int y, int w, int h, Component text) {
        // Build a Button.OnPress impl via Proxy — InvocationHandler is name-agnostic
        // so it works whether the SAM is `onPress`, `method_25306`, or `m_93750_`.
        InvocationHandler handler = (proxy, method, args) -> {
            if (args != null && args.length == 1) {
                e4all$onToggle(args[0]);
            }
            return null;
        };
        Object onPress = Proxy.newProxyInstance(
            Button.OnPress.class.getClassLoader(),
            new Class<?>[]{Button.OnPress.class},
            handler
        );

        // First try: 1.19.4+ Button.builder(Component, OnPress).bounds(x,y,w,h).build()
        try {
            Method builderMethod = e4all$findStaticBuilder();
            if (builderMethod != null) {
                builderMethod.setAccessible(true);
                Object builder = builderMethod.invoke(null, text, onPress);
                Method boundsMethod = e4all$findBoundsMethod(builder.getClass());
                if (boundsMethod != null) {
                    boundsMethod.setAccessible(true);
                    Object next = boundsMethod.invoke(builder, x, y, w, h);
                    if (next != null) {
                        builder = next;
                    }
                }
                Method buildMethod = e4all$findBuildMethod(builder.getClass());
                if (buildMethod != null) {
                    buildMethod.setAccessible(true);
                    Object built = buildMethod.invoke(builder);
                    if (built != null) {
                        return built;
                    }
                }
            }
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("e4all: Button.builder() path failed, trying legacy constructor", t);
        }

        // Fallback: 1.18 - 1.19.3 legacy public constructor
        try {
            Constructor<?> ctor = Button.class.getConstructor(
                int.class, int.class, int.class, int.class, Component.class, Button.OnPress.class
            );
            return ctor.newInstance(x, y, w, h, text, onPress);
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("e4all: Legacy Button constructor not available", t);
        }
        return null;
    }

    @Unique
    private static Method e4all$findStaticBuilder() {
        // A static method on Button returning a Button.Builder-like type with
        // (Component, Button.OnPress) signature. Match by signature, not name.
        for (Method m : Button.class.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 2) continue;
            if (!params[0].equals(Component.class)) continue;
            if (!params[1].equals(Button.OnPress.class)) continue;
            return m;
        }
        return null;
    }

    @Unique
    private static Method e4all$findBoundsMethod(Class<?> builderClass) {
        // Builder.bounds(int, int, int, int) returning the same Builder type.
        for (Method m : builderClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 4) continue;
            if (!params[0].equals(int.class)) continue;
            if (!params[1].equals(int.class)) continue;
            if (!params[2].equals(int.class)) continue;
            if (!params[3].equals(int.class)) continue;
            if (!m.getReturnType().equals(builderClass)) continue;
            return m;
        }
        return null;
    }

    @Unique
    private static Method e4all$findBuildMethod(Class<?> builderClass) {
        // Builder.build() returning a Button.
        for (Method m : builderClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != 0) continue;
            if (!Button.class.isAssignableFrom(m.getReturnType())) continue;
            return m;
        }
        return null;
    }

    /**
     * Add a widget to this Screen via reflection so we work whether the
     * runtime mapping is `addRenderableWidget` (Mojang), `method_37063`
     * (intermediary), or `m_142416_` (SRG 1.20.1).
     */
    @Unique
    private boolean e4all$addWidgetReflectively(Object widget) {
        Class<?> widgetClass = widget.getClass();

        // First pass: known names
        String[] candidates = {
            "addRenderableWidget", // Mojang / yarn 1.17+
            "method_37063",        // intermediary
            "m_142416_",           // SRG 1.20.1
            "addDrawableChild"     // older yarn
        };
        Method best = e4all$findScreenSingleArgMethod(widgetClass, candidates);

        // Second pass: signature-based, in case mappings rename the method
        if (best == null) {
            best = e4all$findScreenSingleArgMethod(widgetClass, null);
        }

        if (best == null) {
            return false;
        }
        try {
            best.setAccessible(true);
            best.invoke(this, widget);
            return true;
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("e4all: Failed to invoke {} reflectively", best.getName(), t);
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
                // Filter out unrelated methods (e.g. removeWidget, isWidgetActive, …).
                // The MC widget-add methods all accept a subtype of GuiEventListener
                // (or AbstractWidget). We accept any single-arg method that takes a
                // type our widget is assignable to AND whose name is on the known
                // list, OR whose param type's simple name contains "Widget" /
                // "GuiEventListener" when name filter is null.
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
}
