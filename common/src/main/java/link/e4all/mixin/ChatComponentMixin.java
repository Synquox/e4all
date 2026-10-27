package link.e4all.mixin;

import link.e4all.Config;
import link.e4all.HostedWorldChatBox;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public class ChatComponentMixin {
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$suppressPublishStarted(Component component, CallbackInfo ci) {
        if (Config.INSTANCE.hostEnabled.value() && HostedWorldChatBox.isPublishStarted(component)) {
            ci.cancel();
        }
    }

    @Inject(method = "addClientSystemMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$suppressClientSystemMessage(Component component, CallbackInfo ci) {
        if (Config.INSTANCE.hostEnabled.value() && HostedWorldChatBox.isPublishStarted(component)) {
            ci.cancel();
        }
    }

    @Inject(method = "addServerSystemMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$suppressServerSystemMessage(Component component, CallbackInfo ci) {
        if (Config.INSTANCE.hostEnabled.value() && HostedWorldChatBox.isPublishStarted(component)) {
            ci.cancel();
        }
    }
}
