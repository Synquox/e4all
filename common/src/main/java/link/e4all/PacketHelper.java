package link.e4all;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import link.e4all.voice.VoiceControlPayload;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class PacketHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("e4all");

    private static final String[] CLIENTBOUND_PACKET_CLASS_NAMES = {
            "net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket",
            "net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket",
            "net.minecraft.class_8708",
            "net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket",
            "net.minecraft.network.protocol.play.ClientboundCustomPayloadPacket",
            "net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket",
            "net.minecraft.class_2658"
    };

    private static final String[] SERVERBOUND_PACKET_CLASS_NAMES = {
            "net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket",
            "net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket",
            "net.minecraft.class_8709",
            "net.minecraft.network.protocol.play.ServerboundCustomPayloadPacket",
            "net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket",
            "net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket",
            "net.minecraft.class_2817"
    };

    private static final String[] PAYLOAD_INTERFACE_NAMES = {
            "net.minecraft.network.protocol.common.custom.CustomPacketPayload",
            "net.minecraft.network.packet.CustomPayload",
            "net.minecraft.class_8710"
    };

    private static final String[] TYPE_CLASS_NAMES = {
            "net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type",
            "net.minecraft.network.protocol.common.custom.CustomPacketPayload$Id",
            "net.minecraft.class_8710$class_9154"
    };

    private static final String[] DISCARDED_PAYLOAD_CLASS_NAMES = {
            "net.minecraft.network.protocol.common.custom.DiscardedPayload",
            "net.minecraft.network.packet.s2c.common.custom.DiscardedCustomPayload",
            "net.minecraft.network.protocol.common.custom.DiscardedCustomPayload",
            "net.minecraft.class_9006"
    };

    private PacketHelper() {}

    private static Class<?> findClass(String[] candidateNames) {
        ClassLoader[] loaders = ResourceLocReflector.collectClassLoaders();
        for (String fqn : candidateNames) {
            for (ClassLoader cl : loaders) {
                try {
                    Class<?> cls = Class.forName(fqn, false, cl);
                    if (cls != null) return cls;
                } catch (ClassNotFoundException | LinkageError ignored) {}
            }
        }
        return null;
    }

    public static Object extractPayloadId(Object payload) {
        if (payload == null) return null;
        Class<?> cls = payload.getClass();
        for (Method m : cls.getMethods()) {
            if (m.getParameterCount() != 0) continue;
            Class<?> ret = m.getReturnType();
            if (isResourceLoc(ret)) {
                try {
                    return m.invoke(payload);
                } catch (Throwable ignored) {}
            }
        }
        for (Method m : cls.getMethods()) {
            if (m.getParameterCount() != 0) continue;
            Class<?> ret = m.getReturnType();
            if (ret == Object.class || ret.isPrimitive() || ret == String.class
                    || ret == void.class || ret == Class.class) continue;
            try {
                Object typeObj = m.invoke(payload);
                if (typeObj == null) continue;
                for (Method tm : typeObj.getClass().getMethods()) {
                    if (tm.getParameterCount() != 0) continue;
                    Class<?> tret = tm.getReturnType();
                    if (isResourceLoc(tret)) {
                        try {
                            return tm.invoke(typeObj);
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static final String[] REGISTRY_BYTE_BUF_CLASS_NAMES = {
            "net.minecraft.network.RegistryFriendlyByteBuf",
            "net.minecraft.network.packet.RegistryFriendlyByteBuf",
            "net.minecraft.class_9129"
    };

    private static final String[] REGISTRY_ACCESS_CLASS_NAMES = {
            "net.minecraft.core.RegistryAccess",
            "net.minecraft.class_5455"
    };

    private static Object createTestByteBuf(ByteBuf rawBuf) {
        Class<?> regCls = findClass(REGISTRY_BYTE_BUF_CLASS_NAMES);
        if (regCls != null) {
            Object regAccess = null;
            Class<?> regAccessCls = findClass(REGISTRY_ACCESS_CLASS_NAMES);
            if (regAccessCls != null) {
                try {
                    java.lang.reflect.Field f = regAccessCls.getDeclaredField("EMPTY");
                    f.setAccessible(true);
                    regAccess = f.get(null);
                } catch (Throwable ignored) {}
                if (regAccess == null) {
                    try {
                        java.lang.reflect.Field f = regAccessCls.getDeclaredField("field_25114");
                        f.setAccessible(true);
                        regAccess = f.get(null);
                    } catch (Throwable ignored) {}
                }
            }

            for (Constructor<?> ctor : regCls.getDeclaredConstructors()) {
                try {
                    ctor.setAccessible(true);
                    Class<?>[] p = ctor.getParameterTypes();
                    if (p.length == 2 && ByteBuf.class.isAssignableFrom(p[0])) {
                        if (regAccess != null && p[1].isAssignableFrom(regAccess.getClass())) {
                            return ctor.newInstance(rawBuf, regAccess);
                        } else {
                            return ctor.newInstance(rawBuf, null);
                        }
                    } else if (p.length == 1 && ByteBuf.class.isAssignableFrom(p[0])) {
                        return ctor.newInstance(rawBuf);
                    }
                } catch (Throwable ignored) {}
            }
        }
        return new FriendlyByteBuf(rawBuf);
    }

    public static Object createCustomPayloadType(Object channel) {
        if (channel == null) return null;
        if (VoiceControlPayload.isOwnChannel(channel)) {
            Object registered = VoiceControlPayload.getRegisteredType();
            if (registered != null) return registered;
        }
        Class<?> payloadClass = findClass(PAYLOAD_INTERFACE_NAMES);
        if (payloadClass == null) return null;
        Class<?> typeClass = findClass(TYPE_CLASS_NAMES);
        if (typeClass == null) {
            for (Class<?> inner : payloadClass.getDeclaredClasses()) {
                String simpleName = inner.getSimpleName();
                if ("Type".equals(simpleName) || "Id".equals(simpleName) || "class_9154".equals(simpleName)) {
                    typeClass = inner;
                    break;
                }
                for (Constructor<?> c : inner.getDeclaredConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 1 && isResourceLoc(p[0])) {
                        typeClass = inner;
                        break;
                    }
                }
                if (typeClass != null) break;
            }
        }
        if (typeClass != null) {
            for (Constructor<?> c : typeClass.getDeclaredConstructors()) {
                try {
                    c.setAccessible(true);
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 1 && isResourceLoc(p[0])) {
                        return c.newInstance(channel);
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    public static Packet<?> createPacketFromPayload(boolean clientbound, Object payload) {
        if (payload == null) return null;
        String[] pktNames = clientbound ? CLIENTBOUND_PACKET_CLASS_NAMES : SERVERBOUND_PACKET_CLASS_NAMES;
        Class<?> pktCls = findClass(pktNames);
        if (pktCls == null) return null;

        for (Constructor<?> ctor : pktCls.getDeclaredConstructors()) {
            try {
                ctor.setAccessible(true);
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 1 && p[0].isAssignableFrom(payload.getClass())) {
                    return (Packet<?>) ctor.newInstance(payload);
                }
            } catch (Throwable ignored) {}
        }
        Class<?> plaCls = findClass(PAYLOAD_INTERFACE_NAMES);
        if (plaCls != null) {
            try {
                Constructor<?> ctor = pktCls.getDeclaredConstructor(plaCls);
                ctor.setAccessible(true);
                return (Packet<?>) ctor.newInstance(payload);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static boolean testEncode(Packet<?> pkt) {
        if (pkt == null) return false;
        ByteBuf rawBuf = Unpooled.buffer();
        boolean tested = false;
        try {
            Object testBuf = createTestByteBuf(rawBuf);
            if (testBuf == null) {
                testBuf = new FriendlyByteBuf(rawBuf);
            }

            Class<?> cls = pkt.getClass();

            // try static streamcodec on packet
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    try {
                        f.setAccessible(true);
                        Object codecObj = f.get(null);
                        if (codecObj != null) {
                            for (Method cm : codecObj.getClass().getMethods()) {
                                if (cm.getParameterCount() == 2 && cm.getName().equals("encode")) {
                                    try {
                                        cm.setAccessible(true);
                                        cm.invoke(codecObj, testBuf, pkt);
                                        tested = true;
                                        return true;
                                    } catch (java.lang.reflect.InvocationTargetException ite) {
                                        LOGGER.debug("e4all: static codec testEncode failed: {}", ite.getCause());
                                        return false;
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }

            // try packet type codec
            for (Method m : cls.getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() != void.class && m.getReturnType() != String.class) {
                    try {
                        Object packetType = m.invoke(pkt);
                        if (packetType != null && packetType != pkt) {
                            for (Method tm : packetType.getClass().getMethods()) {
                                if (tm.getParameterCount() == 0 && tm.getReturnType() != void.class) {
                                    Object codec = tm.invoke(packetType);
                                    if (codec != null && codec != packetType) {
                                        for (Method cm : codec.getClass().getMethods()) {
                                            if (cm.getParameterCount() == 2) {
                                                try {
                                                    cm.setAccessible(true);
                                                    cm.invoke(codec, testBuf, pkt);
                                                    tested = true;
                                                    return true;
                                                } catch (java.lang.reflect.InvocationTargetException ite) {
                                                    LOGGER.debug("e4all: packetType codec testEncode failed: {}", ite.getCause());
                                                    return false;
                                                } catch (Throwable ignored) {}
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }

            // try legacy write method
            for (Method m : cls.getMethods()) {
                if (m.getParameterCount() == 1) {
                    try {
                        m.setAccessible(true);
                        m.invoke(pkt, testBuf);
                        tested = true;
                        return true;
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        LOGGER.debug("e4all: legacy write testEncode failed: {}", ite.getCause());
                        return false;
                    } catch (Throwable ignored) {}
                }
            }

            if (findClass(PAYLOAD_INTERFACE_NAMES) != null || findClass(DISCARDED_PAYLOAD_CLASS_NAMES) != null) {
                return tested;
            }

            return true;
        } finally {
            rawBuf.release();
        }
    }

    public static void sendClientbound(ServerPlayer player, Object channel, byte[] data) {
        try {
            Packet<?> pkt = makePacket(true, channel, data);
            if (pkt != null) {
                if (!testEncode(pkt)) {
                    LOGGER.debug("e4all: payload {} cannot be encoded by server packet codec; skipping", channel);
                    if (!VoiceControlPayload.isOwnChannel(channel)) return;
                }
                player.connection.send(pkt);
            }
        } catch (Throwable t) {
            LOGGER.warn("e4all: failed to send clientbound payload {}", channel, t);
        }
    }

    public static void sendServerbound(Connection connection, Object channel, byte[] data) {
        try {
            Packet<?> pkt = makePacket(false, channel, data);
            if (pkt != null) {
                if (!testEncode(pkt)) {
                    LOGGER.debug("e4all: payload {} cannot be encoded by client packet codec; skipping", channel);
                    if (!VoiceControlPayload.isOwnChannel(channel)) return;
                }
                connection.send(pkt);
            }
        } catch (Throwable t) {
            LOGGER.warn("e4all: failed to send serverbound payload {}", channel, t);
        }
    }

    private static Object makeDiscardedPayload(Object channel, byte[] data) {
        Class<?> cls = findClass(DISCARDED_PAYLOAD_CLASS_NAMES);
        if (cls == null) return null;

        byte[] payloadData = data != null ? data : new byte[0];

        for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
            try {
                ctor.setAccessible(true);
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 2 && isResourceLoc(p[0])) {
                    if (p[1] == byte[].class) {
                        return ctor.newInstance(channel, payloadData);
                    } else if (ByteBuf.class.isAssignableFrom(p[1]) || FriendlyByteBuf.class.isAssignableFrom(p[1])) {
                        return ctor.newInstance(channel, Unpooled.wrappedBuffer(payloadData));
                    }
                } else if (p.length == 1 && isResourceLoc(p[0]) && payloadData.length == 0) {
                    return ctor.newInstance(channel);
                }
            } catch (Throwable t) {
                LOGGER.debug("Failed constructing {} with ctor {}", cls.getName(), ctor, t);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Packet<?> makePacket(boolean clientbound, Object channel, byte[] data) {
        String[] pktNames = clientbound ? CLIENTBOUND_PACKET_CLASS_NAMES : SERVERBOUND_PACKET_CLASS_NAMES;
        Class<?> pktCls = findClass(pktNames);
        if (pktCls == null) {
            LOGGER.warn("e4all: could not resolve custom payload packet class");
            return null;
        }

        Class<?> plaCls = findClass(PAYLOAD_INTERFACE_NAMES);
        byte[] payloadData = data != null ? data : new byte[0];

        // try proxy payload first
        if (plaCls != null) {
            try {
                Object payload = makePayload(plaCls, channel, payloadData);
                if (payload != null) {
                    for (Constructor<?> ctor : pktCls.getDeclaredConstructors()) {
                        try {
                            ctor.setAccessible(true);
                            Class<?>[] p = ctor.getParameterTypes();
                            if (p.length == 1 && p[0].isAssignableFrom(plaCls)) {
                                return (Packet<?>) ctor.newInstance(payload);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("e4all: 1.20.2+ proxy payload construction failed for {}", channel, t);
            }
        }

        // try discarded payload (only for non-own channels, discarded payloads are rejected C2S)
        if (!VoiceControlPayload.isOwnChannel(channel)) {
            Object discarded = makeDiscardedPayload(channel, payloadData);
            if (discarded != null) {
                for (Constructor<?> ctor : pktCls.getDeclaredConstructors()) {
                    try {
                        ctor.setAccessible(true);
                        Class<?>[] p = ctor.getParameterTypes();
                        if (p.length == 1 && p[0].isAssignableFrom(discarded.getClass())) {
                            return (Packet<?>) ctor.newInstance(discarded);
                        }
                    } catch (Throwable t) {
                        LOGGER.debug("Failed constructing packet with discarded payload", t);
                    }
                }
                if (plaCls != null) {
                    try {
                        Constructor<?> ctor = pktCls.getDeclaredConstructor(plaCls);
                        ctor.setAccessible(true);
                        return (Packet<?>) ctor.newInstance(discarded);
                    } catch (Throwable ignored) {}
                }
            }
        }

        // legacy 1.18 - 1.20.1 constructor fallback
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(payloadData));
        try {
            for (Constructor<?> ctor : pktCls.getDeclaredConstructors()) {
                try {
                    ctor.setAccessible(true);
                    Class<?>[] p = ctor.getParameterTypes();
                    if (p.length == 2 && isResourceLoc(p[0]) && FriendlyByteBuf.class.isAssignableFrom(p[1])) {
                        return (Packet<?>) ctor.newInstance(channel, buf);
                    }
                } catch (Throwable t) {
                    LOGGER.debug("Failed constructing legacy packet", t);
                }
            }
        } finally {
            buf.release();
        }

        return null;
    }

    private static boolean isResourceLoc(Class<?> candidate) {
        if (candidate == null) return false;
        Class<?> resourceLocClass = ResourceLocReflector.classOrNull();
        if (resourceLocClass != null && resourceLocClass.isAssignableFrom(candidate)) return true;
        if (candidate.getName().equals("net.minecraft.resources.ResourceLocation")
                || candidate.getName().equals("net.minecraft.resources.Identifier")
                || candidate.getName().equals("net.minecraft.util.Identifier")
                || candidate.getName().equals("net.minecraft.class_2960")) {
            return true;
        }
        return ResourceLocReflector.isAssignableFrom(candidate);
    }

    private static Object makePayload(Class<?> payloadClass, Object channel, byte[] data)
            throws Throwable {
        if (VoiceControlPayload.isOwnChannel(channel)) {
            Object vcPayload = VoiceControlPayload.createPayload(data);
            if (vcPayload != null) return vcPayload;
        }

        Object typeInstance = createCustomPayloadType(channel);
        Class<?> typeClass = typeInstance != null ? typeInstance.getClass() : null;

        final Object finalTypeInstance = typeInstance;
        final Class<?> finalTypeClass = typeClass;
        final Object   finalChannel = channel;
        final byte[]   finalData = data;
        final Class<?> resourceLocClass = ResourceLocReflector.classOrNull();

        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == RawPayload.class) {
                if (method.getName().equals("e4all$data")) return finalData;
                return finalChannel;
            }

            Class<?> ret = method.getReturnType();
            Class<?>[] params = method.getParameterTypes();

            if (finalTypeInstance != null && (ret.isInstance(finalTypeInstance) || (finalTypeClass != null && finalTypeClass.isAssignableFrom(ret))) && params.length == 0)
                return finalTypeInstance;

            if (params.length == 0 && ("type".equals(method.getName()) || "id".equals(method.getName()) || "method_56479".equals(method.getName())))
                return finalTypeInstance;

            if (resourceLocClass != null && resourceLocClass.isAssignableFrom(ret) && params.length == 0)
                return finalChannel;

            if (resourceLocClass == null && ret.getName().startsWith("net.minecraft.resources.") && params.length == 0) {
                return finalChannel;
            }

            if (ret == void.class && params.length == 1 && FriendlyByteBuf.class.isAssignableFrom(params[0])) {
                if (finalData.length > 0 && args != null && args.length > 0)
                    ((FriendlyByteBuf) args[0]).writeBytes(finalData);
                return null;
            }

            if (ret == boolean.class && params.length == 1 && params[0] == Object.class)
                return proxy == args[0];
            if (ret == int.class && params.length == 0)
                return System.identityHashCode(proxy);
            if (ret == String.class && params.length == 0)
                return "e4all.Payload[" + finalChannel + "]";

            return null;
        };

        return Proxy.newProxyInstance(
                payloadClass.getClassLoader(),
                new Class<?>[]{payloadClass, RawPayload.class},
                handler);
    }

    // id setter changes name between versions
    public static void writeIdAndBytes(FriendlyByteBuf buf, Object id, byte[] data) {
        boolean written = false;
        String[] candidateNames = {"writeIdentifier", "writeResourceLocation", "method_10798"};
        for (String name : candidateNames) {
            for (Method m : FriendlyByteBuf.class.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] != Object.class
                        && isResourceLoc(m.getParameterTypes()[0])) {
                    try {
                        m.invoke(buf, id);
                        written = true;
                        break;
                    } catch (Throwable ignored) {}
                }
            }
            if (written) break;
        }

        if (!written) {
            for (Method m : FriendlyByteBuf.class.getMethods()) {
                if (m.getParameterCount() != 1) continue;
                Class<?> param = m.getParameterTypes()[0];
                if (param == Object.class || !isResourceLoc(param)) continue;
                if (!param.isInstance(id)) continue;
                Class<?> ret = m.getReturnType();
                if (ret != void.class && !ret.isAssignableFrom(FriendlyByteBuf.class)) continue;
                try {
                    m.invoke(buf, id);
                    written = true;
                    break;
                } catch (Throwable ignored) {}
            }
        }

        if (!written && id != null) {
            try {
                buf.writeUtf(id.toString());
                written = true;
            } catch (Throwable t) {
                LOGGER.warn("e4all: failed to write payload ID {}", id, t);
            }
        }

        if (data != null && data.length > 0) {
            buf.writeBytes(data);
        }
    }
}
