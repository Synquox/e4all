package link.e4all.mixin;

import link.e4all.XaeroWorldIdentity;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListCookieMixin {
    @Shadow public abstract MinecraftServer getServer();

    @Inject(
            method = {"placeNewPlayer", "method_14570", "m_11261_"},
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
}
