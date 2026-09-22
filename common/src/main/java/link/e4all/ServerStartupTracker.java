package link.e4all;

import net.minecraft.server.MinecraftServer;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ServerStartupTracker {

    private static final Map<MinecraftServer, ServerState> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicLong IDS = new AtomicLong();

    private ServerStartupTracker() {}

    private static final class ServerState {
        final long id = IDS.incrementAndGet();
        final long startNanos = System.nanoTime();
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

    public static boolean withinStartupWindow(MinecraftServer server, long millis) {
        ServerState state = stateOf(server, false);
        if (state == null) return false;
        return (System.nanoTime() - state.startNanos) < millis * 1_000_000L;
    }

    public static long startNanos(MinecraftServer server) {
        ServerState state = stateOf(server, false);
        return state == null ? 0L : state.startNanos;
    }

    // wait for world to finish first tick before admitting guest
    public static boolean awaitReady(MinecraftServer server, long timeoutMs,
                                     java.util.function.BooleanSupplier ownsActiveSession) {
        if (server == null) return false;
        return waitForReady(
                () -> !isStarting(server) && !isMidFirstTick(server),
                () -> isStopping(server),
                () -> ownsActiveSession == null || checkOwnership(ownsActiveSession),
                timeoutMs,
                DEFAULT_POLL_INTERVAL_MS);
    }

    public static final long DEFAULT_POLL_INTERVAL_MS = 50L;

    // loop helper for tests
    static boolean waitForReady(java.util.function.BooleanSupplier ticking,
                                java.util.function.BooleanSupplier stopping,
                                java.util.function.BooleanSupplier owned,
                                long timeoutMs, long pollIntervalMs) {
        long deadline = System.nanoTime() + Math.max(0L, timeoutMs) * 1_000_000L;
        while (true) {
            if (safeGet(stopping)) return false;
            if (safeGet(ticking) && safeGet(owned)) return true;
            if (System.nanoTime() >= deadline) return false;
            try {
                Thread.sleep(Math.max(1L, pollIntervalMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static boolean safeGet(java.util.function.BooleanSupplier check) {
        if (check == null) return false;
        try {
            return check.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean checkOwnership(java.util.function.BooleanSupplier check) {
        if (check == null) return true;
        try {
            return check.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    public static String describe(MinecraftServer server) {
        if (server == null) return "server=null";
        ServerState state = stateOf(server, false);
        if (state == null) {
            return "server#" + System.identityHashCode(server) + " (never ticked)";
        }
        return "server#" + state.id + " (ticks=" + state.ticks + ", stopping=" + state.stopAnnounced + ")";
    }
}