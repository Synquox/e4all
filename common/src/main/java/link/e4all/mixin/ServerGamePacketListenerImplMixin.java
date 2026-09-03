package link.e4all.mixin;

import link.e4all.XaeroWorldIdentity;
import link.e4all.voice.VoiceControl;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// xaero nukes the client if the world id payload arrives before its session
// exists, so wait until the guest is actually in the world and moving
@Mixin(net.minecraft.server.network.ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerImplMixin {

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), require = 0)
    private void e4all$onClientInWorld(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        XaeroWorldIdentity.onPlayerMoved(this, VoiceControl.extractServerPlayer(this));
    }
}
