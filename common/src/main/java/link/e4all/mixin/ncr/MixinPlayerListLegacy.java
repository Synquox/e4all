package link.e4all.mixin.ncr;

import link.e4all.Config;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerList.class)
public class MixinPlayerListLegacy {

    @Inject(method = "/^(m_241091_|method_44793_)$/",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void e4all$onVerifyChatTrustedLegacy(PlayerChatMessage message, @Coerce Object chatSender,
                                                 CallbackInfoReturnable<Boolean> info) {
        if (Config.INSTANCE.offlineMode.value()) {
            info.setReturnValue(true);
        }
    }
}
