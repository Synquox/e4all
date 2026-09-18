package link.e4all.mixin;

import link.e4all.Config;
import link.e4all.E4allClient;
import link.e4all.QuiclimeSession;
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

    @Inject(method = "stopServer", at = @At("HEAD"), require = 0)
    private void e4all$onServerStopping(CallbackInfo ci) {
        MinecraftServer self = (MinecraftServer) (Object) this;
        ServerStartupTracker.onServerStopping(self);
        try {
            QuiclimeSession session = E4allClient.session;
            if (session != null && session.ownsServer(self)) {
                E4allClient.LOGGER.info("e4all: world stopping, tearing down the relay session for this world");
                session.stop();
            } else if (session != null) {
                E4allClient.LOGGER.info("e4all: world stopping, keeping the relay session of another world alive");
            }
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("e4all: could not tear down the relay session on world stop", t);
        }
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

