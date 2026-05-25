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

/**
 * Mixin to inject an "Offline Mode" toggle button into the Share to LAN screen.
 *
 * Uses direct method calls (Button.builder, addRenderableWidget) instead of
 * reflection so that Architectury Loom's transformer can properly remap them
 * to SRG names for Forge at build time. String-based reflection cannot be
 * remapped and silently fails on Forge's SRG runtime.
 *
 * When offline mode is first enabled and the warning hasn't been shown yet,
 * a one-time warning message is displayed in chat when the LAN server opens.
 */
@Mixin(ShareToLanScreen.class)
public abstract class ShareToLanScreenMixin extends Screen {

    protected ShareToLanScreenMixin(Component component) {
        super(component);
    }

    @Unique
    private Button e4all$offlineModeButton;

    @Inject(method = "init", at = @At("TAIL"))
    private void e4all$addOfflineModeButton(CallbackInfo ci) {
        try {
            boolean currentValue = Config.INSTANCE.offlineMode.value();
            Component buttonText = e4all$getButtonText(currentValue);

            Button button;
            try {
                // MC 1.19.4+ — direct call so Architectury Transformer can remap for Forge
                button = Button.builder(buttonText, this::e4all$onToggle)
                    .bounds(this.width / 2 - 155, this.height - 56, 150, 20)
                    .build();
            } catch (NoSuchMethodError e) {
                // MC 1.18–1.19.3 — Button.builder doesn't exist, use legacy constructor
                button = e4all$createButtonLegacy(
                    this.width / 2 - 155, this.height - 56, 150, 20,
                    buttonText
                );
            }

            this.e4all$offlineModeButton = button;

            // Direct call — Architectury Transformer remaps this to the correct SRG name
            this.addRenderableWidget(button);
        } catch (Throwable e) {
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
     * Legacy button constructor for MC 1.18–1.19.3 where Button.builder() doesn't exist.
     * Uses reflection since the constructor was removed in newer versions and can't be
     * referenced directly when compiling against 1.20.2.
     */
    @Unique
    private Button e4all$createButtonLegacy(int x, int y, int width, int height, Component text) {
        try {
            var constructor = Button.class.getConstructor(
                int.class, int.class, int.class, int.class, Component.class, Button.OnPress.class
            );
            return constructor.newInstance(x, y, width, height, text, (Button.OnPress) this::e4all$onToggle);
        } catch (Exception e) {
            throw new RuntimeException("e4all: Could not create button for any known MC version", e);
        }
    }
}
