package link.e4all.voice;

import java.util.concurrent.atomic.AtomicReference;

public final class VoiceBridge {
    private static final AtomicReference<String> pendingDialtoneTicket = new AtomicReference<>(null);

    private VoiceBridge() {}

    public static void setPendingDialtoneTicket(String ticket) {
        pendingDialtoneTicket.set(ticket);
    }

    public static String getAndClearPendingDialtoneTicket() {
        return pendingDialtoneTicket.getAndSet(null);
    }

    public static boolean hasPendingDialtoneTicket() {
        return pendingDialtoneTicket.get() != null;
    }
}
