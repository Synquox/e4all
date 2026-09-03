package link.e4all.mixin;

import link.e4all.Config;
import link.e4all.E4allClient;
import link.e4all.ScreenWidgetHelper;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = {
        "net.minecraft.client.gui.screens.WorldOptionsScreen",
        "net.minecraft.client.gui.screens.options.WorldOptionsScreen"
}, remap = false)
public abstract class WorldOptionsScreenMixin extends Screen {
    protected WorldOptionsScreenMixin(Component component) {
        super(component);
    }

    @Inject(method = "multiplayerOptions", at = @At("TAIL"), require = 0)
    private void e4all$addMultiplayerOnlineModeOption(LinearLayout linearLayout, IntegratedServer server, CallbackInfo ci) {
        try {
            boolean currentValue = Config.INSTANCE.offlineMode.value();
            Component buttonText = ScreenWidgetHelper.getOnlineModeButtonText(currentValue);

            Button.OnPress onPress = button -> {
                boolean newVal = !Config.INSTANCE.offlineMode.value();
                Config.INSTANCE.offlineMode.setValue(newVal, true);
                button.setMessage(ScreenWidgetHelper.getOnlineModeButtonText(newVal));
            };

            Button button = Button.builder(buttonText, onPress)
                    .width(308)
                    .build();

            linearLayout.addChild(button);
            E4allClient.LOGGER.warn("[e4all] Added Online Mode toggle button to WorldOptionsScreen LinearLayout");
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("[e4all] Failed to add Online Mode button to WorldOptionsScreen", t);
        }
    }
}
