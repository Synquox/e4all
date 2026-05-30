package link.e4all.dialtone;

import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import link.e4all.E4allClient;
import link.e4all.QuiclimeSession;
import link.e4mc.iroh.Endpoint;
import link.e4mc.iroh.NativeException;

import java.nio.charset.StandardCharsets;

public class DialtoneAmbientSession {
    public static final DialtoneAmbientSession INSTANCE = new DialtoneAmbientSession();

    public EventLoopGroup group = new DefaultEventLoopGroup();
    volatile Endpoint endpoint;
    volatile Thread dispatcher;

    private DialtoneAmbientSession() {}

    public synchronized void start() throws Exception {
        if (endpoint != null || dispatcher != null) {
            E4allClient.LOGGER.info("Cleaning up stale DialtoneAmbientSession before restart");
            stop();
        }
        E4allClient.LOGGER.info("Starting DialtoneAmbientSession!");
        this.endpoint = new Endpoint(new byte[][]{"e4mc-dialtone".getBytes(StandardCharsets.UTF_8)}, QuiclimeSession.getRelayMap());
        this.dispatcher = new Thread(() -> {
            while (true) {
                Endpoint ep = endpoint;
                if (ep == null) break;
                try {
                    Runnable polled = ep.pollCallbackLoop();
                    polled.run();
                } catch (NativeException e) {
                    E4allClient.LOGGER.error("poll exc, stopping", e);
                    throw e;
                } catch (Throwable e) {
                    E4allClient.LOGGER.error("poll exc, continuing", e);
                }
            }
        }, "Dialtone Session Dispatcher");
        this.dispatcher.setDaemon(true);
        this.dispatcher.start();
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
                        E4allClient.LOGGER.warn("Error during endpoint final close", e);
                    }
                    if (dispatcherRef != null) {
                        dispatcherRef.interrupt();
                    }
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
            }
        } else if (dispatcherRef != null) {
            dispatcherRef.interrupt();
        }
    }
}
