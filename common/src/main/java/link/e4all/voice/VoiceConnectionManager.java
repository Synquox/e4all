package link.e4all.voice;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import link.e4all.E4allClient;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VoiceConnectionManager {
    public static final VoiceConnectionManager INSTANCE = new VoiceConnectionManager();
    private final ConcurrentHashMap<UUID, Channel> streams = new ConcurrentHashMap<>();
    private volatile RelayVoicechatSocket socket;

    public void setSocket(RelayVoicechatSocket socket) { this.socket = socket; }
    public RelayVoicechatSocket getSocket() { return socket; }

    public void registerStream(UUID uuid, Channel channel) {
        streams.put(uuid, channel);
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
        for (Channel ch : streams.values()) {
            if (ch.isActive()) {
                ByteBuf buf = ch.alloc().buffer(1);
                buf.writeByte(VoiceFraming.MSG_CLOSE);
                ch.writeAndFlush(buf).addListener(f -> ch.close());
            }
        }
        streams.clear();
    }
}
