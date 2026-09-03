package link.e4all.voice;

import link.e4all.E4allClient;
import link.e4all.Mirror;
import link.e4all.PacketHelper;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

// Voice control signaling over e4all:voice
public final class VoiceControl {

    public static final byte MSG_HELLO  = 0x01;
    public static final byte MSG_OFFER  = 0x02;
    public static final byte MSG_RESULT = 0x03;
    public static final byte MSG_READY  = 0x04;

    public static final byte TRANSPORT_DIALTONE = 0;
    public static final byte TRANSPORT_UDP      = 1;
    public static final byte TRANSPORT_NONE     = 2;

    private VoiceControl() {}

    public static byte[] encodeHello(boolean hasVoiceClient) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(2);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(MSG_HELLO);
            out.writeBoolean(hasVoiceClient);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] encodeOffer(byte transport, String ticket,
                                     List<String> candidates, VoiceFailure failure) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(MSG_OFFER);
            out.writeByte(transport);
            out.writeUTF(ticket != null ? ticket : "");
            out.writeShort(candidates != null ? candidates.size() : 0);
            if (candidates != null) {
                for (String c : candidates) out.writeUTF(c);
            }
            out.writeByte(failure != null ? failure.ordinal : -1);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] encodeResult(boolean ok, byte transport, int rttMs,
                                      VoiceFailure failure, List<String> candidates) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(MSG_RESULT);
            out.writeBoolean(ok);
            out.writeByte(transport);
            out.writeInt(rttMs);
            out.writeByte(failure != null ? failure.ordinal : -1);
            out.writeShort(candidates != null ? candidates.size() : 0);
            if (candidates != null) {
                for (String c : candidates) out.writeUTF(c);
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] encodeReady(boolean ok, byte transport, int rttMs,
                                     VoiceFailure failure) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(16);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(MSG_READY);
            out.writeBoolean(ok);
            out.writeByte(transport);
            out.writeInt(rttMs);
            out.writeByte(failure != null ? failure.ordinal : -1);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> lastServerHash = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> lastServerTime = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile int lastClientHash = 0;
    private static volatile long lastClientTime = 0L;

    public static void handleServerPayloadDirect(ServerPlayer player, byte[] data) {
        if (player == null || data == null || data.length < 1) return;
        MinecraftServer server = extractServer(player);
        if (server == null) {
            E4allClient.LOGGER.warn("e4all voice: could not get server from player");
            return;
        }
        server.execute(() -> dispatchServerMessage(player, data));
    }

    public static void handleServerPayload(Object listener, byte[] data) {
        ServerPlayer player = extractServerPlayer(listener);
        if (player == null) {
            E4allClient.LOGGER.warn("e4all voice: received voice control payload but could not extract ServerPlayer");
            return;
        }
        handleServerPayloadDirect(player, data);
    }

    public static void dispatchServerMessage(ServerPlayer player, byte[] data) {
        if (player == null || data == null || data.length < 1) return;
        String uuid = player.getStringUUID();
        int hash = java.util.Arrays.hashCode(data);
        long now = System.currentTimeMillis();
        Integer prevHash = lastServerHash.put(uuid, hash);
        Long prevTime = lastServerTime.put(uuid, now);
        if (prevHash != null && prevHash == hash && prevTime != null && (now - prevTime) < 2000) {
            E4allClient.LOGGER.debug("e4all voice: ignoring duplicate server control message from {}", player.getScoreboardName());
            return;
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            byte type = in.readByte();
            switch (type) {
                case MSG_HELLO -> {
                    boolean hasVoiceClient = in.readBoolean();
                    HostVoiceNegotiator.INSTANCE.onHello(player, hasVoiceClient);
                }
                case MSG_RESULT -> {
                    boolean ok = in.readBoolean();
                    byte transport = in.readByte();
                    int rttMs = in.readInt();
                    int failOrd = in.readByte();
                    VoiceFailure failure = failOrd >= 0 ? VoiceFailure.fromOrdinal(failOrd) : null;
                    int candCount = in.readUnsignedShort();
                    List<String> candidates = new ArrayList<>(candCount);
                    for (int i = 0; i < candCount; i++) candidates.add(in.readUTF());
                    HostVoiceNegotiator.INSTANCE.onResult(player, ok, transport, rttMs, failure, candidates);
                }
                case MSG_OFFER, MSG_READY -> {
                    // Loopback on integrated server: client packet received by server listener
                }
                default -> E4allClient.LOGGER.warn("e4all voice: unknown server-side control message type 0x{}",
                        Integer.toHexString(type & 0xFF));
            }
        } catch (IOException e) {
            E4allClient.LOGGER.warn("e4all voice: malformed server-side control message", e);
        }
    }

    public static void handleClientPayload(byte[] data) {
        try {
            net.minecraft.client.Minecraft.getInstance().execute(() -> dispatchClientMessage(data));
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("e4all voice: could not dispatch client voice control message", t);
        }
    }

    public static void dispatchClientMessage(byte[] data) {
        if (data == null || data.length < 1) return;
        int hash = java.util.Arrays.hashCode(data);
        long now = System.currentTimeMillis();
        if (lastClientHash == hash && (now - lastClientTime) < 2000) {
            E4allClient.LOGGER.debug("e4all voice: ignoring duplicate client control message");
            return;
        }
        lastClientHash = hash;
        lastClientTime = now;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            byte type = in.readByte();
            switch (type) {
                case MSG_OFFER -> {
                    byte transport = in.readByte();
                    String ticket = in.readUTF();
                    int candCount = in.readUnsignedShort();
                    List<String> candidates = new ArrayList<>(candCount);
                    for (int i = 0; i < candCount; i++) candidates.add(in.readUTF());
                    int failOrd = in.readByte();
                    VoiceFailure failure = failOrd >= 0 ? VoiceFailure.fromOrdinal(failOrd) : null;
                    ClientVoiceNegotiator.INSTANCE.onOffer(transport, ticket, candidates, failure);
                }
                case MSG_READY -> {
                    boolean ok = in.readBoolean();
                    byte transport = in.readByte();
                    int rttMs = in.readInt();
                    int failOrd = in.readByte();
                    VoiceFailure failure = failOrd >= 0 ? VoiceFailure.fromOrdinal(failOrd) : null;
                    ClientVoiceNegotiator.INSTANCE.onReady(ok, transport, rttMs, failure);
                }
                case MSG_HELLO, MSG_RESULT -> {
                    // Loopback on integrated server: host received own broadcast/client message
                }
                default -> E4allClient.LOGGER.warn("e4all voice: unknown client-side control message type 0x{}",
                        Integer.toHexString(type & 0xFF));
            }
        } catch (IOException e) {
            E4allClient.LOGGER.warn("e4all voice: malformed client-side control message", e);
        }
    }

    public static void sendToPlayer(ServerPlayer player, byte[] data) {
        try {
            if (VoiceControlPayload.sendClientboundFabric(player, data)) {
                return;
            }
            PacketHelper.sendClientbound(player, VoiceControlPayload.channel(), data);
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("e4all voice: failed to send voice control to player {}", player.getScoreboardName(), t);
        }
    }

    public static void sendToServer(byte[] data) {
        try {
            if (VoiceControlPayload.sendServerboundFabric(data)) {
                return;
            }
            var conn = net.minecraft.client.Minecraft.getInstance().getConnection();
            if (conn == null) return;
            PacketHelper.sendServerbound(conn.getConnection(), VoiceControlPayload.channel(), data);
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("e4all voice: failed to send voice control to server", t);
        }
    }

    public static ServerPlayer extractServerPlayer(Object listener) {
        if (listener == null) return null;
        if (listener instanceof ServerPlayer sp) return sp;
        for (String fieldName : new String[]{"player", "field_14898", "f_9712_"}) {
            try {
                Field f = findField(listener.getClass(), fieldName);
                if (f != null) {
                    f.setAccessible(true);
                    Object val = f.get(listener);
                    if (val instanceof ServerPlayer sp) return sp;
                }
            } catch (Throwable ignored) {}
        }
        for (String methodName : new String[]{"getPlayer", "method_31284", "method_18784"}) {
            try {
                Method m = listener.getClass().getMethod(methodName);
                Object val = m.invoke(listener);
                if (val instanceof ServerPlayer sp) return sp;
            } catch (Throwable ignored) {}
        }
        Class<?> c = listener.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (ServerPlayer.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(listener);
                        if (val instanceof ServerPlayer sp) return sp;
                    } catch (Throwable ignored) {}
                }
            }
            c = c.getSuperclass();
        }
        for (Method m : listener.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && ServerPlayer.class.isAssignableFrom(m.getReturnType())) {
                try {
                    m.setAccessible(true);
                    Object val = m.invoke(listener);
                    if (val instanceof ServerPlayer sp) return sp;
                } catch (Throwable ignored) {}
            }
        }
        try {
            Connection conn = getConnectionFromListener(listener);
            if (conn != null) {
                MinecraftServer s = extractServerFromListener(listener);
                if (s != null) {
                    for (ServerPlayer sp : s.getPlayerList().getPlayers()) {
                        if (sp.connection != null && (sp.connection == listener || getConnectionFromListener(sp.connection) == conn)) {
                            return sp;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Connection getConnectionFromListener(Object listener) {
        if (listener == null) return null;
        if (listener instanceof Connection cn) return cn;
        for (Class<?> cl = listener.getClass(); cl != null && cl != Object.class; cl = cl.getSuperclass()) {
            for (Field f : cl.getDeclaredFields()) {
                if (Connection.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(listener);
                        if (val instanceof Connection cn) return cn;
                    } catch (Throwable ignored) {}
                }
            }
        }
        for (Method m : listener.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && Connection.class.isAssignableFrom(m.getReturnType())) {
                try {
                    m.setAccessible(true);
                    Object val = m.invoke(listener);
                    if (val instanceof Connection cn) return cn;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    // server field is public on old versions and private on 26.x
    public static MinecraftServer extractServer(ServerPlayer player) {
        if (player == null) return null;
        try {
            Field f = findField(player.getClass(), "server");
            if (f != null) {
                f.setAccessible(true);
                Object val = f.get(player);
                if (val instanceof MinecraftServer server) return server;
            }
        } catch (Throwable ignored) {}
        try {
            Method m = player.getClass().getMethod("getServer");
            Object val = m.invoke(player);
            if (val instanceof MinecraftServer server) return server;
        } catch (Throwable ignored) {}
        Class<?> c = player.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (MinecraftServer.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(player);
                        if (val instanceof MinecraftServer server) return server;
                    } catch (Throwable ignored) {}
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    public static MinecraftServer extractServerFromListener(Object listener) {
        if (listener == null) return null;
        for (String fieldName : new String[]{"server", "field_14896", "f_9711_"}) {
            try {
                Field f = findField(listener.getClass(), fieldName);
                if (f != null) {
                    f.setAccessible(true);
                    Object val = f.get(listener);
                    if (val instanceof MinecraftServer s) return s;
                }
            } catch (Throwable ignored) {}
        }
        Class<?> c = listener.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (MinecraftServer.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(listener);
                        if (val instanceof MinecraftServer s) return s;
                    } catch (Throwable ignored) {}
                }
            }
            c = c.getSuperclass();
        }
        for (Method m : listener.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && MinecraftServer.class.isAssignableFrom(m.getReturnType())) {
                try {
                    m.setAccessible(true);
                    Object val = m.invoke(listener);
                    if (val instanceof MinecraftServer s) return s;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }
}
