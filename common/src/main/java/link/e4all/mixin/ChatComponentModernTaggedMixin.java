package link.e4all.mixin;

import link.e4all.Config;
import link.e4all.HostedWorldChatBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent")
public class ChatComponentModernTaggedMixin {
    @Inject(
            method = "addPlayerMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void e4all$suppressPublishStartedModern(Component component, MessageSignature signature,
                                                    @Coerce Object tag, CallbackInfo ci) {
        if (Config.INSTANCE.hostEnabled.value() && HostedWorldChatBox.isPublishStarted(component)) {
            ci.cancel();
        }
    }
}
