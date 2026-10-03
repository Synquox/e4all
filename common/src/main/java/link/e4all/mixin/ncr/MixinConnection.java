package link.e4all.mixin.ncr;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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

    // prevent recursion loop
    @Unique
    private boolean e4all$converting = false;

    // two same-shaped decorate methods exist, the narrator one must not win (it does on srg)
    private static final String[] CHAT_DECORATION_METHOD_NAMES = {
            "decorate",            // mojmap
            "applyChatDecoration", // yarn
            "method_44837",        // intermediary
            "m_240977_"            // srg
    };
    private static final String[] NARRATION_DECORATION_METHOD_NAMES = {
            "decorateNarration",
            "applyNarrationDecoration",
            "method_44838",
            "m_240941_"
    };

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$onSend1(Packet<?> packet, CallbackInfo info) {
        if (!link.e4all.Config.INSTANCE.offlineMode.value()) return;
        if (e4all$converting || !(packet instanceof ClientboundPlayerChatPacket chat)) return;
        Packet<?> systemPacket = e4all$toSystemChat(packetListener, chat);
        if (systemPacket == null) return;
        info.cancel();
        e4all$converting = true;
        try {
            ((Connection) (Object) this).send(systemPacket);
        } finally {
            e4all$converting = false;
        }
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void e4all$onSendWithListener(Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo info) {
        if (!link.e4all.Config.INSTANCE.offlineMode.value()) return;
        if (e4all$converting || !(packet instanceof ClientboundPlayerChatPacket chat)) return;
        Packet<?> systemPacket = e4all$toSystemChat(packetListener, chat);
        if (systemPacket == null) return;
        info.cancel();
        e4all$converting = true;
        try {
            ((Connection) (Object) this).send(systemPacket);
        } finally {
            e4all$converting = false;
        }
    }

    private static Packet<?> e4all$toSystemChat(PacketListener listener, ClientboundPlayerChatPacket chat) {
        try {
            Component content = e4all$extractContent(chat);
            if (content == null) return null;
            Component decorated = e4all$decorate(listener, chat, content);
            return new ClientboundSystemChatPacket(decorated, false);
        } catch (Throwable t) {
            link.e4all.E4allClient.LOGGER.warn("e4all: could not convert a chat packet, sending it unchanged", t);
            return null;
        }
    }

    private static Component e4all$extractContent(Object packet) {
        Object raw = e4all$findRawContent(packet);
        if (raw == null) return null;
        if (raw instanceof Component c) {
            String s = c.getString();
            return s != null && !s.trim().isEmpty() ? c : null;
        }
        if (raw instanceof String s && !s.trim().isEmpty()) {
            return link.e4all.Mirror.literal(s);
        }
        return null;
    }

    private static Object e4all$findRawContent(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Component || obj instanceof String) return obj;
        if (obj instanceof java.util.Optional<?> opt) {
            return opt.map(MixinConnection::e4all$findRawContent).orElse(null);
        }
        String[] accessors = {
            "serverContent", "getServerContent", "method_44125",
            "unsignedContent", "getUnsignedContent", "comp_830", "comp_1103",
            "decoratedContent", "getDecoratedContent", "method_46291",
            "signedContent", "getSignedContent", "method_44862",
            "content", "getContent", "comp_929", "comp_1090",
            "plain", "getPlain", "comp_963",
            "message", "getMessage", "comp_942", "comp_1097",
            "body", "getBody", "comp_928"
        };
        for (String name : accessors) {
            try {
                Method m = obj.getClass().getMethod(name);
                m.setAccessible(true);
                Object val = m.invoke(obj);
                Object res = e4all$findRawContent(val);
                if (res != null) return res;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // sender name lives in the chat type bound, matched by shape since names differ per loader
    private static Component e4all$decorate(PacketListener listener, ClientboundPlayerChatPacket chat, Component content) {
        try {
            Object chatType = e4all$chatTypeOf(chat);
            if (chatType != null) {
                Component decorated = e4all$invokeDecorate(chatType, content);
                if (decorated != null) return decorated;
                Object registryAccess = e4all$registryAccess(listener);
                if (registryAccess != null) {
                    Object bound = e4all$resolveChatType(chatType, registryAccess);
                    if (bound != null) {
                        decorated = e4all$invokeDecorate(bound, content);
                        if (decorated != null) return decorated;
                    }
                }
            }
        } catch (Throwable t) {
            link.e4all.E4allClient.LOGGER.debug("e4all: could not decorate a chat message with its chat type", t);
        }

        Component withSender = e4all$decorateWithSender(listener, chat, content);
        if (withSender != null) {
            link.e4all.E4allClient.LOGGER.debug("e4all: chat type decoration unavailable, used the sender name instead");
            return withSender;
        }
        link.e4all.E4allClient.LOGGER.debug("e4all: no chat decoration available, sending the raw content");
        return content;
    }

    private static Object e4all$chatTypeOf(Object chat) {
        for (Method m : chat.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            Class<?> ret = m.getReturnType();
            if (ret.isPrimitive() || ret == void.class || ret == String.class
                    || ret == Class.class || ret == Object.class) continue;
            String name = ret.getName();
            if (!name.contains("Bound") && !name.contains("ChatType")) continue;
            try {
                m.setAccessible(true);
                Object value = m.invoke(chat);
                if (value != null) return value;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean e4all$isNarrationDecoration(String methodName) {
        if (methodName.toLowerCase(java.util.Locale.ROOT).contains("narration")) return true;
        for (String name : NARRATION_DECORATION_METHOD_NAMES) {
            if (name.equals(methodName)) return true;
        }
        return false;
    }

    private static Component e4all$invokeDecorate(Object chatType, Component content) {
        // known names first: a shape only lookup cannot tell the chat decoration from the narrator one
        for (String name : CHAT_DECORATION_METHOD_NAMES) {
            try {
                Method m = chatType.getClass().getMethod(name, Component.class);
                Object value = m.invoke(chatType, content);
                if (value instanceof Component component) return component;
            } catch (Throwable ignored) {}
        }

        // unknown mapping: only trust a shape match when a single non narrator candidate is left
        Method candidate = null;
        for (Method m : chatType.getClass().getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            if (m.getParameterCount() != 1) continue;
            if (!Component.class.isAssignableFrom(m.getParameterTypes()[0])) continue;
            if (!Component.class.isAssignableFrom(m.getReturnType())) continue;
            if (e4all$isNarrationDecoration(m.getName())) continue;
            if (candidate != null) {
                link.e4all.E4allClient.LOGGER.debug(
                        "e4all: chat decoration of {} is ambiguous ({} / {}), using the sender name instead",
                        chatType.getClass().getName(), candidate.getName(), m.getName());
                return null;
            }
            candidate = m;
        }
        if (candidate == null) return null;
        try {
            candidate.setAccessible(true);
            Object value = candidate.invoke(chatType, content);
            if (value instanceof Component component) return component;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object e4all$resolveChatType(Object chatType, Object registryAccess) {
        for (Method m : chatType.getClass().getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            if (m.getParameterCount() != 1) continue;
            if (m.getReturnType() == void.class) continue;
            Class<?> param = m.getParameterTypes()[0];
            if (param == Object.class || !param.isInstance(registryAccess)) continue;
            try {
                m.setAccessible(true);
                Object resolved = m.invoke(chatType, registryAccess);
                if (resolved instanceof java.util.Optional<?> optional) return optional.orElse(null);
                if (resolved != null) return resolved;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object e4all$registryAccess(PacketListener listener) {
        try {
            ServerPlayer player = link.e4all.voice.VoiceControl.extractServerPlayer(listener);
            if (player != null && player.getServer() != null) {
                return player.getServer().registryAccess();
            }
            Object server = link.e4all.voice.VoiceControl.extractServerFromListener(listener);
            if (server instanceof net.minecraft.server.MinecraftServer ms) {
                return ms.registryAccess();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // last resort: rebuild the vanilla "<name> message" decoration from the sender uuid
    private static Component e4all$decorateWithSender(PacketListener listener, Object chat, Component content) {
        try {
            java.util.UUID sender = e4all$senderOf(chat);
            if (sender == null) return null;
            ServerPlayer senderPlayer = null;
            ServerPlayer self = link.e4all.voice.VoiceControl.extractServerPlayer(listener);
            if (self != null && sender.equals(self.getUUID())) {
                senderPlayer = self;
            }
            if (senderPlayer == null) {
                Object server = link.e4all.voice.VoiceControl.extractServerFromListener(listener);
                if (server instanceof net.minecraft.server.MinecraftServer minecraftServer) {
                    for (ServerPlayer candidate : minecraftServer.getPlayerList().getPlayers()) {
                        if (sender.equals(candidate.getUUID())) {
                            senderPlayer = candidate;
                            break;
                        }
                    }
                }
            }
            if (senderPlayer == null) return null;
            return link.e4all.Mirror.translatable("chat.type.text", senderPlayer.getDisplayName(), content);
        } catch (Throwable t) {
            return null;
        }
    }

    private static java.util.UUID e4all$senderOf(Object obj) {
        if (obj == null) return null;
        for (String name : new String[]{"sender", "getSender", "signer", "getSigner"}) {
            try {
                Method m = obj.getClass().getMethod(name);
                Object val = m.invoke(obj);
                if (val instanceof java.util.UUID u) return u;
            } catch (Throwable ignored) {}
        }
        for (String name : new String[]{"message", "getMessage", "comp_942", "signedHeader", "getSignedHeader", "comp_926"}) {
            try {
                Method m = obj.getClass().getMethod(name);
                Object child = m.invoke(obj);
                if (child != null) {
                    for (String sName : new String[]{"sender", "getSender", "signer", "getSigner"}) {
                        try {
                            Method sm = child.getClass().getMethod(sName);
                            Object val = sm.invoke(child);
                            if (val instanceof java.util.UUID u) return u;
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }
        for (Method m : obj.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == java.util.UUID.class) {
                try {
                    Object val = m.invoke(obj);
                    if (val instanceof java.util.UUID u) return u;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }
}
