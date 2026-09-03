package link.e4all.mixin;

import link.e4all.voice.VoiceControl;
import link.e4all.voice.VoiceControlPayload;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public class ClientCommonPacketListenerImplMixin {

    @Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$handleVoicePayload(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        try {
            byte[] data = VoiceControlPayload.extractData(packet);
            if (data != null) {
                VoiceControl.handleClientPayload(data);
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }
}
