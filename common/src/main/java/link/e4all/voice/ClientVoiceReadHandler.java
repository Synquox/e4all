package link.e4all.voice;

import de.maxhenkel.voicechat.api.RawUdpPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import link.e4all.E4allClient;

import java.net.InetSocketAddress;
import java.util.concurrent.LinkedBlockingQueue;

public final class ClientVoiceReadHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private final LinkedBlockingQueue<RawUdpPacket> queue;
    private final InetSocketAddress syntheticSource = new InetSocketAddress("127.0.0.1", 0);
    private volatile long lastDropWarnMs = 0L;

    public ClientVoiceReadHandler(LinkedBlockingQueue<RawUdpPacket> queue) {
        this.queue = queue;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
        if (buf.readableBytes() < 1) return;
        byte type = buf.readByte();
        if (type == VoiceFraming.MSG_VOICE_DATA) {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            if (!queue.offer(new RawUdpPacketImpl(data, System.currentTimeMillis(), syntheticSource))) {
                long now = System.currentTimeMillis();
                if (now - lastDropWarnMs > 5_000L) {
                    lastDropWarnMs = now;
                    E4allClient.LOGGER.warn("e4all voice: client voice queue full, dropping voice packets");
                }
            }
        } else if (type == VoiceFraming.MSG_CLOSE) {
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        E4allClient.LOGGER.info("e4all voice: Dialtone voice channel closed");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        E4allClient.LOGGER.error("e4all voice: Error in client voice read handler", cause);
        ctx.close();
    }
}
