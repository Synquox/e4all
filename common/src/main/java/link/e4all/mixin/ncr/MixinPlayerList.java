package link.e4all.mixin.ncr;

import link.e4all.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.players.PlayerList;

@Mixin(PlayerList.class)
public class MixinPlayerList {

    @Inject(method = "verifyChatTrusted",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$onVerifyChatTrusted(PlayerChatMessage playerChatMessage, CallbackInfoReturnable<Boolean> info) {
        if (Config.INSTANCE.offlineMode.value()) {
            info.setReturnValue(true);
        }
    }
}
