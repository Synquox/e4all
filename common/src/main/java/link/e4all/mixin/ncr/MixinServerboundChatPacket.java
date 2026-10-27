package link.e4all.mixin.ncr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.network.protocol.game.ServerboundChatPacket;

import java.lang.reflect.Method;
import java.util.Optional;

@Mixin(ServerboundChatPacket.class)
public class MixinServerboundChatPacket {

    private static final boolean IS_OPTIONAL = isOptionalReturnType();

    private static boolean isOptionalReturnType() {
        try {
            for (Method m : ServerboundChatPacket.class.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && (m.getName().equals("signature") || m.getName().equals("comp_948"))) {
                    return Optional.class.isAssignableFrom(m.getReturnType());
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "signature", at = @At("RETURN"), cancellable = true, require = 0)
    private void e4all$onGetSignature(CallbackInfoReturnable<?> info) {
        if (link.e4all.Config.INSTANCE.offlineMode.value()) {
            if (IS_OPTIONAL || info.getReturnValue() instanceof Optional) {
                ((CallbackInfoReturnable<Object>) (Object) info).setReturnValue(Optional.empty());
            } else {
                ((CallbackInfoReturnable<Object>) (Object) info).setReturnValue(null);
            }
        }
    }
}

