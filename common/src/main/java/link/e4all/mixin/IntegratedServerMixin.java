package link.e4all.mixin;

import com.mojang.authlib.GameProfile;
import link.e4all.Mirror;
import link.e4all.OpSessionManager;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
    @Inject(method = "publishServer", at = @At("TAIL"), require = 0)
    private void e4all$startOpSession(GameType gameType, boolean allowCommands, int port, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) {
            return;
        }

        MinecraftServer server = (MinecraftServer) (Object) this;
        OpSessionManager.beginSession(server);
        if (!allowCommands) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            GameProfile profile = player.getGameProfile();
            if (Mirror.isSingleplayerOwnerObj(server, profile)) {
                server.getPlayerList().op(profile);
                return;
            }
        }
    }
}
