package link.e4all.mixin;

import link.e4all.voice.VoiceControl;
import link.e4all.voice.VoiceControlPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

// the id arg changes class between MC versions, @Coerce keeps the handler
// matching. we let vanilla discard the payload like any unknown channel and
// just grab the bytes first
@Mixin(ClientboundCustomPayloadPacket.class)
public class ClientboundCustomPayloadPacketMixin {

    @Inject(method = "readPayload",
            at = @At("HEAD"), require = 0)
    private static void e4all$captureVoicePayload(@Coerce Object id, FriendlyByteBuf buf) {
        try {
            if (!VoiceControlPayload.isOwnChannel(id)) return;
            byte[] data = VoiceControlPayload.readRemaining(new FriendlyByteBuf(buf.slice()));
            VoiceControl.handleClientPayload(data);
        } catch (Throwable ignored) {
        }
    }
}
