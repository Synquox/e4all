package link.e4all.mixin;

import com.mojang.authlib.GameProfile;
import link.e4all.OpSessionManager;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {
    @Shadow public abstract GameProfile getOwner();

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), require = 0)
    private void e4all$handleOpSessionPayload(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        Object id = link.e4all.PacketHelper.extractPayloadId(packet.payload());
        if (id != null) OpSessionManager.handleClientPayload(getOwner(), id);
    }
}
