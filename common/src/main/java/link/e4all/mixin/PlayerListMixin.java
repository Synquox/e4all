package link.e4all.mixin;

import link.e4all.Config;
import link.e4all.E4allClient;
import link.e4all.Mirror;
import link.e4all.QuiclimeSession;
import link.e4all.ServerStartupTracker;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserWhiteList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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

    // guests only into a world that is running, belongs to the active relay session and has the host in it
    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true, require = 0)
    public void e4all$gateStartupLogins(SocketAddress socketAddress, @Coerce Object gameProfile, CallbackInfoReturnable<Component> cir) {
        if (cir.isCancelled()) return;
        MinecraftServer server = getServer();
        if (socketAddress == null || socketAddress instanceof io.netty.channel.local.LocalAddress || Mirror.isSingleplayerOwnerObj(server, gameProfile)) {
            return; // local/owner logins are fine
        }
        if (server == null || server.isDedicatedServer()) return;

        try {
            if (ServerStartupTracker.isStopping(server)) {
                E4allClient.LOGGER.info("e4all: rejecting guest login, world is stopping ({})",
                        ServerStartupTracker.describe(server));
                cir.setReturnValue(Mirror.translatable("text.e4all_minecraft.worldLoading"));
                return;
            }

            // hold guest if world is still finishing startup
            long readyTimeoutMs = 0L;
            try {
                readyTimeoutMs = Config.INSTANCE.loginReadyTimeoutMs.value();
            } catch (Throwable ignored) {}
            if (readyTimeoutMs > 0
                    && (ServerStartupTracker.isStarting(server) || ServerStartupTracker.isMidFirstTick(server))) {
                E4allClient.LOGGER.info("e4all: holding guest login until the world is ticking ({}, up to {} ms)",
                        ServerStartupTracker.describe(server), readyTimeoutMs);
                if (!ServerStartupTracker.awaitReady(server, readyTimeoutMs, () -> e4all$ownsActiveSession(server))) {
                    E4allClient.LOGGER.info("e4all: rejecting guest login, world did not finish starting in time ({})",
                            ServerStartupTracker.describe(server));
                    cir.setReturnValue(Mirror.translatable("text.e4all_minecraft.worldLoading"));
                    return;
                }
                E4allClient.LOGGER.info("e4all: world is ticking, letting the held guest login through ({})",
                        ServerStartupTracker.describe(server));
            }

            if (!e4all$ownsActiveSession(server)) {
                E4allClient.LOGGER.info("e4all: rejecting guest login, the relay session belongs to another world ({})",
                        ServerStartupTracker.describe(server));
                cir.setReturnValue(Mirror.translatable("text.e4all_minecraft.worldLoading"));
                return;
            }
            if (ServerStartupTracker.isStarting(server)
                    || ServerStartupTracker.isMidFirstTick(server)
                    || ServerStartupTracker.isStopping(server)) {
                E4allClient.LOGGER.info("e4all: rejecting guest login, world is (re)starting or stopping ({})",
                        ServerStartupTracker.describe(server));
                cir.setReturnValue(Mirror.translatable("text.e4all_minecraft.worldLoading"));
                return;
            }
            if (!ServerStartupTracker.hasAnyPlayer(server)) {
                E4allClient.LOGGER.info("e4all: rejecting guest login, the host is not in the world yet ({})",
                        ServerStartupTracker.describe(server));
                cir.setReturnValue(Mirror.translatable("text.e4all_minecraft.hostNotReady"));
            }
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("e4all: login gate check failed", t);
        }
    }

    // a login is only ours to admit while no other world holds the tunnel
    @Unique
    private static boolean e4all$ownsActiveSession(MinecraftServer server) {
        QuiclimeSession session = E4allClient.session;
        return session == null || session.ownsServer(server);
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
        } catch (Throwable ignored) {}
    }
}
