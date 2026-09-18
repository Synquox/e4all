package link.e4all;

import io.netty.channel.Channel;
import link.e4all.dialtone.DialtoneChannel;
import net.minecraft.network.Connection;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Set;

public final class E4allTunnels {
    private static final Set<Connection> TUNNELS =
            Collections.synchronizedSet(Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    private E4allTunnels() {}

    public static void register(Connection connection) {
        try {
            if (connection != null) {
                TUNNELS.add(connection);
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean isTunnel(Connection connection) {
        try {
            if (connection == null) {
                return false;
            }
            if (TUNNELS.contains(connection)) {
                return true;
            }
            Channel channel = channelOf(connection);
            if (channel instanceof DialtoneChannel) {
                return true;
            }
            return channel != null && channel.getClass().getSimpleName().contains("QuicStream");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Channel channelOf(Connection connection) {
        try {
            Field field = Connection.class.getDeclaredField("channel");
            field.setAccessible(true);
            Object value = field.get(connection);
            return value instanceof Channel ch ? ch : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
