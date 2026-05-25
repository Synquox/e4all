package link.e4all.mixin;

import link.e4all.Config;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces offlineMode for the LAN integrated server.
 *
 * MinecraftServer is the parent, but IntegratedServer overrides
 * usesAuthentication() to return its own field (`publishedPort != -1 &&
 * super/this.usesAuthentication`). On MC versions where the override does
 * NOT chain to super, the MinecraftServerMixin alone is insufficient and
 * the LAN server still requests encryption from joining players. Cracked
 * clients then hang at "Encrypting…" until they time out.
 *
 * Mixing into the override directly guarantees the offline-mode toggle
 * actually skips encryption for LAN-tunneled connections regardless of how
 * the subclass implements the method.
 */
@Mixin(IntegratedServer.class)
public class IntegratedServerMixin {

    @Inject(method = "/^(usesAuthentication|method_3828|m_129797_)$/", at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$forceOfflineMode(CallbackInfoReturnable<Boolean> cir) {
        if (Config.INSTANCE.offlineMode.value()) {
            cir.setReturnValue(false);
        }
    }
}
