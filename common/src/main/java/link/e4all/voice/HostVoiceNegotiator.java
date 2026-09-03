package link.e4all.voice;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import link.e4all.AndroidDetector;
import link.e4all.Config;
import link.e4all.E4allClient;
import link.e4all.dialtone.DialtoneAddress;
import link.e4all.dialtone.DialtoneServerChannel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class HostVoiceNegotiator {
    public static final HostVoiceNegotiator INSTANCE = new HostVoiceNegotiator();

    private final EventLoopGroup voiceGroup = new DefaultEventLoopGroup(1);
    private volatile DialtoneServerChannel voiceServerChannel;
    private volatile String voiceTicket;
    private final ConcurrentHashMap<String, NegotiationState> negotiations = new ConcurrentHashMap<>();

    private enum Phase { OFFERED, RESULT_RECEIVED }
    private record NegotiationState(Phase phase, byte transport, long createdAtMs) {}

    private static final long NEGOTIATION_TTL_MS = 60_000;

    private HostVoiceNegotiator() {}

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        negotiations.values().removeIf(state -> now - state.createdAtMs() > NEGOTIATION_TTL_MS);
    }

    private static boolean hasSvc() {
        try {
            Class.forName("de.maxhenkel.voicechat.api.VoicechatApi", false,
                    HostVoiceNegotiator.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public void ensureVoiceEndpointAsync() {
        if (!AndroidDetector.isAndroid() && Config.INSTANCE.dialtoneHostEnabled.value()) {
            java.util.concurrent.CompletableFuture.runAsync(this::ensureVoiceEndpoint);
        }
    }

    public synchronized void ensureVoiceEndpoint() {
        if (voiceServerChannel != null && voiceServerChannel.isActive()) return;
        if (AndroidDetector.isAndroid()) {
            E4allClient.LOGGER.info("e4all voice: Android host - skipping Dialtone voice endpoint");
            return;
        }
        try {
            E4allClient.LOGGER.info("e4all voice: starting voice endpoint");
            var bootstrap = new ServerBootstrap()
                    .channel(DialtoneServerChannel.class)
                    .option(DialtoneServerChannel.VOICE_MODE, true)
                    .handler(new io.netty.channel.ChannelInboundHandlerAdapter() {
                        @Override
                        public void userEventTriggered(io.netty.channel.ChannelHandlerContext ctx, Object evt) throws Exception {
                            super.userEventTriggered(ctx, evt);
                            if (evt instanceof DialtoneAddress addr && addr.actualAddress != null && !addr.actualAddress.isEmpty()) {
                                voiceTicket = addr.actualAddress;
                                E4allClient.LOGGER.info("e4all voice: updated voice ticket from watchAddress, length={}", voiceTicket.length());
                            }
                        }
                    })
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast("voiceRouter",
                                    new VoiceStreamRouter(new io.netty.channel.ChannelInboundHandlerAdapter()));
                        }
                    })
                    .group(voiceGroup)
                    .localAddress(new DialtoneAddress(""));

            var future = bootstrap.bind().syncUninterruptibly();
            voiceServerChannel = (DialtoneServerChannel) future.channel();
            // direct addresses only (not sanitizeTicket which strips them)
            voiceTicket = voiceServerChannel.getEndpoint().address();
            E4allClient.LOGGER.info("e4all voice: voice endpoint bound, ticket length={}",
                    voiceTicket != null ? voiceTicket.length() : 0);
        } catch (Throwable t) {
            E4allClient.LOGGER.error("e4all voice: failed to start voice endpoint", t);
            voiceServerChannel = null;
            voiceTicket = null;
        }
    }

    public void onHello(ServerPlayer player, boolean hasVoiceClient) {
        sweepExpired();
        E4allClient.LOGGER.info("e4all voice: HELLO from {} (hasVoiceClient={})",
                player.getScoreboardName(), hasVoiceClient);

        if (!Config.INSTANCE.voiceP2PEnabled.value()) {
            E4allClient.LOGGER.info("e4all voice: P2P voice disabled in config, declining");
            VoiceControl.sendToPlayer(player, VoiceControl.encodeOffer(
                    VoiceControl.TRANSPORT_NONE, "", List.of(),
                    VoiceFailure.TRANSPORT_UNAVAILABLE));
            return;
        }

        if (!hasVoiceClient) {
            // Guest has e4all but no SVC; voice requires SVC on both sides
            VoiceControl.sendToPlayer(player, VoiceControl.encodeOffer(
                    VoiceControl.TRANSPORT_NONE, "", List.of(),
                    VoiceFailure.PEER_LACKS_E4ALL));
            return;
        }

        if (!hasSvc()) {
            E4allClient.LOGGER.info("e4all voice: host lacks SVC, cannot offer voice to {}",
                    player.getScoreboardName());
            VoiceControl.sendToPlayer(player, VoiceControl.encodeOffer(
                    VoiceControl.TRANSPORT_NONE, "", List.of(),
                    VoiceFailure.HOST_LACKS_SVC));
            return;
        }

        // Determine transport: Dialtone if available, else offer no transport
        byte transport;
        String ticket = "";
        VoiceFailure failure = null;

        if (!AndroidDetector.isAndroid() && Config.INSTANCE.dialtoneHostEnabled.value()) {
            ensureVoiceEndpoint();
            // wait for direct addresses to resolve
            for (int i = 0; i < 25; i++) {
                if (voiceTicket != null && voiceTicket.length() > 95) break;
                DialtoneServerChannel ch = voiceServerChannel;
                if (ch != null && ch.isActive() && ch.getEndpoint() != null) {
                    String addr = ch.getEndpoint().address();
                    if (addr != null && addr.length() > 95) {
                        voiceTicket = addr;
                        break;
                    }
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            DialtoneServerChannel ch = voiceServerChannel;
            if (ch != null && ch.isActive() && ch.getEndpoint() != null) {
                String addr = ch.getEndpoint().address();
                if (addr != null && !addr.isEmpty()) {
                    voiceTicket = addr;
                }
            }
            if (voiceTicket != null && !voiceTicket.isEmpty()) {
                transport = VoiceControl.TRANSPORT_DIALTONE;
                ticket = voiceTicket;
            } else {
                // no Dialtone ticket; UDP fallback not implemented, so offer nothing
                transport = VoiceControl.TRANSPORT_NONE;
                failure = VoiceFailure.TRANSPORT_UNAVAILABLE;
            }
        } else {
            // Android host or Dialtone disabled: no P2P voice transport available
            transport = VoiceControl.TRANSPORT_NONE;
            failure = VoiceFailure.TRANSPORT_UNAVAILABLE;
        }

        negotiations.put(player.getStringUUID(),
                new NegotiationState(Phase.OFFERED, transport, System.currentTimeMillis()));

        E4allClient.LOGGER.info("e4all voice: OFFER to {} (transport={}, ticket={})",
                player.getScoreboardName(), transport,
                ticket.isEmpty() ? "<none>" : ticket.substring(0, Math.min(20, ticket.length())) + "...");

        VoiceControl.sendToPlayer(player, VoiceControl.encodeOffer(
                transport, ticket, List.of(), failure));
    }

    public void onResult(ServerPlayer player, boolean ok, byte transport,
                         int rttMs, VoiceFailure failure, List<String> candidates) {
        sweepExpired();
        E4allClient.LOGGER.info("e4all voice: RESULT from {} (ok={}, transport={}, rttMs={}, failure={})",
                player.getScoreboardName(), ok, transport, rttMs,
                failure != null ? failure.logTag : "none");

        NegotiationState state = negotiations.remove(player.getStringUUID());
        if (state == null) {
            E4allClient.LOGGER.warn("e4all voice: received RESULT from {} without prior OFFER",
                player.getScoreboardName());
        }

        VoiceControl.sendToPlayer(player, VoiceControl.encodeReady(
                ok, transport, rttMs, failure));

        if (ok) {
            E4allClient.LOGGER.info("e4all voice: voice connected with {} via {} (RTT {}ms)",
                    player.getScoreboardName(),
                    transport == VoiceControl.TRANSPORT_DIALTONE ? "Dialtone" : "UDP",
                    rttMs);
        } else {
            E4allClient.LOGGER.warn("e4all voice: voice failed with {} ({})",
                    player.getScoreboardName(),
                    failure != null ? failure.logTag : "unknown");
        }
    }

    public void stop() {
        negotiations.clear();
        DialtoneServerChannel ch = voiceServerChannel;
        voiceServerChannel = null;
        voiceTicket = null;
        if (ch != null && ch.isActive()) {
            try {
                ch.close().syncUninterruptibly();
            } catch (Throwable t) {
                E4allClient.LOGGER.debug("e4all voice: error closing voice server channel", t);
            }
        }
    }
}
