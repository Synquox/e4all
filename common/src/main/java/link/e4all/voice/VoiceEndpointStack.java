package link.e4all.voice;

import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import link.e4all.AndroidDetector;
import link.e4all.AndroidNatives;
import link.e4all.E4allClient;
import link.e4all.QuiclimeSession;
import link.e4all.dialtone.DialtoneAddress;
import link.e4mc.iroh.Endpoint;
import link.e4mc.iroh.NativeException;

import java.nio.charset.StandardCharsets;

// Client-side voice endpoint for Dialtone P2P voice
public final class VoiceEndpointStack {
    public static final VoiceEndpointStack INSTANCE = new VoiceEndpointStack();

    private final EventLoopGroup group = new DefaultEventLoopGroup(1);
    private volatile Endpoint endpoint;
    private volatile Thread dispatcher;

    private VoiceEndpointStack() {}

    public EventLoopGroup group() {
        return group;
    }

    public synchronized Endpoint getOrCreate() throws Exception {
        if (endpoint != null) {
            return endpoint;
        }
        if (AndroidDetector.isAndroid() && !AndroidNatives.hasIrohNative()) {
            throw new UnsupportedOperationException("Dialtone voice is not supported on Android without a Bionic iroh native");
        }
        E4allClient.LOGGER.info("e4all voice: starting voice endpoint");
        String[] relayMap;
        try {
            relayMap = QuiclimeSession.getRelayMap();
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("e4all voice: failed to fetch relay map, using empty", t);
            relayMap = new String[0];
        }
        this.endpoint = new Endpoint(
                new byte[][]{DialtoneAddress.VOICE_ALPN.getBytes(StandardCharsets.UTF_8)},
                relayMap);
        this.dispatcher = new Thread(() -> {
            while (endpoint != null) {
                try {
                    Endpoint ep = endpoint;
                    if (ep == null) break;
                    Runnable polled = ep.pollCallbackLoop();
                    if (polled != null) {
                        polled.run();
                    }
                } catch (NativeException e) {
                    if (endpoint == null) break;
                    E4allClient.LOGGER.debug("e4all voice: client native poll notice: {}", e.getMessage());
                } catch (Throwable e) {
                    if (endpoint == null) break;
                    E4allClient.LOGGER.debug("e4all voice: client endpoint poll error, continuing", e);
                }
            }
        }, "e4all-voice-endpoint");
        this.dispatcher.setDaemon(true);
        this.dispatcher.start();
        return endpoint;
    }

    public synchronized void stop() {
        final Endpoint endpointRef = endpoint;
        final Thread dispatcherRef = dispatcher;
        endpoint = null;
        dispatcher = null;
        if (endpointRef != null) {
            try {
                endpointRef.closeAsync().thenRun(() -> {
                    try {
                        endpointRef.close();
                    } catch (Throwable e) {
                        E4allClient.LOGGER.warn("e4all voice: error during endpoint final close", e);
                    }
                    if (dispatcherRef != null) {
                        dispatcherRef.interrupt();
                    }
                });
            } catch (Throwable e) {
                E4allClient.LOGGER.warn("e4all voice: error closing voice endpoint", e);
                try {
                    endpointRef.close();
                } catch (Throwable ignored) {}
                if (dispatcherRef != null) {
                    dispatcherRef.interrupt();
                }
            }
        } else if (dispatcherRef != null) {
            dispatcherRef.interrupt();
        }
    }
}