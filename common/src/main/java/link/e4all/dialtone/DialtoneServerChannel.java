package link.e4all.dialtone;

import io.netty.channel.AbstractServerChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import link.e4all.E4allClient;
import link.e4all.QuiclimeSession;
import link.e4mc.iroh.Endpoint;
import link.e4mc.iroh.NativeException;
import link.e4mc.iroh.Resolvable;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

public class DialtoneServerChannel extends AbstractServerChannel {
    private final ChannelConfig config = new DefaultChannelConfig(this);
    Endpoint endpoint;
    Thread dispatcher;
    volatile boolean closed = false;

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    @Override
    protected SocketAddress localAddress0() {
        return new DialtoneAddress(endpoint.address());
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        this.endpoint = new Endpoint(new byte[][]{"e4mc-dialtone".getBytes(StandardCharsets.UTF_8)}, QuiclimeSession.getRelayMap());
        this.dispatcher = new Thread(() -> {
                while (true) {
                    try {
                        Runnable polled = endpoint.pollCallbackLoop();
                        polled.run();
                    } catch (NativeException e) {
                        E4allClient.LOGGER.error("poll exc, stopping", e);
                        throw e;
                    } catch (Throwable e) {
                        E4allClient.LOGGER.error("poll exc, continuing", e);
                    }
                }
        }, "Dialtone Server Dispatcher");
        this.dispatcher.setDaemon(true);
        this.dispatcher.start();
        endpoint.watchAddress(new Resolvable<>() {
            @Override
            public void resolve(String addr) {
                if (addr != null) {
                    E4allClient.LOGGER.info("got new session ticket");
                    eventLoop().execute(() -> pipeline().fireUserEventTriggered(new DialtoneAddress(addr)));
                }
            }

            @Override
            public void reject(Throwable throwable) {
                E4allClient.LOGGER.warn("Dialtone address watch rejected", throwable);
            }
        });
    }

    @Override
    protected void doClose() {
        if (closed) {
            return;
        }
        closed = true;
        final Thread dispatcherRef = dispatcher;
        dispatcher = null;
        try {
            if (endpoint != null) {
                final Endpoint endpointRef = endpoint;
                endpoint = null;
                endpointRef.closeAsync().thenRun(() -> {
                    try {
                        endpointRef.close();
                    } catch (Throwable e) {
                        E4allClient.LOGGER.warn("Error during endpoint close", e);
                    }
                    if (dispatcherRef != null) {
                        dispatcherRef.interrupt();
                    }
                });
            } else {
                if (dispatcherRef != null) {
                    dispatcherRef.interrupt();
                }
            }
        } catch (Throwable e) {
            E4allClient.LOGGER.warn("Error during async endpoint close", e);
            endpoint = null;
            if (dispatcherRef != null) {
                dispatcherRef.interrupt();
            }
        }
    }

    @Override
    protected void doBeginRead() throws Exception {
        endpoint.accept().thenAccept(preconn -> {
            E4allClient.LOGGER.info("preconn accepted, dialtone child registered");
            var channel = new DialtoneChannel(this);
            // All pipeline events must be dispatched on the event loop thread
            eventLoop().execute(() -> {
                pipeline().fireChannelRead(channel);
                pipeline().fireChannelReadComplete();
            });
            preconn.thenAccept(conn -> {
                E4allClient.LOGGER.info("conn accepted, dialtone child pre-active");
                channel.connection = conn;
                conn.acceptBi().thenAccept(bidi -> {
                    E4allClient.LOGGER.info("bidi accepted, dialtone child active");
                    channel.stream = bidi;
                    channel.eventLoop().execute(() -> channel.pipeline().fireChannelActive());
                }).exceptionally(biErr -> {
                    E4allClient.LOGGER.warn("Failed to accept bidirectional stream", biErr);
                    channel.eventLoop().execute(() -> channel.pipeline().fireChannelInactive());
                    return null;
                });
            }).exceptionally(e -> {
                channel.eventLoop().execute(() -> channel.pipeline().fireChannelInactive());
                eventLoop().execute(() -> pipeline().fireExceptionCaught(e));
                return null;
            });
        }).exceptionally(e -> {
            eventLoop().execute(() -> pipeline().fireExceptionCaught(e));
            return null;
        });
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public boolean isActive() {
        return !closed && endpoint != null;
    }
}
