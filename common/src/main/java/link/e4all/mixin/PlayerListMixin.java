package link.e4all.mixin;

import link.e4all.Config;
import link.e4all.E4allClient;
import link.e4all.Mirror;
import link.e4all.ServerStartupTracker;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserWhiteList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.net.SocketAddress;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Shadow public abstract UserBanList getBans();

    @Shadow public abstract UserWhiteList getWhiteList();

    @Shadow public abstract MinecraftServer getServer();

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    void injectListLoads(CallbackInfo ci) {
        if (Config.INSTANCE.restoreDedicatedCommands.value()) {
            try {
                Mirror.setUsingWhitelist(getServer(), (PlayerList) (Object) this, Config.INSTANCE.useWhiteList.value());
            } catch (RuntimeException e) {
                E4allClient.LOGGER.warn("Failed to set whitelist state: ", e);
            }
            try {
                this.getBans().load();
            } catch (IOException e) {
                E4allClient.LOGGER.warn("Failed to load user banlist: ", e);
            }
            try {
                this.getWhiteList().load();
            } catch (IOException e) {
                E4allClient.LOGGER.warn("Failed to load whitelist: ", e);
            }
        }
    }

    // reject logins while the integrated server is mid-(re)init
    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true, require = 0)
    public void e4all$gateStartupLogins(SocketAddress socketAddress, @Coerce Object gameProfile, CallbackInfoReturnable<Component> cir) {
        if (cir.isCancelled()) return;
        MinecraftServer server = getServer();
        if (socketAddress == null || socketAddress instanceof io.netty.channel.local.LocalAddress || Mirror.isSingleplayerOwnerObj(server, gameProfile)) {
            return; // local/owner logins are fine
        }
        if (server == null || server.isDedicatedServer()) return;
        if (ServerStartupTracker.isStarting(server) || ServerStartupTracker.isMidFirstTick(server)) {
            E4allClient.LOGGER.info("e4all: rejecting login during integrated server (re)init");
            cir.setReturnValue(Mirror.translatable("text.e4all_minecraft.worldLoading"));
        }
    }

    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true, require = 0)
    public void allowOwnerLogin(SocketAddress socketAddress, @Coerce Object gameProfile, CallbackInfoReturnable<Component> cir) {
        if (cir.isCancelled()) return;
        if (socketAddress == null || socketAddress instanceof io.netty.channel.local.LocalAddress) {
            cir.setReturnValue(null);
            return;
        }
        try {
            if (Mirror.isSingleplayerOwnerObj(getServer(), gameProfile)) {
                cir.setReturnValue(null);
            }
        } catch (RuntimeException ignored) {}
    }
}
