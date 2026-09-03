package link.e4all.voice;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import link.e4all.AndroidDetector;
import link.e4all.E4allClient;
import link.e4all.Mirror;
import link.e4all.dialtone.DialtoneAddress;
import link.e4all.dialtone.DialtoneChannel;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ClientVoiceNegotiator {
    public static final ClientVoiceNegotiator INSTANCE = new ClientVoiceNegotiator();

    private static final long CONNECT_TIMEOUT_MS = 20_000;
    private static final int MAX_QUEUED_PACKETS = 256;

    private volatile DialtoneChannel voiceChannel;
    private volatile boolean negotiating = false;
    private volatile boolean connectedMessageShown = false;
    private final AtomicReference<LinkedBlockingQueue<VoiceDataPacket>> receiveQueue = new AtomicReference<>();

    private ClientVoiceNegotiator() {}

    public void sendHello() {
        boolean hasVoiceClient = hasSvc();
        E4allClient.LOGGER.info("e4all voice: sending HELLO (hasVoiceClient={})", hasVoiceClient);
        VoiceControl.sendToServer(VoiceControl.encodeHello(hasVoiceClient));
        if (hasVoiceClient && !AndroidDetector.isAndroid()) {
            CompletableFuture.runAsync(() -> {
                try {
                    VoiceEndpointStack.INSTANCE.getOrCreate();
                } catch (Throwable t) {
                    E4allClient.LOGGER.debug("e4all voice: pre-warming voice endpoint failed", t);
                }
            });
        }
    }

    public void onOffer(byte transport, String ticket, List<String> candidates,
                        VoiceFailure failure) {
        E4allClient.LOGGER.info("e4all voice: OFFER received (transport={}, ticket={}, candidates={}, failure={})",
                transport, ticket.isEmpty() ? "<none>" : ticket.substring(0, Math.min(20, ticket.length())) + "...",
                candidates.size(), failure != null ? failure.logTag : "none");

        if (failure != null) {
            E4allClient.LOGGER.warn("e4all voice: host reported voice failure: {}", failure.logTag);
            showFailure(failure);
            return;
        }

        if (transport == VoiceControl.TRANSPORT_NONE) {
            E4allClient.LOGGER.info("e4all voice: host offered TRANSPORT_NONE, voice disabled");
            showFailure(VoiceFailure.TRANSPORT_UNAVAILABLE);
            return;
        }

        if (transport == VoiceControl.TRANSPORT_DIALTONE) {
            if (AndroidDetector.isAndroid()) {
                E4allClient.LOGGER.info("e4all voice: Android client cannot use Dialtone, reporting back");
                VoiceControl.sendToServer(VoiceControl.encodeResult(
                        false, VoiceControl.TRANSPORT_DIALTONE, 0,
                        VoiceFailure.TRANSPORT_UNAVAILABLE, List.of()));
                return;
            }
            if (voiceChannel != null && voiceChannel.isActive()) {
                E4allClient.LOGGER.debug("e4all voice: OFFER received while voice channel is already active; ignoring");
                return;
            }
            if (negotiating) {
                E4allClient.LOGGER.warn("e4all voice: OFFER received while already negotiating; ignoring duplicate");
                return;
            }
            negotiating = true;
            dialDialtone(ticket);
        } else if (transport == VoiceControl.TRANSPORT_UDP) {
            E4allClient.LOGGER.info("e4all voice: UDP transport offered but not implemented yet");
            VoiceControl.sendToServer(VoiceControl.encodeResult(
                    false, VoiceControl.TRANSPORT_UDP, 0,
                    VoiceFailure.TRANSPORT_UNAVAILABLE, List.of()));
        } else {
            E4allClient.LOGGER.warn("e4all voice: unknown transport {} offered; reporting failure", transport);
            VoiceControl.sendToServer(VoiceControl.encodeResult(
                    false, transport, 0,
                    VoiceFailure.TRANSPORT_UNAVAILABLE, List.of()));
        }
    }

    private void dialDialtone(String ticket) {
        final AtomicBoolean resultSent = new AtomicBoolean(false);
        CompletableFuture.runAsync(() -> {
            DialtoneChannel ch = null;
            try {
                E4allClient.LOGGER.info("e4all voice: dialing Dialtone voice endpoint");

                Bootstrap bs = new Bootstrap()
                        .group(VoiceEndpointStack.INSTANCE.group())
                        .channel(DialtoneChannel.class)
                        .handler(new ChannelInboundHandlerAdapter());

                ch = (DialtoneChannel) bs
                        .connect(new DialtoneAddress(ticket, DialtoneAddress.VOICE_ALPN))
                        .syncUninterruptibly().channel();

                // raw header first: the host's VoiceStreamRouter peeks the magic before any frames
                ByteBuf header = Unpooled.buffer(VoiceFraming.HEADER_LEN);
                header.writeBytes(VoiceFraming.MAGIC);
                header.writeByte(VoiceFraming.VERSION);
                ch.writeAndFlush(header).syncUninterruptibly();

                // Install voice framing + client read handler, then send the handshake
                ch.pipeline().addLast("voiceFrameDecoder",
                        new LengthFieldBasedFrameDecoder(65535, 0, 2, 0, 2));
                ch.pipeline().addLast("voiceFrameEncoder",
                        new LengthFieldPrepender(2));
                ch.pipeline().addLast("voiceHandler",
                        new VoiceClientStreamHandler(() -> pillQueue(receiveQueue.get())));

                UUID playerUuid = getPlayerUuid();
                byte[] handshake = VoiceFraming.createHandshake(playerUuid);
                ByteBuf handshakeBuf = Unpooled.buffer(handshake.length);
                handshakeBuf.writeBytes(handshake);
                ch.writeAndFlush(handshakeBuf).syncUninterruptibly();

                int rttMs = measureRtt(ch);

                // late-dial guard: skip ownership if the dial already timed out
                if (resultSent.compareAndSet(false, true)) {
                    voiceChannel = ch;
                    E4allClient.LOGGER.info("e4all voice: Dialtone voice connected (RTT {}ms)", rttMs);
                    DialtoneClientVoicechatSocket activeSocket = DialtoneClientVoicechatSocket.getActiveInstance();
                    if (activeSocket != null) {
                        activeSocket.flushPending(ch);
                    }
                    VoiceControl.sendToServer(VoiceControl.encodeResult(
                            true, VoiceControl.TRANSPORT_DIALTONE, rttMs,
                            null, List.of()));
                } else {
                    E4allClient.LOGGER.warn("e4all voice: dial finished after a failure was already reported; closing late channel");
                    ch.close();
                }
            } catch (Throwable t) {
                E4allClient.LOGGER.error("e4all voice: Dialtone voice dial failed", t);
                if (resultSent.compareAndSet(false, true)) {
                    VoiceControl.sendToServer(VoiceControl.encodeResult(
                            false, VoiceControl.TRANSPORT_DIALTONE, 0,
                            VoiceFailure.TRANSPORT_UNAVAILABLE, List.of()));
                }
                if (ch != null) {
                    try { ch.close(); } catch (Throwable ignored) {}
                }
            } finally {
                negotiating = false;
            }
        }).orTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
          .exceptionally(t -> {
              // orTimeout() surfaces as CompletionException; unwrap it
              Throwable cause = (t instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : t;
              if (cause instanceof TimeoutException) {
                  E4allClient.LOGGER.warn("e4all voice: Dialtone dial timed out after {}ms", CONNECT_TIMEOUT_MS);
                  if (resultSent.compareAndSet(false, true)) {
                      VoiceControl.sendToServer(VoiceControl.encodeResult(
                              false, VoiceControl.TRANSPORT_DIALTONE, 0,
                              VoiceFailure.TIMEOUT, List.of()));
                  }
                  DialtoneChannel late = voiceChannel;
                  if (late != null) {
                      try { late.close(); } catch (Throwable ignored) {}
                  }
              } else {
                  E4allClient.LOGGER.debug("e4all voice: dial future completed exceptionally", t);
              }
              negotiating = false;
              return null;
          });
    }


    private int measureRtt(DialtoneChannel ch) {
        try {
            CompletableFuture<Long> pongFuture = new CompletableFuture<>();
            ch.attr(VoiceStreamHandler.PONG_FUTURE).set(pongFuture);

            byte[] ping = VoiceFraming.createPing(System.nanoTime());
            ByteBuf pingBuf = Unpooled.buffer(ping.length);
            pingBuf.writeBytes(ping);
            ch.writeAndFlush(pingBuf).syncUninterruptibly();

            long rttNanos = pongFuture.get(5, TimeUnit.SECONDS);
            int rttMs = (int) (rttNanos / 1_000_000);
            ch.attr(VoiceStreamHandler.PONG_FUTURE).set(null);
            return rttMs;
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("e4all voice: RTT measurement failed, defaulting to 0", t);
            return 0;
        }
    }

    public void onReady(boolean ok, byte transport, int rttMs, VoiceFailure failure) {
        if (ok) {
            E4allClient.LOGGER.info("e4all voice: READY - voice connected via {} (RTT {}ms)",
                    transportName(transport), rttMs);
            if (!connectedMessageShown) {
                connectedMessageShown = true;
                showSuccess(transport, rttMs);
            }
        } else {
            E4allClient.LOGGER.warn("e4all voice: READY - voice failed (failure={})",
                    failure != null ? failure.logTag : "unknown");
            showFailure(failure);
        }
    }

    public void stop() {
        connectedMessageShown = false;
        negotiating = false;
        DialtoneChannel ch = voiceChannel;
        voiceChannel = null;
        if (ch != null) {
            if (ch.isActive()) {
                try {
                    ByteBuf close = ch.alloc().buffer(1);
                    close.writeByte(VoiceFraming.MSG_CLOSE);
                    ch.writeAndFlush(close);
                } catch (Throwable ignored) {}
            }
            try { ch.close(); } catch (Throwable ignored) {}
        }
        pillQueue(receiveQueue.getAndSet(null));
    }

    public DialtoneChannel voiceChannel() {
        return voiceChannel;
    }

    public LinkedBlockingQueue<VoiceDataPacket> getOrCreateReceiveQueue() {
        LinkedBlockingQueue<VoiceDataPacket> q = receiveQueue.get();
        if (q != null) {
            q.remove(VoiceDataPacket.POISON_PILL);
            return q;
        }
        var newQ = new LinkedBlockingQueue<VoiceDataPacket>(MAX_QUEUED_PACKETS);
        if (receiveQueue.compareAndSet(null, newQ)) {
            return newQ;
        }
        LinkedBlockingQueue<VoiceDataPacket> existing = receiveQueue.get();
        existing.remove(VoiceDataPacket.POISON_PILL);
        return existing;
    }

    public boolean offerReceivedPacket(byte[] data) {
        LinkedBlockingQueue<VoiceDataPacket> q = getOrCreateReceiveQueue();
        return q.offer(new VoiceDataPacket(data, System.currentTimeMillis()));
    }

    public LinkedBlockingQueue<VoiceDataPacket> resetReceiveQueue() {
        var newQ = new LinkedBlockingQueue<VoiceDataPacket>(MAX_QUEUED_PACKETS);
        LinkedBlockingQueue<VoiceDataPacket> old = receiveQueue.getAndSet(newQ);
        pillQueue(old);
        return newQ;
    }

    public void detachReceiveQueue(LinkedBlockingQueue<VoiceDataPacket> expected) {
        receiveQueue.compareAndSet(expected, null);
    }

    static void pillQueue(LinkedBlockingQueue<VoiceDataPacket> queue) {
        if (queue == null) return;
        if (!queue.offer(VoiceDataPacket.POISON_PILL)) {
            queue.clear();
            queue.offer(VoiceDataPacket.POISON_PILL);
        }
    }


    private static boolean hasSvc() {
        try {
            Class.forName("de.maxhenkel.voicechat.api.VoicechatApi", false,
                    ClientVoiceNegotiator.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static UUID getPlayerUuid() {
        try {
            if (Minecraft.getInstance().player != null) {
                UUID uuid = Minecraft.getInstance().player.getUUID();
                if (uuid != null) return uuid;
            }
        } catch (Throwable ignored) {}
        try {
            UUID uuid = Minecraft.getInstance().getUser().getProfileId();
            if (uuid != null) return uuid;
        } catch (Throwable ignored) {}
        try {
            String name = Minecraft.getInstance().getUser().getName();
            return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return UUID.randomUUID();
        }
    }

    private static String transportName(byte transport) {
        return switch (transport) {
            case VoiceControl.TRANSPORT_DIALTONE -> "Direct P2P";
            case VoiceControl.TRANSPORT_UDP -> "Direct UDP";
            default -> "None";
        };
    }

    private static void showSuccess(byte transport, int rttMs) {
        try {
            Mirror.addMessage(Mirror.translatable("text.e4all_minecraft.voice.connected",
                    transportName(transport), rttMs));
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("e4all voice: could not display success message", t);
        }
    }

    private static void showFailure(VoiceFailure failure) {
        try {
            if (failure != null) {
                Mirror.addMessage(Mirror.translatable(failure.langKey));
            } else {
                Mirror.addMessage(Mirror.translatable(
                        VoiceFailure.TRANSPORT_UNAVAILABLE.langKey));
            }
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("e4all voice: could not display failure message", t);
        }
    }
}
