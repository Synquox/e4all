package link.e4all;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DialtoneTicketCache {

    private static final long TTL_MS = 60_000L;
    private static final int MAX_ENTRIES = 64;

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private DialtoneTicketCache() {}

    public static final class Lookup {
        public final String ticket;
        public final boolean noTicket;

        Lookup(String ticket) {
            this.ticket = ticket;
            this.noTicket = ticket == null;
        }

        public boolean hasTicket() {
            return ticket != null;
        }
    }

    public static Lookup fresh(String host) {
        if (host == null) return null;
        Entry entry = ENTRIES.get(host);
        if (entry == null) return null;
        if (System.currentTimeMillis() - entry.atMs > TTL_MS) {
            ENTRIES.remove(host, entry);
            return null;
        }
        return new Lookup(entry.ticket);
    }

    public static void putTicket(String host, String ticket) {
        put(host, ticket);
    }

    public static void putNoTicket(String host) {
        put(host, null);
    }

    private static void put(String host, String ticket) {
        if (host == null) return;
        if (ENTRIES.size() >= MAX_ENTRIES) {
            long now = System.currentTimeMillis();
            ENTRIES.entrySet().removeIf(entry -> now - entry.getValue().atMs > TTL_MS);
        }
        ENTRIES.put(host, new Entry(ticket, System.currentTimeMillis()));
    }

    private static final class Entry {
        final String ticket;
        final long atMs;

        Entry(String ticket, long atMs) {
            this.ticket = ticket;
            this.atMs = atMs;
        }
    }
}