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
public class VoiceChatPacketHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("e4all-voicebridge");
    public static final String SVC_SECRET_CHANNEL = "voicechat:secret";
    private static volatile boolean reflectionInitialized = false;
    private static Class<?> resourceLocationClass;
    private static Constructor<?> resourceLocationConstructor;
    private static Class<?> s2cPayloadClass;
    private static Constructor<?> s2cPayloadConstructor;
    private static Class<?> c2sPayloadClass;
    private static Constructor<?> c2sPayloadConstructor;
    private static Class<?> friendlyByteBufClass;
    private static Constructor<?> friendlyByteBufConstructor;
    private static boolean usePayloadSystem = false;
    static void initReflection() {
        if (reflectionInitialized) return;
        synchronized (VoiceChatPacketHelper.class) {
            if (reflectionInitialized) return;
            try {
                resourceLocationClass = findClass(
                    "net.minecraft.resources.ResourceLocation",
                    "net.minecraft.resources.Identifier",
                    "net.minecraft.util.Identifier",
                    "net.minecraft.class_2960"
                );
                try {
                    resourceLocationConstructor = resourceLocationClass.getConstructor(String.class, String.class);
                } catch (NoSuchMethodException e) {
                    try {
                        resourceLocationClass.getMethod("fromNamespaceAndPath", String.class, String.class);
                        resourceLocationConstructor = null; 
                    } catch (NoSuchMethodException e2) {
                        resourceLocationConstructor = resourceLocationClass.getConstructor(String.class);
                    }
                }
                friendlyByteBufClass = findClass(
                    "net.minecraft.network.FriendlyByteBuf",
                    "net.minecraft.network.PacketByteBuf",
                    "net.minecraft.class_2540"
                );
                friendlyByteBufConstructor = friendlyByteBufClass.getConstructor(ByteBuf.class);
                try {
                    s2cPayloadClass = Class.forName("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket");
                    c2sPayloadClass = Class.forName("net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket");
                    try {
                        Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload");
                        usePayloadSystem = true;
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
                }
            } catch (Exception e) {
                LOGGER.error("Failed to initialize voice chat packet reflection", e);
            }
            reflectionInitialized = true;
        }
    }
    static Class<?> findClass(String... names) throws ClassNotFoundException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = ClassLoader.getSystemClassLoader();
        for (String name : names) {
            try {
                return Class.forName(name, true, cl);
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
    public static final int VOICE_FRAME_MAGIC = 0xE4A11BED;
    public static void sendVoiceData(Channel channel, byte[] data, boolean serverToClient) {
        ByteBuf frame = channel.alloc().buffer(4 + data.length);
        frame.writeInt(VOICE_FRAME_MAGIC);
        frame.writeBytes(data);
        io.netty.channel.ChannelHandlerContext compressCtx = channel.pipeline().context("compress");
        if (compressCtx != null) {
            compressCtx.writeAndFlush(frame);
        } else {
            io.netty.channel.ChannelHandlerContext encoderCtx = channel.pipeline().context("encoder");
            if (encoderCtx != null) {
                encoderCtx.writeAndFlush(frame);
            } else {
                channel.writeAndFlush(frame);
            }
        }
    }
    public static boolean isCustomPayloadPacket(Object msg) {
        if (msg == null) return false;
        initReflection();
        if (s2cPayloadClass != null && s2cPayloadClass.isInstance(msg)) return true;
        if (c2sPayloadClass != null && c2sPayloadClass.isInstance(msg)) return true;
        String name = msg.getClass().getSimpleName();
        return name.contains("CustomPayload");
    }
    public static String getPayloadChannel(Object packet) {
        try {
            for (String methodName : new String[]{"payload", "getPayload"}) {
                try {
                    Method m = packet.getClass().getMethod(methodName);
                    Object payload = m.invoke(packet);
                    if (payload != null) {
                        try {
                            Method typeMethod = payload.getClass().getMethod("type");
                            Object type = typeMethod.invoke(payload);
                            Method idMethod = type.getClass().getMethod("id");
                            return idMethod.invoke(type).toString();
                        } catch (Exception ignored) {}
                        try {
                            Method getId = payload.getClass().getMethod("getId");
                            return getId.invoke(payload).toString();
                        } catch (Exception ignored) {}
                    }
                } catch (NoSuchMethodException ignored) {}
            }
            for (String methodName : new String[]{"getIdentifier", "getName", "getId"}) {
                try {
                    Method m = packet.getClass().getMethod(methodName);
                    Object result = m.invoke(packet);
                    if (result != null && resourceLocationClass != null && resourceLocationClass.isInstance(result)) {
                        return result.toString();
                    }
                } catch (NoSuchMethodException ignored) {}
            }
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
    public static byte[] getPayloadData(Object packet) {
        try {
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
    public static byte[] rewriteSecretPacket(byte[] originalData, int newPort, String newHost) {
        ByteBuf in = null;
        ByteBuf out = null;
        try {
            in = Unpooled.wrappedBuffer(originalData);
            out = Unpooled.buffer(originalData.length + 64);
            out.writeBytes(in, 16);
            in.skipBytes(4); 
            out.writeInt(newPort);
            out.writeBytes(in, 34);
            int origHostLen = readVarInt(in);
            in.skipBytes(origHostLen);
            byte[] hostBytes = newHost.getBytes(StandardCharsets.UTF_8);
            writeVarInt(out, hostBytes.length);
            out.writeBytes(hostBytes);
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
    public static int readSecretPacketPort(byte[] data) {
        if (data.length < 20) return -1;
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            buf.skipBytes(16); 
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
