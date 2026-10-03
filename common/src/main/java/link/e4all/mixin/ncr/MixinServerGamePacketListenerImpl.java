package link.e4all.mixin.ncr;

import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerGamePacketListenerImpl.class)
public class MixinServerGamePacketListenerImpl {

    @Inject(method = "/^(m_242598_)$/", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void e4all$allowOfflineChat(PlayerChatMessage message, CallbackInfoReturnable<Boolean> info) {
        if (link.e4all.Config.INSTANCE.offlineMode.value()) {
            info.setReturnValue(true);
        }
    }
}
