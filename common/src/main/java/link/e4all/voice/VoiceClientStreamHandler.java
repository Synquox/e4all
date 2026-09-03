package link.e4all.voice;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import link.e4all.E4allClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

public class VoiceClientStreamHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private final Runnable onClosed;
    private volatile long lastDropWarnMs = 0L;

    public VoiceClientStreamHandler(Runnable onClosed) {
        this.onClosed = onClosed;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
        if (buf.readableBytes() < 1) return;
        byte type = buf.readByte();
        switch (type) {
            case VoiceFraming.MSG_VOICE_DATA -> {
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                if (!ClientVoiceNegotiator.INSTANCE.offerReceivedPacket(data)) {
                    long now = System.currentTimeMillis();
                    if (now - lastDropWarnMs > 5_000L) {
                        lastDropWarnMs = now;
                        E4allClient.LOGGER.warn("e4all voice: client voice queue full, dropping voice packets");
                    }
                }
            }
            case VoiceFraming.MSG_PONG -> {
                if (buf.readableBytes() >= 8) {
                    CompletableFuture<Long> pongFuture =
                            ctx.channel().attr(VoiceStreamHandler.PONG_FUTURE).get();
                    if (pongFuture != null) {
                        byte[] ts = new byte[8];
                        buf.readBytes(ts);
                        pongFuture.complete(System.nanoTime() - VoiceFraming.readTimestamp(ts, 0));
                    }
                }
            }
            case VoiceFraming.MSG_CLOSE -> ctx.close();
            case VoiceFraming.MSG_KEEPALIVE -> { /* ignore */ }
            default -> E4allClient.LOGGER.debug("e4all voice: ignoring voice message type 0x{} on client stream",
                    Integer.toHexString(type & 0xFF));
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        E4allClient.LOGGER.info("e4all voice: client voice channel closed");
        if (ClientVoiceNegotiator.INSTANCE.voiceChannel() == ctx.channel()) {
            if (onClosed != null) {
                try {
                    onClosed.run();
                } catch (Throwable t) {
                    E4allClient.LOGGER.debug("e4all voice: client voice channel close callback failed", t);
                }
            }
        } else {
            E4allClient.LOGGER.debug("e4all voice: closed channel was not the active channel; keeping receive queue alive");
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        E4allClient.LOGGER.error("e4all voice: error in client voice stream", cause);
        ctx.close();
    }
}
