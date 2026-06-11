package link.e4all;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Reflection-based helper to construct and parse Minecraft custom payload packets
 * across versions (1.18 - 1.21+).
 *
 * Voice chat data is now sent as raw ByteBuf frames with a magic header,
 * bypassing the Minecraft payload system entirely (see {@link VoiceChatRawCodec}).
 *
 * This class still handles intercepting SVC's "voicechat:secret" packets to
 * rewrite the voice host and port for tunneled connections.
 */
public class VoiceChatPacketHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("e4all-voicebridge");

    public static final String SVC_SECRET_CHANNEL = "voicechat:secret";

    // Cached reflection lookups
    private static volatile boolean reflectionInitialized = false;
    private static Class<?> resourceLocationClass;
    private static Constructor<?> resourceLocationConstructor;

    // S2C custom payload packet
    private static Class<?> s2cPayloadClass;
    private static Constructor<?> s2cPayloadConstructor;

    // C2S custom payload packet
    private static Class<?> c2sPayloadClass;
    private static Constructor<?> c2sPayloadConstructor;

    // FriendlyByteBuf
    private static Class<?> friendlyByteBufClass;
    private static Constructor<?> friendlyByteBufConstructor;

    // For 1.20.2+ payload system
    private static boolean usePayloadSystem = false;


    static void initReflection() {
        if (reflectionInitialized) return;
        synchronized (VoiceChatPacketHelper.class) {
            if (reflectionInitialized) return;
            try {
                // ResourceLocation
                resourceLocationClass = findClass(
                    "net.minecraft.resources.ResourceLocation",
                    "net.minecraft.util.Identifier",
                    "net.minecraft.class_2960"
                );
                // Try the 2-arg constructor (namespace, path) or 1-arg (string with colon)
                try {
                    resourceLocationConstructor = resourceLocationClass.getConstructor(String.class, String.class);
                } catch (NoSuchMethodException e) {
                    try {
                        // MC 1.21+ uses factory method
                        resourceLocationClass.getMethod("fromNamespaceAndPath", String.class, String.class);
                        resourceLocationConstructor = null; // Use factory method instead
                    } catch (NoSuchMethodException e2) {
                        resourceLocationConstructor = resourceLocationClass.getConstructor(String.class);
                    }
                }

                // FriendlyByteBuf
                friendlyByteBufClass = findClass(
                    "net.minecraft.network.FriendlyByteBuf",
                    "net.minecraft.network.PacketByteBuf",
                    "net.minecraft.class_2540"
                );
                friendlyByteBufConstructor = friendlyByteBufClass.getConstructor(ByteBuf.class);

                // Try 1.20.2+ common payload classes first
                try {
                    s2cPayloadClass = Class.forName("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket");
                    c2sPayloadClass = Class.forName("net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket");

                    // Check if these use the payload system (CustomPacketPayload)
                    try {
                        Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload");
                        usePayloadSystem = true;
                        // For 1.20.2+ with payload system, try to find legacy-compatible constructors
                        try {
                            s2cPayloadConstructor = s2cPayloadClass.getConstructor(resourceLocationClass, friendlyByteBufClass);
                        } catch (NoSuchMethodException ignored) {
                            s2cPayloadConstructor = null;
                        }
                        try {
                            c2sPayloadConstructor = c2sPayloadClass.getConstructor(resourceLocationClass, friendlyByteBufClass);
                        } catch (NoSuchMethodException ignored) {
                            c2sPayloadConstructor = null;
                        }
                        LOGGER.debug("Using 1.20.2+ payload system for voice chat bridge");
                    } catch (ClassNotFoundException e) {
                        s2cPayloadConstructor = s2cPayloadClass.getConstructor(resourceLocationClass, friendlyByteBufClass);
                        c2sPayloadConstructor = c2sPayloadClass.getConstructor(resourceLocationClass, friendlyByteBufClass);
                    }
                } catch (ClassNotFoundException e) {
                    // Pre-1.20.2 - use game package
                    s2cPayloadClass = findClass(
                        "net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket",
                        "net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket",
                        "net.minecraft.class_2713"
                    );
                    c2sPayloadClass = findClass(
                        "net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket",
                        "net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket",
                        "net.minecraft.class_2817"
                    );
                    s2cPayloadConstructor = s2cPayloadClass.getConstructor(resourceLocationClass, friendlyByteBufClass);
                    c2sPayloadConstructor = c2sPayloadClass.getConstructor(resourceLocationClass, friendlyByteBufClass);
                }

            } catch (Exception e) {
                LOGGER.error("Failed to initialize voice chat packet reflection", e);
            }
            reflectionInitialized = true;
        }
    }

    static Class<?> findClass(String... names) throws ClassNotFoundException {
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {}
        }
        throw new ClassNotFoundException("Could not find any of the specified classes");
    }

    static Object makeResourceLocation(String namespace, String path) throws Exception {
        if (resourceLocationConstructor != null) {
            if (resourceLocationConstructor.getParameterCount() == 2) {
                return resourceLocationConstructor.newInstance(namespace, path);
            } else {
                return resourceLocationConstructor.newInstance(namespace + ":" + path);
            }
        }
        // Factory method (MC 1.21+)
        return resourceLocationClass.getMethod("fromNamespaceAndPath", String.class, String.class)
                .invoke(null, namespace, path);
    }

    static String findFriendlyByteBufClassName() {
        for (String name : new String[]{
            "net.minecraft.network.FriendlyByteBuf",
            "net.minecraft.network.PacketByteBuf",
            "net.minecraft.class_2540"
        }) {
            try {
                Class.forName(name);
                return name;
            } catch (ClassNotFoundException ignored) {}
        }
        return "net.minecraft.network.FriendlyByteBuf";
    }

    static Class<?> findS2CPayloadClass() throws ClassNotFoundException {
        return findClass(
            "net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket",
            "net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket",
            "net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket",
            "net.minecraft.class_2713"
        );
    }

    /**
     * Magic bytes that prefix all e4all voice data frames.
     * Chosen to be unlikely to collide with any Minecraft packet ID or valid data.
     * The receiver (VoiceChatRawCodec) scans for this prefix before the Minecraft
     * decoder sees the data.
     */
    public static final int VOICE_FRAME_MAGIC = 0xE4A11BED;

    /**
     * Sends voice chat data through the Minecraft connection by writing a raw
     * length-prefixed ByteBuf directly through the Netty pipeline, bypassing
     * the Minecraft packet encoder/decoder.
     *
     * This avoids the 1.20.2+ payload registration requirement. The frame is
     * picked up on the other end by {@link VoiceChatRawCodec} which sits after
     * the splitter but before the Minecraft decoder in the pipeline.
     *
     * Frame format (before the VarInt length-prefix added by the "prepender"):
     *   int32  VOICE_FRAME_MAGIC  (4 bytes, 0xE4A11BED)
     *   byte[] voiceData          (remaining bytes)
     *
     * We write via the ChannelHandlerContext of the "encoder" handler so the
     * frame bypasses the encoder (which would try to cast it to a Packet) but
     * still flows through the "compress" and "prepender" handlers below it.
     *
     * @param channel     the Netty channel (Minecraft connection)
     * @param data        raw UDP datagram bytes
     * @param serverToClient unused — framing is direction-agnostic
     */
    public static void sendVoiceData(Channel channel, byte[] data, boolean serverToClient) {
        ByteBuf frame = channel.alloc().buffer(4 + data.length);
        frame.writeInt(VOICE_FRAME_MAGIC);
        frame.writeBytes(data);

        // Write through a context that bypasses both the encoder AND the compressor.
        // On the receiver, the raw codec sits right after the splitter (before decompress),
        // so the frame must arrive uncompressed for the magic header to be recognized.
        //
        // Pipeline outbound order: encoder → compress → prepender → network
        // We write via the "compress" context so it goes: prepender → network
        io.netty.channel.ChannelHandlerContext compressCtx = channel.pipeline().context("compress");
        if (compressCtx != null) {
            compressCtx.writeAndFlush(frame);
        } else {
            // No compressor (e.g. DialtoneChannel, or compression not yet enabled)
            io.netty.channel.ChannelHandlerContext encoderCtx = channel.pipeline().context("encoder");
            if (encoderCtx != null) {
                encoderCtx.writeAndFlush(frame);
            } else {
                // Fallback for non-standard pipelines — write directly on the channel
                channel.writeAndFlush(frame);
            }
        }
    }

    /**
     * Checks if a Minecraft packet object is a custom payload packet.
     */
    public static boolean isCustomPayloadPacket(Object msg) {
        if (msg == null) return false;
        initReflection();
        if (s2cPayloadClass != null && s2cPayloadClass.isInstance(msg)) return true;
        if (c2sPayloadClass != null && c2sPayloadClass.isInstance(msg)) return true;
        // Fallback: check class name
        String name = msg.getClass().getSimpleName();
        return name.contains("CustomPayload");
    }

    /**
     * Extracts the channel identifier string from a custom payload packet.
     * Returns null if unable to extract.
     */
    public static String getPayloadChannel(Object packet) {
        try {
            // Try 1.20.2+ payload approach: packet has a payload() method
            for (String methodName : new String[]{"payload", "getPayload"}) {
                try {
                    Method m = packet.getClass().getMethod(methodName);
                    Object payload = m.invoke(packet);
                    if (payload != null) {
                        // payload.type().id() returns ResourceLocation
                        try {
                            Method typeMethod = payload.getClass().getMethod("type");
                            Object type = typeMethod.invoke(payload);
                            Method idMethod = type.getClass().getMethod("id");
                            return idMethod.invoke(type).toString();
                        } catch (Exception ignored) {}
                        // Try payload.getId()
                        try {
                            Method getId = payload.getClass().getMethod("getId");
                            return getId.invoke(payload).toString();
                        } catch (Exception ignored) {}
                    }
                } catch (NoSuchMethodException ignored) {}
            }

            // Try pre-1.20.2: packet has getIdentifier() or getName()
            for (String methodName : new String[]{"getIdentifier", "getName", "getId"}) {
                try {
                    Method m = packet.getClass().getMethod(methodName);
                    Object result = m.invoke(packet);
                    if (result != null && resourceLocationClass != null && resourceLocationClass.isInstance(result)) {
                        return result.toString();
                    }
                } catch (NoSuchMethodException ignored) {}
            }

            // Try field access - look for ResourceLocation fields
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (resourceLocationClass != null && resourceLocationClass.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object val = f.get(packet);
                    if (val != null) return val.toString();
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to extract channel from payload packet", e);
        }
        return null;
    }

    /**
     * Extracts the raw payload data bytes from a custom payload packet.
     * Returns null if unable to extract.
     */
    public static byte[] getPayloadData(Object packet) {
        try {
            // Try to get the data via methods
            for (String methodName : new String[]{"getData", "data", "getPayload", "payload"}) {
                try {
                    Method m = packet.getClass().getMethod(methodName);
                    Object result = m.invoke(packet);
                    if (result instanceof ByteBuf buf) {
                        byte[] data = new byte[buf.readableBytes()];
                        buf.markReaderIndex();
                        buf.readBytes(data);
                        buf.resetReaderIndex();
                        return data;
                    }
                    // If result is a CustomPacketPayload, try to get data from it
                    if (result != null && !(result instanceof ByteBuf)) {
                        for (String innerMethod : new String[]{"getData", "data", "buf"}) {
                            try {
                                Method im = result.getClass().getMethod(innerMethod);
                                Object innerResult = im.invoke(result);
                                if (innerResult instanceof ByteBuf innerBuf) {
                                    byte[] data = new byte[innerBuf.readableBytes()];
                                    innerBuf.markReaderIndex();
                                    innerBuf.readBytes(data);
                                    innerBuf.resetReaderIndex();
                                    return data;
                                }
                            } catch (NoSuchMethodException ignored) {}
                        }
                        
                        // Fallback: Serialize the payload using its write() method
                        for (String writeMethodName : new String[]{"write", "m_293152_"}) {
                            try {
                                Method writeMethod = result.getClass().getMethod(writeMethodName, friendlyByteBufClass);
                                ByteBuf newRawBuf = Unpooled.buffer();
                                Object newFriendlyBuf = friendlyByteBufConstructor.newInstance(newRawBuf);
                                writeMethod.invoke(result, newFriendlyBuf);
                                byte[] data = new byte[newRawBuf.readableBytes()];
                                newRawBuf.readBytes(data);
                                newRawBuf.release();
                                return data;
                            } catch (NoSuchMethodException ignored) {}
                        }
                    }
                } catch (NoSuchMethodException ignored) {}
            }

            // Try field access
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (ByteBuf.class.isAssignableFrom(f.getType()) ||
                    (friendlyByteBufClass != null && friendlyByteBufClass.isAssignableFrom(f.getType()))) {
                    f.setAccessible(true);
                    ByteBuf buf = (ByteBuf) f.get(packet);
                    if (buf != null) {
                        byte[] data = new byte[buf.readableBytes()];
                        buf.markReaderIndex();
                        buf.readBytes(data);
                        buf.resetReaderIndex();
                        return data;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to extract data from payload packet", e);
        }
        return null;
    }

    /**
     * Reads the SVC SecretPacket data and returns a modified version where
     * serverPort and voiceHost are rewritten to point to a local proxy.
     *
     * SecretPacket binary format (FriendlyByteBuf encoding):
     *   UUID secret       (16 bytes: 2 longs)
     *   int  serverPort   (4 bytes)
     *   UUID playerUUID   (16 bytes: 2 longs)
     *   byte codec        (1 byte)
     *   int  mtuSize      (4 bytes)
     *   double distance   (8 bytes)
     *   int  keepAlive    (4 bytes)
     *   boolean groups    (1 byte)
     *   String voiceHost  (VarInt len + UTF-8)
     *   boolean recording (1 byte)
     *
     * @param originalData the original SecretPacket payload bytes
     * @param newPort      the new port to write
     * @param newHost      the new voiceHost to write
     * @return modified packet bytes, or null on failure
     */
    public static byte[] rewriteSecretPacket(byte[] originalData, int newPort, String newHost) {
        ByteBuf in = null;
        ByteBuf out = null;
        try {
            in = Unpooled.wrappedBuffer(originalData);
            out = Unpooled.buffer(originalData.length + 64);

            // Copy secret UUID (16 bytes)
            out.writeBytes(in, 16);

            // Replace serverPort
            in.skipBytes(4); // skip original port
            out.writeInt(newPort);

            // Copy playerUUID (16) + codec (1) + mtuSize (4) + distance (8) + keepAlive (4) + groups (1) = 34 bytes
            out.writeBytes(in, 34);

            // Skip original voiceHost string (VarInt length + UTF-8 data)
            int origHostLen = readVarInt(in);
            in.skipBytes(origHostLen);

            // Write new voiceHost
            byte[] hostBytes = newHost.getBytes(StandardCharsets.UTF_8);
            writeVarInt(out, hostBytes.length);
            out.writeBytes(hostBytes);

            // Copy remaining (allowRecording boolean + any future fields)
            if (in.readableBytes() > 0) {
                out.writeBytes(in, in.readableBytes());
            }

            byte[] result = new byte[out.readableBytes()];
            out.readBytes(result);
            return result;
        } catch (Exception e) {
            LOGGER.warn("Failed to rewrite SVC SecretPacket", e);
            return null;
        } finally {
            if (out != null) out.release();
            if (in != null) in.release();
        }
    }

    /**
     * Reads the serverPort from SVC SecretPacket data (at byte offset 16).
     */
    public static int readSecretPacketPort(byte[] data) {
        if (data.length < 20) return -1;
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            buf.skipBytes(16); // skip secret UUID
            return buf.readInt();
        } finally {
            buf.release();
        }
    }

    static int readVarInt(ByteBuf buf) {
        int value = 0;
        int shift = 0;
        byte b;
        do {
            b = buf.readByte();
            value |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new RuntimeException("VarInt too big");
        } while ((b & 0x80) != 0);
        return value;
    }

    static void writeVarInt(ByteBuf buf, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            buf.writeByte(value & 0x7F | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}
