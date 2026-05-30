package link.e4all.mixin.ncr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.commands.arguments.ArgumentSignatures;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;

@Mixin(ServerboundChatCommandPacket.class)
public class MixinServerboundChatCommandPacket {

    @Inject(method = "argumentSignatures", at = @At("RETURN"), cancellable = true, require = 0)
    private void e4all$onGetSignatures(CallbackInfoReturnable<ArgumentSignatures> info) {
        info.setReturnValue(ArgumentSignatures.EMPTY);
    }
}
