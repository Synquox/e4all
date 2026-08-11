package link.e4all;

import io.netty.buffer.Unpooled;
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

    private PacketHelper() {}

    public static Object extractPayloadId(Object payload) {
        if (payload == null) return null;
        Class<?> resourceLocClass = ResourceLocReflector.classOrNull();
        Class<?> cls = payload.getClass();
        for (Method m : cls.getMethods()) {
            if (m.getParameterCount() != 0) continue;
            Class<?> ret = m.getReturnType();
            if (resourceLocClass != null && resourceLocClass.isAssignableFrom(ret)) {
                try {
                    return m.invoke(payload);
                } catch (Throwable ignored) {}
            }
            if (resourceLocClass == null && ret.getName().startsWith("net.minecraft.resources.")) {
                try {
                    Object candidate = m.invoke(payload);
                    if (candidate != null && ResourceLocReflector.isInstance(candidate)) {
                        return candidate;
                    }
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
                    if (resourceLocClass != null && resourceLocClass.isAssignableFrom(tret)) {
                        try {
                            return tm.invoke(typeObj);
                        } catch (Throwable ignored) {}
                    }
                    if (resourceLocClass == null && tret.getName().startsWith("net.minecraft.resources.")) {
                        try {
                            Object candidate = tm.invoke(typeObj);
                            if (candidate != null && ResourceLocReflector.isInstance(candidate)) {
                                return candidate;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static void sendClientbound(ServerPlayer player, Object channel, byte[] data) {
        try {
            Packet<?> pkt = makePacket(true, channel, data);
            if (pkt != null) player.connection.send(pkt);
        } catch (Throwable t) {
            LOGGER.warn("e4all: failed to send clientbound payload {}", channel, t);
        }
    }

    public static void sendServerbound(Connection connection, Object channel) {
        try {
            Packet<?> pkt = makePacket(false, channel, new byte[0]);
            if (pkt != null) connection.send(pkt);
        } catch (Throwable t) {
            LOGGER.warn("e4all: failed to send serverbound payload {}", channel, t);
        }
    }

    @SuppressWarnings("unchecked")
    private static Packet<?> makePacket(boolean clientbound, Object channel, byte[] data) {
        String newPktClass = clientbound
                ? "net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket"
                : "net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket";
        String payloadIface = "net.minecraft.network.protocol.common.custom.CustomPacketPayload";

        try {
            Class<?> pktCls  = Class.forName(newPktClass);
            Class<?> plaCls  = Class.forName(payloadIface);
            Object   payload = makePayload(plaCls, channel, data);
            Constructor<?> ctor = pktCls.getConstructor(plaCls);
            return (Packet<?>) ctor.newInstance(payload);
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) {
            LOGGER.warn("e4all: 1.20.2+ packet construction failed for {}", channel, t);
        }

        String[] legacyNames = clientbound
                ? new String[]{
                    "net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket",
                    "net.minecraft.network.protocol.play.ClientboundCustomPayloadPacket"}
                : new String[]{
                    "net.minecraft.network.protocol.play.ServerboundCustomPayloadPacket",
                    "net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket"};

        Class<?> resourceLocClass = ResourceLocReflector.classOrNull();
        for (String cls : legacyNames) {
            try {
                Class<?> pktCls = Class.forName(cls);
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
                try {
                    for (Constructor<?> ctor : pktCls.getConstructors()) {
                        Class<?>[] p = ctor.getParameterTypes();
                        if (p.length == 2
                                && isResourceLoc(p[0], resourceLocClass)
                                && FriendlyByteBuf.class.isAssignableFrom(p[1])) {
                            return (Packet<?>) ctor.newInstance(channel, buf);
                        }
                    }
                } finally {
                    buf.release();
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                LOGGER.warn("e4all: legacy packet construction failed for {}", channel, t);
            }
        }

        return null;
    }

    private static boolean isResourceLoc(Class<?> candidate, Class<?> resourceLocClass) {
        if (resourceLocClass != null) return resourceLocClass.isAssignableFrom(candidate);
        return candidate != null && candidate.getName().startsWith("net.minecraft.resources.")
                && ResourceLocReflector.isAssignableFrom(candidate);
    }

    private static Object makePayload(Class<?> payloadClass, Object channel, byte[] data)
            throws Throwable {
        Class<?> typeClass = null;
        for (Class<?> inner : payloadClass.getDeclaredClasses()) {
            String simpleName = inner.getSimpleName();
            if ("Type".equals(simpleName) || "Id".equals(simpleName)) {
                typeClass = inner;
                break;
            }
        }

        Method typeMethod = null;
        if (typeClass != null) {
            for (Method m : payloadClass.getDeclaredMethods()) {
                if (m.getParameterCount() == 0
                        && typeClass.isAssignableFrom(m.getReturnType())) {
                    typeMethod = m;
                    break;
                }
            }
        }

        final Object typeInstance;
        if (typeMethod != null && typeClass != null) {
            Class<?> resourceLocClass = ResourceLocReflector.classOrThrow();
            Constructor<?> typeCtor = null;
            for (Constructor<?> c : typeClass.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 1 && resourceLocClass.isAssignableFrom(p[0])) {
                    typeCtor = c;
                    break;
                }
            }
            if (typeCtor == null) {
                for (Constructor<?> c : typeClass.getDeclaredConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 1 && p[0].isInstance(channel)) {
                        typeCtor = c;
                        break;
                    }
                }
            }
            if (typeCtor != null) {
                typeCtor.setAccessible(true);
                typeInstance = typeCtor.newInstance(channel);
            } else {
                typeInstance = null;
            }
        } else {
            typeInstance = null;
        }

        final Method   finalTypeMethod = typeMethod;
        final Class<?> finalTypeClass = typeClass;
        final Object   finalChannel = channel;
        final byte[]   finalData = data;
        final Class<?> resourceLocClass = ResourceLocReflector.classOrNull();

        InvocationHandler handler = (proxy, method, args) -> {
            Class<?> ret = method.getReturnType();
            Class<?>[] params = method.getParameterTypes();

            if (finalTypeMethod != null && method.equals(finalTypeMethod)) return typeInstance;

            if (finalTypeClass != null && finalTypeClass.isAssignableFrom(ret) && params.length == 0)
                return typeInstance;

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
                new Class<?>[]{payloadClass},
                handler);
    }
}
