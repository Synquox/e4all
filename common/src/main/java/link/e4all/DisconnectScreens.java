package link.e4all;

import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class DisconnectScreens {

    private DisconnectScreens() {}

    public static boolean isTunnelConnection(net.minecraft.network.Connection connection) {
        return E4allTunnels.isTunnel(connection);
    }

    public static Component reasonOf(DisconnectedScreen screen) {
        Component reason = componentField(screen, "reason");
        if (reason != null) return reason;
        try {
            Field details = DisconnectedScreen.class.getDeclaredField("details");
            details.setAccessible(true);
            Object record = details.get(screen);
            if (record != null) {
                Method accessor = record.getClass().getMethod("reason");
                Object value = accessor.invoke(record);
                if (value instanceof Component c) return c;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static Screen parentOf(DisconnectedScreen screen) {
        try {
            Field parent = DisconnectedScreen.class.getDeclaredField("parent");
            parent.setAccessible(true);
            Object value = parent.get(screen);
            if (value instanceof Screen s) return s;
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static Component genericDisconnectMessage() {
        Component generic = firstStaticComponent("net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl");
        if (generic != null) {
            return generic;
        }
        return firstStaticComponent("net.minecraft.client.multiplayer.ClientPacketListener");
    }

    public static boolean isUnexplainedTunnelDrop(Component reason) {
        if (reason == null) return true;
        String text = reason.getString();
        if (text == null || text.isEmpty()) return true;
        if (text.startsWith("e4all: Connection lost")) return true;
        String key = translationKeyOf(reason);
        if (key != null) {
            return key.equals("multiplayer.disconnect.generic")
                    || key.equals("disconnect.endOfStream")
                    || key.equals("disconnect.disconnected")
                    || key.equals("text.e4all_minecraft.connectionLost");
        }
        Component generic = genericDisconnectMessage();
        return generic != null && text.equals(generic.getString());
    }

    public static DisconnectedScreen withHint(DisconnectedScreen original, Component hint) {
        try {
            Component reason = reasonOf(original);
            if (reason == null) return null;
            Component combined = Mirror.append(reason, Mirror.append(Mirror.literal("\n"), hint));
            return new DisconnectedScreen(parentOf(original), original.getTitle(), combined);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Component componentField(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(owner);
            if (value instanceof Component c) return c;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Component firstStaticComponent(String ownerClassName) {
        try {
            Class<?> owner = Class.forName(ownerClassName);
            Component fallback = null;
            for (Field field : owner.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                if (!Component.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof Component c) {
                    if ("GENERIC_DISCONNECT_MESSAGE".equals(field.getName())) return c;
                    if (fallback == null) fallback = c;
                }
            }
            return fallback;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String translationKeyOf(Component component) {
        if (component == null) return null;
        Object contents = null;
        for (String mName : new String[]{"getContents", "method_43471", "m_237141_"}) {
            try {
                contents = component.getClass().getMethod(mName).invoke(component);
                if (contents != null) break;
            } catch (Throwable ignored) {
            }
        }
        if (contents != null) {
            for (String mName : new String[]{"getKey", "method_43478", "m_237309_"}) {
                try {
                    Object key = contents.getClass().getMethod(mName).invoke(contents);
                    if (key instanceof String s) return s;
                } catch (Throwable ignored) {
                }
            }
        }
        for (String mName : new String[]{"getKey", "method_10892", "m_131310_"}) {
            try {
                Object key = component.getClass().getMethod(mName).invoke(component);
                if (key instanceof String s) return s;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
