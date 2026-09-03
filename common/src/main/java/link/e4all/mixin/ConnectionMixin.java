package link.e4all.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import link.e4all.DialtoneConnectionExtensions;
import link.e4all.E4allClient;
import link.e4all.SmugglersInetSocketAddress;
import link.e4all.voice.VoiceBridge;
import link.e4all.dialtone.DialtoneAddress;
import link.e4all.dialtone.DialtoneAmbientSession;
import link.e4all.dialtone.DialtoneChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;

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

    @Unique
    private static final ThreadLocal<DialtoneAddress> e4mc$smuggledDialtoneAddress = new ThreadLocal<>();

    @Override
    public byte[] e4mc$exportKeyingMaterial(byte[] label, byte[] context, int length) {
        if (channel instanceof DialtoneChannel dialtoneChannel) {
            return dialtoneChannel.exportKeyingMaterial(label, context, length);
        }
        return null;
    }

    @Inject(method = "connect", at = @At("HEAD"), require = 0)
    private static void hijackStart(InetSocketAddress inetSocketAddress, @Coerce Object obj, Connection connection, CallbackInfoReturnable<ChannelFuture> cir) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress.set(new DialtoneAddress(smuggledAddress.ticket));
        } else {
            VoiceBridge.setPendingDialtoneTicket(null);
        }
    }

    @Surrogate
    private static void hijackStart(InetSocketAddress inetSocketAddress, boolean bl, Connection connection, CallbackInfoReturnable<ChannelFuture> cir) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress.set(new DialtoneAddress(smuggledAddress.ticket));
        } else {
            VoiceBridge.setPendingDialtoneTicket(null);
        }
    }

    @Surrogate
    private static void hijackStart(InetSocketAddress inetSocketAddress, @Coerce Object obj, Connection connection, CallbackInfo ci) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress.set(new DialtoneAddress(smuggledAddress.ticket));
        } else {
            VoiceBridge.setPendingDialtoneTicket(null);
        }
    }

    @Surrogate
    private static void hijackStart(InetSocketAddress inetSocketAddress, boolean bl, Connection connection, CallbackInfo ci) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress.set(new DialtoneAddress(smuggledAddress.ticket));
        } else {
            VoiceBridge.setPendingDialtoneTicket(null);
        }
    }

    @Inject(method = "connectToServer", at = @At("HEAD"), require = 0)
    private static void hijackStartAlt(InetSocketAddress inetSocketAddress, boolean bl, CallbackInfoReturnable<Connection> cir) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress.set(new DialtoneAddress(smuggledAddress.ticket));
        } else {
            VoiceBridge.setPendingDialtoneTicket(null);
        }
    }

    @Surrogate
    private static void hijackStartAlt(InetSocketAddress inetSocketAddress, boolean bl, @Coerce Object sampleLogger, CallbackInfoReturnable<Connection> cir) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress.set(new DialtoneAddress(smuggledAddress.ticket));
        } else {
            VoiceBridge.setPendingDialtoneTicket(null);
        }
    }

    @ModifyArg(method = {"connect", "connectToServer"}, at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;"), require = 0)
    private static Class hijackChannel(Class clazz) {
        if (e4mc$smuggledDialtoneAddress.get() != null) {
            return DialtoneChannel.class;
        } else {
            return clazz;
        }
    }

    @ModifyArg(method = {"connect", "connectToServer"}, at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;group(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/AbstractBootstrap;"), require = 0)
    private static EventLoopGroup hijackGroup(EventLoopGroup group) {
        if (e4mc$smuggledDialtoneAddress.get() != null) {
            return DialtoneAmbientSession.INSTANCE.group;
        } else {
            return group;
        }
    }

    @WrapOperation(method = {"connect", "connectToServer"}, at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/InetAddress;I)Lio/netty/channel/ChannelFuture;"), require = 0)
    private static ChannelFuture hijackConnect(Bootstrap instance, InetAddress inetHost, int inetPort, Operation<ChannelFuture> operation) {
        if (e4mc$smuggledDialtoneAddress.get() != null) {
            var ret = instance.connect(e4mc$smuggledDialtoneAddress.get());
            e4mc$smuggledDialtoneAddress.remove();
            return ret;
        } else {
            return operation.call(instance, inetHost, inetPort);
        }
    }

    @WrapOperation(method = {"connect", "connectToServer"}, at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/SocketAddress;)Lio/netty/channel/ChannelFuture;"), require = 0)
    private static ChannelFuture hijackConnectSocketAddress(Bootstrap instance, SocketAddress remoteAddress, Operation<ChannelFuture> operation) {
        if (e4mc$smuggledDialtoneAddress.get() != null) {
            var ret = instance.connect(e4mc$smuggledDialtoneAddress.get());
            e4mc$smuggledDialtoneAddress.remove();
            return ret;
        } else {
            return operation.call(instance, remoteAddress);
        }
    }

    @Inject(method = {"connect", "connectToServer"}, at = @At("RETURN"), require = 0)
    private static void e4all$cleanupSmuggledAddress(CallbackInfoReturnable<?> cir) {
        e4mc$smuggledDialtoneAddress.remove();
    }

    @Surrogate
    private static void e4all$cleanupSmuggledAddress(CallbackInfo ci) {
        e4mc$smuggledDialtoneAddress.remove();
    }

    @Inject(method = "setEncryptionKey", at = @At(value = "FIELD", target = "Lnet/minecraft/network/Connection;channel:Lio/netty/channel/Channel;", opcode = org.objectweb.asm.Opcodes.GETFIELD, ordinal = 0), cancellable = true, require = 0)
    private void killDoubleEncryption(Cipher cipher, Cipher cipher2, CallbackInfo ci) {
        if (channel instanceof DialtoneChannel) {
            ci.cancel();
        }
    }

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

    // server sends compression packet before calling setupCompression().
    // killDoubleCompression cancels it server-side, but relay guests still receive
    // the packet and enable compression, corrupting the stream.
    // suppress it for relay connections so the stream stays uncompressed.
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$suppressCompressionPacket(Packet<?> packet, CallbackInfo ci) {
        if (channel instanceof DialtoneChannel && e4all$isLoginCompressionPacket(packet)) {
            E4allClient.LOGGER.debug("e4all: suppressed LoginCompressionPacket for relay connection");
            ci.cancel();
        }
    }

    @Unique
    private static boolean e4all$isLoginCompressionPacket(Object packet) {
        if (packet == null) return false;
        // Mojang-mapped: ClientboundLoginCompressionPacket
        // Yarn-mapped:   LoginCompressionS2CPacket
        return packet.getClass().getSimpleName().contains("Compression");
    }

    @Inject(method = "disconnect", at = @At("HEAD"), require = 0)
    private void e4all$onDisconnect(@Coerce Object reason, CallbackInfo ci) {
        // voice cleanup on game connection drop (clean or raw reset)
        if (link.e4all.Agnos.isClient()) {
            try {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc != null && mc.getConnection() != null && (Object) this == mc.getConnection().getConnection()) {
                    link.e4all.voice.ClientVoiceNegotiator.INSTANCE.stop();
                    link.e4all.voice.VoiceEndpointStack.INSTANCE.stop();
                }
            } catch (Throwable t) {
                link.e4all.E4allClient.LOGGER.debug("e4all voice: client voice cleanup failed", t);
            }
        }
    }

    // replace raw "Internal Exception: Connection reset" reasons with a readable message
    @WrapOperation(method = "exceptionCaught", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;disconnect(Lnet/minecraft/network/chat/Component;)V"), require = 0)
    private void e4all$friendlyIoDisconnectReason(Connection instance, Component reason, Operation<Void> original) {
        if (reason != null) {
            try {
                String text = reason.getString();
                // relay guests can trigger decode errors for custom_payload packets
                // from mods the host lacks. framing is intact so skipping one payload is safe
                if (e4all$isRelayChannel(channel) && text.contains("DecoderException")) {
                    E4allClient.LOGGER.warn("e4all: swallowed packet decode error on relay connection (guest stays connected): {}", text);
                    return; // don't disconnect
                }
                if (text.contains("SocketException") || text.contains("IOException")) {
                    String detail = text.substring(text.lastIndexOf(':') + 1).trim();
                    if (detail.isEmpty() || detail.equals(text)) detail = text;
                    link.e4all.E4allClient.LOGGER.warn("e4all: replaced raw IO disconnect reason '{}' with a friendly message", text);
                    original.call(instance, Component.translatable("text.e4all_minecraft.connectionLost", detail));
                    return;
                }
            } catch (Throwable t) {
                link.e4all.E4allClient.LOGGER.debug("e4all: could not post-process disconnect reason", t);
            }
        }
        original.call(instance, reason);
    }

    // check class name to avoid hard compile dep on netty-incubator-codec-quic
    @Unique
    private static boolean e4all$isRelayChannel(Channel ch) {
        if (ch instanceof DialtoneChannel) return true;
        if (ch == null) return false;
        try {
            return ch.getClass().getSimpleName().contains("QuicStream");
        } catch (Throwable t) {
            return false;
        }
    }
}
