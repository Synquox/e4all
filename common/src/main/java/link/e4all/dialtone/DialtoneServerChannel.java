package link.e4all.dialtone;

import io.netty.channel.AbstractServerChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import link.e4all.AndroidDetector;
import link.e4all.AndroidNatives;
import link.e4all.E4allClient;
import link.e4all.QuiclimeSession;
import link.e4mc.iroh.Endpoint;
import link.e4mc.iroh.NativeException;
import link.e4mc.iroh.Resolvable;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

public class DialtoneServerChannel extends AbstractServerChannel {
    // bootstrap option for voice-only relay-less endpoint
    public static final ChannelOption<Boolean> VOICE_MODE = ChannelOption.valueOf("e4all:voice_mode");

    private final ChannelConfig config = new DefaultChannelConfig(this) {
        @Override
        public <T> boolean setOption(ChannelOption<T> option, T value) {
            if (option == VOICE_MODE) {
                voiceMode = Boolean.TRUE.equals(value);
                return true;
            }
            return super.setOption(option, value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getOption(ChannelOption<T> option) {
            if (option == VOICE_MODE) {
                return (T) Boolean.valueOf(voiceMode);
            }
            return super.getOption(option);
        }
    };
    Endpoint endpoint;
    Thread dispatcher;
    volatile boolean closed = false;
    boolean voiceMode = false;

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    @Override
    protected SocketAddress localAddress0() {
        return new DialtoneAddress(endpoint.address(), voiceMode ? DialtoneAddress.VOICE_ALPN : DialtoneAddress.GAME_ALPN);
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        if (AndroidDetector.isAndroid() && !AndroidNatives.hasIrohNative()) {
            E4allClient.LOGGER.warn("e4all: Dialtone (iroh) unavailable on Android: no Bionic iroh native bundled.");
            throw new UnsupportedOperationException("Dialtone is not supported on Android without a Bionic iroh native");
        }
        if (config.getOption(VOICE_MODE) != null) {
            voiceMode = Boolean.TRUE.equals(config.getOption(VOICE_MODE));
        }
        String[] relayMap;
        try {
            relayMap = QuiclimeSession.getRelayMap();
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("e4all: failed to fetch relay map, using empty", t);
            relayMap = new String[0];
        }
        if (voiceMode) {
            this.endpoint = new Endpoint(new byte[][]{DialtoneAddress.VOICE_ALPN.getBytes(StandardCharsets.UTF_8)}, relayMap);
            E4allClient.LOGGER.info("e4all voice: voice endpoint bound (ALPN: {})", DialtoneAddress.VOICE_ALPN);
        } else {
            this.endpoint = new Endpoint(new byte[][]{DialtoneAddress.GAME_ALPN.getBytes(StandardCharsets.UTF_8)}, relayMap);
        }
        this.dispatcher = new Thread(() -> {
                while (!closed && endpoint != null) {
                    try {
                        Endpoint ep = endpoint;
                        if (ep == null || closed) break;
                        Runnable polled = ep.pollCallbackLoop();
                        if (polled != null) {
                            polled.run();
                        }
                    } catch (NativeException e) {
                        if (closed || endpoint == null) break;
                        E4allClient.LOGGER.debug("e4all voice: server native poll notice: {}", e.getMessage());
                    } catch (Throwable e) {
                        if (closed || endpoint == null) break;
                        E4allClient.LOGGER.debug("e4all voice: server endpoint poll error, continuing", e);
                    }
                }
        }, "Dialtone Server Dispatcher");
        this.dispatcher.setDaemon(true);
        this.dispatcher.start();
        endpoint.watchAddress(new Resolvable<>() {
            @Override
            public void resolve(String addr) {
                if (addr != null) {
                    E4allClient.LOGGER.info("got new session ticket (length={})", addr.length());
                    eventLoop().execute(() -> pipeline().fireUserEventTriggered(new DialtoneAddress(addr, voiceMode ? DialtoneAddress.VOICE_ALPN : DialtoneAddress.GAME_ALPN)));
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
        if (endpoint == null) return;
        endpoint.accept().thenAccept(preconn -> {
            E4allClient.LOGGER.info("preconn accepted, dialtone child registered");
            var channel = new DialtoneChannel(this);
            eventLoop().execute(() -> {
                if (closed) {
                    channel.closed = true;
                    return;
                }
                pipeline().fireChannelRead(channel);
                pipeline().fireChannelReadComplete();
                if (!closed && config().isAutoRead()) {
                    try {
                        doBeginRead();
                    } catch (Exception e) {
                        pipeline().fireExceptionCaught(e);
                    }
                }
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
