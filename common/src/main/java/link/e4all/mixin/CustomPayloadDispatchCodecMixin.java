package link.e4all.mixin;

import link.e4all.PacketHelper;
import link.e4all.RawPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.20.5+ fallback codec drops the proxy payload's bytes, write id + data ourselves
@Mixin(targets = "net.minecraft.network.protocol.common.custom.CustomPacketPayload$1")
public class CustomPayloadDispatchCodecMixin {

    @Inject(method = {
            "encode(Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V",
            "encode(Lio/netty/buffer/ByteBuf;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"
    }, at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$writeRawPayload(FriendlyByteBuf buf, CustomPacketPayload payload, CallbackInfo ci) {
        try {
            if (!(payload instanceof RawPayload raw)) return;
            PacketHelper.writeIdAndBytes(buf, raw.e4all$channel(), raw.e4all$data());
            ci.cancel();
        } catch (Throwable ignored) {
        }
    }
}
