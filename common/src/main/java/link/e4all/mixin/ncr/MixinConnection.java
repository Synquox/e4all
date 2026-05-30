package link.e4all.mixin.ncr;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import java.lang.reflect.Method;

@Mixin(Connection.class)
public abstract class MixinConnection {

    @Shadow
    private PacketListener packetListener;

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$onSend(Packet<?> packet, @Nullable PacketSendListener packetSendListener, CallbackInfo info) {
        if (packet instanceof ClientboundPlayerChatPacket chat) {
            info.cancel();
            Packet<?> systemPacket = e4all$toSystemChat(packetListener, chat);
            ((Connection) (Object) this).send(systemPacket, packetSendListener);
        }
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$onSend3(Packet<?> packet, @Nullable PacketSendListener packetSendListener, boolean flush, CallbackInfo info) {
        if (packet instanceof ClientboundPlayerChatPacket chat) {
            info.cancel();
            Packet<?> systemPacket = e4all$toSystemChat(packetListener, chat);
            ((Connection) (Object) this).send(systemPacket, packetSendListener, flush);
        }
    }

    private static Packet<?> e4all$toSystemChat(PacketListener listener, ClientboundPlayerChatPacket chat) {
        ServerPlayer player = null;
        if (listener != null) {
            try {
                Method getPlayerMethod = listener.getClass().getMethod("getPlayer");
                player = (ServerPlayer) getPlayerMethod.invoke(listener);
            } catch (Exception e1) {
                try {
                    player = (ServerPlayer) listener.getClass().getField("player").get(listener);
                } catch (Exception ignored) {}
            }
        }

        Component decorated = e4all$decorate(player, chat);
        return new ClientboundSystemChatPacket(decorated, false);
    }

    private static Component e4all$decorate(ServerPlayer player, ClientboundPlayerChatPacket chat) {
        try {
            // Modern approach (1.20+): ChatType.Bound.decorate(Component)
            Object bound = chat.getClass().getMethod("chatType").invoke(chat);
            Method decorate = bound.getClass().getMethod("decorate", Component.class);
            Component content = chat.unsignedContent() != null ? chat.unsignedContent() : Component.literal(chat.body().content());
            return (Component) decorate.invoke(bound, content);
        } catch (Exception e) {
            if (player != null) {
                try {
                    // Fallback approach (1.19.4): Registry resolution
                    Object chatType = chat.getClass().getMethod("chatType").invoke(chat);
                    Object level;
                    try {
                        level = player.getClass().getMethod("level").invoke(player);
                    } catch (Exception ignored) {
                        level = player.getClass().getField("level").get(player);
                    }
                    Object registryAccess = level.getClass().getMethod("registryAccess").invoke(level);
                    
                    Method resolveMethod = null;
                    for (Method m : chatType.getClass().getMethods()) {
                        if (m.getName().equals("resolve") && m.getParameterCount() == 1) {
                            resolveMethod = m;
                            break;
                        }
                    }
                    
                    if (resolveMethod != null) {
                        Object resolved = resolveMethod.invoke(chatType, registryAccess);
                        Object chatTypeInstance = resolved.getClass().getMethod("get").invoke(resolved);
                        Component content = chat.unsignedContent() != null ? chat.unsignedContent() : Component.literal(chat.body().content());
                        return (Component) chatTypeInstance.getClass().getMethod("decorate", Component.class).invoke(chatTypeInstance, content);
                    }
                } catch (Exception ignored) {}
            }
            
            // Ultimate fallback: undecorated text content
            try {
                return chat.unsignedContent() != null ? chat.unsignedContent() : Component.literal(chat.body().content());
            } catch (Exception ignored) {
                return Component.literal("");
            }
        }
    }
}
