package link.e4all.mixin;

import link.e4all.voice.VoiceControlPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// unknown payload bytes get skipped here before handleCustomPayload sees them,
// stash e4all:voice for the dispatch mixins. regex selector keeps the jar
// remapper from touching it (lambda only exists on 1.20.5+/26.x)
@Mixin(DiscardedPayload.class)
public class DiscardedPayloadMixin {

    @Inject(method = "/^lambda\\$codec\\$1$/", at = @At("HEAD"), require = 0, remap = false)
    private static void e4all$captureVoicePayload(int maxSize, @Coerce Object id, FriendlyByteBuf buf,
                                                  CallbackInfoReturnable<DiscardedPayload> cir) {
        try {
            if (!VoiceControlPayload.isOwnChannel(id)) return;
            byte[] data = VoiceControlPayload.readRemaining(new FriendlyByteBuf(buf.slice()));
            VoiceControlPayload.PENDING.set(data);
            VoiceControlPayload.enqueuePendingServer(data);
        } catch (Throwable ignored) {
        }
    }
}
