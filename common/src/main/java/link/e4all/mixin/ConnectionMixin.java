package link.e4all.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import link.e4all.Config;
import link.e4all.DialtoneConnectionExtensions;
import link.e4all.E4allClient;
import link.e4all.VoiceChatBridgeHandler;
import link.e4all.SmugglersInetSocketAddress;
import link.e4all.dialtone.DialtoneAddress;
import link.e4all.dialtone.DialtoneAmbientSession;
import link.e4all.dialtone.DialtoneChannel;
import net.minecraft.network.Connection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.crypto.Cipher;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

@Mixin(Connection.class)
public abstract class ConnectionMixin implements DialtoneConnectionExtensions {

    @Shadow private Channel channel;
    @Shadow private boolean encrypted;
    @Unique
    private static volatile DialtoneAddress e4mc$smuggledDialtoneAddress = null;

    @Override
    public byte[] e4mc$exportKeyingMaterial(byte[] label, byte[] context, int length) {
        if (channel instanceof DialtoneChannel dialtoneChannel) {
            return dialtoneChannel.exportKeyingMaterial(label, context, length);
        }
        return null;
    }

    @Inject(method = "/^(connect|method_52271|m_290025_)$/", at = @At("HEAD"), require = 0)
    private static void hijackStart(InetSocketAddress inetSocketAddress, @Coerce Object obj, Connection connection, CallbackInfoReturnable<ChannelFuture> cir) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress = new DialtoneAddress(smuggledAddress.ticket);
        }
    }

    @Surrogate
    private static void hijackStart(InetSocketAddress inetSocketAddress, boolean bl, Connection connection, CallbackInfoReturnable<ChannelFuture> cir) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress = new DialtoneAddress(smuggledAddress.ticket);
        }
    }

    @Surrogate
    private static void hijackStart(InetSocketAddress inetSocketAddress, @Coerce Object obj, Connection connection, CallbackInfo ci) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress = new DialtoneAddress(smuggledAddress.ticket);
        }
    }

    @Surrogate
    private static void hijackStart(InetSocketAddress inetSocketAddress, boolean bl, Connection connection, CallbackInfo ci) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress = new DialtoneAddress(smuggledAddress.ticket);
        }
    }

    @Inject(method = "name=/^(connectToServer|method_10753|m_178300_)$/ desc=/^\\(Ljava\\/net\\/InetSocketAddress;Z\\)L.+;$/", at = @At("HEAD"), require = 0)
    private static void hijackStartAlt(InetSocketAddress inetSocketAddress, boolean bl, CallbackInfoReturnable<Connection> cir) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress = new DialtoneAddress(smuggledAddress.ticket);
        }
    }

    @ModifyArg(method = "/^(connect|method_52271|m_290025_|connectToServer|method_10753|m_178300_)$/", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;"), require = 0)
    private static Class hijackChannel(Class clazz) {
        if (e4mc$smuggledDialtoneAddress != null) {
            return DialtoneChannel.class;
        } else {
            return clazz;
        }
    }

    @ModifyArg(method = "/^(connect|method_52271|m_290025_|connectToServer|method_10753|m_178300_)$/", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;group(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/AbstractBootstrap;"), require = 0)
    private static EventLoopGroup hijackGroup(EventLoopGroup group) {
        if (e4mc$smuggledDialtoneAddress != null) {
            return DialtoneAmbientSession.INSTANCE.group;
        } else {
            return group;
        }
    }

    @WrapOperation(method = "/^(connect|method_52271|m_290025_|connectToServer|method_10753|m_178300_)$/", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/InetAddress;I)Lio/netty/channel/ChannelFuture;"), require = 0)
    private static ChannelFuture hijackConnect(Bootstrap instance, InetAddress inetHost, int inetPort, Operation<ChannelFuture> operation) {
        if (e4mc$smuggledDialtoneAddress != null) {
            var ret = instance.connect(e4mc$smuggledDialtoneAddress);
            e4mc$smuggledDialtoneAddress = null;
            return ret;
        } else {
            return operation.call(instance, inetHost, inetPort);
        }
    }

    // MC 26.1.2+ changed Connection.connect() to call Bootstrap.connect(SocketAddress)
    // instead of Bootstrap.connect(InetAddress, int). This overload handles that signature.
    @WrapOperation(method = "/^(connect|method_52271|m_290025_|connectToServer|method_10753|m_178300_)$/", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/SocketAddress;)Lio/netty/channel/ChannelFuture;"), require = 0)
    private static ChannelFuture hijackConnectSocketAddress(Bootstrap instance, SocketAddress remoteAddress, Operation<ChannelFuture> operation) {
        if (e4mc$smuggledDialtoneAddress != null) {
            var ret = instance.connect(e4mc$smuggledDialtoneAddress);
            e4mc$smuggledDialtoneAddress = null;
            return ret;
        } else {
            return operation.call(instance, remoteAddress);
        }
    }

    // Safety net: ensure the static variable is always cleaned up at the end of the connect
    // method, even if the @WrapOperation hooks didn't fire (e.g., on an unknown MC version).
    @Inject(method = "/^(connect|method_52271|m_290025_|connectToServer|method_10753|m_178300_)$/", at = @At("RETURN"), require = 0)
    private static void e4all$cleanupSmuggledAddress(CallbackInfoReturnable<?> cir) {
        e4mc$smuggledDialtoneAddress = null;
    }

    @Surrogate
    private static void e4all$cleanupSmuggledAddress(CallbackInfo ci) {
        e4mc$smuggledDialtoneAddress = null;
    }

    @Inject(method = "/^(setEncryptionKey|method_10746|m_129506_)$/", at = @At("HEAD"), cancellable = true, require = 0)
    private void killDoubleEncryption(Cipher cipher, Cipher cipher2, CallbackInfo ci) {
        if (channel instanceof DialtoneChannel) {
            encrypted = true;
            ci.cancel();
        }
    }

    // Add client-side voice chat bridge handler to ALL connections
    @Inject(method = "channelActive", at = @At("TAIL"), require = 0)
    private void e4all$addVoiceBridgeOnActive(ChannelHandlerContext ctx, CallbackInfo ci) {
        if (Config.INSTANCE.voiceChatBridgeEnabled.value()) {
            try {
                if (channel.pipeline().get("packet_handler") != null) {
                    channel.pipeline().addBefore("packet_handler", "e4all_voicebridge", new VoiceChatBridgeHandler(false));
                } else {
                    channel.pipeline().addLast("e4all_voicebridge", new VoiceChatBridgeHandler(false));
                }
                E4allClient.LOGGER.debug("Added client-side voice chat bridge to connection");
            } catch (Exception e) {
                E4allClient.LOGGER.debug("Could not add client voice chat bridge", e);
            }
        }
    }

    // Skip compression for DialtoneChannel — the QUIC/iroh transport handles
    // data efficiently already; applying Minecraft's zlib on top causes
    // "incorrect header check" DecoderExceptions due to compression state
    // mismatches during the async QUIC handshake.
    @Inject(method = "setupCompression", at = @At("HEAD"), cancellable = true, require = 0)
    private void killDoubleCompression(int threshold, boolean validate, CallbackInfo ci) {
        if (channel instanceof DialtoneChannel) {
            ci.cancel();
        }
    }

    @Surrogate
    private void killDoubleCompression(int threshold, CallbackInfo ci) {
        if (channel instanceof DialtoneChannel) {
            ci.cancel();
        }
    }
}


