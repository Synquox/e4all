package link.e4all.mixin;

import link.e4all.voice.VoiceControlPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;

// same capture as the clientbound side, but the player context only exists at
// handleCustomPayload time, so stash for the listener mixin (same netty thread)
@Mixin(ServerboundCustomPayloadPacket.class)
public class ServerboundCustomPayloadPacketMixin {

    // vanilla caps unknown-channel payloads at 32767 here but accepts 1MB clientbound.
    // registered types are unaffected, this just keeps the sniffing fallback alive.
    // regex selector so the jar remapper leaves it alone (no lambda$static$0 in 1.20.2)
    @ModifyConstant(method = "/^lambda\\$static\\$0$/", constant = @Constant(intValue = 32767), require = 0)
    private static int e4all$raiseFallbackPayloadLimit(int limit) {
        return 1048576;
    }

    @Inject(method = "readPayload",
            at = @At("HEAD"), require = 0)
    private static void e4all$captureVoicePayload(@Coerce Object id, FriendlyByteBuf buf) {
        try {
            if (!VoiceControlPayload.isOwnChannel(id)) return;
            byte[] data = VoiceControlPayload.readRemaining(new FriendlyByteBuf(buf.slice()));
            VoiceControlPayload.PENDING.set(data);
            VoiceControlPayload.enqueuePendingServer(data);
        } catch (Throwable ignored) {
        }
    }
}
