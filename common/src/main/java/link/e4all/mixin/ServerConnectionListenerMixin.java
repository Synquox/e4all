package link.e4all.mixin;

import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import link.e4all.Config;
import link.e4all.E4allClient;
import link.e4all.QuiclimeSession;
import link.e4all.ServerStartupTracker;
import link.e4all.XaeroWorldIdentity;
import net.minecraft.server.MinecraftServer;
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
        ChannelHandler childHandler = e4mc$childHandler;
        EventLoopGroup group = e4mc$group;
        e4mc$childHandler = null;
        e4mc$group = null;

        boolean realE4mcInstalled = false;
        try {
            Class.forName("link.e4mc.Agnos", false, this.getClass().getClassLoader());
            realE4mcInstalled = true;
        } catch (ClassNotFoundException ignored) {}

        if (realE4mcInstalled) {
            link.e4all.E4allClient.LOGGER.info("e4all: Real e4mc mod detected! Skipping e4all tunnel creation. e4all will function strictly as an Offline Mode addon.");
            return;
        }

        if (link.e4all.AndroidDetector.isAndroid() && !link.e4all.AndroidNatives.hasQuicheNative()) {
            link.e4all.E4allClient.LOGGER.warn("e4all: Android detected but no Bionic QUIC native is available, relay hosting disabled. Detection: {}", link.e4all.AndroidDetector.detect().reason());
            if (link.e4all.Agnos.isClient()) {
                link.e4all.Mirror.addMessage(link.e4all.Mirror.translatable("text.e4all_minecraft.android.noNative"));
            }
            return;
        }

        if (!Config.INSTANCE.hostEnabled.value()) {
            return;
        }

        MinecraftServer server = ((ServerConnectionListener) (Object) this).getServer();

        synchronized (E4allClient.SESSION_LOCK) {
            QuiclimeSession existing = E4allClient.session;

            // session of this world is still valid, keep it so re-opening LAN keeps the address
            if (existing != null && existing.ownsServer(server)
                    && (existing.state == QuiclimeSession.State.STARTING
                        || existing.state == QuiclimeSession.State.STARTED)) {
                E4allClient.LOGGER.info("e4all: relay session already active for this world (state: {}), keeping it",
                        existing.state);
                return;
            }

            if (existing != null) {
                E4allClient.LOGGER.info("e4all: dropping stale relay session (state: {}, owns this world: {}), starting the new one",
                        existing.state, existing.ownsServer(server));
                E4allClient.session = null;
                existing.stop(); // asynchronous, never blocks the server thread
            }

            XaeroWorldIdentity.initializeForRelay(server);
            QuiclimeSession session = new QuiclimeSession(childHandler, group, server);
            E4allClient.session = session;
            E4allClient.LOGGER.info("e4all: starting a relay session for the current world ({})",
                    ServerStartupTracker.describe(server));
            session.startAsync();
        }
    }

    @Inject(method = "stop", at = @At(value = "HEAD"), require = 0)
    private void interceptStop(CallbackInfo ci) {
        MinecraftServer server = ((ServerConnectionListener) (Object) this).getServer();
        synchronized (E4allClient.SESSION_LOCK) {
            QuiclimeSession session = E4allClient.session;
            if (session == null) return;
            if (!session.ownsServer(server)) {
                E4allClient.LOGGER.info("e4all: listener of another world stopped, keeping the active relay session");
                return;
            }
            if (session.state != QuiclimeSession.State.STOPPED) {
                E4allClient.LOGGER.info("e4all: world listener stopping, tearing down the relay session");
                session.stop();
            }
            E4allClient.SESSION_LOCK.notifyAll();
        }
    }
}
