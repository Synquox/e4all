package link.e4all.mixin;

import link.e4all.OpSessionClient;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin {
    @Inject(method = "handleCustomPayload", at = @At("HEAD"))
    private void e4all$handleOpSessionPayload(CustomPacketPayload payload, CallbackInfo ci) {
        OpSessionClient.handlePayload(payload.id());
    }
}
