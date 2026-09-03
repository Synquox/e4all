package link.e4all.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.netty.handler.codec.DecoderException;
import link.e4all.E4allClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// vanilla only surfaces the packet name in the kick screen, log the real cause
@Mixin(targets = "net.minecraft.network.codec.IdDispatchCodec")
public class IdDispatchCodecMixin {

    @WrapOperation(method = "decode", at = @At(value = "NEW",
            target = "(Ljava/lang/String;Ljava/lang/Throwable;)Lio/netty/handler/codec/DecoderException;"), require = 0)
    private DecoderException e4all$logDecodeFailure(String msg, Throwable cause, Operation<DecoderException> op) {
        if (cause != null) {
            E4allClient.LOGGER.warn("e4all: packet decode failed: {} / {}", msg, String.valueOf(cause));
        }
        return op.call(msg, cause);
    }
}
