package link.e4all.mixin;

import link.e4all.OpSessionClient;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin {
    @Inject(method = "handleCustomPayload", at = @At("HEAD"), require = 0)
    private void e4all$handleOpSessionPayload(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        Object id = link.e4all.PacketHelper.extractPayloadId(packet.payload());
        if (id != null) OpSessionClient.handlePayload(id);
    }
}
