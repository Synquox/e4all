package link.e4all.dialtone;

import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import link.e4all.AndroidDetector;
import link.e4all.AndroidNatives;
import link.e4all.E4allClient;
import link.e4all.QuiclimeSession;
import link.e4mc.iroh.Endpoint;
import link.e4mc.iroh.NativeException;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public class DialtoneAmbientSession {
    public static final DialtoneAmbientSession INSTANCE = new DialtoneAmbientSession();

    public EventLoopGroup group = new DefaultEventLoopGroup(
            new DefaultThreadFactory("e4all-dialtone", true));
    volatile Endpoint endpoint;
    volatile Thread dispatcher;
    private CompletableFuture<Void> closeFuture = CompletableFuture.completedFuture(null);

    private DialtoneAmbientSession() {}

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public boolean isStarted() {
        return endpoint != null;
    }

    public synchronized Endpoint getOrStart() throws Exception {
        if (endpoint != null) {
            return endpoint;
        }
        if (!closeFuture.isDone()) {
            throw new IllegalStateException("Dialtone endpoint shutdown is still in progress");
        }
        if (AndroidDetector.isAndroid() && !AndroidNatives.hasIrohNative()) {
            E4allClient.LOGGER.warn("e4all: Dialtone (iroh) unavailable on Android: no Bionic iroh native bundled.");
            throw new UnsupportedOperationException("Dialtone is not supported on Android without a Bionic iroh native");
        }
        E4allClient.LOGGER.info("Starting DialtoneAmbientSession!");
        Endpoint createdEndpoint = new Endpoint(
                new byte[][]{"e4mc-dialtone".getBytes(StandardCharsets.UTF_8)},
                QuiclimeSession.getRelayMap());
        Thread createdDispatcher = new Thread(() -> {
            while (endpoint == createdEndpoint) {
                try {
                    Runnable polled = createdEndpoint.pollCallbackLoop();
                    polled.run();
                } catch (NativeException e) {
                    E4allClient.LOGGER.error("poll exc, stopping", e);
                    throw e;
                } catch (Throwable e) {
                    E4allClient.LOGGER.error("poll exc, continuing", e);
                }
            }
        }, "Dialtone Session Dispatcher");
        createdDispatcher.setDaemon(true);
        this.endpoint = createdEndpoint;
        this.dispatcher = createdDispatcher;
        createdDispatcher.start();
        return createdEndpoint;
    }

    public void start() throws Exception {
        getOrStart();
    }

    public synchronized void stop() {
        final Endpoint endpointRef = endpoint;
        final Thread dispatcherRef = dispatcher;
        endpoint = null;
        dispatcher = null;

        if (endpointRef != null) {
            try {
                closeFuture = endpointRef.closeAsync().handle((ignored, failure) -> {
                    if (failure != null) {
                        E4allClient.LOGGER.warn("Error closing DialtoneAmbientSession endpoint asynchronously", failure);
                    }
                    try {
                        endpointRef.close();
                    } catch (Throwable e) {
                        E4allClient.LOGGER.warn("Error during endpoint final close", e);
                    }
                    if (dispatcherRef != null) {
                        dispatcherRef.interrupt();
                    }
                    return null;
                });
            } catch (Throwable e) {
                E4allClient.LOGGER.warn("Error closing DialtoneAmbientSession endpoint", e);
                try {
                    endpointRef.close();
                } catch (Throwable e2) {
                    E4allClient.LOGGER.warn("Error during fallback endpoint close", e2);
                }
                if (dispatcherRef != null) {
                    dispatcherRef.interrupt();
                }
                closeFuture = CompletableFuture.completedFuture(null);
            }
        } else if (dispatcherRef != null) {
            dispatcherRef.interrupt();
        }
    }
}
