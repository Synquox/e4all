package link.e4all;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslHandler;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddresses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class NetDns {
    private static final Logger LOGGER = LoggerFactory.getLogger(E4allClient.MOD_ID);

    private static final EventLoopGroup GROUP = new NioEventLoopGroup(1, r -> {
        Thread t = new Thread(r, "e4all-net");
        t.setDaemon(true);
        return t;
    });

    private static volatile DnsNameResolver resolver;

    private NetDns() {}

    public static final class Response {
        public final int status;
        public final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    // android jvms have no /etc/resolv.conf so the system resolver is often dead,
    // we do dns ourselves with netty and the servers the launcher put in resolv.conf
    public static InetAddress resolve(String host) throws Exception {
        try {
            List<InetAddress> all = resolver().resolveAll(host).get(10, TimeUnit.SECONDS);
            for (InetAddress addr : all) {
                if (addr instanceof Inet4Address) return addr;
            }
            if (!all.isEmpty()) return all.get(0);
            throw new UnknownHostException(host);
        } catch (Exception e) {
            InetAddress[] sys = InetAddress.getAllByName(host);
            for (InetAddress addr : sys) {
                if (addr instanceof Inet4Address) return addr;
            }
            if (sys.length > 0) return sys[0];
            throw new UnknownHostException(host);
        }
    }

    public static Response httpGet(URI uri) throws Exception {
        String host = uri.getHost();
        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        int defaultPort = tls ? 443 : 80;
        int port = uri.getPort() == -1 ? defaultPort : uri.getPort();
        InetAddress addr = resolve(host);
        SSLEngine sslEngine = tls ? newEngine(host, port) : null;

        CompletableFuture<Response> done = new CompletableFuture<>();
        Bootstrap bs = new Bootstrap()
                .group(GROUP)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline p = ch.pipeline();
                        if (sslEngine != null) {
                            p.addLast(new SslHandler(sslEngine));
                        }
                        p.addLast(new HttpClientCodec());
                        p.addLast(new HttpObjectAggregator(2 * 1024 * 1024));
                        p.addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) {
                                byte[] bytes = new byte[msg.content().readableBytes()];
                                msg.content().readBytes(bytes);
                                done.complete(new Response(msg.status().code(), new String(bytes, StandardCharsets.UTF_8)));
                                ctx.close();
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                done.completeExceptionally(cause);
                                ctx.close();
                            }
                        });
                    }
                });
        Channel ch = bs.connect(new InetSocketAddress(addr, port)).syncUninterruptibly().channel();
        ch.eventLoop().schedule(() -> {
            if (!done.isDone()) {
                done.completeExceptionally(new SocketTimeoutException("no response within 15s"));
                ch.close();
            }
        }, 15, TimeUnit.SECONDS);

        String path = uri.getRawPath() == null ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path);
        req.headers().set(HttpHeaderNames.HOST, host + (port == defaultPort ? "" : ":" + port));
        req.headers().set(HttpHeaderNames.ACCEPT, "application/json");
        req.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        req.headers().set(HttpHeaderNames.USER_AGENT, "e4all");
        ch.writeAndFlush(req).addListener(f -> {
            if (!f.isSuccess()) {
                done.completeExceptionally(f.cause());
                ch.close();
            }
        });
        try {
            return done.get(20, TimeUnit.SECONDS);
        } finally {
            ch.close();
        }
    }

    private static SSLEngine newEngine(String host, int port) throws Exception {
        SSLEngine engine = SSLContext.getDefault().createSSLEngine(host, port);
        engine.setUseClientMode(true);
        SSLParameters params = engine.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        engine.setSSLParameters(params);
        return engine;
    }

    private static DnsNameResolver resolver() {
        DnsNameResolver r = resolver;
        if (r != null) return r;
        synchronized (NetDns.class) {
            if (resolver == null) {
                DnsNameResolverBuilder b = new DnsNameResolverBuilder(GROUP.next())
                        .channelType(NioDatagramChannel.class)
                        .queryTimeoutMillis(5000)
                        .nameServerProvider(hostname -> DnsServerAddresses.sequential(nameServers()).stream());
                resolver = b.build();
            }
            return resolver;
        }
    }

    private static List<InetSocketAddress> nameServers() {
        List<InetSocketAddress> out = new ArrayList<>();
        String path = System.getProperty("ext.net.resolvPath");
        if (path != null) {
            try {
                for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (line.startsWith("nameserver")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            out.add(new InetSocketAddress(InetAddress.getByName(parts[1]), 53));
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        if (out.isEmpty()) {
            LOGGER.warn("e4all: no nameservers in {}, falling back to public dns", path);
            try {
                out.add(new InetSocketAddress(InetAddress.getByName("8.8.8.8"), 53));
                out.add(new InetSocketAddress(InetAddress.getByName("1.1.1.1"), 53));
            } catch (Exception ignored) {}
        }
        return out;
    }
}

