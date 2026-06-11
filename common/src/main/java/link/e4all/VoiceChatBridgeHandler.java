package link.e4all;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import link.e4all.dialtone.DialtoneChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketException;
import java.nio.charset.StandardCharsets;

/**
 * Netty handler added to the pipeline of tunneled (e4all) connections.
 * Handles voice chat bridging by:
 *
 * HOST side (isServerSide=true):
 *   - Only activates for DialtoneChannel connections (where the client has e4all)
 *   - Intercepts outgoing SVC "voicechat:secret" packets to detect voice port
 *   - Starts a per-player UDP relay to the local SVC voice server
 *   - Relays voice data between UDP and custom payload packets
 *
 * CLIENT side (isServerSide=false):
 *   - Intercepts incoming SVC "voicechat:secret" packets with the bridge marker
 *   - Starts a local UDP proxy and rewrites the SecretPacket to point to it
 *   - Relays voice data between the local UDP proxy and custom payload packets
 */
public class VoiceChatBridgeHandler extends ChannelDuplexHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("e4all-voicebridge");

    private final boolean isServerSide;
    private VoiceChatBridge.HostUdpRelay hostRelay;
    private VoiceChatBridge.ClientUdpProxy clientProxy;
    private boolean bridgeActive = false;

    public VoiceChatBridgeHandler(boolean isServerSide) {
        this.isServerSide = isServerSide;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // Server side: only intercept SecretPackets for DialtoneChannel connections
        // where SVC is actually running. Relay clients don't have e4all's client
        // handler, so modifying their SecretPacket would break voice chat.
        if (isServerSide && isTunneledConnection(ctx.channel())
                && VoiceChatBridge.getVoiceChatPort() > 0
                && VoiceChatPacketHelper.isCustomPayloadPacket(msg)) {
            String channel = VoiceChatPacketHelper.getPayloadChannel(msg);
            if (VoiceChatPacketHelper.SVC_SECRET_CHANNEL.equals(channel)) {
                handleOutgoingSecretPacket(ctx, msg, promise);
                return;
            }
        }
        super.write(ctx, msg, promise);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (VoiceChatPacketHelper.isCustomPayloadPacket(msg)) {
            String channel = VoiceChatPacketHelper.getPayloadChannel(msg);

            // Client side: intercept incoming SVC SecretPacket
            if (!isServerSide && VoiceChatPacketHelper.SVC_SECRET_CHANNEL.equals(channel)) {
                handleIncomingSecretPacket(ctx, msg);
                return;
            }
        }
        super.channelRead(ctx, msg);
    }

    /**
     * Check if the channel is a tunneled connection (peer-to-peer Dialtone or proxy QuicStream)
     * AND is fully connected (stream is ready).
     */
    private boolean isTunneledConnection(Channel channel) {
        return channel instanceof DialtoneChannel && channel.isActive();
    }

    /**
     * HOST side: SVC is sending a SecretPacket to a tunneled player.
     * We intercept it, start a UDP relay, and rewrite the packet to
     * tell the client's e4all to use its local proxy.
     */
    private void handleOutgoingSecretPacket(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        byte[] data = VoiceChatPacketHelper.getPayloadData(msg);
        if (data == null) {
            LOGGER.warn("Could not read SVC SecretPacket data, passing through");
            super.write(ctx, msg, promise);
            return;
        }

        int svcPort = VoiceChatPacketHelper.readSecretPacketPort(data);
        if (svcPort <= 0) {
            LOGGER.warn("Invalid SVC port in SecretPacket: {}, passing through", svcPort);
            super.write(ctx, msg, promise);
            return;
        }

        // Start the host-side UDP relay for this player
        try {
            if (hostRelay != null) {
                hostRelay.close();
            }
            hostRelay = new VoiceChatBridge.HostUdpRelay(svcPort, ctx.channel());
            bridgeActive = true;
            LOGGER.info("Started voice chat bridge for tunneled player (SVC port: {})", svcPort);
        } catch (SocketException e) {
            LOGGER.error("Failed to start voice chat UDP relay", e);
            super.write(ctx, msg, promise);
            return;
        }

        // Modify the SecretPacket in-place using Unsafe for 1.20.4+ records
        boolean modified = false;
        try {
            Object payload = null;
            for (String methodName : new String[]{"payload", "getPayload"}) {
                try {
                    java.lang.reflect.Method m = msg.getClass().getMethod(methodName);
                    payload = m.invoke(msg);
                    if (payload != null) break;
                } catch (NoSuchMethodException ignored) {}
            }
            if (payload != null) {
                java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
                
                java.lang.reflect.Field portField = payload.getClass().getDeclaredField("serverPort");
                java.lang.reflect.Field hostField = payload.getClass().getDeclaredField("voiceHost");
                
                unsafe.putInt(payload, unsafe.objectFieldOffset(portField), svcPort);
                unsafe.putObject(payload, unsafe.objectFieldOffset(hostField), "e4all-vc-bridge");
                modified = true;
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to use Unsafe to modify SecretPayload in-place", e);
        }

        if (modified) {
            super.write(ctx, msg, promise);
            return;
        }

        // Rewrite the SecretPacket: set voiceHost to "e4all-vc-bridge" marker
        // so the client's e4all handler knows to use its local proxy
        byte[] rewritten = VoiceChatPacketHelper.rewriteSecretPacket(data, svcPort, "e4all-vc-bridge");
        if (rewritten != null) {
            sendModifiedSecretPacket(ctx, rewritten, msg, promise);
        } else {
            LOGGER.warn("Failed to rewrite SecretPacket, passing through unchanged");
            super.write(ctx, msg, promise);
        }
    }

    private void sendModifiedSecretPacket(ChannelHandlerContext ctx, byte[] newData, Object originalPacket, ChannelPromise promise) throws Exception {
        Object packet = buildSecretPacket(newData, originalPacket);
        if (packet != null) {
            ctx.write(packet, promise);
        } else {
            LOGGER.warn("Could not reconstruct SecretPacket, passing original");
            ctx.write(originalPacket, promise);
        }
    }

    /**
     * CLIENT side: We received a SVC SecretPacket from the server.
     * If the voiceHost is "e4all-vc-bridge", we start a local UDP proxy
     * and rewrite the packet to point to it.
     */
    private void handleIncomingSecretPacket(ChannelHandlerContext ctx, Object msg) throws Exception {
        byte[] data = VoiceChatPacketHelper.getPayloadData(msg);
        if (data == null) {
            LOGGER.debug("Could not read incoming SecretPacket data, passing through");
            ctx.fireChannelRead(msg);
            return;
        }

        // Check if this SecretPacket has our marker
        String voiceHost = readVoiceHostFromSecretPacket(data);
        if (!"e4all-vc-bridge".equals(voiceHost)) {
            // Not a bridged connection, pass through unchanged
            ctx.fireChannelRead(msg);
            return;
        }

        // Start the client-side UDP proxy
        try {
            if (clientProxy != null) {
                clientProxy.close();
            }
            clientProxy = new VoiceChatBridge.ClientUdpProxy(ctx.channel());
            int proxyPort = clientProxy.getLocalPort();
            bridgeActive = true;
            LOGGER.info("Voice chat bridge active — SVC client will use local proxy on port {}", proxyPort);

            // Modify the SecretPacket in-place using Unsafe for 1.20.4+ records
            boolean modified = false;
            try {
                Object payload = null;
                for (String methodName : new String[]{"payload", "getPayload"}) {
                    try {
                        java.lang.reflect.Method m = msg.getClass().getMethod(methodName);
                        payload = m.invoke(msg);
                        if (payload != null) break;
                    } catch (NoSuchMethodException ignored) {}
                }
                if (payload != null) {
                    java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                    unsafeField.setAccessible(true);
                    sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
                    
                    java.lang.reflect.Field portField = payload.getClass().getDeclaredField("serverPort");
                    java.lang.reflect.Field hostField = payload.getClass().getDeclaredField("voiceHost");
                    
                    unsafe.putInt(payload, unsafe.objectFieldOffset(portField), proxyPort);
                    unsafe.putObject(payload, unsafe.objectFieldOffset(hostField), "127.0.0.1");
                    modified = true;
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to use Unsafe to modify SecretPayload in-place", e);
            }
            
            if (modified) {
                ctx.fireChannelRead(msg);
                return;
            }

            // Rewrite the SecretPacket to point to our local proxy
            byte[] rewritten = VoiceChatPacketHelper.rewriteSecretPacket(data, proxyPort, "127.0.0.1");
            if (rewritten != null) {
                Object packet = buildSecretPacket(rewritten, msg);
                if (packet != null) {
                    ctx.fireChannelRead(packet);
                } else {
                    LOGGER.warn("Could not reconstruct SecretPacket for client, passing original");
                    ctx.fireChannelRead(msg);
                }
            } else {
                LOGGER.warn("Failed to rewrite SecretPacket for client, passing original");
                ctx.fireChannelRead(msg);
            }
        } catch (SocketException e) {
            LOGGER.error("Failed to start voice chat client proxy", e);
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * Build a voicechat:secret custom payload packet with the given data.
     * Uses shared reflection helpers from VoiceChatPacketHelper.
     *
     * Supports both pre-1.20.2 (ResourceLocation + FriendlyByteBuf constructor)
     * and 1.20.2+ (payload().type() system with either FriendlyByteBuf ctor
     * or no-arg ctor + fromBytes/read).
     */
    private Object buildSecretPacket(byte[] newData, Object originalPacket) {
        VoiceChatPacketHelper.initReflection();
        ByteBuf rawBuf = null;
        Object friendlyBuf = null;
        try {
            rawBuf = Unpooled.wrappedBuffer(newData);
            Class<?> friendlyBufClass = Class.forName(VoiceChatPacketHelper.findFriendlyByteBufClassName());
            friendlyBuf = friendlyBufClass.getConstructor(ByteBuf.class).newInstance(rawBuf);

            // First, try 1.20.2+ approach using the original packet's payload object
            try {
                Object payload = null;
                for (String methodName : new String[]{"payload", "getPayload"}) {
                    try {
                        java.lang.reflect.Method m = originalPacket.getClass().getMethod(methodName);
                        payload = m.invoke(originalPacket);
                        if (payload != null) break;
                    } catch (NoSuchMethodException ignored) {}
                }

                if (payload != null) {
                    Object newPayload = null;

                    // Approach A: Try a 1-arg FriendlyByteBuf constructor
                    for (java.lang.reflect.Constructor<?> ctor : payload.getClass().getConstructors()) {
                        Class<?>[] params = ctor.getParameterTypes();
                        if (params.length == 1 && params[0].isAssignableFrom(friendlyBufClass)) {
                            newPayload = ctor.newInstance(friendlyBuf);
                            break;
                        }
                    }

                    // Approach B: No-arg constructor + fromBytes(buf) / read(buf)
                    // SVC 1.20.2+ uses this pattern instead of a FriendlyByteBuf ctor
                    if (newPayload == null) {
                        try {
                            java.lang.reflect.Constructor<?> noArgCtor = payload.getClass().getDeclaredConstructor();
                            noArgCtor.setAccessible(true);
                            Object tempPayload = noArgCtor.newInstance();
                            for (String readMethod : new String[]{"fromBytes", "read"}) {
                                try {
                                    // Try with FriendlyByteBuf parameter type
                                    java.lang.reflect.Method m = payload.getClass().getMethod(readMethod, friendlyBufClass);
                                    Object result = m.invoke(tempPayload, friendlyBuf);
                                    // fromBytes returns 'this' or a new instance
                                    newPayload = (result != null) ? result : tempPayload;
                                    break;
                                } catch (NoSuchMethodException ignored) {}
                                try {
                                    // Try with ByteBuf supertype
                                    java.lang.reflect.Method m = payload.getClass().getMethod(readMethod, io.netty.buffer.ByteBuf.class);
                                    Object result = m.invoke(tempPayload, friendlyBuf);
                                    newPayload = (result != null) ? result : tempPayload;
                                    break;
                                } catch (NoSuchMethodException ignored) {}
                            }
                        } catch (NoSuchMethodException ignored) {
                            // No no-arg constructor available
                        }
                    }

                    if (newPayload != null) {
                        for (java.lang.reflect.Constructor<?> ctor : originalPacket.getClass().getConstructors()) {
                            Class<?>[] params = ctor.getParameterTypes();
                            if (params.length == 1 && params[0].isAssignableFrom(newPayload.getClass())) {
                                Object packet = ctor.newInstance(newPayload);
                                rawBuf = null;
                                friendlyBuf = null;
                                return packet;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("1.20.2+ payload reconstruction failed, trying fallback", e);
            }

            // Fallback to pre-1.20.2 approach
            Object rl = VoiceChatPacketHelper.makeResourceLocation("voicechat", "secret");
            Class<?> packetClass = VoiceChatPacketHelper.findS2CPayloadClass();
            for (java.lang.reflect.Constructor<?> ctor : packetClass.getConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 2) {
                    try {
                        Object packet = ctor.newInstance(rl, friendlyBuf);
                        rawBuf = null;
                        friendlyBuf = null;
                        return packet;
                    } catch (Exception ignored) {}
                }
            }
            return null;
        } catch (Exception e) {
            LOGGER.warn("Failed to build SecretPacket", e);
            return null;
        } finally {
            if (friendlyBuf instanceof ByteBuf buf) {
                buf.release();
            } else if (rawBuf != null) {
                rawBuf.release();
            }
        }
    }

    /**
     * Called by {@link VoiceChatRawCodec} when a raw voice data frame is received.
     * On the host side: forward UDP data to the local SVC server.
     * On the client side: forward UDP data to the local SVC client.
     *
     * @param data raw UDP datagram bytes (magic header already stripped)
     */
    public void handleRawVoiceData(byte[] data) {
        if (isServerSide && hostRelay != null) {
            hostRelay.onClientData(data);
        } else if (!isServerSide && clientProxy != null) {
            clientProxy.onServerData(data);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cleanup();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        cleanup();
        super.handlerRemoved(ctx);
    }

    private void cleanup() {
        if (hostRelay != null) {
            hostRelay.close();
            hostRelay = null;
        }
        if (clientProxy != null) {
            clientProxy.close();
            clientProxy = null;
        }
        bridgeActive = false;
    }

    /**
     * Reads the voiceHost string from SVC SecretPacket data.
     * Offset: 16 (UUID) + 4 (port) + 16 (UUID) + 1 (codec) + 4 (mtu) + 8 (dist) + 4 (keepalive) + 1 (groups) = 54 bytes
     * Then: VarInt string length + UTF-8 string
     */
    private String readVoiceHostFromSecretPacket(byte[] data) {
        if (data.length < 55) return null;
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            buf.skipBytes(54); // skip to voiceHost field
            int len = VoiceChatPacketHelper.readVarInt(buf);
            if (len <= 0 || len > buf.readableBytes()) return null;
            byte[] hostBytes = new byte[len];
            buf.readBytes(hostBytes);
            return new String(hostBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        } finally {
            buf.release();
        }
    }
}
