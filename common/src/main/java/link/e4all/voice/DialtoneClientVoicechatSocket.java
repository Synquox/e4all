package link.e4all.voice;

import de.maxhenkel.voicechat.api.ClientVoicechatSocket;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import io.netty.buffer.ByteBuf;
import link.e4all.E4allClient;
import link.e4all.dialtone.DialtoneChannel;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.LinkedBlockingQueue;

// SVC client socket over Dialtone
public final class DialtoneClientVoicechatSocket implements ClientVoicechatSocket {
    private static volatile DialtoneClientVoicechatSocket activeInstance;

    public static DialtoneClientVoicechatSocket getActiveInstance() {
        return activeInstance;
    }

    private final LinkedBlockingQueue<VoiceDataPacket> queue;
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> pendingOutgoing =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final InetSocketAddress syntheticSource = new InetSocketAddress("127.0.0.1", 24454);
    private volatile boolean closed = false;
    private volatile long lastDropWarnMs = 0L;

    public DialtoneClientVoicechatSocket() {
        this.queue = ClientVoiceNegotiator.INSTANCE.getOrCreateReceiveQueue();
        activeInstance = this;
    }

    @Override
    public void open() {
        E4allClient.LOGGER.info("e4all voice: SVC client socket installed; voice will use the negotiated Dialtone channel");
    }

    public void flushPending(DialtoneChannel ch) {
        if (ch == null || !ch.isActive()) return;
        byte[] pending;
        while ((pending = pendingOutgoing.poll()) != null) {
            try {
                ByteBuf buf = ch.alloc().buffer(1 + pending.length);
                buf.writeByte(VoiceFraming.MSG_VOICE_DATA);
                buf.writeBytes(pending);
                ch.writeAndFlush(buf);
            } catch (Throwable t) {
                E4allClient.LOGGER.debug("e4all voice: failed to flush pending voice packet", t);
            }
        }
    }

    @Override
    public RawUdpPacket read() throws Exception {
        while (!closed) {
            VoiceDataPacket packet = queue.take();
            if (closed) {
                throw new java.io.IOException("e4all voice socket closed");
            }
            if (packet == VoiceDataPacket.POISON_PILL) {
                if (closed) {
                    throw new java.io.IOException("e4all voice socket closed");
                }
                // Ignore stale poison pill from prior connection if this socket is still active
                continue;
            }
            return new RawUdpPacketImpl(packet.data(), packet.timestampAtReceipt(), syntheticSource);
        }
        throw new java.io.IOException("e4all voice socket closed");
    }

    @Override
    public void send(byte[] data, SocketAddress address) {
        if (closed) return;
        DialtoneChannel ch = ClientVoiceNegotiator.INSTANCE.voiceChannel();
        if (ch == null || !ch.isActive()) {
            if (pendingOutgoing.size() < 32) {
                pendingOutgoing.offer(data);
            }
            warnChannelNotReady();
            return;
        }
        flushPending(ch);
        ByteBuf buf = ch.alloc().buffer(1 + data.length);
        buf.writeByte(VoiceFraming.MSG_VOICE_DATA);
        buf.writeBytes(data);
        ch.writeAndFlush(buf);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (activeInstance == this) {
            activeInstance = null;
        }
        pendingOutgoing.clear();
        // wake a blocked read(); the negotiator closes the channel on disconnect
        ClientVoiceNegotiator.pillQueue(queue);
        ClientVoiceNegotiator.INSTANCE.detachReceiveQueue(queue);
        E4allClient.LOGGER.info("e4all voice: SVC client socket closed");
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    private void warnChannelNotReady() {
        long now = System.currentTimeMillis();
        if (now - lastDropWarnMs > 5_000L) {
            lastDropWarnMs = now;
            E4allClient.LOGGER.warn("e4all voice: voice channel not ready yet, buffering outgoing voice packets (negotiation still in progress?)");
        }
    }
}
