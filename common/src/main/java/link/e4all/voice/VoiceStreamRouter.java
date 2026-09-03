package link.e4all.voice;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import link.e4all.E4allClient;

import java.util.List;

public class VoiceStreamRouter extends ByteToMessageDecoder {
    private final ChannelHandler minecraftHandler;
    private boolean routed = false;

    public VoiceStreamRouter(ChannelHandler minecraftHandler) {
        this.minecraftHandler = minecraftHandler;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (routed) return;
        if (in.readableBytes() < VoiceFraming.HEADER_LEN) return;

        routed = true;

        byte[] head = new byte[VoiceFraming.HEADER_LEN];
        in.getBytes(in.readerIndex(), head);

        ChannelPipeline pipeline = ctx.pipeline();

        if (VoiceFraming.matchesMagic(head, head.length)) {
            byte version = head[VoiceFraming.MAGIC.length];
            if (version != VoiceFraming.VERSION) {
                byte[] hex = new byte[Math.min(16, in.readableBytes())];
                in.getBytes(in.readerIndex(), hex);
                E4allClient.LOGGER.warn("e4all voice: voice protocol version mismatch (stream says 0x{}, we speak 0x{}), first bytes: {} - closing",
                        Integer.toHexString(version & 0xFF), Integer.toHexString(VoiceFraming.VERSION & 0xFF),
                        VoiceFraming.toHex(hex, 16));
                ctx.close();
                return;
            }

            in.skipBytes(VoiceFraming.HEADER_LEN);
            E4allClient.LOGGER.info("e4all voice: voice stream detected (magic E4V1 v{}), installing voice pipeline",
                    Integer.toHexString(VoiceFraming.VERSION & 0xFF));

            pipeline.addLast("voiceFrameDecoder",
                    new LengthFieldBasedFrameDecoder(65535, 0, 2, 0, 2));
            pipeline.addLast("voiceFrameEncoder",
                    new LengthFieldPrepender(2));
            pipeline.addLast("voiceHandler",
                    new VoiceStreamHandler(VoiceConnectionManager.INSTANCE));

            if (ctx.channel().isActive()) {
                pipeline.fireChannelActive();
            }
            pipeline.remove(this);
        } else {
            E4allClient.LOGGER.debug("e4all voice: game stream detected (first bytes {}), passing to minecraft handler byte-intact",
                    VoiceFraming.toHex(head, 4));

            pipeline.addLast(minecraftHandler);
            if (ctx.channel().isActive()) {
                pipeline.fireChannelActive();
            }
            pipeline.remove(this);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        E4allClient.LOGGER.error("Error in VoiceStreamRouter", cause);
        ctx.close();
    }
}
