package link.e4all;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Netty inbound handler inserted AFTER the "splitter" (VarInt length-field
 * decoder) but BEFORE the Minecraft "decoder" in the pipeline.
 *
 * After the splitter has extracted a complete length-delimited frame, this
 * handler peeks at the first 4 bytes. If they match
 * {@link VoiceChatPacketHelper#VOICE_FRAME_MAGIC}, the frame is consumed as
 * voice data and forwarded to the bridge handler. Otherwise, it is passed
 * through unchanged so Minecraft can decode it normally.
 *
 * This approach sidesteps the 1.20.2+ {@code CustomPacketPayload} registration
 * requirement entirely because voice data never reaches the Minecraft codec.
 */
public class VoiceChatRawCodec extends ChannelInboundHandlerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger("e4all-voicebridge");

    private final VoiceChatBridgeHandler bridgeHandler;

    public VoiceChatRawCodec(VoiceChatBridgeHandler bridgeHandler) {
        this.bridgeHandler = bridgeHandler;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf buf && buf.readableBytes() >= 4) {
            // Peek at the first 4 bytes without advancing the reader index
            int magic = buf.getInt(buf.readerIndex());

            if (magic == VoiceChatPacketHelper.VOICE_FRAME_MAGIC) {
                // This is a voice data frame — skip the magic header
                buf.skipBytes(4);

                // Extract the voice data
                byte[] voiceData = new byte[buf.readableBytes()];
                buf.readBytes(voiceData);
                buf.release();

                // Forward to the bridge handler
                bridgeHandler.handleRawVoiceData(voiceData);
                // Frame is fully consumed — do NOT pass to next handler
                return;
            }
        }

        // Not a voice frame — pass through to the next handler (Minecraft decoder)
        super.channelRead(ctx, msg);
    }
}
