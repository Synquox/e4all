package link.e4all.voice;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;
import link.e4all.E4allClient;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class VoiceStreamHandler extends SimpleChannelInboundHandler<ByteBuf> {
    public static final long HANDSHAKE_TIMEOUT_MS = 10_000;
    public static final AttributeKey<CompletableFuture<Long>> PONG_FUTURE =
            AttributeKey.valueOf("e4all_voice_pong_future");

    private static final Set<Byte> warnedTypes = ConcurrentHashMap.newKeySet();

    private final VoiceConnectionManager manager;
    private UUID playerUuid;
    private SyntheticAddress syntheticAddress;
    private ScheduledFuture<?> handshakeTimeout;

    public VoiceStreamHandler(VoiceConnectionManager manager) {
        this.manager = manager;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        E4allClient.LOGGER.debug("e4all voice: stream active, waiting {} ms for handshake", HANDSHAKE_TIMEOUT_MS);
        handshakeTimeout = ctx.executor().schedule(() -> {
            if (playerUuid == null) {
                E4allClient.LOGGER.warn("e4all voice: handshake timeout after {} ms, closing voice stream", HANDSHAKE_TIMEOUT_MS);
                ctx.close();
            }
        }, HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
        if (buf.readableBytes() < 1) return;
        byte type = buf.readByte();

        if (playerUuid == null) {
            if (type != VoiceFraming.MSG_HANDSHAKE || buf.readableBytes() < 16) {
                byte[] head = new byte[Math.min(16, Math.max(0, buf.readableBytes()))];
                buf.getBytes(buf.readerIndex(), head);
                E4allClient.LOGGER.warn("e4all voice: invalid voice handshake (type=0x{}, remaining={}), first bytes: {} - closing",
                        Integer.toHexString(type & 0xFF), buf.readableBytes(), VoiceFraming.toHex(head, 16));
                ctx.close();
                return;
            }

            byte[] uuidBytes = new byte[16];
            buf.readBytes(uuidBytes);
            playerUuid = VoiceFraming.readUuid(uuidBytes, 0);

            if (handshakeTimeout != null) {
                handshakeTimeout.cancel(false);
                handshakeTimeout = null;
            }

            this.syntheticAddress = new SyntheticAddress(playerUuid);
            manager.registerStream(playerUuid, ctx.channel());
            E4allClient.LOGGER.info("e4all voice: handshake complete for player {}", playerUuid);
            return;
        }

        switch (type) {
            case VoiceFraming.MSG_CLOSE:
                ctx.close();
                break;
            case VoiceFraming.MSG_KEEPALIVE:
                break;
            case VoiceFraming.MSG_PING:
                ByteBuf pong = ctx.alloc().buffer(1 + buf.readableBytes());
                pong.writeByte(VoiceFraming.MSG_PONG);
                pong.writeBytes(buf);
                ctx.writeAndFlush(pong);
                break;
            case VoiceFraming.MSG_PONG:
                CompletableFuture<Long> pongFuture = ctx.channel().attr(PONG_FUTURE).get();
                if (pongFuture != null && buf.readableBytes() >= 8) {
                    byte[] ts = new byte[8];
                    buf.readBytes(ts);
                    long sent = VoiceFraming.readTimestamp(ts, 0);
                    pongFuture.complete(System.nanoTime() - sent);
                }
                break;
            case VoiceFraming.MSG_VOICE_DATA:
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                VoiceConnectionManager.VoicePacketConsumer consumer = manager.getPacketConsumer();
                if (consumer != null) {
                    consumer.accept(data, System.currentTimeMillis(), syntheticAddress);
                } else {
                    E4allClient.LOGGER.warn("e4all voice: voice data received for player {} but no consumer is registered", playerUuid);
                }
                break;
            default:
                // unknown types after a completed handshake never close the stream
                if (warnedTypes.add(type)) {
                    E4allClient.LOGGER.warn("e4all voice: unknown voice message type 0x{}, ignoring",
                            Integer.toHexString(type & 0xFF));
                }
                break;
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (handshakeTimeout != null) {
            handshakeTimeout.cancel(false);
            handshakeTimeout = null;
        }
        if (playerUuid != null) {
            E4allClient.LOGGER.info("e4all voice: stream closed for player {}", playerUuid);
            manager.removeStream(playerUuid, ctx.channel());
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        E4allClient.LOGGER.error("e4all voice: error in voice stream for player {}", playerUuid, cause);
        ctx.close();
    }
}
