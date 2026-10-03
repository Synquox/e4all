package link.e4all.mixin;

import link.e4all.voice.VoiceControl;
import link.e4all.voice.VoiceControlPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientboundCustomPayloadPacket.class)
public class ClientboundCustomPayloadPacketMixin {

    @Inject(method = "readPayload",
            at = @At("HEAD"), require = 0)
    private static void e4all$captureVoicePayload(ResourceLocation id, FriendlyByteBuf buf,
                                                  CallbackInfoReturnable<CustomPacketPayload> cir) {
        try {
            if (!VoiceControlPayload.isOwnChannel(id)) return;
            if (buf.readableBytes() == 0 && !e4all$drainedReported) {
                e4all$drainedReported = true;
                link.e4all.E4allClient.LOGGER.warn("e4all voice: an e4all:voice payload arrived with an empty "
                        + "buffer, so it could not be captured (another mod, e.g. Krypton, modified the payload "
                        + "buffer before this hook ran). voice may not work; everything else is unaffected");
            }
            byte[] data = VoiceControlPayload.readRemaining(new FriendlyByteBuf(buf.slice()));
            VoiceControl.handleClientPayload(data);
        } catch (Throwable ignored) {
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static volatile boolean e4all$drainedReported = false;
}
