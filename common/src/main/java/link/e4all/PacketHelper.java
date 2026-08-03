package link.e4all;

import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
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

    public static ResourceLocation extractPayloadId(Object payload) {
        if (payload == null) return null;
        Class<?> cls = payload.getClass();
        for (Method m : cls.getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (ResourceLocation.class.isAssignableFrom(m.getReturnType())) {
                try {
                    return (ResourceLocation) m.invoke(payload);
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
                    if (tm.getParameterCount() == 0
                            && ResourceLocation.class.isAssignableFrom(tm.getReturnType())) {
                        return (ResourceLocation) tm.invoke(typeObj);
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static void sendClientbound(ServerPlayer player, ResourceLocation channel, byte[] data) {
        try {
            Packet<?> pkt = makePacket(true, channel, data);
            if (pkt != null) player.connection.send(pkt);
        } catch (Throwable t) {
            LOGGER.warn("e4all: failed to send clientbound payload {}", channel, t);
        }
    }

    public static void sendServerbound(Connection connection, ResourceLocation channel) {
        try {
            Packet<?> pkt = makePacket(false, channel, new byte[0]);
            if (pkt != null) connection.send(pkt);
        } catch (Throwable t) {
            LOGGER.warn("e4all: failed to send serverbound payload {}", channel, t);
        }
    }

    @SuppressWarnings("unchecked")
    private static Packet<?> makePacket(boolean clientbound, ResourceLocation channel, byte[] data) {
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

        for (String cls : legacyNames) {
            try {
                Class<?> pktCls = Class.forName(cls);
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
                try {
                    for (Constructor<?> ctor : pktCls.getConstructors()) {
                        Class<?>[] p = ctor.getParameterTypes();
                        if (p.length == 2
                                && ResourceLocation.class.isAssignableFrom(p[0])
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

    private static Object makePayload(Class<?> payloadClass, ResourceLocation channel, byte[] data)
            throws Throwable {
        Class<?> typeClass = null;
        for (Class<?> inner : payloadClass.getDeclaredClasses()) {
            if ("Type".equals(inner.getSimpleName())) {
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
            Constructor<?> typeCtor = typeClass.getDeclaredConstructor(ResourceLocation.class);
            typeCtor.setAccessible(true);
            typeInstance = typeCtor.newInstance(channel);
        } else {
            typeInstance = null;
        }

        final Method   finalTypeMethod = typeMethod;
        final Class<?> finalTypeClass = typeClass;
        final ResourceLocation finalChannel = channel;
        final byte[]   finalData = data;

        InvocationHandler handler = (proxy, method, args) -> {
            Class<?> ret = method.getReturnType();
            Class<?>[] params = method.getParameterTypes();

            if (finalTypeMethod != null && method.equals(finalTypeMethod)) return typeInstance;

            if (finalTypeClass != null && finalTypeClass.isAssignableFrom(ret) && params.length == 0)
                return typeInstance;

            if (ResourceLocation.class.isAssignableFrom(ret) && params.length == 0)
                return finalChannel;

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
