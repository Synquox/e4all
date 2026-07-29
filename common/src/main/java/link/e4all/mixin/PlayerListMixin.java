package link.e4all.mixin;

import com.mojang.authlib.GameProfile;
import link.e4all.Config;
import link.e4all.E4allClient;
import link.e4all.Mirror;
import link.e4all.OpSessionManager;
import link.e4all.XaeroWorldIdentity;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserWhiteList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
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

    @Inject(method = "/^<init>$/", at = @At("TAIL"), require = 0)
    void injectListLoads(CallbackInfo ci) {
        if (Config.INSTANCE.restoreDedicatedCommands.value()) {
            Mirror.setUsingWhitelist(getServer(), (PlayerList) (Object) this, Config.INSTANCE.useWhiteList.value());
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

    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true, require = 0)
    public void allowOwnerLogin(SocketAddress socketAddress, GameProfile gameProfile, CallbackInfoReturnable<Component> cir) {
        if (socketAddress == null || Mirror.isSingleplayerOwnerObj(getServer(), gameProfile)) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "placeNewPlayer", at = @At("HEAD"), require = 0)
    private void e4all$startOpVerification(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        OpSessionManager.onPlayerConnecting(getServer(), player);
    }

    @Inject(
            method = "placeNewPlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V",
                    ordinal = 0
            ),
            require = 0
    )
    private void e4all$sendXaeroWorldIdentityBeforeJoin(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        XaeroWorldIdentity.sendToRelayPlayer(getServer(), connection, player);
    }

    @Inject(method = "remove", at = @At("TAIL"), require = 0)
    private void e4all$finishOpVerification(ServerPlayer player, CallbackInfo ci) {
        OpSessionManager.onPlayerDisconnected(getServer(), player);
    }

    @Inject(method = "isOp", at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$hideUnverifiedOp(GameProfile profile, CallbackInfoReturnable<Boolean> cir) {
        Boolean overridden = OpSessionManager.getOpOverride(getServer(), profile);
        if (overridden != null) {
            cir.setReturnValue(overridden);
        }
    }

    @Inject(method = "op", at = @At("TAIL"), require = 0)
    private void e4all$createOpSession(GameProfile profile, CallbackInfo ci) {
        OpSessionManager.onOpGranted(getServer(), profile);
    }

    @Inject(method = "deop", at = @At("TAIL"), require = 0)
    private void e4all$removeOpSession(GameProfile profile, CallbackInfo ci) {
        OpSessionManager.onDeop(getServer(), profile);
    }

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void e4all$tickOpVerification(CallbackInfo ci) {
        OpSessionManager.tick(getServer());
    }
}


