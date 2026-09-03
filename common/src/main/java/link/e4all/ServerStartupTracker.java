package link.e4all;

import net.minecraft.server.MinecraftServer;

// gates logins while integrated server is (re)starting to avoid block/entity race
public final class ServerStartupTracker {
    private static volatile MinecraftServer current;
    private static volatile boolean firstTickDone;
    private static volatile long startNanos;

    private ServerStartupTracker() {}

    // called from MinecraftServerMixin on every tickServer head
    public static void observeTick(MinecraftServer server) {
        if (current != server) {
            current = server;
            firstTickDone = false;
            startNanos = System.nanoTime();
            E4allClient.LOGGER.info("e4all: integrated server (re)started, holding logins until the first tick completes");
        }
        if (!firstTickDone) {
            firstTickDone = true;
        }
    }

    // server exists but has not ticked once yet
    public static boolean isStarting(MinecraftServer server) {
        return server != null && server != current && !server.isStopped();
    }

    // first tick is currently running, logins completing here land mid-init
    public static boolean isMidFirstTick(MinecraftServer server) {
        return server != null && server == current && !firstTickDone;
    }

    public static boolean withinStartupWindow(MinecraftServer server, long millis) {
        if (server == null || server != current) return false;
        return (System.nanoTime() - startNanos) < millis * 1_000_000L;
    }

    public static long startNanos() {
        return startNanos;
    }
}