package link.e4all.mixin.ncr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.protocol.game.ServerboundChatPacket;

@Mixin(ServerboundChatPacket.class)
public class MixinServerboundChatPacket {

    @Inject(method = "signature", at = @At("RETURN"), cancellable = true, require = 0)
    private void e4all$onGetSignature(CallbackInfoReturnable<MessageSignature> info) {
        info.setReturnValue(null);
    }
}
