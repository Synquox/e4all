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
        if (in.readableBytes() < 1) return;

        routed = true;
        byte magic = in.getByte(in.readerIndex());

        ChannelPipeline pipeline = ctx.pipeline();

        if (magic == VoiceFraming.VOICE_MAGIC) {
            in.readByte();
            E4allClient.LOGGER.info("Voice stream detected (magic 0xE4). Installing voice pipeline.");

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
            E4allClient.LOGGER.debug("Non-voice stream detected (first byte 0x{}). Passing to Minecraft handler.",
                    Integer.toHexString(magic & 0xFF));

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
