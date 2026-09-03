package link.e4all.mixin;

import link.e4all.Config;
import link.e4all.ServerStartupTracker;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {

    // first tick means startup finished, see ServerStartupTracker
    @Inject(method = "tickServer", at = @At("HEAD"), require = 0)
    private void e4all$observeTick(BooleanSupplier haveTime, CallbackInfo ci) {
        ServerStartupTracker.observeTick((MinecraftServer) (Object) this);
    }

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
}

