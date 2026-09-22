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
    private static final long HELLO_TIMEOUT_MS = 10_000;
    private static final int MAX_HELLO_RETRIES = 3;

    // dedicated scheduler so watchdog isn't blocked by common pool
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "e4all-voice-hello-watchdog");
                t.setDaemon(true);
                return t;
            });

    private volatile DialtoneChannel voiceChannel;
    private volatile boolean negotiating = false;
    private volatile boolean connectedMessageShown = false;
    private final AtomicReference<LinkedBlockingQueue<VoiceDataPacket>> receiveQueue = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicLong helloGeneration = new java.util.concurrent.atomic.AtomicLong();
    // invalidates in-flight dials on stop()/rejoin
    private final java.util.concurrent.atomic.AtomicLong dialGeneration = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean stopped = false;
    private volatile long offerAcceptedAtMs = 0L;
    private volatile boolean offerSeen = false;
    private volatile int helloRetries = 0;
    // true once HELLO retries exhausted without an OFFER
    private volatile boolean negotiationFailed = false;

    private ClientVoiceNegotiator() {}

    public void sendHello() {
        boolean hasVoiceClient = hasSvc();
        stopped = false;
        negotiationFailed = false;
        offerSeen = false;
        helloRetries = 0;
        E4allClient.LOGGER.info("e4all voice: sending HELLO (hasVoiceClient={})", hasVoiceClient);
        VoiceControl.sendToServer(VoiceControl.encodeHello(hasVoiceClient));
        scheduleHelloWatchdog();
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

    private void scheduleHelloWatchdog() {
        final long generation = helloGeneration.incrementAndGet();
        // linear backoff: 10s for the initial attempt, then 20s / 30s / 40s per retry
        long delayMs = HELLO_TIMEOUT_MS * (helloRetries + 1);
        WATCHDOG.schedule(() -> {
            if (helloGeneration.get() != generation) return;
            onHelloTimeout();
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void onHelloTimeout() {
        DialtoneChannel ch = voiceChannel;
        if (ch != null && ch.isActive()) return;
        E4allClient.LOGGER.warn(
                "e4all voice: no OFFER from the host {} ms after HELLO (offerSeen={}, retries={}) - the e4all:voice control channel is not getting through; "
                        + "check that both players run the same e4all build for this Minecraft version",
                HELLO_TIMEOUT_MS * (helloRetries + 1), offerSeen, helloRetries);
        if (helloRetries >= MAX_HELLO_RETRIES) {
            negotiationFailed = true;
            E4allClient.LOGGER.error(
                    "e4all voice: voice negotiation failed permanently - {} HELLO attempts produced no OFFER; "
                            + "outgoing voice packets will be dropped until the next reconnect instead of buffering forever",
                    helloRetries + 1);
            showFailure(VoiceFailure.NEGOTIATION_TIMEOUT);
            return;
        }
        helloRetries++;
        E4allClient.LOGGER.info("e4all voice: re-sending HELLO (retry {} of {})", helloRetries, MAX_HELLO_RETRIES);
        VoiceControl.sendToServer(VoiceControl.encodeHello(hasSvc()));
        scheduleHelloWatchdog();
    }

    public void onOffer(byte transport, String ticket, List<String> candidates,
                        VoiceFailure failure) {
        offerSeen = true;
        negotiationFailed = false;
        helloGeneration.incrementAndGet();
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
            dialGeneration.incrementAndGet();
            offerAcceptedAtMs = System.currentTimeMillis();
            dialDialtone(ticket, dialGeneration.get());
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

    private void dialDialtone(String ticket, long gen) {
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
                    // check if late dial can still be adopted as recovery
                    DialtoneChannel current = voiceChannel;
                    boolean newerChannelActive = current != null && current.isActive() && current != ch;
                    DialRecoveryPolicy.Decision decision = DialRecoveryPolicy.evaluate(
                            stopped, dialGeneration.get() == gen, isSessionAlive(), newerChannelActive,
                            System.currentTimeMillis() - offerAcceptedAtMs);
                    switch (decision) {
                        case ADOPT -> {
                            E4allClient.LOGGER.info(
                                    "e4all voice: dial completed after the negotiation timer - adopting late channel as relay recovery ({}ms after OFFER)",
                                    System.currentTimeMillis() - offerAcceptedAtMs);
                            voiceChannel = ch;
                            E4allClient.LOGGER.info("e4all voice: Dialtone voice connected via late relay dial (RTT {}ms)", rttMs);
                            DialtoneClientVoicechatSocket lateSocket = DialtoneClientVoicechatSocket.getActiveInstance();
                            if (lateSocket != null) {
                                lateSocket.flushPending(ch);
                            }
                            VoiceControl.sendToServer(VoiceControl.encodeResult(
                                    true, VoiceControl.TRANSPORT_DIALTONE, rttMs,
                                    null, List.of()));
                        }
                        case CLOSE_SUPERSEDED -> {
                            E4allClient.LOGGER.warn("e4all voice: dial finished after a failure was already reported; a newer voice channel is active - closing late channel");
                            ch.close();
                        }
                        case CLOSE_SESSION_GONE -> {
                            E4allClient.LOGGER.debug("e4all voice: late dial finished but the game session is gone; closing late channel");
                            ch.close();
                        }
                        case CLOSE_WINDOW_EXPIRED -> {
                            E4allClient.LOGGER.warn("e4all voice: late dial finished {}ms after the OFFER, beyond the {}ms recovery window; closing late channel",
                                    System.currentTimeMillis() - offerAcceptedAtMs, DialRecoveryPolicy.RECOVERY_WINDOW_MS);
                            ch.close();
                        }
                        default -> {
                            E4allClient.LOGGER.debug("e4all voice: negotiation was stopped; closing orphaned late dial");
                            ch.close();
                        }
                    }
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
              Throwable cause = (t instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : t;
              if (cause instanceof TimeoutException) {
                  E4allClient.LOGGER.warn("e4all voice: Dialtone dial timed out after {}ms - keeping the dial alive as a relay recovery attempt", CONNECT_TIMEOUT_MS);
                  if (resultSent.compareAndSet(false, true)) {
                      VoiceControl.sendToServer(VoiceControl.encodeResult(
                              false, VoiceControl.TRANSPORT_DIALTONE, 0,
                              VoiceFailure.TIMEOUT, List.of()));
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
        offerSeen = false;
        stopped = true;
        dialGeneration.incrementAndGet();
        helloGeneration.incrementAndGet();
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

    public boolean isNegotiationFailed() {
        return negotiationFailed;
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
    private static boolean isSessionAlive() {
        try {
            return Minecraft.getInstance().getConnection() != null;
        } catch (Throwable t) {
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
