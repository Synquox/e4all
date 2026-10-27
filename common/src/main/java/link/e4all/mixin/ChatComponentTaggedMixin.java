package link.e4all.mixin;

import link.e4all.Config;
import link.e4all.HostedWorldChatBox;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public class ChatComponentTaggedMixin {
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$suppressPublishStartedTagged(Component component, MessageSignature headerSignature, GuiMessageTag tag, CallbackInfo ci) {
        if (Config.INSTANCE.hostEnabled.value() && HostedWorldChatBox.isPublishStarted(component)) {
            ci.cancel();
        }
    }
}
