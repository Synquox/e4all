package link.e4all;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.kqueue.KQueueDatagramChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.incubator.codec.quic.*;
import link.e4all.dialtone.DialtoneAddress;
import link.e4all.dialtone.DialtoneServerChannel;
import link.e4mc.iroh.Endpoint;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class QuiclimeSession {
    private static final Gson gson = new Gson();
    private static final Logger LOGGER = LoggerFactory.getLogger(E4allClient.MOD_ID);
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final int RECONNECT_BASE_DELAY_SECONDS = 2;
    private static final int KEEPALIVE_INTERVAL_SECONDS = 15;
    private static final int MAX_IDLE_TIMEOUT_SECONDS = 60;

    private int getMaxReconnectAttempts() {
        try { return Config.INSTANCE.reconnectMaxAttempts.value(); } catch (Throwable t) { return MAX_RECONNECT_ATTEMPTS; }
    }
    private int getReconnectBaseDelay() {
        try { return Config.INSTANCE.reconnectBaseDelaySeconds.value(); } catch (Throwable t) { return RECONNECT_BASE_DELAY_SECONDS; }
    }
    private int getKeepaliveInterval() {
        try { return Config.INSTANCE.keepaliveIntervalSeconds.value(); } catch (Throwable t) { return KEEPALIVE_INTERVAL_SECONDS; }
    }

    final ChannelHandler handler;

    private static class ControlMessageCodec extends ByteToMessageCodec<ControlMessageCodec.ControlMessage> {
        public ControlMessageCodec() {
            super();
        }

        public interface ControlMessage {}

        public static class ProbeCapabilitiesMessageServerbound implements ControlMessage {
            String kind = "probe_capabilities";
            public ProbeCapabilitiesMessageServerbound() {}
        }

        public static class RequestDomainAssignmentMessageServerbound implements ControlMessage {
            String kind = "request_domain_assignment";
            public RequestDomainAssignmentMessageServerbound() {}
        }

        public static class DialtoneRegisterTicketMessageServerbound implements ControlMessage {
            String kind = "dialtone_register_ticket";
            String ticket;
            public DialtoneRegisterTicketMessageServerbound(String ticket) {
                this.ticket = ticket;
            }
        }

        public static class DomainAssignmentCompleteMessageClientbound implements ControlMessage {
            String kind = "domain_assignment_complete";
            String domain;
            public DomainAssignmentCompleteMessageClientbound(String domain) {
                this.domain = domain;
            }
        }

        public static class RequestMessageBroadcastMessageClientbound implements ControlMessage {
            String kind = "request_message_broadcast";
            String message;
            public RequestMessageBroadcastMessageClientbound(String message) {
                this.message = message;
            }
        }

        public static class HasCapabilitiesMessageClientbound implements ControlMessage {
            String kind = "has_capabilities";
            String[] caps;
            public HasCapabilitiesMessageClientbound(String[] caps) {
                this.caps = caps;
            }
        }

        public static class TicketRegisteredMessageClientbound implements ControlMessage {
            String kind = "ticket_registered";
            public TicketRegisteredMessageClientbound() {
            }
        }

        public static class UnknownMessageMessageClientbound implements ControlMessage {
            String kind = "unknown_message";
            public UnknownMessageMessageClientbound() {
            }
        }

        @Override
        protected void encode(ChannelHandlerContext ctx, ControlMessage msg, ByteBuf out) {
            E4allClient.LOGGER.debug("writing {}", msg);
            try {
                byte[] json = gson.toJson(msg).getBytes(StandardCharsets.UTF_8);
                writeVarInt(out, json.length);
                out.writeBytes(json);
            } catch (Throwable e) {
                E4allClient.LOGGER.error("weird", e);
            }
            E4allClient.LOGGER.debug("writing {} bytes", out.readableBytes());
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            in.markReaderIndex();
            int size = 0;
            int shift = 0;
            byte b;
            do {
                if (!in.isReadable()) {
                    in.resetReaderIndex();
                    return; // Not enough data to read the VarInt
                }
                b = in.readByte();
                size |= (b & 0x7F) << shift;
                shift += 7;
                if (shift > 35) {
                    throw new RuntimeException("VarInt too big");
                }
            } while ((b & 0x80) != 0);

            if (in.readableBytes() < size) {
                in.resetReaderIndex();
                return; // Not enough data for the full message
            }

            var buf = new byte[size];
            in.readBytes(buf);
            var json = gson.fromJson(new String(buf, StandardCharsets.UTF_8), JsonObject.class);
            if (json == null) {
                LOGGER.warn("Received empty/malformed control message");
                return;
            }
            var kindElement = json.get("kind");
            if (kindElement == null || kindElement.isJsonNull()) {
                LOGGER.warn("Received control message with no 'kind' field: {}", json);
                return;
            }
            String kind = kindElement.getAsString();
            switch (kind) {
                case "domain_assignment_complete":
                    out.add(gson.fromJson(json, DomainAssignmentCompleteMessageClientbound.class));
                    break;
                case "request_message_broadcast":
                    out.add(gson.fromJson(json, RequestMessageBroadcastMessageClientbound.class));
                    break;
                case "has_capabilities":
                    out.add(gson.fromJson(json, HasCapabilitiesMessageClientbound.class));
                    break;
                case "ticket_registered":
                    out.add(gson.fromJson(json, TicketRegisteredMessageClientbound.class));
                    break;
                case "unknown_message":
                    out.add(gson.fromJson(json, UnknownMessageMessageClientbound.class));
                    break;
                default:
                    LOGGER.warn("Unknown control message kind: {}", kind);
                    break;
            }
        }
    }

    public volatile State state = State.STARTING;
    public volatile Throwable failureCause = null;
    public volatile String assignedDomain = null;

    private final AtomicInteger reconnectCount = new AtomicInteger(0);

    public enum State {
        STARTING,
        STARTED,
        UNHEALTHY,
        STOPPING,
        STOPPED,
        RECONNECTING
    }

    static class BrokerResponse {
        String id;
        String host;
        int port;
    }

    final EventLoopGroup group;
    private volatile DatagramChannel datagramChannel;
    private volatile QuicChannel quicChannel;
    private volatile DialtoneServerChannel dialtoneChannel;
    private volatile ScheduledFuture<?> keepaliveFuture;
    private volatile QuicStreamChannel controlStreamChannel;
    private volatile String cachedTicket;
    private volatile String previousDomain;
    private volatile long lastCapabilitiesResponseTime;
    private final AtomicInteger missedKeepaliveCount = new AtomicInteger(0);

    public QuiclimeSession(ChannelHandler handler, EventLoopGroup group) {
        this.handler = handler;
        this.group = group;
    }

    public void startAsync() {
        var thread = new Thread(this::start, "e4all_minecraft-init");
        thread.setDaemon(true);
        thread.start();
    }

    private static BrokerResponse getRelay() throws Exception {
        if (Config.INSTANCE.useBroker.value()) {
            var url = new URI(Config.INSTANCE.brokerUrl.value());
            LOGGER.info("broker req: {} GET", url);
            var response = httpFetch(url);
            LOGGER.info("broker resp: status {} body {}", response.status, response.body);
            if (response.status != 200) {
                throw new RuntimeException("Broker returned status " + response.status);
            }
            return gson.fromJson(response.body, BrokerResponse.class);
        } else {
            var resp = new BrokerResponse();
            resp.id = "custom";
            resp.host = Config.INSTANCE.relayHost.value();
            resp.port = Config.INSTANCE.relayPort.value();
            return resp;
        }
    }

    private static final String[] DEFAULT_RELAY_MAP = new String[]{
        "https://ap.e4mc.link:8443",
        "https://de.e4mc.link:8443",
        "https://eu.e4mc.link:8443",
        "https://jp.e4mc.link:8443",
        "https://na.e4mc.link:8443",
        "https://oc.e4mc.link:8443",
        "https://sg.e4mc.link:8443",
        "https://us.e4mc.link:8443",
        "https://cl.e4mc.link:8443"
    };
    private static volatile String[] cachedRelayMap = null;

    public static String[] getRelayMap() throws Exception {
        if (cachedRelayMap != null) {
            return cachedRelayMap;
        }
        try {
            var url = new URI(Config.INSTANCE.dialtoneRelayMap.value());
            LOGGER.info("relaymap req: {} GET", url);
            var response = httpFetch(url);
            LOGGER.info("relaymap resp: status {} body {}", response.status, response.body);
            if (response.status == 200) {
                String[] parsed = gson.fromJson(response.body, String[].class);
                if (parsed != null && parsed.length > 0) {
                    cachedRelayMap = parsed;
                    return parsed;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to fetch dynamic relay map, using default relay list", t);
        }
        cachedRelayMap = DEFAULT_RELAY_MAP;
        return DEFAULT_RELAY_MAP;
    }

    static NetDns.Response httpFetch(URI uri) throws Exception {
        if (AndroidDetector.isAndroid()) {
            return NetDns.httpGet(uri);
        }
        var request = HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(3))
                .header("Accept", "application/json")
                .build();
        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return new NetDns.Response(response.statusCode(), response.body());
    }

    public int getReconnectCount() {
        return reconnectCount.get();
    }

    public void start() {
        try {
            var relayInfo = getRelay();
            LOGGER.info("using relay {}", relayInfo.id);
            QuicSslContext context = QuicSslContextBuilder
                    .forClient()
                    .applicationProtocols("quiclime")
                    .build();
            var codec = new QuicClientCodecBuilder()
                    .sslContext(context)
                    .sslEngineProvider(it -> context.newEngine(it.alloc(), relayInfo.host, relayInfo.port))
                    .initialMaxStreamsBidirectional(512)
                    .maxIdleTimeout(MAX_IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .initialMaxData(4611686018427387903L)
                    .initialMaxStreamDataBidirectionalRemote(1250000)
                    .initialMaxStreamDataBidirectionalLocal(1250000)
                    .initialMaxStreamDataUnidirectional(1250000)
                    .build();
            Class<? extends DatagramChannel> channelClass = null;
            if (group instanceof EpollEventLoopGroup) {
                channelClass = EpollDatagramChannel.class;
            } else if (group instanceof NioEventLoopGroup) {
                channelClass = NioDatagramChannel.class;
            } else if (group instanceof KQueueEventLoopGroup) {
                channelClass = KQueueDatagramChannel.class;
            } else if (group instanceof MultiThreadIoEventLoopGroup mig) {
                if (mig.isIoType(EpollIoHandler.class)) {
                    channelClass = EpollDatagramChannel.class;
                } else if (mig.isIoType(NioIoHandler.class)) {
                    channelClass = NioDatagramChannel.class;
                } else if (mig.isIoType(KQueueIoHandler.class)) {
                    channelClass = KQueueDatagramChannel.class;
                } else {
                    throw new RuntimeException("Unknown IoHandler type in MultiThreadIoEventLoopGroup: " + mig);
                }
            } else {
                throw new RuntimeException("Unknown EventLoopGroup " + group.getClass().getName());
            }
            new Bootstrap()
                    .group(group)
                    .channel(channelClass)
                    .handler(codec)
                    .bind(0)
                    .addListener(datagramChannelFuture -> {
                if (!datagramChannelFuture.isSuccess()) {
                    fail(datagramChannelFuture.cause());
                    return;
                }
                if (state == State.STOPPING || state == State.STOPPED) {
                    ((ChannelFuture) datagramChannelFuture).channel().close();
                    return;
                }
                datagramChannel = (DatagramChannel) ((ChannelFuture) datagramChannelFuture).channel();
                QuicChannel.newBootstrap(datagramChannel)
                        .streamOption(ChannelOption.ALLOW_HALF_CLOSURE, false)
                        .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                            @Override
                            protected void initChannel(QuicStreamChannel ch) {
                                ch.config().setAllowHalfClosure(false);
                                ch.pipeline().addLast("e4all$halfClosureHandler", new ChannelInboundHandlerAdapter() {
                                    @Override
                                    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                                        if (evt instanceof ChannelInputShutdownEvent) {
                                            ctx.close();
                                            return;
                                        }
                                        super.userEventTriggered(ctx, evt);
                                    }
                                });
                                // relay streams carry game traffic only, voice moved to
                                // direct transports (2.1.0)
                                ch.pipeline().addLast(handler);
                            }
                        })
                        .handler(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                                super.exceptionCaught(ctx, cause);
                                LOGGER.warn("QUIC channel exception", cause);
                                fail(cause);
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                super.channelInactive(ctx);
                                LOGGER.warn("QUIC channel became inactive (relay connection lost)");
                                cancelKeepalive();
                                if (state != State.STOPPING && state != State.STOPPED) {
                                    int attempts = reconnectCount.incrementAndGet();
                                    if (attempts <= getMaxReconnectAttempts()) {
                                        state = State.RECONNECTING;
                                        int delay = getReconnectBaseDelay() * (1 << (attempts - 1));
                                        LOGGER.info("Auto-reconnecting to relay in {}s (attempt {}/{})", delay, attempts, getMaxReconnectAttempts());
                                        if (Agnos.isClient()) {
                                            Mirror.addMessage(Mirror.translatable("text.e4all_minecraft.reconnecting"));
                                        }
                                        var reconnectThread = new Thread(() -> {
                                            try {
                                                Thread.sleep(delay * 1000L);
                                                synchronized (E4allClient.SESSION_LOCK) {
                                                    State currentState = state;
                                                    if (currentState == State.RECONNECTING) {
                                                        QuiclimeSession.this.cleanupControlChannels();
                                                        start();
                                                    } else {
                                                        LOGGER.info("Reconnect cancelled (state changed to {})", currentState);
                                                    }
                                                }
                                            } catch (InterruptedException ignored) {
                                                state = State.STOPPED;
                                            } catch (Throwable e) {
                                                LOGGER.error("Failed to reconnect", e);
                                                state = State.STOPPED;
                                            }
                                        }, "e4all_minecraft-reconnect");
                                        reconnectThread.setDaemon(true);
                                        reconnectThread.start();
                                    } else {
                                        LOGGER.error("Max reconnect attempts ({}) reached; giving up. Re-open the world to LAN or run /e4all restart.", getMaxReconnectAttempts());
                                        state = State.STOPPED;
                                        link.e4all.voice.VoiceConnectionManager.INSTANCE.closeAll();
                                        if (Agnos.isClient()) {
                                            Mirror.addMessage(Mirror.translatable("text.e4all_minecraft.maxReconnectFailed"));
                                        }
                                    }
                                } else {
                                    state = State.STOPPED;
                                }
                            }
                        })
                        .remoteAddress(new InetSocketAddress(resolvePreferIpv4(relayInfo.host), relayInfo.port))
                        .connect()
                        .addListener(quicChannelFuture -> {
                    if (!quicChannelFuture.isSuccess()) {
                        fail(quicChannelFuture.cause());
                        return;
                    }
                    if (state == State.STOPPING || state == State.STOPPED) {
                        return;
                    }
                    quicChannel = (QuicChannel) quicChannelFuture.get();

                    // Start QUIC-level keepalive pings to prevent idle timeout
                    startKeepalive(quicChannel);

                    quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                            new ChannelInitializer<QuicStreamChannel>() {
                                @Override
                                protected void initChannel(QuicStreamChannel ch) {
                            ch.pipeline().addLast(new ControlMessageCodec(), new SimpleChannelInboundHandler<ControlMessageCodec.ControlMessage>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, ControlMessageCodec.ControlMessage msg) {
                                    // any inbound message proves liveness; has_capabilities alone is unreliable here
                                    lastCapabilitiesResponseTime = System.currentTimeMillis();
                                    missedKeepaliveCount.set(0);
                                    if (msg instanceof ControlMessageCodec.DomainAssignmentCompleteMessageClientbound) {
                                        state = State.STARTED;
                                        reconnectCount.set(0);

                                        if (!Agnos.isClient()) {
                                            LOGGER.warn("e4all running on Dedicated Server; This works, but isn't recommended as e4all is designed for short-lived LAN servers");
                                        }
                                        String domain = ((ControlMessageCodec.DomainAssignmentCompleteMessageClientbound) msg).domain;
                                        boolean isReassignment = assignedDomain != null;
                                        String oldDomain = assignedDomain;
                                        assignedDomain = domain;
                                        if (isReassignment) {
                                            LOGGER.info("Domain reassigned after reconnect: {} (was: {})", domain, oldDomain);
                                        } else {
                                            LOGGER.info("Domain assigned: {}", domain);
                                        }
                                        if (Agnos.isClient()) {
                                            try {
                                                Component domainComponent = Mirror.literal(domain);
                                                if (Config.INSTANCE.hideDomainInChat.value()) {
                                                    domainComponent = Mirror.translatable("text.e4all_minecraft.hiddenDomain");
                                                }
                                                ClickEvent copyEvent = Mirror.copyToClipboard(domain);
                                                HoverEvent hoverEvent = Mirror.showText(Mirror.translatable("chat.copy.click"));
                                                Component styledDomain = Mirror.withStyle(domainComponent, it -> {
                                                    Style s = it.withColor(ChatFormatting.GREEN);
                                                    if (copyEvent != null) s = s.withClickEvent(copyEvent);
                                                    if (hoverEvent != null) s = s.withHoverEvent(hoverEvent);
                                                    return s;
                                                });
                                                ClickEvent stopEvent = Mirror.runCommand("/e4all stop");
                                                Component styledStop = Mirror.withStyle(Mirror.translatable("text.e4all_minecraft.clickToStop"), it -> {
                                                    Style s = it.withColor(ChatFormatting.GRAY);
                                                    if (stopEvent != null) s = s.withClickEvent(stopEvent);
                                                    return s;
                                                });
                                                Component message = Mirror.append(
                                                        Mirror.translatable("text.e4all_minecraft.domainAssigned", styledDomain),
                                                        styledStop
                                                );
                                                Mirror.addMessage(message);
                                                if (isReassignment) {
                                                    Mirror.addMessage(Mirror.withStyle(
                                                            Mirror.translatable("text.e4all_minecraft.domainReassigned"),
                                                            it -> it.withColor(ChatFormatting.YELLOW)));
                                                }
                                                // show offline warning on lan open (first assignment only)
                                                if (!isReassignment && Config.INSTANCE.offlineMode.value()) {
                                                    Config.INSTANCE.offlineWarningShown.setValue(true, true);
                                                    LOGGER.warn("e4all: Offline mode enabled, mojang auth is disabled for this session.");
                                                    Mirror.addMessage(Mirror.withStyle(Mirror.translatable("text.e4all_minecraft.offlineModeWarning"), it -> it.withColor(ChatFormatting.RED)));
                                                }
                                                // one-time welcome message for the host (first assignment only)
                                                if (!isReassignment && !Config.INSTANCE.welcomeShown.value()) {
                                                    Config.INSTANCE.welcomeShown.setValue(true, true);
                                                    Mirror.addMessage(Mirror.append(
                                                            E4allClient.welcomeHeader(),
                                                            Mirror.append(Mirror.literal("\n"),
                                                                    Mirror.translatable("text.e4all_minecraft.welcome.hint"))));
                                                }
                                            } catch (Throwable t) {
                                                LOGGER.error("Failed to format or send domain assigned message", t);
                                                Mirror.addMessage(Mirror.literal("e4all: Domain assigned: " + domain));
                                            }
                                        }
                                    }
                                    if (msg instanceof ControlMessageCodec.RequestMessageBroadcastMessageClientbound) {
                                        if (Agnos.isClient()) {
                                            Mirror.addMessage(Mirror.literal(((ControlMessageCodec.RequestMessageBroadcastMessageClientbound) msg).message));
                                        }
                                    }
                                    if (msg instanceof ControlMessageCodec.UnknownMessageMessageClientbound) {
                                        LOGGER.debug("Relay replied unknown_message to a control message (expected after domain assignment, e.g. for keepalive probes)");
                                    }
                                    if (msg instanceof ControlMessageCodec.HasCapabilitiesMessageClientbound) {
                                        var streamChannel = ctx.channel();
                                        boolean hasDialtoneSidecar = false;
                                        for (String cap : ((ControlMessageCodec.HasCapabilitiesMessageClientbound) msg).caps) {
                                            if (cap.equals("dialtone_sidecar")) {
                                                hasDialtoneSidecar = true;
                                                break;
                                            }
                                        }
                                        if (hasDialtoneSidecar
                                                && Config.INSTANCE.dialtoneHostEnabled.value()
                                                && (!AndroidDetector.isAndroid() || AndroidNatives.hasIrohNative())) {
                                            // endpoint alive: re-register ticket instead of re-binding
                                            if (dialtoneChannel != null && dialtoneChannel.isActive()) {
                                                LOGGER.info("Dialtone endpoint still alive across reconnect, re-registering ticket");
                                                String ticket = cachedTicket;
                                                if (ticket != null) {
                                                    streamChannel
                                                            .writeAndFlush(new ControlMessageCodec.DialtoneRegisterTicketMessageServerbound(ticket))
                                                            .addListener(ignored -> LOGGER.info("notified server of our ticket (re-registered after reconnect)"));
                                                } else {
                                                    LOGGER.warn("Dialtone endpoint alive but no cached ticket to re-register");
                                                }
                                            } else {
                                                if (dialtoneChannel != null) {
                                                    LOGGER.info("Dialtone endpoint was unhealthy, recreating");
                                                    try { dialtoneChannel.close(); } catch (Throwable ignored) {}
                                                    dialtoneChannel = null;
                                                }
                                                LOGGER.info("Binding new Dialtone endpoint");
                                                new ServerBootstrap()
                                                    .channel(DialtoneServerChannel.class)
                                                    .handler(new ChannelInboundHandlerAdapter() {
                                                        @Override
                                                        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                                                            super.userEventTriggered(ctx, evt);
                                                            if (evt instanceof DialtoneAddress addr) {
                                                                var ticket = addr.actualAddress;
                                                                if (Config.INSTANCE.dialtoneSanitizeTicket.value()) {
                                                                    ticket = Endpoint.sanitizeTicket(ticket);
                                                                }
                                                                String fullTicket = "v1_" + ticket;
                                                                cachedTicket = fullTicket;
                                                                streamChannel
                                                                        .writeAndFlush(new ControlMessageCodec.DialtoneRegisterTicketMessageServerbound(fullTicket))
                                                                        .addListener(ignored -> LOGGER.info("notified server of our ticket"));
                                                            }
                                                        }
                                                    })
                                                    .childHandler(new ChannelInitializer<Channel>() {
                                                        @Override
                                                        protected void initChannel(Channel ch) {
                                                            ch.pipeline().addLast("voiceRouter",
                                                                    new link.e4all.voice.VoiceStreamRouter(handler));
                                                        }
                                                    })
                                                    .group(group)
                                                    .localAddress(new DialtoneAddress(""))
                                                    .bind()
                                                    .addListener(dialtoneChannelFuture -> {
                                                        if (!dialtoneChannelFuture.isSuccess()) {
                                                            fail(dialtoneChannelFuture.cause());
                                                            return;
                                                        }
                                                        dialtoneChannel = (DialtoneServerChannel) dialtoneChannelFuture.get();
                                                    });
                                            }
                                        }
                                    }
                                }
                            });
                        }
                    }).addListener(it -> {
                        if (!it.isSuccess()) {
                            fail(it.cause());
                            return;
                        }
                        QuicStreamChannel streamChannel = (QuicStreamChannel) it.getNow();
                        controlStreamChannel = streamChannel;
                        lastCapabilitiesResponseTime = System.currentTimeMillis();
                        missedKeepaliveCount.set(0);
                        LOGGER.info("control channel open: {}", streamChannel);
                        streamChannel
                                .writeAndFlush(new ControlMessageCodec.ProbeCapabilitiesMessageServerbound())
                                .addListener(f -> {
                                    if (!f.isSuccess()) {
                                        LOGGER.warn("ProbeCapabilities write failed", f.cause());
                                        fail(f.cause());
                                    } else {
                                        LOGGER.info("probing capabilities");
                                    }
                                });
                        streamChannel
                                .writeAndFlush(new ControlMessageCodec.RequestDomainAssignmentMessageServerbound())
                                .addListener(f -> {
                                    if (!f.isSuccess()) {
                                        LOGGER.warn("RequestDomainAssignment write failed", f.cause());
                                        fail(f.cause());
                                    } else {
                                        LOGGER.info("control channel write complete");
                                    }
                                });
                        quicChannel.closeFuture().addListener(ignored -> datagramChannel.close());
                    });
                });
            });
        } catch (Throwable e) {
            failWithDiagnostics(e);
        }
    }

    private void startKeepalive(QuicChannel channel) {
        cancelKeepalive();
        int interval = getKeepaliveInterval();
        keepaliveFuture = channel.eventLoop().scheduleAtFixedRate(() -> {
                if (!channel.isActive()) {
                    cancelKeepalive();
                    return;
                }
                QuicStreamChannel ctrlStream = controlStreamChannel;
                if (ctrlStream == null || !ctrlStream.isActive()) {
                    LOGGER.debug("Keepalive skipped: control stream not available");
                    return;
                }
                try {
                    ctrlStream.writeAndFlush(new ControlMessageCodec.ProbeCapabilitiesMessageServerbound())
                            .addListener(f -> {
                                if (!f.isSuccess()) {
                                    LOGGER.warn("Keepalive probe_capabilities write failed", f.cause());
                                }
                            });
                    int missed = missedKeepaliveCount.incrementAndGet();
                    if (missed >= 3) {
                        LOGGER.warn("Relay link unhealthy: {} consecutive keepalive probes unanswered", missed);
                    }
                } catch (Throwable t) {
                    LOGGER.warn("Keepalive probe failed", t);
                }
        }, interval, interval, TimeUnit.SECONDS);
    }

    private void cancelKeepalive() {
        ScheduledFuture<?> future = keepaliveFuture;
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
        }
        keepaliveFuture = null;
    }

    private void fail(Throwable e) {
        QuiclimeSession.this.state = State.UNHEALTHY;
        failureCause = e;
        E4allClient.LOGGER.error("error in e4all", e);
        if (Agnos.isClient()) {
            Mirror.addMessage(Mirror.append(Mirror.translatable("text.e4all_minecraft.error"),
                    Mirror.literal(" (" + e.getClass().getSimpleName() + ")")));
        }
    }

    private void failWithDiagnostics(Throwable e) {
        Throwable cursor = e;
        UnsatisfiedLinkError linkError = null;
        while (cursor != null) {
            if (cursor instanceof UnsatisfiedLinkError ule) {
                linkError = ule;
                break;
            }
            cursor = cursor.getCause();
        }

        QuiclimeSession.this.state = State.UNHEALTHY;
        failureCause = e;

        if (linkError != null) {
            AndroidDetector.DetectionResult androidCheck = AndroidDetector.detect();
            if (androidCheck.isAndroid()) {
                E4allClient.LOGGER.error("e4all: Failed to load the QUIC native library on Android. " +
                        "Make sure you are running the -android variant of the jar (it bundles the Bionic build) " +
                        "and that the extracted native is writable/executable. " +
                        "Detection: {}. Linker error: {}", androidCheck.reason(), linkError.getMessage(), e);
                if (Agnos.isClient()) {
                    Mirror.addMessage(Mirror.translatable("text.e4all_minecraft.error.nativeLoadFailed"));
                }
            } else {
                E4allClient.LOGGER.error("e4all: Failed to load native QUIC library. " +
                        "Platform: os.name={}, os.arch={}, java.vm.name={}. Linker error: {}",
                        System.getProperty("os.name", "unknown"),
                        System.getProperty("os.arch", "unknown"),
                        System.getProperty("java.vm.name", "unknown"),
                        linkError.getMessage(), e);
                if (Agnos.isClient()) {
                    Mirror.addMessage(Mirror.translatable("text.e4all_minecraft.error.nativeLoadFailed"));
                }
            }
        } else {
            E4allClient.LOGGER.error("error in e4all", e);
            if (Agnos.isClient()) {
                Mirror.addMessage(Mirror.append(Mirror.translatable("text.e4all_minecraft.error"),
                        Mirror.literal(" (" + e.getClass().getSimpleName() + ")")));
            }
        }
    }

    private static void afterCloseIfPresent(Channel channel, Consumer<Boolean> callback) {
        if (channel == null) {
            callback.accept(false);
        } else {
            channel.close().addListener(it -> callback.accept(true));
        }
    }

    public void stop() {
        state = State.STOPPING;
        cancelKeepalive();
        controlStreamChannel = null;
        link.e4all.voice.VoiceConnectionManager.INSTANCE.closeAll();
        afterCloseIfPresent(dialtoneChannel, q -> afterCloseIfPresent(quicChannel, a -> afterCloseIfPresent(datagramChannel, b -> state = State.STOPPED)));
    }

    public void stopSync() {
        state = State.STOPPING;
        cancelKeepalive();
        controlStreamChannel = null;
        link.e4all.voice.VoiceConnectionManager.INSTANCE.closeAll();
        try { if (dialtoneChannel != null && dialtoneChannel.isOpen()) dialtoneChannel.close().syncUninterruptibly(); } catch (Throwable ignored) {}
        try { if (quicChannel != null && quicChannel.isOpen()) quicChannel.close().syncUninterruptibly(); } catch (Throwable ignored) {}
        try { if (datagramChannel != null && datagramChannel.isOpen()) datagramChannel.close().syncUninterruptibly(); } catch (Throwable ignored) {}
        dialtoneChannel = null;
        quicChannel = null;
        datagramChannel = null;
        state = State.STOPPED;
    }

    private void cleanupChannels() {
        controlStreamChannel = null;
        try {
            if (dialtoneChannel != null && dialtoneChannel.isOpen()) {
                dialtoneChannel.close();
            }
        } catch (Throwable e) {
            LOGGER.warn("Error closing dialtone channel during cleanup", e);
        }
        dialtoneChannel = null;
        try {
            if (quicChannel != null && quicChannel.isOpen()) {
                quicChannel.close();
            }
        } catch (Throwable e) {
            LOGGER.warn("Error closing QUIC channel during cleanup", e);
        }
        quicChannel = null;
        try {
            if (datagramChannel != null && datagramChannel.isOpen()) {
                datagramChannel.close();
            }
        } catch (Throwable e) {
            LOGGER.warn("Error closing datagram channel during cleanup", e);
        }
        datagramChannel = null;
    }

    // close control channels only, keeping iroh endpoint and voice streams for reconnect
    private void cleanupControlChannels() {
        controlStreamChannel = null;
        try {
            if (quicChannel != null && quicChannel.isOpen()) {
                quicChannel.close();
            }
        } catch (Throwable e) {
            LOGGER.warn("Error closing QUIC channel during control cleanup", e);
        }
        quicChannel = null;
        try {
            if (datagramChannel != null && datagramChannel.isOpen()) {
                datagramChannel.close();
            }
        } catch (Throwable e) {
            LOGGER.warn("Error closing datagram channel during control cleanup", e);
        }
        datagramChannel = null;
    }


    private static InetAddress resolvePreferIpv4(String host) throws java.net.UnknownHostException {
        if (AndroidDetector.isAndroid()) {
            try {
                return NetDns.resolve(host);
            } catch (Throwable t) {
                LOGGER.warn("e4all: netty dns failed for {}, trying system resolver", host);
            }
        }
        InetAddress[] all = InetAddress.getAllByName(host);
        for (InetAddress addr : all) {
            if (addr instanceof Inet4Address) {
                return addr;
            }
        }
        return all[0];
    }

    private static ByteBuf writeVarInt(ByteBuf buf, int value) {
        while ((value & 0xffffff80) != 0) {
            buf.writeByte(value & 0x7F | 0x80);
            value >>>= 7;
        }

        buf.writeByte(value);
        return buf;
    }
}
