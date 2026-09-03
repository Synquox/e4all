package link.e4all.mixin;

import link.e4all.ScreenWidgetHelper;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = {
        "net.minecraft.client.gui.screens.MultiplayerOptionsScreen",
        "net.minecraft.client.gui.screens.options.MultiplayerOptionsScreen"
}, remap = false)
public abstract class MultiplayerOptionsScreenMixin extends Screen {
    @Unique
    private Object e4all$button = null;

    protected MultiplayerOptionsScreenMixin(Component component) {
        super(component);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void e4all$onInit(CallbackInfo ci) {
        this.e4all$button = ScreenWidgetHelper.updateOrCreateOnlineModeButton(this, this.e4all$button, "MultiplayerOptionsScreen");
    }
}
