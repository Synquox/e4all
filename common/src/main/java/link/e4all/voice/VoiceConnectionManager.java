package link.e4all.voice;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import link.e4all.E4allClient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VoiceConnectionManager {
    public static final VoiceConnectionManager INSTANCE = new VoiceConnectionManager();
    private final ConcurrentHashMap<UUID, Channel> streams = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicReference<VoicePacketConsumer> packetConsumer = new java.util.concurrent.atomic.AtomicReference<>(null);

    @FunctionalInterface
    public interface VoicePacketConsumer {
        void accept(byte[] data, long timestamp, SyntheticAddress address);
    }

    public void setPacketConsumer(VoicePacketConsumer consumer) {
        this.packetConsumer.set(consumer);
    }

    public VoicePacketConsumer getPacketConsumer() {
        return packetConsumer.get();
    }

    public boolean clearPacketConsumer(VoicePacketConsumer expected) {
        return packetConsumer.compareAndSet(expected, null);
    }

    public void registerStream(UUID uuid, Channel channel) {
        Channel old = streams.put(uuid, channel);
        if (old != null && old != channel && old.isOpen()) {
            old.close();
        }
        E4allClient.LOGGER.info("Registered voice stream for player {}", uuid);
    }

    public void removeStream(UUID uuid) {
        streams.remove(uuid);
        E4allClient.LOGGER.info("Removed voice stream for player {}", uuid);
    }

    public void sendToPlayer(UUID uuid, byte[] data) {
        Channel channel = streams.get(uuid);
        if (channel != null && channel.isActive()) {
            ByteBuf buf = channel.alloc().buffer(1 + data.length);
            buf.writeByte(VoiceFraming.MSG_VOICE_DATA);
            buf.writeBytes(data);
            channel.writeAndFlush(buf);
        }
    }

    public void closeAll() {
        Map<UUID, Channel> snapshot = new HashMap<>(streams);
        streams.clear();
        for (Channel ch : snapshot.values()) {
            try {
                if (ch.isActive()) {
                    ByteBuf buf = ch.alloc().buffer(1);
                    buf.writeByte(VoiceFraming.MSG_CLOSE);
                    ch.writeAndFlush(buf).addListener(f -> ch.close());
                } else if (ch.isOpen()) {
                    ch.close();
                }
            } catch (Exception e) {
                E4allClient.LOGGER.debug("Error closing voice channel", e);
            }
        }
    }
}
