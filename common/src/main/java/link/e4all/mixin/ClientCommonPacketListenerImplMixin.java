package link.e4all.mixin;

import link.e4all.Agnos;
import link.e4all.DisconnectScreens;
import link.e4all.E4allClient;
import link.e4all.Mirror;
import link.e4all.voice.VoiceControl;
import link.e4all.voice.VoiceControlPayload;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientCommonPacketListenerImpl.class)
public class ClientCommonPacketListenerImplMixin {

    @Shadow
    protected Connection connection;

    @Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$handleVoicePayload(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        try {
            byte[] data = VoiceControlPayload.extractData(packet);
            if (data != null) {
                VoiceControl.handleClientPayload(data);
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "createDisconnectScreen", at = @At("RETURN"), require = 0)
    private void e4all$appendTunnelLostHint(@Coerce Object reason, CallbackInfoReturnable<Screen> cir) {
        try {
            if (!Agnos.isClient()) return;
            Screen original = cir.getReturnValue();
            if (!(original instanceof DisconnectedScreen disconnected)) return;
            if (!DisconnectScreens.isTunnelConnection(connection)) return;
            if (!DisconnectScreens.isUnexplainedTunnelDrop(DisconnectScreens.reasonOf(disconnected))) return;
            DisconnectedScreen replaced = DisconnectScreens.withHint(disconnected,
                    Mirror.translatable("text.e4all_minecraft.tunnelLostHint"));
            if (replaced != null) {
                E4allClient.LOGGER.debug("e4all: added stale address hint to disconnect screen");
                cir.setReturnValue(replaced);
            }
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("e4all: could not decorate disconnect screen", t);
        }
    }
}
