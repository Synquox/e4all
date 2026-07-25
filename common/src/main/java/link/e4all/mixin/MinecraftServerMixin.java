package link.e4all.mixin;

import link.e4all.Config;
import link.e4all.OpSessionManager;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {

    @Inject(method = "enforceSecureProfile", at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$enforceSecureProfile(CallbackInfoReturnable<Boolean> cir) {
        if (Config.INSTANCE.offlineMode.value()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "usesAuthentication", at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$usesAuthentication(CallbackInfoReturnable<Boolean> cir) {
        if (Config.INSTANCE.offlineMode.value()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void e4all$endOpSession(CallbackInfo ci) {
        OpSessionManager.endSession((MinecraftServer) (Object) this);
    }
}
