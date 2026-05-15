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
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.incubator.codec.quic.*;
import link.e4all.dialtone.DialtoneAddress;
import link.e4all.dialtone.DialtoneServerChannel;
import link.e4mc.iroh.Endpoint;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final int KEEPALIVE_INTERVAL_SECONDS = 5;
    private static final int MAX_IDLE_TIMEOUT_SECONDS = 30;

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
            switch (json.get("kind").getAsString()) {
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
                    LOGGER.warn("Unknown control message kind: {}", json.get("kind").getAsString());
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
    private DatagramChannel datagramChannel;
    private QuicChannel quicChannel;
    private DialtoneServerChannel dialtoneChannel;
    private ScheduledFuture<?> keepaliveFuture;

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
            var request = HttpRequest
                    .newBuilder(new URI(Config.INSTANCE.brokerUrl.value()))
                    .header("Accept", "application/json")
                    .build();
            LOGGER.info("broker req: {}", request);
            var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            LOGGER.info("broker resp: {}", response);
            if (response.statusCode() != 200) {
                throw new RuntimeException("Broker returned status " + response.statusCode());
            }
            return gson.fromJson(response.body(), BrokerResponse.class);
        } else {
            var resp = new BrokerResponse();
            resp.id = "custom";
            resp.host = Config.INSTANCE.relayHost.value();
            resp.port = Config.INSTANCE.relayPort.value();
            return resp;
        }
    }

    public static String[] getRelayMap() throws Exception {
        var request = HttpRequest
                .newBuilder(new URI(Config.INSTANCE.dialtoneRelayMap.value()))
                .header("Accept", "application/json")
                .build();
        LOGGER.info("relaymap req: {}", request);
        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        LOGGER.info("relaymap resp: {}", response);
        if (response.statusCode() != 200) {
            throw new RuntimeException("Relay map returned status " + response.statusCode());
        }
        return gson.fromJson(response.body(), String[].class);
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
                datagramChannel = (DatagramChannel) ((ChannelFuture) datagramChannelFuture).channel();
                QuicChannel.newBootstrap(datagramChannel)
                        .streamHandler(handler)
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
                                    if (attempts <= MAX_RECONNECT_ATTEMPTS) {
                                        state = State.RECONNECTING;
                                        int delay = RECONNECT_BASE_DELAY_SECONDS * (1 << (attempts - 1));
                                        LOGGER.info("Auto-reconnecting to relay in {}s (attempt {}/{})", delay, attempts, MAX_RECONNECT_ATTEMPTS);
                                        if (Agnos.isClient()) {
                                            Mirror.addMessage(Mirror.translatable("text.e4mc_minecraft.reconnecting"));
                                        }
                                        var reconnectThread = new Thread(() -> {
                                            try {
                                                Thread.sleep(delay * 1000L);
                                                State currentState = state;
                                                if (currentState == State.RECONNECTING) {
                                                    QuiclimeSession.this.cleanupChannels();
                                                    start();
                                                } else {
                                                    LOGGER.info("Reconnect cancelled (state changed to {})", currentState);
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
                                        LOGGER.error("Max reconnect attempts ({}) reached; giving up", MAX_RECONNECT_ATTEMPTS);
                                        state = State.STOPPED;
                                        if (Agnos.isClient()) {
                                            Mirror.addMessage(Mirror.translatable("text.e4mc_minecraft.error"));
                                        }
                                    }
                                } else {
                                    state = State.STOPPED;
                                }
                            }
                        })
                        .remoteAddress(new InetSocketAddress(InetAddress.getByName(relayInfo.host), relayInfo.port))
                        .connect()
                        .addListener(quicChannelFuture -> {
                    if (!quicChannelFuture.isSuccess()) {
                        fail(quicChannelFuture.cause());
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
                                    if (msg instanceof ControlMessageCodec.DomainAssignmentCompleteMessageClientbound) {
                                        state = State.STARTED;
                                        reconnectCount.set(0);

                                        if (!Agnos.isClient()) {
                                            LOGGER.warn("e4all running on Dedicated Server; This works, but isn't recommended as e4all is designed for short-lived LAN servers");
                                        }
                                        String domain = ((ControlMessageCodec.DomainAssignmentCompleteMessageClientbound) msg).domain;
                                        assignedDomain = domain;
                                        LOGGER.info("Domain assigned: {}", domain);
                                        if (Agnos.isClient()) {
                                            Component message = Mirror.append(Mirror.translatable(
                                                    "text.e4mc_minecraft.domainAssigned",
                                                    Mirror.withStyle(Mirror.literal(domain), it ->
                                                    it
                                                            .withClickEvent(Mirror.copyToClipboard(domain))
                                                            .withColor(ChatFormatting.GREEN)
                                                            .withHoverEvent(Mirror.showText(Mirror.translatable("chat.copy.click"))))
                                            ),
                                                    Mirror.withStyle(Mirror.translatable("text.e4mc_minecraft.clickToStop"), it ->
                                                            it
                                                                    .withClickEvent(Mirror.runCommand("/e4all stop"))
                                                                    .withColor(ChatFormatting.GRAY)
                                                    )
                                            );
                                            Mirror.addMessage(message);
                                            if (E4allClient.badurl) {
                                                Mirror.addMessage(Mirror.translatable("text.e4mc_minecraft.poisonpill.badurl"));
                                            }
                                            // Show one-time offline mode warning when LAN actually opens
                                            if (Config.INSTANCE.offlineMode.value() && !Config.INSTANCE.offlineWarningShown.value()) {
                                                Config.INSTANCE.offlineWarningShown.setValue(true, true);
                                                LOGGER.warn("e4all: Offline mode enabled — Microsoft authentication will be disabled for LAN connections");
                                                Mirror.addMessage(Mirror.withStyle(Mirror.translatable("text.e4mc_minecraft.offlineModeWarning"), it -> it.withColor(ChatFormatting.RED)));
                                            }
                                        }
                                    }
                                    if (msg instanceof ControlMessageCodec.RequestMessageBroadcastMessageClientbound) {
                                        if (Agnos.isClient()) {
                                            Mirror.addMessage(Mirror.literal(((ControlMessageCodec.RequestMessageBroadcastMessageClientbound) msg).message));
                                        }
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
                                        if (hasDialtoneSidecar && Config.INSTANCE.dialtoneHostEnabled.value()) {
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
                                                                streamChannel
                                                                        .writeAndFlush(new ControlMessageCodec.DialtoneRegisterTicketMessageServerbound("v1_" + ticket))
                                                                        .addListener(ignored -> LOGGER.info("notified server of our ticket"));
                                                            }
                                                        }
                                                    })
                                                    .childHandler(handler)
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
                            });
                        }
                    }).addListener(it -> {
                        if (!it.isSuccess()) {
                            fail(it.cause());
                            return;
                        }
                        QuicStreamChannel streamChannel = (QuicStreamChannel) it.getNow();
                        LOGGER.info("control channel open: {}", streamChannel);
                        streamChannel
                                .writeAndFlush(new ControlMessageCodec.ProbeCapabilitiesMessageServerbound())
                                .addListener(ignored -> LOGGER.info("probing capabilities"));
                        streamChannel
                                .writeAndFlush(new ControlMessageCodec.RequestDomainAssignmentMessageServerbound())
                                .addListener(ignored -> LOGGER.info("control channel write complete"));
                        quicChannel.closeFuture().addListener(ignored -> datagramChannel.close());
                    });
                });
            });
        } catch (Throwable e) {
            fail(e);
            throw new RuntimeException(e);
        }
    }

    private void startKeepalive(QuicChannel channel) {
        cancelKeepalive();
        keepaliveFuture = channel.eventLoop().scheduleAtFixedRate(() -> {
                channel.flush();
        }, KEEPALIVE_INTERVAL_SECONDS, KEEPALIVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void cancelKeepalive() {
        if (keepaliveFuture != null && !keepaliveFuture.isCancelled()) {
            keepaliveFuture.cancel(false);
            keepaliveFuture = null;
        }
    }

    private void fail(Throwable e) {
        QuiclimeSession.this.state = State.UNHEALTHY;
        failureCause = e;
        E4allClient.LOGGER.error("error in e4all", e);
        if (Agnos.isClient()) {
            Mirror.addMessage(Mirror.translatable("text.e4mc_minecraft.error"));
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
        afterCloseIfPresent(dialtoneChannel, q -> afterCloseIfPresent(quicChannel, a -> afterCloseIfPresent(datagramChannel, b -> state = State.STOPPED)));
    }

    private void cleanupChannels() {
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


    private static ByteBuf writeVarInt(ByteBuf buf, int value) {
        while ((value & 0xffffff80) != 0) {
            buf.writeByte(value & 0x7F | 0x80);
            value >>>= 7;
        }

        buf.writeByte(value);
        return buf;
    }
}
