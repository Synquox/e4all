package link.e4all.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import link.e4all.DialtoneConnectionExtensions;
import link.e4all.E4allClient;
import link.e4all.SmugglersInetSocketAddress;
import link.e4all.voice.RelayClientVoicechatSocket;
import link.e4all.dialtone.DialtoneAddress;
import link.e4all.dialtone.DialtoneAmbientSession;
import link.e4all.dialtone.DialtoneChannel;
import net.minecraft.network.Connection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.crypto.Cipher;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

@Mixin(Connection.class)
public abstract class ConnectionMixin implements DialtoneConnectionExtensions {

    @Shadow private Channel channel;

    @Unique
    private static final ThreadLocal<DialtoneAddress> e4mc$smuggledDialtoneAddress = new ThreadLocal<>();

    @Override
    public byte[] e4mc$exportKeyingMaterial(byte[] label, byte[] context, int length) {
        if (channel instanceof DialtoneChannel dialtoneChannel) {
            return dialtoneChannel.exportKeyingMaterial(label, context, length);
        }
        return null;
    }

    @Inject(method = "/^(connect|method_52271|m_290025_)$/", at = @At("HEAD"), require = 0)
    private static void hijackStart(InetSocketAddress inetSocketAddress, @Coerce Object obj, Connection connection, CallbackInfoReturnable<ChannelFuture> cir) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress.set(new DialtoneAddress(smuggledAddress.ticket));
        } else {
            RelayClientVoicechatSocket.setPendingDialtoneTicket(null);
        }
    }

    @Surrogate
    private static void hijackStart(InetSocketAddress inetSocketAddress, boolean bl, Connection connection, CallbackInfoReturnable<ChannelFuture> cir) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress.set(new DialtoneAddress(smuggledAddress.ticket));
        } else {
            RelayClientVoicechatSocket.setPendingDialtoneTicket(null);
        }
    }

    @Surrogate
    private static void hijackStart(InetSocketAddress inetSocketAddress, @Coerce Object obj, Connection connection, CallbackInfo ci) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress.set(new DialtoneAddress(smuggledAddress.ticket));
        } else {
            RelayClientVoicechatSocket.setPendingDialtoneTicket(null);
        }
    }

    @Surrogate
    private static void hijackStart(InetSocketAddress inetSocketAddress, boolean bl, Connection connection, CallbackInfo ci) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress.set(new DialtoneAddress(smuggledAddress.ticket));
        } else {
            RelayClientVoicechatSocket.setPendingDialtoneTicket(null);
        }
    }

    @Inject(method = "name=/^(connectToServer|method_10753|m_178300_)$/ desc=/^\\(Ljava\\/net\\/InetSocketAddress;Z\\)L.+;$/", at = @At("HEAD"), require = 0)
    private static void hijackStartAlt(InetSocketAddress inetSocketAddress, boolean bl, CallbackInfoReturnable<Connection> cir) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress.set(new DialtoneAddress(smuggledAddress.ticket));
        } else {
            RelayClientVoicechatSocket.setPendingDialtoneTicket(null);
        }
    }

    @ModifyArg(method = "/^(connect|method_52271|m_290025_|connectToServer|method_10753|m_178300_)$/", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;"), require = 0)
    private static Class hijackChannel(Class clazz) {
        if (e4mc$smuggledDialtoneAddress.get() != null) {
            return DialtoneChannel.class;
        } else {
            return clazz;
        }
    }

    @ModifyArg(method = "/^(connect|method_52271|m_290025_|connectToServer|method_10753|m_178300_)$/", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;group(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/AbstractBootstrap;"), require = 0)
    private static EventLoopGroup hijackGroup(EventLoopGroup group) {
        if (e4mc$smuggledDialtoneAddress.get() != null) {
            return DialtoneAmbientSession.INSTANCE.group;
        } else {
            return group;
        }
    }

    @WrapOperation(method = "/^(connect|method_52271|m_290025_|connectToServer|method_10753|m_178300_)$/", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/InetAddress;I)Lio/netty/channel/ChannelFuture;"), require = 0)
    private static ChannelFuture hijackConnect(Bootstrap instance, InetAddress inetHost, int inetPort, Operation<ChannelFuture> operation) {
        if (e4mc$smuggledDialtoneAddress.get() != null) {
            var ret = instance.connect(e4mc$smuggledDialtoneAddress.get());
            e4mc$smuggledDialtoneAddress.remove();
            return ret;
        } else {
            return operation.call(instance, inetHost, inetPort);
        }
    }

    @WrapOperation(method = "/^(connect|method_52271|m_290025_|connectToServer|method_10753|m_178300_)$/", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/SocketAddress;)Lio/netty/channel/ChannelFuture;"), require = 0)
    private static ChannelFuture hijackConnectSocketAddress(Bootstrap instance, SocketAddress remoteAddress, Operation<ChannelFuture> operation) {
        if (e4mc$smuggledDialtoneAddress.get() != null) {
            var ret = instance.connect(e4mc$smuggledDialtoneAddress.get());
            e4mc$smuggledDialtoneAddress.remove();
            return ret;
        } else {
            return operation.call(instance, remoteAddress);
        }
    }

    @Inject(method = "/^(connect|method_52271|m_290025_|connectToServer|method_10753|m_178300_)$/", at = @At("RETURN"), require = 0)
    private static void e4all$cleanupSmuggledAddress(CallbackInfoReturnable<?> cir) {
        e4mc$smuggledDialtoneAddress.remove();
    }

    @Surrogate
    private static void e4all$cleanupSmuggledAddress(CallbackInfo ci) {
        e4mc$smuggledDialtoneAddress.remove();
    }

    @Inject(method = "/^(setEncryptionKey|method_10746|m_129506_)$/", at = @At(value = "FIELD", target = "Lnet/minecraft/network/Connection;channel:Lio/netty/channel/Channel;", opcode = org.objectweb.asm.Opcodes.GETFIELD, ordinal = 0), cancellable = true, require = 0)
    private void killDoubleEncryption(Cipher cipher, Cipher cipher2, CallbackInfo ci) {
        if (channel instanceof DialtoneChannel) {
            ci.cancel();
        }
    }

    @Inject(method = "setupCompression", at = @At("HEAD"), cancellable = true, require = 0)
    private void killDoubleCompression(int threshold, boolean validate, CallbackInfo ci) {
        if (channel instanceof DialtoneChannel) {
            ci.cancel();
        }
    }

    @Surrogate
    private void killDoubleCompression(int threshold, CallbackInfo ci) {
        if (channel instanceof DialtoneChannel) {
            ci.cancel();
        }
    }
}
