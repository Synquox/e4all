package link.e4all.mixin;

import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import link.e4all.Config;
import link.e4all.E4allClient;
import link.e4all.QuiclimeSession;
import link.e4all.XaeroWorldIdentity;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetAddress;

@Mixin(ServerConnectionListener.class)
public abstract class ServerConnectionListenerMixin {
    @Unique
    private ChannelHandler e4mc$childHandler;
    @Unique
    private EventLoopGroup e4mc$group;

    @ModifyArg(method = "startTcpServerListener", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/ServerBootstrap;childHandler(Lio/netty/channel/ChannelHandler;)Lio/netty/bootstrap/ServerBootstrap;", remap = false), require = 0)
    private ChannelHandler interceptHandler(ChannelHandler childHandler) {
        e4mc$childHandler = childHandler;
        return childHandler;
    }

    @ModifyArg(method = "startTcpServerListener", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/ServerBootstrap;group(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/ServerBootstrap;", remap = false), require = 0)
    private EventLoopGroup interceptGroup(EventLoopGroup group) {
        e4mc$group = group;
        return group;
    }

    @Inject(method = "startTcpServerListener", at = @At(value = "TAIL"), require = 0)
    private void interceptGroup(InetAddress inetAddress, int i, CallbackInfo ci) {
        boolean realE4mcInstalled = false;
        try {
            Class.forName("link.e4mc.Agnos", false, this.getClass().getClassLoader());
            realE4mcInstalled = true;
        } catch (ClassNotFoundException ignored) {}
        
        if (realE4mcInstalled) {
            link.e4all.E4allClient.LOGGER.info("e4all: Real e4mc mod detected! Skipping e4all tunnel creation. e4all will function strictly as an Offline Mode addon.");
            e4mc$childHandler = null;
            e4mc$group = null;
            return;
        }

        if (link.e4all.AndroidDetector.isAndroid() && !link.e4all.AndroidNatives.hasQuicheNative()) {
            link.e4all.E4allClient.LOGGER.warn("e4all: Android detected but no Bionic QUIC native is available, relay hosting disabled. Detection: {}", link.e4all.AndroidDetector.detect().reason());
            if (link.e4all.Agnos.isClient()) {
                link.e4all.Mirror.addMessage(link.e4all.Mirror.translatable("text.e4all_minecraft.android.noNative"));
            }
            e4mc$childHandler = null;
            e4mc$group = null;
            return;
        }

        if (Config.INSTANCE.hostEnabled.value()) {
            synchronized (E4allClient.SESSION_LOCK) {
                QuiclimeSession existing = E4allClient.session;
                if (existing != null && existing.state == QuiclimeSession.State.STOPPING) {
                    // old session is mid-teardown; wait for a terminal state so we
                    // never run two sessions at once
                    E4allClient.LOGGER.info("e4all: session is stopping, waiting for teardown to finish");
                    long deadline = System.currentTimeMillis() + 5000;
                    while (existing.state == QuiclimeSession.State.STOPPING && System.currentTimeMillis() < deadline) {
                        try {
                            E4allClient.SESSION_LOCK.wait(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    if (existing.state == QuiclimeSession.State.STOPPING) {
                        E4allClient.LOGGER.warn("e4all: session still stopping after 5s, forcing sync stop");
                        try {
                            existing.stopSync();
                        } catch (Throwable t) {
                            E4allClient.LOGGER.warn("e4all: forced stop failed", t);
                        }
                    }
                    existing = E4allClient.session;
                }
                if (existing != null) {
                    if (existing.state == QuiclimeSession.State.UNHEALTHY
                            || existing.state == QuiclimeSession.State.STOPPED) {
                        E4allClient.LOGGER.info("e4all: Cleaning up stale session (state: {})", existing.state);
                        E4allClient.session = null;
                    } else if (existing.state == QuiclimeSession.State.STARTING
                            || existing.state == QuiclimeSession.State.STARTED
                            || existing.state == QuiclimeSession.State.RECONNECTING) {
                        // session is still active, dont make another one
                        E4allClient.LOGGER.info("e4all: Session already active (state: {}), skipping new tunnel creation", existing.state);
                        e4mc$childHandler = null;
                        e4mc$group = null;
                        return;
                    } else if (existing.state == QuiclimeSession.State.STOPPING) {
                        // should not happen after the wait above, but never spawn a
                        // duplicate session mid-teardown
                        E4allClient.LOGGER.warn("e4all: session still stopping, skipping new tunnel creation");
                        e4mc$childHandler = null;
                        e4mc$group = null;
                        return;
                    }
                }
                XaeroWorldIdentity.initializeForRelay(((ServerConnectionListener) (Object) this).getServer());
                E4allClient.session = new QuiclimeSession(e4mc$childHandler, e4mc$group);
                e4mc$childHandler = null;
                e4mc$group = null;
                E4allClient.session.startAsync();
            }
        } else {
            e4mc$childHandler = null;
            e4mc$group = null;
        }
    }

    @Inject(method = "stop", at = @At(value = "HEAD"), require = 0)
    private void interceptStop(CallbackInfo ci) {
        synchronized (E4allClient.SESSION_LOCK) {
            QuiclimeSession session = E4allClient.session;
            if ((session != null) && (session.state != QuiclimeSession.State.STOPPED)) {
                session.stop();
                // keep the reference around until it reaches a terminal state; the
                // creation path handles STOPPING/STOPPED cleanup under the same lock
                E4allClient.SESSION_LOCK.notifyAll();
            }
        }
    }
}
