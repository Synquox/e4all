package link.e4all.voice;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
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
        byte magic = in.readByte();

        if (magic == VoiceFraming.VOICE_MAGIC) {
            E4allClient.LOGGER.debug("Voice stream detected (magic 0xE4). Installing voice pipeline.");
            ctx.pipeline().remove(this);

            ctx.pipeline().addLast("voiceFrameDecoder",
                    new LengthFieldBasedFrameDecoder(65535, 0, 2, 0, 2));
            ctx.pipeline().addLast("voiceFrameEncoder",
                    new LengthFieldPrepender(2));
            ctx.pipeline().addLast("voiceHandler",
                    new VoiceStreamHandler(VoiceConnectionManager.INSTANCE));

            if (in.readableBytes() > 0) {
                out.add(in.retain());
            }
        } else {
            E4allClient.LOGGER.debug("Non-voice stream detected. Passing to Minecraft handler.");
            in.readerIndex(in.readerIndex() - 1);
            ctx.pipeline().remove(this);
            ctx.pipeline().addLast(minecraftHandler);
            out.add(in.retain());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        E4allClient.LOGGER.error("Error in VoiceStreamRouter", cause);
        ctx.close();
    }
}
