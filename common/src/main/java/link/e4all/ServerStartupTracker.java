package link.e4all;

import net.minecraft.server.MinecraftServer;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class ServerStartupTracker {

    private static final Map<MinecraftServer, ServerState> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private ServerStartupTracker() {}

    private static final class ServerState {
        volatile int ticks;
        volatile boolean stopAnnounced;
    }

    private static ServerState stateOf(MinecraftServer server, boolean create) {
        if (server == null) return null;
        synchronized (STATES) {
            ServerState state = STATES.get(server);
            if (state == null && create) {
                state = new ServerState();
                STATES.put(server, state);
            }
            return state;
        }
    }

    // called from MinecraftServerMixin on every tickServer head
    public static void observeTick(MinecraftServer server) {
        ServerState state = stateOf(server, true);
        if (state == null) return;
        int ticks = state.ticks + 1;
        state.ticks = ticks;
        if (ticks == 1) {
            E4allClient.LOGGER.info("e4all: {} is ticking, holding logins until the first tick is done", describe(server));
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        ServerState state = stateOf(server, false);
        if (state != null && !state.stopAnnounced) {
            state.stopAnnounced = true;
            E4allClient.LOGGER.info("e4all: {} is stopping", describe(server));
        }
    }

    // server exists but has not ticked once yet
    public static boolean isStarting(MinecraftServer server) {
        if (server == null) return false;
        ServerState state = stateOf(server, false);
        return state == null || state.ticks == 0;
    }

    public static boolean isMidFirstTick(MinecraftServer server) {
        if (server == null) return false;
        ServerState state = stateOf(server, false);
        return state != null && state.ticks == 1;
    }

    public static boolean isStopping(MinecraftServer server) {
        if (server == null) return true;
        ServerState state = stateOf(server, false);
        if (state != null && state.stopAnnounced) return true;
        return server.isStopped();
    }

    public static boolean hasAnyPlayer(MinecraftServer server) {
        if (server == null) return true;
        try {
            var playerList = server.getPlayerList();
            if (playerList == null) return true;
            var players = playerList.getPlayers();
            return players != null && !players.isEmpty();
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("e4all: could not check the player list", t);
            return true;
        }
    }

    // wait for world to finish first tick before admitting guest
    public static boolean awaitReady(MinecraftServer server, long timeoutMs,
                                     java.util.function.BooleanSupplier ownsActiveSession) {
        if (server == null) return false;
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (isStopping(server)) return false;
            if (!isStarting(server) && !isMidFirstTick(server)) {
                try {
                    if (ownsActiveSession == null || ownsActiveSession.getAsBoolean()) return true;
                } catch (Throwable ignored) {
                    return true;
                }
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public static String describe(MinecraftServer server) {
        if (server == null) return "server=null";
        ServerState state = stateOf(server, false);
        if (state == null) {
            return "server#" + System.identityHashCode(server) + " (never ticked)";
        }
        return "server#" + System.identityHashCode(server) + " (ticks=" + state.ticks + ", stopping=" + state.stopAnnounced + ")";
    }
}