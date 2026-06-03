package link.e4all.mixin;

import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import link.e4all.Config;
import link.e4all.E4allClient;
import link.e4all.QuiclimeSession;
import link.e4all.VoiceChatBridge;
import link.e4all.VoiceChatBridgeInitializer;
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

    @ModifyArg(method = "/^(startTcpServerListener|method_14354|m_9711_)$/", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/ServerBootstrap;childHandler(Lio/netty/channel/ChannelHandler;)Lio/netty/bootstrap/ServerBootstrap;", remap = false), require = 0)
    private ChannelHandler interceptHandler(ChannelHandler childHandler) {
        e4mc$childHandler = childHandler;
        return childHandler;
    }

    @ModifyArg(method = "/^(startTcpServerListener|method_14354|m_9711_)$/", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/ServerBootstrap;group(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/ServerBootstrap;", remap = false), require = 0)
    private EventLoopGroup interceptGroup(EventLoopGroup group) {
        e4mc$group = group;
        return group;
    }

    @Inject(method = "/^(startTcpServerListener|method_14354|m_9711_)$/", at = @At(value = "TAIL"), require = 0)
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

        if (Config.INSTANCE.hostEnabled.value()) {
            synchronized (E4allClient.SESSION_LOCK) {
                QuiclimeSession existing = E4allClient.session;
                if (existing != null) {
                    if (existing.state == QuiclimeSession.State.UNHEALTHY
                            || existing.state == QuiclimeSession.State.STOPPED) {
                        E4allClient.LOGGER.info("e4all: Cleaning up stale session (state: {})", existing.state);
                        E4allClient.session = null;
                    } else if (existing.state == QuiclimeSession.State.STARTING
                            || existing.state == QuiclimeSession.State.STARTED
                            || existing.state == QuiclimeSession.State.RECONNECTING) {
                        // Session is still active or reconnecting — don't create a new one
                        E4allClient.LOGGER.info("e4all: Session already active (state: {}), skipping new tunnel creation", existing.state);
                        e4mc$childHandler = null;
                        e4mc$group = null;
                        return;
                    }
                }
                E4allClient.session = new QuiclimeSession(new VoiceChatBridgeInitializer(e4mc$childHandler, true), e4mc$group);
                VoiceChatBridge.resetCachedPort(); // Re-detect SVC on each session start
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
                E4allClient.session = null;
            }
        }
    }
}


