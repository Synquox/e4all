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

import java.lang.reflect.Method;

/**
 * Mixin to inject an "Offline Mode" toggle button into the Share to LAN screen.
 * Uses reflection to call addRenderableWidget (name varies across MC versions)
 * to add a toggle button that controls the offline mode config.
 *
 * When offline mode is first enabled and the warning hasn't been shown yet,
 * a one-time warning message is displayed in chat when the LAN server opens.
 */
@Mixin(targets = {
    "net.minecraft.client.gui.screens.ShareToLanScreen",
    "net.minecraft.client.gui.screen.OpenToLanScreen",
    "net.minecraft.class_436"
})
public abstract class ShareToLanScreenMixin extends Screen {

    protected ShareToLanScreenMixin(Component component) {
        super(component);
    }

    @Unique
    private Button e4all$offlineModeButton;

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void e4all$addOfflineModeButton(CallbackInfo ci) {
        String className = this.getClass().getSimpleName();
        if (!className.equals("ShareToLanScreen") && !className.equals("OpenToLanScreen") && !className.equals("class_436")) {
            return;
        }

        try {
            boolean currentValue = Config.INSTANCE.offlineMode.value();
            Component buttonText = e4all$getButtonText(currentValue);

            // Create a simple Button — compatible across all MC versions >=1.18
            // The button is placed below the existing buttons
            // Button.builder was added in 1.19.3; for older versions, use constructor
            Button button = e4all$createButton(
                this.width / 2 - 155, this.height - 56, 150, 20,
                buttonText
            );

            this.e4all$offlineModeButton = button;

            // Try to add the button using the version-appropriate method
            e4all$addWidget(button);
        } catch (Exception e) {
            E4allClient.LOGGER.warn("e4all: Failed to add offline mode button to LAN screen", e);
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
    private void e4all$onToggle(Button button) {
        boolean newValue = !Config.INSTANCE.offlineMode.value();
        Config.INSTANCE.offlineMode.setValue(newValue, true);
        button.setMessage(e4all$getButtonText(newValue));
    }

    /**
     * Creates a Button using reflection to handle version differences.
     * MC 1.19.4+: Button.builder(text, onPress).bounds(x, y, w, h).build()
     * MC 1.18-1.19.3: new Button(x, y, w, h, text, onPress)
     */
    @Unique
    private Button e4all$createButton(int x, int y, int width, int height, Component text) {
        // Try modern Button.builder first (1.19.4+)
        try {
            Method builderMethod = null;
            for (String name : new String[]{"builder", "method_46430", "m_252437_"}) {
                try {
                    builderMethod = Button.class.getMethod(name, Component.class, Button.OnPress.class);
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
            if (builderMethod != null) {
                Object builder = builderMethod.invoke(null, text, (Button.OnPress) this::e4all$onToggle);
                Method boundsMethod = null;
                for (String name : new String[]{"bounds", "dimensions", "method_46432", "m_253166_"}) {
                    try {
                        boundsMethod = builder.getClass().getMethod(name, int.class, int.class, int.class, int.class);
                        break;
                    } catch (NoSuchMethodException ignored) {}
                }
                
                if (boundsMethod != null) {
                    builder = boundsMethod.invoke(builder, x, y, width, height);
                } else {
                    // MC 1.20+ separated bounds into pos and size
                    Method posMethod = null;
                    for (String name : new String[]{"pos", "method_46434", "m_252582_", "position"}) {
                        try { posMethod = builder.getClass().getMethod(name, int.class, int.class); break; } catch (NoSuchMethodException ignored) {}
                    }
                    if (posMethod != null) {
                        builder = posMethod.invoke(builder, x, y);
                    }
                    
                    Method sizeMethod = null;
                    for (String name : new String[]{"size", "method_46435", "m_253249_", "dimensions"}) {
                        try { sizeMethod = builder.getClass().getMethod(name, int.class, int.class); break; } catch (NoSuchMethodException ignored) {}
                    }
                    if (sizeMethod != null) {
                        builder = sizeMethod.invoke(builder, width, height);
                    } else {
                        // Fallback to width only if size is missing
                        Method widthMethod = null;
                        for (String name : new String[]{"width", "method_46436", "m_252758_"}) {
                            try { widthMethod = builder.getClass().getMethod(name, int.class); break; } catch (NoSuchMethodException ignored) {}
                        }
                        if (widthMethod != null) builder = widthMethod.invoke(builder, width);
                    }
                }
                Method buildMethod = null;
                for (String name : new String[]{"build", "method_46431", "m_253018_"}) {
                    try {
                        buildMethod = builder.getClass().getMethod(name);
                        break;
                    } catch (NoSuchMethodException ignored) {}
                }
                return (Button) buildMethod.invoke(builder);
            }
        } catch (Exception ignored) {}

        // Try legacy constructor (1.18-1.19.3)
        try {
            var constructor = Button.class.getConstructor(
                int.class, int.class, int.class, int.class, Component.class, Button.OnPress.class
            );
            return constructor.newInstance(x, y, width, height, text, (Button.OnPress) this::e4all$onToggle);
        } catch (Exception ignored) {}

        // Last resort: try with tooltip parameter (some versions)
        try {
            var constructor = Button.class.getConstructor(
                int.class, int.class, int.class, int.class, Component.class, Button.OnPress.class, Button.CreateNarration.class
            );
            return constructor.newInstance(x, y, width, height, text, (Button.OnPress) this::e4all$onToggle, (Button.CreateNarration) supplier -> (net.minecraft.network.chat.MutableComponent) text);
        } catch (Exception e) {
            throw new RuntimeException("e4all: Could not create button for any known MC version", e);
        }
    }

    /**
     * Adds a widget using the version-appropriate method name.
     * addRenderableWidget (1.18+), method_25411 (intermediary), m_142416_ (SRG)
     */
    @Unique
    private void e4all$addWidget(Button button) {
        String[] methodNames = {
            "addRenderableWidget",
            "addDrawableChild",
            "addButton",
            "m_142416_", // SRG
            "method_37063", // Intermediary addDrawableChild/addRenderableWidget
            "method_25411", // Intermediary addButton
            "m_142414_", // Forge/NeoForge
            "func_230480_a_" // Old SRG
        };
        for (String name : methodNames) {
            try {
                // Try with GuiEventListener (common for addRenderableWidget)
                try {
                    Method method = Screen.class.getDeclaredMethod(name, net.minecraft.client.gui.components.events.GuiEventListener.class);
                    method.setAccessible(true);
                    method.invoke(this, button);
                    return;
                } catch (NoSuchMethodException ignored) {}

                // Try with Renderable (some versions)
                try {
                    Method method = Screen.class.getDeclaredMethod(name, net.minecraft.client.gui.components.Renderable.class);
                    method.setAccessible(true);
                    method.invoke(this, button);
                    return;
                } catch (NoSuchMethodException ignored) {}

                // Try with Widget (newer versions)
                for (Method method : Screen.class.getDeclaredMethods()) {
                    if (method.getName().equals(name) && method.getParameterCount() == 1) {
                        method.setAccessible(true);
                        method.invoke(this, button);
                        return;
                    }
                }
            } catch (Exception ignored) {}
        }
        E4allClient.LOGGER.warn("e4all: Could not find addRenderableWidget method in any known form");
    }
}


