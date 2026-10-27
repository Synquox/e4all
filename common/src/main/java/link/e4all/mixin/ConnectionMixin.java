package link.e4all.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import link.e4all.DialtoneConnectionExtensions;
import link.e4all.E4allClient;
import link.e4all.E4allTunnels;
import link.e4all.SmugglersInetSocketAddress;
import link.e4all.voice.LegacyPayloadBridge;
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
    @Unique
    private static final ThreadLocal<Class<?>> e4mc$originalChannelClass = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<EventLoopGroup> e4mc$originalGroup = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<ChannelHandler> e4mc$originalHandler = new ThreadLocal<>();
    @Unique
    private static final java.util.concurrent.atomic.AtomicLong e4all$swallowedDecodeErrors =
            new java.util.concurrent.atomic.AtomicLong();

    @Override
    public byte[] e4mc$exportKeyingMaterial(byte[] label, byte[] context, int length) {
        if (channel instanceof DialtoneChannel dialtoneChannel) {
            return dialtoneChannel.exportKeyingMaterial(label, context, length);
        }
        return null;
    }

    @Override
    public String e4mc$connInfo() {
        if (channel instanceof DialtoneChannel dialtoneChannel) {
            return dialtoneChannel.debugInfo();
        }
        return channel == null ? "no channel" : channel.getClass().getCanonicalName();
    }

    @Inject(method = "connect", at = @At("HEAD"), require = 0)
    private static void hijackStart(InetSocketAddress inetSocketAddress, @Coerce Object obj, Connection connection, CallbackInfoReturnable<ChannelFuture> cir) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress.set(new DialtoneAddress(smuggledAddress.ticket));
            E4allTunnels.register(connection);
        } else {
            VoiceBridge.setPendingDialtoneTicket(null);
        }
    }

    @Surrogate
    private static void hijackStart(InetSocketAddress inetSocketAddress, boolean bl, Connection connection, CallbackInfoReturnable<ChannelFuture> cir) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress.set(new DialtoneAddress(smuggledAddress.ticket));
            E4allTunnels.register(connection);
        } else {
            VoiceBridge.setPendingDialtoneTicket(null);
        }
    }

    @Surrogate
    private static void hijackStart(InetSocketAddress inetSocketAddress, @Coerce Object obj, Connection connection, CallbackInfo ci) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress.set(new DialtoneAddress(smuggledAddress.ticket));
            E4allTunnels.register(connection);
        } else {
            VoiceBridge.setPendingDialtoneTicket(null);
        }
    }

    @Surrogate
    private static void hijackStart(InetSocketAddress inetSocketAddress, boolean bl, Connection connection, CallbackInfo ci) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress.set(new DialtoneAddress(smuggledAddress.ticket));
            E4allTunnels.register(connection);
        } else {
            VoiceBridge.setPendingDialtoneTicket(null);
        }
    }

    @Inject(method = "connectToServer", at = @At("HEAD"), require = 0)
    private static void hijackStartAlt(CallbackInfoReturnable<Connection> cir,
                                       @Local(argsOnly = true, ordinal = 0) InetSocketAddress inetSocketAddress) {
        e4all$prepareConnectToServer(inetSocketAddress);
    }

    @Surrogate
    private static void hijackStartAlt(CallbackInfo ci,
                                       @Local(argsOnly = true, ordinal = 0) InetSocketAddress inetSocketAddress) {
        e4all$prepareConnectToServer(inetSocketAddress);
    }

    @Unique
    private static void e4all$prepareConnectToServer(InetSocketAddress inetSocketAddress) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress.set(new DialtoneAddress(smuggledAddress.ticket));
        } else {
            VoiceBridge.setPendingDialtoneTicket(null);
        }
    }

    // install legacy payload sniffer (1.18 - 1.20.1) on connect
    @Inject(method = "connect", at = @At("RETURN"), require = 0)
    private static void e4all$installLegacyPayloadSnifferOnConnect(InetSocketAddress inetSocketAddress, @Coerce Object obj, Connection connection, CallbackInfoReturnable<?> cir) {
        try {
            link.e4all.voice.LegacyPayloadBridge.install(connection);
        } catch (Throwable ignored) {}
    }

    @Surrogate
    private static void e4all$installLegacyPayloadSnifferOnConnect(InetSocketAddress inetSocketAddress, boolean bl, Connection connection, CallbackInfoReturnable<?> cir) {
        try {
            link.e4all.voice.LegacyPayloadBridge.install(connection);
        } catch (Throwable ignored) {}
    }

    @Surrogate
    private static void e4all$installLegacyPayloadSnifferOnConnect(InetSocketAddress inetSocketAddress, @Coerce Object obj, Connection connection, CallbackInfo ci) {
        try {
            link.e4all.voice.LegacyPayloadBridge.install(connection);
        } catch (Throwable ignored) {}
    }

    @Surrogate
    private static void e4all$installLegacyPayloadSnifferOnConnect(InetSocketAddress inetSocketAddress, boolean bl, Connection connection, CallbackInfo ci) {
        try {
            link.e4all.voice.LegacyPayloadBridge.install(connection);
        } catch (Throwable ignored) {}
    }

    @ModifyArg(method = {"connect", "connectToServer"}, at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;"), require = 0)
    private static Class hijackChannel(Class clazz) {
        if (e4mc$smuggledDialtoneAddress.get() != null) {
            e4mc$originalChannelClass.set(clazz);
            return DialtoneChannel.class;
        } else {
            return clazz;
        }
    }

    @ModifyArg(method = {"connect", "connectToServer"},
            at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;handler(Lio/netty/channel/ChannelHandler;)Lio/netty/bootstrap/AbstractBootstrap;"),
            require = 0)
    private static ChannelHandler captureOriginalHandler(ChannelHandler handler) {
        if (e4mc$smuggledDialtoneAddress.get() != null) {
            e4mc$originalHandler.set(handler);
        }
        return handler;
    }

    @ModifyArg(method = {"connect", "connectToServer"}, at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;group(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/AbstractBootstrap;"), require = 0)
    private static EventLoopGroup hijackGroup(EventLoopGroup group) {
        if (e4mc$smuggledDialtoneAddress.get() != null) {
            e4mc$originalGroup.set(group);
            return DialtoneAmbientSession.INSTANCE.group;
        } else {
            return group;
        }
    }

    @WrapOperation(method = {"connect", "connectToServer"}, at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/InetAddress;I)Lio/netty/channel/ChannelFuture;"), require = 0)
    private static ChannelFuture hijackConnect(Bootstrap instance, InetAddress inetHost, int inetPort, Operation<ChannelFuture> operation) {
        DialtoneAddress dialtoneAddress = e4mc$smuggledDialtoneAddress.get();
        if (dialtoneAddress != null) {
            E4allClient.LOGGER.info("e4all: Dialtone ticket found; attempting direct game connection");
            var ret = instance.connect(dialtoneAddress);
            e4mc$smuggledDialtoneAddress.remove();
            return e4all$withRelayFallback(ret, new InetSocketAddress(inetHost, inetPort));
        } else {
            return operation.call(instance, inetHost, inetPort);
        }
    }

    @WrapOperation(method = {"connect", "connectToServer"}, at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/SocketAddress;)Lio/netty/channel/ChannelFuture;"), require = 0)
    private static ChannelFuture hijackConnectSocketAddress(Bootstrap instance, SocketAddress remoteAddress, Operation<ChannelFuture> operation) {
        DialtoneAddress dialtoneAddress = e4mc$smuggledDialtoneAddress.get();
        if (dialtoneAddress != null) {
            E4allClient.LOGGER.info("e4all: Dialtone ticket found; attempting direct game connection");
            var ret = instance.connect(dialtoneAddress);
            e4mc$smuggledDialtoneAddress.remove();
            return e4all$withRelayFallback(ret, remoteAddress);
        } else {
            return operation.call(instance, remoteAddress);
        }
    }

    @Unique
    private static ChannelFuture e4all$withRelayFallback(ChannelFuture directFuture, SocketAddress relayAddress) {
        Class<?> originalChannelClass = e4mc$originalChannelClass.get();
        EventLoopGroup originalGroup = e4mc$originalGroup.get();
        ChannelHandler originalHandler = e4mc$originalHandler.get();
        e4mc$originalChannelClass.remove();
        e4mc$originalGroup.remove();
        e4mc$originalHandler.remove();
        if (originalChannelClass == null || originalGroup == null || originalHandler == null) {
            E4allClient.LOGGER.warn("e4all: missing Minecraft connection setup for relay fallback (channel={}, group={}, handler={})",
                    originalChannelClass != null, originalGroup != null, originalHandler != null);
            return directFuture;
        }
        EventLoop eventLoop = directFuture.channel().eventLoop();
        DefaultChannelPromise result = new DefaultChannelPromise(directFuture.channel(), eventLoop);
        java.util.concurrent.atomic.AtomicBoolean fallbackStarted = new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable startFallback = () -> {
            if (!fallbackStarted.compareAndSet(false, true)) {
                return;
            }
            E4allClient.LOGGER.info("e4all: Dialtone game connection failed; retrying through the e4mc relay");
            try {
                @SuppressWarnings("unchecked")
                Class<? extends Channel> vanillaChannelClass = (Class<? extends Channel>) originalChannelClass;
                Bootstrap fallbackBootstrap = new Bootstrap()
                        .group(originalGroup)
                        .channel(vanillaChannelClass)
                        .handler(originalHandler);
                ChannelFuture fallback = fallbackBootstrap.connect(relayAddress);
                fallback.addListener(f -> {
                    if (f.isSuccess()) {
                        E4allClient.LOGGER.info("e4all: connected through the relay after Dialtone failed");
                        result.trySuccess();
                    } else {
                        E4allClient.LOGGER.warn("e4all: relay fallback also failed", f.cause());
                        result.tryFailure(f.cause());
                    }
                });
            } catch (Throwable t) {
                E4allClient.LOGGER.warn("e4all: could not prepare the relay fallback connect", t);
                result.tryFailure(t);
            }
        };
        long fallbackTimeoutMs = 15_000L;
        java.util.concurrent.ScheduledFuture<?> timeout = eventLoop.schedule(() -> {
            if (directFuture.isDone() || result.isDone()) {
                return;
            }
            E4allClient.LOGGER.warn("e4all: direct connection did not complete within {} ms, falling back to the relay", fallbackTimeoutMs);
            try {
                directFuture.cancel(false);
                directFuture.channel().close();
            } catch (Throwable ignored) {}
            startFallback.run();
        }, fallbackTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        directFuture.addListener(f -> {
            try {
                if (!timeout.isDone()) timeout.cancel(false);
            } catch (Throwable ignored) {}
            if (f.isSuccess()) {
                E4allClient.LOGGER.info("e4all: Dialtone game connection established");
                result.trySuccess();
            } else {
                E4allClient.LOGGER.warn("e4all: direct connection failed ({}), falling back to the relay", String.valueOf(f.cause()));
                try {
                    directFuture.channel().close();
                } catch (Throwable ignored) {}
                startFallback.run();
            }
        });
        return result;
    }

    @Inject(method = {"connect", "connectToServer"}, at = @At("RETURN"), require = 0)
    private static void e4all$cleanupSmuggledAddress(CallbackInfoReturnable<?> cir) {
        if (e4mc$smuggledDialtoneAddress.get() != null
                && cir != null && cir.getReturnValue() instanceof Connection conn) {
            E4allTunnels.register(conn);
        }
        e4mc$smuggledDialtoneAddress.remove();
        e4mc$originalChannelClass.remove();
        e4mc$originalGroup.remove();
        e4mc$originalHandler.remove();
    }

    @Surrogate
    private static void e4all$cleanupSmuggledAddress(CallbackInfo ci) {
        e4mc$smuggledDialtoneAddress.remove();
        e4mc$originalChannelClass.remove();
        e4mc$originalGroup.remove();
        e4mc$originalHandler.remove();
    }

    @Inject(method = "setEncryptionKey", at = @At(value = "FIELD", target = "Lnet/minecraft/network/Connection;channel:Lio/netty/channel/Channel;", opcode = org.objectweb.asm.Opcodes.GETFIELD, ordinal = 0), cancellable = true, require = 0)
    private void killDoubleEncryption(Cipher cipher, Cipher cipher2, CallbackInfo ci) {
        if (channel instanceof DialtoneChannel) {
            ci.cancel();
        }
    }

    @Inject(method = "setupCompression", at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$disableDialtoneCompression(int threshold, boolean validate, CallbackInfo ci) {
        if (channel instanceof DialtoneChannel) {
            E4allClient.LOGGER.info("e4all: disabled Minecraft compression for Dialtone connection");
            ci.cancel();
        }
    }

    @Surrogate
    private void e4all$disableDialtoneCompression(int threshold, CallbackInfo ci) {
        if (channel instanceof DialtoneChannel) {
            E4allClient.LOGGER.info("e4all: disabled Minecraft compression for Dialtone connection");
            ci.cancel();
        }
    }

    @Inject(method = "setupInboundCompression", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void e4all$disableDialtoneInboundCompression(int threshold, boolean validate, CallbackInfo ci) {
        if (channel instanceof DialtoneChannel) {
            E4allClient.LOGGER.info("e4all: disabled inbound Minecraft compression for Dialtone connection");
            ci.cancel();
        }
    }

    @Inject(method = "setupOutboundCompression", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void e4all$disableDialtoneOutboundCompression(int threshold, CallbackInfo ci) {
        if (channel instanceof DialtoneChannel) {
            E4allClient.LOGGER.info("e4all: disabled outbound Minecraft compression for Dialtone connection");
            ci.cancel();
        }
    }

    // 1.18 - 1.20.1 payloads are plain packets the payload mixins can't see, sniff them here
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"), require = 0)
    private void e4all$installLegacyPayloadSniffer(Packet<?> packet, CallbackInfo ci) {
        LegacyPayloadBridge.install((Connection) (Object) this);
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$suppressDialtoneCompressionPacket(Packet<?> packet, CallbackInfo ci) {
        if (e4all$shouldSuppressDialtoneCompressionPacket(packet)) {
            ci.cancel();
        }
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$suppressDialtoneCompressionPacketWithListener(Packet<?> packet,
                                                                    ChannelFutureListener listener,
                                                                    CallbackInfo ci) {
        if (e4all$shouldSuppressDialtoneCompressionPacket(packet)) {
            ci.cancel();
        }
    }

    @Unique
    private boolean e4all$shouldSuppressDialtoneCompressionPacket(Packet<?> packet) {
        if (channel instanceof DialtoneChannel && e4all$isLoginCompressionPacket(packet)) {
            E4allClient.LOGGER.info("e4all: suppressed login compression packet for Dialtone connection");
            return true;
        }
        return false;
    }

    @Unique
    private static boolean e4all$isLoginCompressionPacket(Object packet) {
        return packet != null && packet.getClass().getSimpleName().contains("Compression");
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
                if (e4all$isRelayChannel(channel) && e4all$isSafeCustomPayloadDecodeFailure(text)) {
                    long count = e4all$swallowedDecodeErrors.incrementAndGet();
                    E4allClient.LOGGER.warn("e4all: skipped unreadable custom payload on relay connection ({} total): {}",
                            count, text);
                    return; // don't disconnect
                }
                if (text.contains("SocketException") || text.contains("IOException")
                        || text.contains("NativeException") || text.contains("CompletionException")) {
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

    @WrapOperation(method = "exceptionCaught", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;disconnect(Lnet/minecraft/network/DisconnectionDetails;)V"), require = 0)
    private void e4all$friendlyIoDisconnectReasonModern(Connection instance, @Coerce Object details, Operation<Void> original) {
        if (details != null) {
            try {
                String text = details.toString();
                if (e4all$isRelayChannel(channel) && e4all$isSafeCustomPayloadDecodeFailure(text)) {
                    long count = e4all$swallowedDecodeErrors.incrementAndGet();
                    E4allClient.LOGGER.warn("e4all: skipped unreadable custom payload on relay connection ({} total): {}",
                            count, text);
                    return;
                }
                if (text.contains("SocketException") || text.contains("IOException")
                        || text.contains("NativeException") || text.contains("CompletionException")) {
                    String detail = text.substring(text.lastIndexOf(':') + 1).trim();
                    if (detail.isEmpty() || detail.equals(text)) detail = text;
                    link.e4all.E4allClient.LOGGER.warn("e4all: replaced raw IO disconnect reason '{}' with a friendly message", text);
                    instance.disconnect(Component.translatable("text.e4all_minecraft.connectionLost", detail));
                    return;
                }
            } catch (Throwable t) {
                link.e4all.E4allClient.LOGGER.debug("e4all: could not post-process modern disconnect details", t);
            }
        }
        original.call(instance, details);
    }

    @Unique
    private static boolean e4all$isSafeCustomPayloadDecodeFailure(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("decoderexception")
                && (lower.contains("custom_payload") || lower.contains("custom payload"))
                && !lower.contains("dataformatexception")
                && !lower.contains("incorrect header check");
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
