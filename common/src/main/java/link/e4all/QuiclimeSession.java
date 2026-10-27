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
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class QuiclimeSession {
    private static final Gson gson = new Gson();
    private static final Logger LOGGER = LoggerFactory.getLogger(E4allClient.MOD_ID);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final int KEEPALIVE_INTERVAL_SECONDS = 15;
    private static final int MAX_IDLE_TIMEOUT_SECONDS = 15;
    private static final int RELAY_CONNECT_TIMEOUT_MS = 15_000;

    private static final String DISCORD_URL = "https://discord.gg/mUYW9Rw2ae";

    private int getKeepaliveInterval() {
        try { return Config.INSTANCE.keepaliveIntervalSeconds.value(); } catch (Throwable t) { return KEEPALIVE_INTERVAL_SECONDS; }
    }
    private int getRelayConnectTimeoutMs() {
        try { return Config.INSTANCE.relayConnectTimeoutMs.value(); } catch (Throwable t) { return RELAY_CONNECT_TIMEOUT_MS; }
    }

    final ChannelHandler handler;

    // the world this tunnel belongs to, so a session never outlives it
    private final net.minecraft.server.MinecraftServer ownerServer;
    private final int localPort;

    private static final long STOP_WATCHDOG_MS = 5000L;

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

    public enum State {
        STARTING,
        STARTED,
        UNHEALTHY,
        STOPPING,
        STOPPED
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
    private volatile QuicStreamChannel controlStreamChannel;
    private volatile String cachedTicket;
    private volatile ScheduledFuture<?> keepaliveFuture;
    private volatile ScheduledFuture<?> connectWatchdogFuture;
    private volatile long lastCapabilitiesResponseTime;
    private final AtomicInteger missedKeepaliveCount = new AtomicInteger(0);

    public QuiclimeSession(ChannelHandler handler, EventLoopGroup group) {
        this(handler, group, null, 0);
    }

    public QuiclimeSession(ChannelHandler handler, EventLoopGroup group, net.minecraft.server.MinecraftServer ownerServer) {
        this.handler = handler;
        this.group = group;
        this.ownerServer = ownerServer;
        this.localPort = 0;
    }

    public QuiclimeSession(ChannelHandler handler, EventLoopGroup group, net.minecraft.server.MinecraftServer ownerServer, int localPort) {
        this.handler = handler;
        this.group = group;
        this.ownerServer = ownerServer;
        this.localPort = localPort;
    }

    public int localPort() {
        if (localPort > 0) {
            return localPort;
        }
        if (ownerServer != null) {
            try {
                int p = ownerServer.getPort();
                if (p > 0) return p;
            } catch (Throwable ignored) {}
        }
        if (Agnos.isClient()) {
            try {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc != null && mc.getSingleplayerServer() != null) {
                    int p = mc.getSingleplayerServer().getPort();
                    if (p > 0) return p;
                }
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    public net.minecraft.server.MinecraftServer ownerServer() {
        return ownerServer;
    }

    public boolean ownsServer(net.minecraft.server.MinecraftServer server) {
        return server != null && server == ownerServer;
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
        "https://cl.e4mc.link:8443",
        "https://ca.e4mc.link:8443"
    };
    private static String[] cachedRelayMap = null;

    public static synchronized String[] getRelayMap() {
        if (cachedRelayMap == null) {
            cachedRelayMap = fetchRelayMap();
        }
        return cachedRelayMap;
    }

    public static String[] getDefaultRelayMap() {
        return DEFAULT_RELAY_MAP;
    }

    private static String[] fetchRelayMap() {
        try {
            var url = new URI(Config.INSTANCE.dialtoneRelayMap.value());
            var response = httpFetch(url);
            if (response.status == 200) {
                String[] parsed = gson.fromJson(response.body, String[].class);
                if (parsed != null && parsed.length > 0) {
                    return parsed;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to fetch dynamic relay map, using default relay list", t);
        }
        return DEFAULT_RELAY_MAP;
    }

    static NetDns.Response httpFetch(URI uri) throws Exception {
        if (AndroidDetector.isAndroid()) {
            return NetDns.httpGet(uri);
        }
        var request = HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .build();
        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return new NetDns.Response(response.statusCode(), response.body());
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
                startConnectWatchdog(datagramChannel.eventLoop());
                QuicChannel.newBootstrap(datagramChannel)
                        .streamOption(ChannelOption.ALLOW_HALF_CLOSURE, false)
                        .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                            @Override
                            protected void initChannel(QuicStreamChannel ch) {
                                ch.config().setAllowHalfClosure(false);
                                State current = state;
                                if (current == State.STOPPING || current == State.STOPPED || current == State.UNHEALTHY) {
                                    LOGGER.info("e4all: closing relay stream, session is {}", current);
                                    ch.close();
                                    return;
                                }
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
                                stopWithRelayLostMessage();
                            }
                        })
                        .remoteAddress(new InetSocketAddress(resolvePreferIpv4(relayInfo.host), relayInfo.port))
                        .connect()
                        .addListener(quicChannelFuture -> {
                    cancelConnectWatchdog();
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

                                        if (!Agnos.isClient()) {
                                            LOGGER.warn("e4all running on Dedicated Server; This works, but isn't recommended as e4all is designed for short-lived LAN servers");
                                        }
                                        String domain = ((ControlMessageCodec.DomainAssignmentCompleteMessageClientbound) msg).domain;
                                        boolean isReassignment = assignedDomain != null;
                                        String oldDomain = assignedDomain;
                                        assignedDomain = domain;
                                        if (isReassignment) {
                                            LOGGER.info("Domain reassigned: {} (was: {})", domain, oldDomain);
                                        } else {
                                            LOGGER.info("Domain assigned: {}", domain);
                                        }
                                        if (Agnos.isClient()) {
                                            try {
                                                int port = localPort();
                                                if (port <= 0) {
                                                    port = 25565;
                                                }
                                                String cleanDomain = domain;
                                                if (domain.contains(":")) {
                                                    int idx = domain.lastIndexOf(':');
                                                    try {
                                                        port = Integer.parseInt(domain.substring(idx + 1));
                                                        cleanDomain = domain.substring(0, idx);
                                                    } catch (NumberFormatException ignored) {}
                                                }

                                                boolean isOffline = Config.INSTANCE.offlineMode.value();
                                                boolean hideDomain = Config.INSTANCE.hideDomainInChat.value();

                                                Component box = HostedWorldChatBox.create(cleanDomain, port, isOffline, hideDomain);
                                                Mirror.addMessage(box);

                                                if (isReassignment) {
                                                    LOGGER.info("Domain reassigned: {} (was: {})", domain, oldDomain);
                                                }
                                                if (!isReassignment && isOffline) {
                                                    Config.INSTANCE.offlineWarningShown.setValue(true, true);
                                                    LOGGER.warn("e4all: Offline mode enabled, mojang auth is disabled for this session.");
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
                                        LOGGER.debug("Relay replied unknown_message to a control message (expected after domain assignment)");
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
                                                LOGGER.info("Dialtone endpoint still alive, re-registering ticket");
                                                String ticket = cachedTicket;
                                                if (ticket != null) {
                                                    streamChannel
                                                            .writeAndFlush(new ControlMessageCodec.DialtoneRegisterTicketMessageServerbound(ticket))
                                                            .addListener(ignored -> LOGGER.info("notified server of our ticket (re-registered)"));
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
                        LOGGER.warn("Relay link unhealthy: {} consecutive keepalive probes unanswered; recycling the relay connection", missed);
                        if (missed == 3) {
                            try {
                                channel.close();
                            } catch (Throwable t) {
                                LOGGER.debug("e4all: error closing unhealthy relay channel", t);
                            }
                        }
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

    private void cancelConnectWatchdog() {
        ScheduledFuture<?> future = connectWatchdogFuture;
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
        }
        connectWatchdogFuture = null;
    }

    private void cancelScheduledTasks() {
        cancelKeepalive();
        cancelConnectWatchdog();
    }

    private void closeVoiceStreams() {
        link.e4all.voice.VoiceConnectionManager.INSTANCE.closeAll();
    }

    private void startConnectWatchdog(EventLoop loop) {
        cancelConnectWatchdog();
        int timeoutMs = getRelayConnectTimeoutMs();
        connectWatchdogFuture = loop.schedule(() -> {
            connectWatchdogFuture = null;
            if (quicChannel != null) {
                return;
            }
            State current = state;
            if (current == State.STOPPING || current == State.STOPPED) {
                return;
            }
            LOGGER.warn("Relay connect did not complete within {} ms, treating it as failed", timeoutMs);
            DatagramChannel dc = datagramChannel;
            datagramChannel = null;
            if (dc != null) {
                try {
                    dc.close();
                } catch (Throwable t) {
                    LOGGER.debug("e4all: error closing datagram channel after connect timeout", t);
                }
            }
            fail(new java.util.concurrent.TimeoutException("relay connect timed out after " + timeoutMs + " ms"));
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    private static UnsatisfiedLinkError findNativeLoadError(Throwable t) {
        Throwable cursor = t;
        while (cursor != null) {
            if (cursor instanceof UnsatisfiedLinkError error) return error;
            cursor = cursor.getCause();
        }
        return null;
    }

    private static Component withDiscordHelp(String message) {
        Component discordLink = Mirror.withStyle(
                Mirror.literal(DISCORD_URL),
                style -> {
                    Style styled = style.withColor(ChatFormatting.AQUA).withUnderlined(true);
                    ClickEvent open = Mirror.openUrlEvent(DISCORD_URL);
                    if (open != null) styled = styled.withClickEvent(open);
                    return styled;
                }
        );
        return Mirror.append(
                Mirror.literal(message + " Need help? Join my Discord: "),
                discordLink
        );
    }

    private void stopWithRelayLostMessage() {
        State current = state;
        if (current == State.STOPPING || current == State.STOPPED) return;
        state = State.STOPPED;
        failureCause = null;
        cancelScheduledTasks();
        cleanupChannels();
        closeVoiceStreams();
        LOGGER.warn("e4all: relay connection lost, session stopped");
        if (Agnos.isClient()) {
            Mirror.addMessage(withDiscordHelp(
                    "e4all: Relay connection lost. Re-open to LAN or run /e4all restart to host again."));
        }
    }

    private void fail(Throwable e) {
        State current = state;
        if (current == State.STOPPING || current == State.STOPPED) return;
        state = State.STOPPED;
        failureCause = e;
        cancelScheduledTasks();
        cleanupChannels();
        closeVoiceStreams();

        boolean nativeError = findNativeLoadError(e) != null;
        E4allClient.LOGGER.error("e4all: relay session failed" + (nativeError ? " (native library load failure)" : ""), e);

        if (Agnos.isClient()) {
            String reason = e.getClass().getSimpleName();
            String message = e.getMessage();
            if (message != null && !message.isBlank()) {
                reason = reason + ": " + message;
            }
            String text = "e4all: could not host the world (" + reason + "). Re-open to LAN or run /e4all restart to try again.";
            if (nativeError) {
                text += " A required native library could not be loaded (on Windows this is often Smart App Control).";
            }

            Mirror.addMessage(withDiscordHelp(text));
        }
    }

    private void failWithDiagnostics(Throwable e) {
        UnsatisfiedLinkError linkError = findNativeLoadError(e);

        if (linkError != null) {
            AndroidDetector.DetectionResult androidCheck = AndroidDetector.detect();
            if (androidCheck.isAndroid()) {
                E4allClient.LOGGER.error("e4all: Failed to load the QUIC native library on Android. " +
                        "Make sure you are running the -android variant of the jar (it bundles the Bionic build) " +
                        "and that the extracted native is writable/executable. " +
                        "Detection: {}. Linker error: {}", androidCheck.reason(), linkError.getMessage(), e);
            } else {
                E4allClient.LOGGER.error("e4all: Failed to load native QUIC library. " +
                        "Platform: os.name={}, os.arch={}, java.vm.name={}. Linker error: {}",
                        System.getProperty("os.name", "unknown"),
                        System.getProperty("os.arch", "unknown"),
                        System.getProperty("java.vm.name", "unknown"),
                        linkError.getMessage(), e);
            }
        }
        fail(e);
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
        cancelScheduledTasks();
        controlStreamChannel = null;
        closeVoiceStreams();
        // async, a stuck native close would freeze the game here
        afterCloseIfPresent(dialtoneChannel, q -> afterCloseIfPresent(quicChannel, a -> afterCloseIfPresent(datagramChannel, b -> markStopped())));
        scheduleStopWatchdog();
    }

    public void stopSync() {
        stop();
    }

    private void markStopped() {
        if (state != State.STOPPED) {
            state = State.STOPPED;
            E4allClient.LOGGER.info("e4all: relay session stopped");
        }
    }

    // a stuck native close must not leave the session in STOPPING forever
    private void scheduleStopWatchdog() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(STOP_WATCHDOG_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (state == State.STOPPING) {
                E4allClient.LOGGER.warn("e4all: relay teardown stuck for {} ms, forcing stopped", STOP_WATCHDOG_MS);
                markStopped();
            }
        }, "e4all_minecraft-stop-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
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

    private static InetAddress resolvePreferIpv4(String host) throws java.net.UnknownHostException {
        if (AndroidDetector.isAndroid()) {
            try {
                return NetDns.resolve(host);
            } catch (Throwable t) {
                LOGGER.warn("e4all: Android DNS lookup for {} failed, retrying system resolver", host);
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
