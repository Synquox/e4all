package link.e4all.mixin;

import link.e4all.Config;
import link.e4all.E4allClient;
import link.e4all.QuiclimeSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// keep integrated server ticking while hosted so connected guests don't freeze
@Mixin(IntegratedServer.class)
public class IntegratedServerPauseMixin {

    @Unique
    private static boolean e4all$pauseNoticeShown = false;
    @Unique
    private static boolean e4all$redirectBroken = false;

    @Redirect(method = "tickServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;isPaused()Z"), require = 0)
    private boolean e4all$keepHostedWorldTicking(Minecraft minecraft) {
        try {
            if (Config.INSTANCE.preventPauseWhileHosting.value() && e4all$isHosting()) {
                if (!e4all$pauseNoticeShown) {
                    e4all$pauseNoticeShown = true;
                    E4allClient.LOGGER.info("e4all: keeping hosted world ticking while paused (prevents guests from freezing)");
                }
                return false;
            }
        } catch (Throwable t) {
            if (!e4all$redirectBroken) {
                e4all$redirectBroken = true;
                E4allClient.LOGGER.debug("e4all: could not override the singleplayer pause", t);
            }
        }
        return minecraft.isPaused();
    }

    @Unique
    private boolean e4all$isHosting() {
        QuiclimeSession session = E4allClient.session;
        return session != null && session.ownsServer((IntegratedServer) (Object) this);
    }
}
