package link.e4all.voice;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import link.e4all.E4allClient;
import link.e4all.PacketHelper;
import link.e4all.ResourceLocReflector;
import link.e4all.dialtone.DialtoneChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

// 1.18 - 1.20.1 ship custom payloads as plain packets, sniff them out of the pipeline
// and hand the e4all:voice channel to VoiceControl
public final class LegacyPayloadBridge {
    private static final String HANDLER_NAME = "e4all_legacy_payload";
    private static final String RAW_CACHE_HANDLER_NAME = "e4all_legacy_raw_frame_cache";
    private static final String VOICE_CHANNEL_NAME = "e4all:voice";
    // 1.20.2+ payload packets
    private static final String[] MODERN_PAYLOAD_PACKET = {
            "net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket",
            "net.minecraft.class_8709"
    };

    private static final Map<Channel, byte[]> LAST_RAW_FRAMES =
            Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static volatile Boolean legacyRuntime;
    private static final Set<Connection> INSTALLED =
            Collections.synchronizedSet(Collections.newSetFromMap(new java.util.WeakHashMap<>()));
    private static final Set<String> WARNED_REASONS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private LegacyPayloadBridge() {}

    private static void warnOnce(String reason) {
        if (WARNED_REASONS.add(reason)) {
            E4allClient.LOGGER.warn("e4all voice: legacy payload sniffer could not install - {}", reason);
        }
    }

    private static boolean classPresent(String resource) {
        try {
            return LegacyPayloadBridge.class.getClassLoader().getResource(resource) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isLegacyRuntime() {
        Boolean cached = legacyRuntime;
        if (cached != null) return cached;
        // check if runtime uses legacy (1.18-1.20.1) payload format
        boolean modern = VoiceControlPayload.getPayloadInterface() != null
                && PacketHelper.hasClass(MODERN_PAYLOAD_PACKET);
        boolean legacy = !modern;
        legacyRuntime = legacy;
        if (legacy) {
            E4allClient.LOGGER.info("e4all voice: legacy runtime detected (1.18 - 1.20.1 payload format), pipeline sniffer required");
        }
        return legacy;
    }

    public static void install(Connection connection) {
        if (connection == null || !isLegacyRuntime()) return;
        try {
            if (INSTALLED.contains(connection)) return;
            Channel channel = channelOf(connection);
            if (channel == null) {
                return;
            }
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null) {
                INSTALLED.add(connection);
                return;
            }

            ChannelHandler sniffer = new Sniffer(connection);
            String vanillaDispatch = null;
            for (Map.Entry<String, ChannelHandler> entry : pipeline.toMap().entrySet()) {
                if (entry.getValue() == connection) {
                    vanillaDispatch = entry.getKey();
                    break;
                }
            }

            installRawFrameCacher(channel, pipeline, vanillaDispatch);

            if (vanillaDispatch != null) {
                pipeline.addBefore(vanillaDispatch, HANDLER_NAME, sniffer);
            } else if (pipeline.get("decoder") != null) {
                pipeline.addAfter("decoder", HANDLER_NAME, sniffer);
            } else {
                pipeline.addLast(HANDLER_NAME, sniffer);
            }

            INSTALLED.add(connection);
            E4allClient.LOGGER.info("e4all voice: legacy payload sniffer installed (1.18 - 1.20.1 payload format)");
        } catch (Throwable t) {
            warnOnce("unexpected error on connection " + connection.getClass().getName() + ": " + t);
        }
    }

    // helper to extract connection from packet listeners
    public static void installFromConnectionLike(Object holder) {
        if (holder == null || !isLegacyRuntime()) return;
        try {
            if (holder instanceof Connection connection) {
                install(connection);
                return;
            }
            Class<?> c = holder.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Connection.class.isAssignableFrom(f.getType())) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(holder);
                        if (val instanceof Connection connection) {
                            install(connection);
                            return;
                        }
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            warnOnce("could not resolve connection from " + holder.getClass().getName() + ": " + t);
        }
    }

    private static Channel channelOf(Connection connection) {
        Class<?> c = connection.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!Channel.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(connection);
                    if (value instanceof Channel ch) return ch;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    // cache frames before the decoder, compression is off on relay connections
    private static void installRawFrameCacher(Channel channel, ChannelPipeline pipeline, String vanillaDispatch) {
        try {
            if (pipeline.get(RAW_CACHE_HANDLER_NAME) != null) return;
            String decoderName = null;
            for (Map.Entry<String, ChannelHandler> entry : pipeline.toMap().entrySet()) {
                String name = entry.getKey();
                if (name.equals("decoder") || name.equals("packet_decoder") || name.equals("unpack")) {
                    decoderName = name;
                    break;
                }
            }
            if (decoderName == null) return;
            pipeline.addBefore(decoderName, RAW_CACHE_HANDLER_NAME, new RawFrameCacher());
            E4allClient.LOGGER.info("e4all voice: raw frame cache installed before '{}' (1.18 - 1.20.1 payload recovery)", decoderName);
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("e4all voice: could not install the raw frame cache", t);
        }
    }

    // [varint packetId][varint channelLen][channel utf8][payload bytes...]
    private static byte[] parseVoicePayloadFromFrame(byte[] frame) {
        try {
            int[] idx = {0};
            if (!skipVarint(frame, idx)) return null;
            int[] len = {0};
            if (!readVarint(frame, idx, len)) return null;
            if (len[0] <= 0 || len[0] > 64 || idx[0] + len[0] > frame.length) return null;
            String channel = new String(frame, idx[0], len[0], StandardCharsets.US_ASCII);
            if (!VOICE_CHANNEL_NAME.equals(channel)) return null;
            idx[0] += len[0];
            int payload = frame.length - idx[0];
            if (payload < 1 || payload > 32767) return null;
            byte[] data = new byte[payload];
            System.arraycopy(frame, idx[0], data, 0, payload);
            return data;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean readVarint(byte[] buf, int[] idx, int[] out) {
        int value = 0;
        int shift = 0;
        while (true) {
            if (idx[0] >= buf.length || shift >= 32) return false;
            byte b = buf[idx[0]++];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                out[0] = value;
                return true;
            }
            shift += 7;
        }
    }

    private static boolean skipVarint(byte[] buf, int[] idx) {
        return readVarint(buf, idx, new int[1]);
    }

    // relay connections only (no compression wrapper around the frame)
    private static byte[] recoverFromRawFrame(Connection connection, boolean clientbound) {
        try {
            Channel ch = channelOf(connection);
            if (ch == null) return null;
            byte[] frame = LAST_RAW_FRAMES.get(ch);
            if (frame == null) return null;
            byte[] data = parseVoicePayloadFromFrame(frame);
            if (data != null) {
                E4allClient.LOGGER.info("e4all voice: recovered {} control message from raw frame ({} bytes)",
                        clientbound ? "clientbound" : "serverbound", data.length);
            }
            return data;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class RawFrameCacher extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try {
                if (msg instanceof ByteBuf buf && buf.refCnt() > 0) {
                    int readable = buf.readableBytes();
                    if (readable > 0 && readable <= 4096) {
                        byte[] copy = new byte[readable];
                        buf.getBytes(buf.readerIndex(), copy);
                        LAST_RAW_FRAMES.put(ctx.channel(), copy);
                    } else {
                        LAST_RAW_FRAMES.remove(ctx.channel());
                    }
                }
            } catch (Throwable ignored) {
            }
            super.channelRead(ctx, msg);
        }
    }

    // server side only: the game packet listener holds the ServerPlayer
    private static Object listenerOf(Connection connection) {
        Class<?> c = connection.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!PacketListener.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(connection);
                    if (value instanceof PacketListener listener) return listener;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    // match by type, member names differ per loader
    private static Object channelIdOf(Object packet) {
        for (Method m : packet.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (!ResourceLocReflector.isAssignableFrom(m.getReturnType())) continue;
            try {
                m.setAccessible(true);
                Object value = m.invoke(packet);
                if (value != null) return value;
            } catch (Throwable ignored) {}
        }
        Class<?> c = packet.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!ResourceLocReflector.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(packet);
                    if (value != null) return value;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static byte[] dataOf(Object packet) {
        for (Method m : packet.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            Class<?> ret = m.getReturnType();
            if (!ByteBuf.class.isAssignableFrom(ret) && ret != byte[].class) continue;
            try {
                m.setAccessible(true);
                byte[] data = asBytes(m.invoke(packet));
                if (data != null) return data;
            } catch (Throwable ignored) {}
        }
        Class<?> c = packet.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                Class<?> type = f.getType();
                if (!ByteBuf.class.isAssignableFrom(type) && type != byte[].class) continue;
                try {
                    f.setAccessible(true);
                    byte[] data = asBytes(f.get(packet));
                    if (data != null) return data;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    // non consuming copy, vanilla dispatch still gets the full buffer
    private static byte[] asBytes(Object value) {
        if (value instanceof ByteBuf buf && buf.readableBytes() > 0) {
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);
            return data;
        }
        if (value instanceof byte[] arr && arr.length > 0) {
            return arr;
        }
        return null;
    }

    private static final class Sniffer extends ChannelInboundHandlerAdapter {
        private final Connection connection;

        private Sniffer(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try {
                sniff(msg);
            } catch (Throwable t) {
                E4allClient.LOGGER.warn("e4all voice: legacy payload sniffing failed", t);
            }
            super.channelRead(ctx, msg);
        }

        private void sniff(Object packet) {
            if (packet == null) return;
            boolean clientbound = PacketHelper.isClientboundCustomPayloadPacket(packet);
            boolean serverbound = !clientbound && PacketHelper.isServerboundCustomPayloadPacket(packet);
            if (!clientbound && !serverbound) return;
            if (!VoiceControlPayload.isOwnChannel(channelIdOf(packet))) return;
            byte[] data = dataOf(packet);
            if (data == null || data.length < 1) {
                data = recoverFromRawFrame(connection, clientbound);
            }
            if (data == null || data.length < 1) {
                E4allClient.LOGGER.warn("e4all voice: saw an e4all:voice payload on this connection but could not read its bytes ({})",
                        clientbound ? "clientbound" : "serverbound");
                return;
            }

            E4allClient.LOGGER.info("e4all voice: {} control message 0x{} ({} bytes)",
                    clientbound ? "clientbound" : "serverbound", Integer.toHexString(data[0] & 0xFF), data.length);

            if (clientbound) {
                VoiceControl.handleClientPayload(data);
                return;
            }

            Object listener = listenerOf(connection);
            ServerPlayer player = VoiceControl.extractServerPlayer(listener);
            if (player == null) {
                E4allClient.LOGGER.warn("e4all voice: legacy voice payload arrived but no ServerPlayer was found");
                return;
            }
            VoiceControl.handleServerPayloadDirect(player, data);
        }
    }
}