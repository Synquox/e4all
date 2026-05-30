package link.e4all.mixin.ncr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket;

@Mixin(ServerboundChatSessionUpdatePacket.class)
public class MixinServerboundChatSessionUpdatePacket {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$onHandle(ServerGamePacketListener listener, CallbackInfo info) {
        info.cancel();
    }
}
