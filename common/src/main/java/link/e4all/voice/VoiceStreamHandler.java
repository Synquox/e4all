package link.e4all.voice;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import link.e4all.E4allClient;

import java.util.UUID;

public class VoiceStreamHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private final VoiceConnectionManager manager;
    private UUID playerUuid;
    private SyntheticAddress syntheticAddress;

    public VoiceStreamHandler(VoiceConnectionManager manager) {
        this.manager = manager;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        E4allClient.LOGGER.debug("Voice stream channel active, waiting for handshake...");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
        if (buf.readableBytes() < 1) return;
        byte type = buf.readByte();

        if (playerUuid == null) {
            if (type != VoiceFraming.MSG_HANDSHAKE || buf.readableBytes() < 16) {
                E4allClient.LOGGER.warn("Invalid voice handshake (type=0x{}, remaining={}). Closing.",
                        Integer.toHexString(type & 0xFF), buf.readableBytes());
                ctx.close();
                return;
            }

            byte[] uuidBytes = new byte[16];
            buf.readBytes(uuidBytes);
            playerUuid = VoiceFraming.readUuid(uuidBytes, 0);

            this.syntheticAddress = new SyntheticAddress(playerUuid);
            manager.registerStream(playerUuid, ctx.channel());
            E4allClient.LOGGER.info("Voice handshake complete for player {}", playerUuid);
            return;
        }

        switch (type) {
            case VoiceFraming.MSG_CLOSE:
                ctx.close();
                break;
            case VoiceFraming.MSG_KEEPALIVE:
                break;
            case VoiceFraming.MSG_VOICE_DATA:
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                RelayVoicechatSocket socket = manager.getSocket();
                if (socket != null) {
                    socket.enqueuePacket(data, System.currentTimeMillis(), syntheticAddress);
                } else {
                    E4allClient.LOGGER.warn("Voice data received for player {} but RelayVoicechatSocket is null!", playerUuid);
                }
                break;
            default:
                E4allClient.LOGGER.warn("Unknown voice message type: 0x{}", Integer.toHexString(type & 0xFF));
                break;
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (playerUuid != null) {
            E4allClient.LOGGER.info("Voice stream closed for player {}", playerUuid);
            manager.removeStream(playerUuid);
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        E4allClient.LOGGER.error("Error in voice stream for player {}", playerUuid, cause);
        ctx.close();
    }
}
