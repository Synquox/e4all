package link.e4all.mixin;

import link.e4all.voice.VoiceControl;
import link.e4all.voice.VoiceControlPayload;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// server-side dispatch for stashed payload bytes from decode time
@Mixin(ServerCommonPacketListenerImpl.class)
public class ServerCommonPacketListenerImplMixin {

    @Inject(method = "handleCustomPayload",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$handleVoicePayload(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        try {
            byte[] data = VoiceControlPayload.extractData(packet);
            if (data != null) {
                VoiceControl.handleServerPayload(this, data);
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }
}

