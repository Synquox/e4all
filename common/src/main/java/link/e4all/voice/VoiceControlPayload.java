package link.e4all.voice;

import io.netty.buffer.ByteBuf;
import link.e4all.E4allClient;
import link.e4all.RawPayload;
import link.e4all.ResourceLocReflector;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentLinkedQueue;

// Reflective payload wrapper for e4all:voice
public final class VoiceControlPayload {
    public static final ThreadLocal<byte[]> PENDING = new ThreadLocal<>();
    private static final ConcurrentLinkedQueue<byte[]> PENDING_SERVER_QUEUE = new ConcurrentLinkedQueue<>();

    private VoiceControlPayload() {}

    private static final String[] PAYLOAD_INTERFACE_NAMES = {
            "net.minecraft.network.protocol.common.custom.CustomPacketPayload",
            "net.minecraft.class_8710"
    };

    private static final String[] TYPE_CLASS_NAMES = {
            "net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type",
            "net.minecraft.network.protocol.common.custom.CustomPacketPayload$Id",
            "net.minecraft.class_8710$class_9154"
    };

    // registering e4all:voice with vanilla's payload dispatch. without it the
    // fallback codec drops unknown-channel bodies and the next packet desyncs,
    // which kicks the guest right after the voice handshake
    private static volatile Object registeredType;

    public static Object getRegisteredType() {
        return registeredType;
    }

    public static Class<?> getPayloadInterface() {
        ClassLoader cl = VoiceControlPayload.class.getClassLoader();
        for (String name : PAYLOAD_INTERFACE_NAMES) {
            try {
                return Class.forName(name, false, cl);
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    public static Class<?> getTypeClass(Class<?> payloadIf) {
        ClassLoader cl = VoiceControlPayload.class.getClassLoader();
        for (String name : TYPE_CLASS_NAMES) {
            try {
                return Class.forName(name, false, cl);
            } catch (ClassNotFoundException ignored) {}
        }
        if (payloadIf != null) {
            for (Class<?> inner : payloadIf.getDeclaredClasses()) {
                String simpleName = inner.getSimpleName();
                if ("Type".equals(simpleName) || "Id".equals(simpleName) || "class_9154".equals(simpleName)) {
                    return inner;
                }
                for (Constructor<?> c : inner.getDeclaredConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 1 && ResourceLocReflector.isAssignableFrom(p[0])) {
                        return inner;
                    }
                }
            }
        }
        return null;
    }

    public static Object createPayload(byte[] data) {
        Class<?> payloadIf = getPayloadInterface();
        if (payloadIf == null) return null;
        ClassLoader cl = payloadIf.getClassLoader();
        final byte[] finalData = data != null ? data : new byte[0];
        final Object type = registeredType;
        final Object channel = channel();

        return Proxy.newProxyInstance(cl, new Class<?>[]{payloadIf, RawPayload.class}, (p, m, a) -> {
            if (m.getDeclaringClass() == Object.class) {
                if ("hashCode".equals(m.getName())) return System.identityHashCode(p);
                if ("equals".equals(m.getName())) return a != null && a.length == 1 && p == a[0];
                return "VoiceControlPayload[" + (channel != null ? channel : "voice") + "]";
            }
            if (m.getDeclaringClass() == RawPayload.class) {
                if ("e4all$data".equals(m.getName())) return finalData;
                if ("e4all$channel".equals(m.getName())) return channel;
                return null;
            }

            int pCount = m.getParameterCount();
            Class<?> ret = m.getReturnType();

            // CustomPacketPayload.type() / id() / method_56479()
            if (pCount == 0 && type != null && ret.isInstance(type)) {
                return type;
            }
            if (pCount == 0 && ("type".equals(m.getName()) || "id".equals(m.getName())
                    || "getType".equals(m.getName()) || "getId".equals(m.getName())
                    || "method_56479".equals(m.getName()))) {
                return type;
            }

            // Older ResourceLocation id()
            if (pCount == 0 && channel != null && ret.isInstance(channel)) {
                return channel;
            }

            // write(FriendlyByteBuf)
            if (pCount == 1 && (ret == void.class || ret.isAssignableFrom(payloadIf))) {
                if (a != null && a.length == 1 && a[0] instanceof FriendlyByteBuf buf) {
                    if (finalData.length > 0) {
                        buf.writeBytes(finalData);
                    }
                    return ret == void.class ? null : p;
                }
            }

            return null;
        });
    }

    public static void registerFabric() {
        try {
            ClassLoader cl = VoiceControlPayload.class.getClassLoader();
            Class<?> payloadIf = getPayloadInterface();
            if (payloadIf == null) return;
            Class<?> typeCls = getTypeClass(payloadIf);
            if (typeCls == null) return;

            Class<?> encIf = findClass(cl, "net.minecraft.network.codec.StreamMemberEncoder", "net.minecraft.class_9142");
            Class<?> decIf = findClass(cl, "net.minecraft.network.codec.StreamDecoder", "net.minecraft.class_9141");
            Class<?> codecIf = findClass(cl, "net.minecraft.network.codec.StreamCodec", "net.minecraft.class_9139");
            Class<?> registryCls = findClass(cl, "net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry");

            if (encIf == null || decIf == null || codecIf == null || registryCls == null) {
                E4allClient.LOGGER.debug("e4all: payload classes missing for fabric registration");
                return;
            }

            Object channel = channel();
            Object typeTmp = null;
            if (typeCls != null) {
                for (Constructor<?> ctor : typeCls.getDeclaredConstructors()) {
                    try {
                        ctor.setAccessible(true);
                        Class<?>[] p = ctor.getParameterTypes();
                        if (p.length == 1 && ((channel != null && p[0].isInstance(channel)) || ResourceLocReflector.isAssignableFrom(p[0]))) {
                            typeTmp = ctor.newInstance(channel);
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            if (typeTmp == null) {
                for (Method m : payloadIf.getMethods()) {
                    if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1
                            && (m.getReturnType().equals(typeCls) || (typeCls != null && typeCls.isAssignableFrom(m.getReturnType())))) {
                        try {
                            if (m.getParameterTypes()[0].equals(String.class)) {
                                typeTmp = m.invoke(null, "e4all:voice");
                                break;
                            } else if (channel != null && m.getParameterTypes()[0].isInstance(channel)) {
                                typeTmp = m.invoke(null, channel);
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
            final Object type = typeTmp;
            if (type == null) {
                E4allClient.LOGGER.warn("e4all: could not construct CustomPacketPayload.Type");
                return;
            }
            registeredType = type;

            Object encoder = Proxy.newProxyInstance(cl, new Class<?>[]{encIf}, (p, m, a) -> {
                if (m.getDeclaringClass() == Object.class) return null;
                // 26.x flipped the StreamMemberEncoder args (value, buf), older are (buf, value)
                if (a == null || a.length != 2) return null;
                byte[] data = null;
                FriendlyByteBuf buf = null;
                for (Object arg : a) {
                    if (arg instanceof RawPayload raw) data = raw.e4all$data();
                    else if (arg instanceof FriendlyByteBuf b) buf = b;
                }
                if (data != null && buf != null && data.length > 0) {
                    buf.writeBytes(data);
                }
                return null;
            });

            Object decoder = Proxy.newProxyInstance(cl, new Class<?>[]{decIf}, (p, m, a) -> {
                if (m.getDeclaringClass() == Object.class) return null;
                if (a == null || a.length != 1 || !(a[0] instanceof FriendlyByteBuf buf)) return null;
                byte[] data = readRemaining(buf);
                PENDING.set(data);
                return createPayload(data);
            });

            Method codecMethod = null;
            for (Method m : payloadIf.getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 2) {
                    Class<?>[] p = m.getParameterTypes();
                    if ((p[0].isAssignableFrom(encIf) || encIf.isAssignableFrom(p[0]))
                            && (p[1].isAssignableFrom(decIf) || decIf.isAssignableFrom(p[1]))) {
                        codecMethod = m;
                        break;
                    }
                }
            }
            if (codecMethod == null) {
                codecMethod = payloadIf.getMethod("codec", encIf, decIf);
            }
            codecMethod.setAccessible(true);
            Object codec = codecMethod.invoke(null, encoder, decoder);

            registerDirection(registryCls, new String[]{"serverboundPlay", "playC2S"}, typeCls, codecIf, type, codec);
            registerDirection(registryCls, new String[]{"clientboundPlay", "playS2C"}, typeCls, codecIf, type, codec);

            // Register global receivers via Fabric API if available
            registerFabricReceivers(cl, type, typeCls);

            E4allClient.LOGGER.info("e4all: voice payload channel registered");
        } catch (ClassNotFoundException | LinkageError e) {
            E4allClient.LOGGER.debug("e4all: payload registration unavailable, using packet sniffing", e);
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("e4all: payload registration failed, guests may get kicked on join", t);
        }
    }

    private static void registerFabricReceivers(ClassLoader cl, Object type, Class<?> typeCls) {
        // Register server receiver
        try {
            Class<?> spNetCls = findClass(cl, "net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking");
            if (spNetCls != null) {
                for (Method m : spNetCls.getMethods()) {
                    if ("registerGlobalReceiver".equals(m.getName()) && m.getParameterCount() == 2) {
                        Class<?>[] p = m.getParameterTypes();
                        if (p[0].isAssignableFrom(typeCls)) {
                            Class<?> handlerIf = p[1];
                            Object handler = Proxy.newProxyInstance(cl, new Class<?>[]{handlerIf}, (proxy, method, args) -> {
                                try {
                                    if ("receive".equals(method.getName()) || (args != null && args.length == 2)) {
                                        Object payloadArg = args[0];
                                        Object contextArg = args[1];
                                        byte[] data = null;
                                        if (payloadArg instanceof RawPayload raw) {
                                            data = raw.e4all$data();
                                        } else {
                                            data = extractDataFromPayload(payloadArg);
                                        }
                                        ServerPlayer player = extractPlayerFromContext(contextArg);
                                        if (player != null && data != null) {
                                            VoiceControl.handleServerPayloadDirect(player, data);
                                        } else if (player == null) {
                                            E4allClient.LOGGER.warn("e4all voice: could not extract ServerPlayer from Fabric context {}",
                                                    contextArg != null ? contextArg.getClass().getName() : "null");
                                        }
                                    }
                                } catch (Throwable t) {
                                    E4allClient.LOGGER.error("e4all voice: error in ServerPlayNetworking receiver", t);
                                }
                                return null;
                            });
                            m.invoke(null, type, handler);
                            E4allClient.LOGGER.info("e4all voice: registered ServerPlayNetworking receiver");
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("e4all voice: ServerPlayNetworking receiver registration skipped/failed", t);
        }

        // Register client receiver
        try {
            Class<?> cpNetCls = findClass(cl, "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
            if (cpNetCls != null) {
                for (Method m : cpNetCls.getMethods()) {
                    if ("registerGlobalReceiver".equals(m.getName()) && m.getParameterCount() == 2) {
                        Class<?>[] p = m.getParameterTypes();
                        if (p[0].isAssignableFrom(typeCls)) {
                            Class<?> handlerIf = p[1];
                            Object handler = Proxy.newProxyInstance(cl, new Class<?>[]{handlerIf}, (proxy, method, args) -> {
                                try {
                                    if ("receive".equals(method.getName()) || (args != null && args.length == 2)) {
                                        Object payloadArg = args[0];
                                        byte[] data = null;
                                        if (payloadArg instanceof RawPayload raw) {
                                            data = raw.e4all$data();
                                        } else {
                                            data = extractDataFromPayload(payloadArg);
                                        }
                                        if (data != null) {
                                            VoiceControl.handleClientPayload(data);
                                        }
                                    }
                                } catch (Throwable t) {
                                    E4allClient.LOGGER.error("e4all voice: error in ClientPlayNetworking receiver", t);
                                }
                                return null;
                            });
                            m.invoke(null, type, handler);
                            E4allClient.LOGGER.info("e4all voice: registered ClientPlayNetworking receiver");
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("e4all voice: ClientPlayNetworking receiver registration skipped/failed", t);
        }
    }

    public static ServerPlayer extractPlayerFromContext(Object contextArg) {
        if (contextArg == null) return null;
        // check public interfaces first to avoid IllegalAccessException on package-private impls
        for (Class<?> iface : contextArg.getClass().getInterfaces()) {
            if (Modifier.isPublic(iface.getModifiers())) {
                for (Method m : iface.getMethods()) {
                    if (m.getParameterCount() == 0 && ServerPlayer.class.isAssignableFrom(m.getReturnType())) {
                        try {
                            return (ServerPlayer) m.invoke(contextArg);
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }
        Class<?> sc = contextArg.getClass().getSuperclass();
        while (sc != null && sc != Object.class) {
            for (Class<?> iface : sc.getInterfaces()) {
                if (Modifier.isPublic(iface.getModifiers())) {
                    for (Method m : iface.getMethods()) {
                        if (m.getParameterCount() == 0 && ServerPlayer.class.isAssignableFrom(m.getReturnType())) {
                            try {
                                return (ServerPlayer) m.invoke(contextArg);
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
            sc = sc.getSuperclass();
        }
        for (Method m : contextArg.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && ServerPlayer.class.isAssignableFrom(m.getReturnType())) {
                try {
                    m.setAccessible(true);
                    return (ServerPlayer) m.invoke(contextArg);
                } catch (Throwable ignored) {}
            }
        }
        Class<?> c = contextArg.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (ServerPlayer.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(contextArg);
                        if (val instanceof ServerPlayer sp) return sp;
                    } catch (Throwable ignored) {}
                }
            }
            c = c.getSuperclass();
        }
        try {
            for (Method m : contextArg.getClass().getMethods()) {
                if (m.getParameterCount() == 0) {
                    try {
                        m.setAccessible(true);
                        Object res = m.invoke(contextArg);
                        if (res != null) {
                            ServerPlayer sp = VoiceControl.extractServerPlayer(res);
                            if (sp != null) return sp;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static boolean sendClientboundFabric(ServerPlayer player, byte[] data) {
        try {
            Class<?> snCls = findClass(VoiceControlPayload.class.getClassLoader(),
                    "net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking");
            if (snCls == null) return false;
            Object payload = createPayload(data);
            if (payload == null) return false;

            for (Method m : snCls.getMethods()) {
                if ("send".equals(m.getName()) && m.getParameterCount() == 2) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p[0].isAssignableFrom(player.getClass()) && (p[1].isInstance(payload) || p[1].isAssignableFrom(payload.getClass()))) {
                        m.invoke(null, player, payload);
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("e4all: ServerPlayNetworking.send failed", t);
        }
        return false;
    }

    public static boolean sendServerboundFabric(byte[] data) {
        try {
            Class<?> cnCls = findClass(VoiceControlPayload.class.getClassLoader(),
                    "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
            if (cnCls == null) return false;
            Object payload = createPayload(data);
            if (payload == null) return false;

            for (Method m : cnCls.getMethods()) {
                if ("send".equals(m.getName()) && m.getParameterCount() == 1) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p[0].isInstance(payload) || p[0].isAssignableFrom(payload.getClass())) {
                        m.invoke(null, payload);
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("e4all: ClientPlayNetworking.send failed", t);
        }
        return false;
    }

    private static void registerDirection(Class<?> registryCls, String[] accessors,
                                          Class<?> typeCls, Class<?> codecIf,
                                          Object type, Object codec) throws Exception {
        Object registry = null;
        for (String accessor : accessors) {
            try {
                registry = registryCls.getMethod(accessor).invoke(null);
                break;
            } catch (NoSuchMethodException ignored) {}
        }
        if (registry == null) throw new NoSuchMethodException("payload registry accessor not found");
        for (Method m : registry.getClass().getMethods()) {
            if (!m.getName().equals("register") || m.getParameterCount() != 2) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p[0].isAssignableFrom(typeCls) && p[1].isAssignableFrom(codecIf)) {
                m.invoke(registry, type, codec);
                return;
            }
        }
        throw new NoSuchMethodException("payload registry register method not found");
    }

    private static Class<?> findClass(ClassLoader cl, String... names) {
        for (String name : names) {
            try {
                return Class.forName(name, false, cl);
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    public static boolean isOwnChannel(Object id) {
        if (id == null) return false;
        if (id instanceof String s) {
            return "e4all:voice".equals(s);
        }
        if (registeredType != null && registeredType.equals(id)) {
            return true;
        }
        Object unwrap = unwrapTypeId(id);
        if (unwrap != null) {
            id = unwrap;
        }
        return "e4all".equals(ResourceLocReflector.getNamespace(id))
                && "voice".equals(ResourceLocReflector.getPath(id));
    }

    public static Object unwrapTypeId(Object obj) {
        if (obj == null) return null;
        if (ResourceLocReflector.isInstance(obj)) return obj;
        Class<?> cls = obj.getClass();
        for (String mName : new String[]{"id", "getId", "method_56480"}) {
            try {
                Method m = cls.getMethod(mName);
                if (m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    Object res = m.invoke(obj);
                    if (res != null && ResourceLocReflector.isInstance(res)) {
                        return res;
                    }
                }
            } catch (Throwable ignored) {}
        }
        for (Field f : cls.getDeclaredFields()) {
            if (ResourceLocReflector.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val != null) return val;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    public static Object extractChannelFromPayload(Object payload) {
        if (payload == null) return null;
        if (payload instanceof RawPayload raw) {
            Object ch = raw.e4all$channel();
            if (ch != null) return ch;
        }
        Class<?> cls = payload.getClass();
        for (String typeMethodName : new String[]{"type", "getType", "method_56479"}) {
            try {
                Method m = cls.getMethod(typeMethodName);
                if (m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    Object typeObj = m.invoke(payload);
                    if (typeObj != null) {
                        if (registeredType != null && registeredType.equals(typeObj)) {
                            return channel();
                        }
                        Object id = unwrapTypeId(typeObj);
                        if (id != null) return id;
                    }
                }
            } catch (Throwable ignored) {}
        }

        for (String idMethodName : new String[]{"id", "getId", "method_56479"}) {
            try {
                Method m = cls.getMethod(idMethodName);
                if (m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    Object id = m.invoke(payload);
                    if (id != null) {
                        Object unwrap = unwrapTypeId(id);
                        return unwrap != null ? unwrap : id;
                    }
                }
            } catch (Throwable ignored) {}
        }

        for (Field f : cls.getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object val = f.get(payload);
                if (val != null) {
                    Object unwrap = unwrapTypeId(val);
                    if (unwrap != null) return unwrap;
                }
            } catch (Throwable ignored) {}
        }

        return null;
    }

    public static Object extractChannelFromPacket(Object packet) {
        if (packet == null) return null;
        Class<?> cls = packet.getClass();
        for (String mName : new String[]{"getName", "getIdentifier", "channel", "getId", "method_11453"}) {
            try {
                Method m = cls.getMethod(mName);
                if (m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    Object id = m.invoke(packet);
                    if (id != null) {
                        Object unwrap = unwrapTypeId(id);
                        return unwrap != null ? unwrap : id;
                    }
                }
            } catch (Throwable ignored) {}
        }
        for (Field f : cls.getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object val = f.get(packet);
                if (val != null) {
                    Object unwrap = unwrapTypeId(val);
                    if (unwrap != null) return unwrap;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static boolean isOwnPacket(Object packet, Object payload) {
        if (payload != null) {
            if (payload instanceof RawPayload raw) {
                Object ch = raw.e4all$channel();
                return ch == null || isOwnChannel(ch);
            }
            Object ch = extractChannelFromPayload(payload);
            if (ch != null) {
                return isOwnChannel(ch);
            }
            return false;
        }
        if (packet != null) {
            Object ch = extractChannelFromPacket(packet);
            if (ch != null) {
                return isOwnChannel(ch);
            }
        }
        return false;
    }

    public static Object channel() {
        return ResourceLocReflector.create("e4all", "voice");
    }

    public static byte[] readRemaining(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }

    public static void enqueuePendingServer(byte[] data) {
        if (data != null) {
            PENDING_SERVER_QUEUE.offer(data);
        }
    }

    public static byte[] takePending() {
        byte[] data = PENDING.get();
        if (data != null) {
            PENDING.remove();
            return data;
        }
        return PENDING_SERVER_QUEUE.poll();
    }

    public static byte[] extractData(Object packet) {
        if (packet == null) return null;
        Object payload = extractPayloadObject(packet);
        if (!isOwnPacket(packet, payload)) {
            return null;
        }
        if (payload != null) {
            byte[] data = extractDataFromPayload(payload);
            if (data != null) {
                PENDING.remove();
                return data;
            }
        }
        return takePending();
    }

    public static Object extractPayloadObject(Object packet) {
        if (packet == null) return null;
        Class<?> cls = packet.getClass();
        for (Method m : cls.getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() != void.class && m.getReturnType() != cls) {
                if ("payload".equals(m.getName()) || "getPayload".equals(m.getName()) || "method_56439".equals(m.getName())) {
                    try {
                        m.setAccessible(true);
                        Object res = m.invoke(packet);
                        if (res != null) return res;
                    } catch (Throwable ignored) {}
                }
            }
        }
        for (Field f : cls.getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object val = f.get(packet);
                if (val instanceof RawPayload) return val;
                if (val != null) {
                    Class<?> pIf = getPayloadInterface();
                    if (pIf != null && pIf.isInstance(val)) return val;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static byte[] extractDataFromPayload(Object payload) {
        if (payload == null) return null;
        if (payload instanceof RawPayload raw) {
            Object ch = raw.e4all$channel();
            if (ch == null || isOwnChannel(ch)) {
                return raw.e4all$data();
            }
        }
        Class<?> cls = payload.getClass();
        boolean isVoice = false;
        for (Method m : cls.getMethods()) {
            if (m.getParameterCount() == 0) {
                try {
                    Object ret = m.invoke(payload);
                    if (ret != null && (isOwnChannel(ret) || (registeredType != null && registeredType.equals(ret)))) {
                        isVoice = true;
                        break;
                    }
                } catch (Throwable ignored) {}
            }
        }
        if (isVoice) {
            for (Method m : cls.getMethods()) {
                if (m.getParameterCount() == 0) {
                    if (m.getReturnType() == byte[].class) {
                        try {
                            return (byte[]) m.invoke(payload);
                        } catch (Throwable ignored) {}
                    } else if (ByteBuf.class.isAssignableFrom(m.getReturnType()) || FriendlyByteBuf.class.isAssignableFrom(m.getReturnType())) {
                        try {
                            ByteBuf b = (ByteBuf) m.invoke(payload);
                            if (b != null) {
                                byte[] d = new byte[b.readableBytes()];
                                b.getBytes(b.readerIndex(), d);
                                return d;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(payload);
                    if (val instanceof byte[] b) return b;
                    if (val instanceof ByteBuf b) {
                        byte[] d = new byte[b.readableBytes()];
                        b.getBytes(b.readerIndex(), d);
                        return d;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }
}