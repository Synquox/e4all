package link.e4all.voice;

import java.util.UUID;

public final class VoiceFraming {
    // streams start with this magic plus a version byte. routing cannot rely on
    // a single byte: a minecraft varint length prefix can start with 0xE4, and
    // game traffic must reach the minecraft handler unchanged
    public static final byte[] MAGIC = {'E', '4', 'V', '1'};
    public static final byte VERSION = 0x01;
    public static final int HEADER_LEN = MAGIC.length + 1;

    public static final byte MSG_VOICE_DATA = 0x01;
    public static final byte MSG_HANDSHAKE  = 0x02;
    public static final byte MSG_KEEPALIVE  = 0x03;
    public static final byte MSG_CLOSE      = 0x04;
    public static final byte MSG_PING       = 0x05;
    public static final byte MSG_PONG       = 0x06;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private VoiceFraming() {}

    public static boolean matchesMagic(byte[] buf, int len) {
        if (len < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) {
            if (buf[i] != MAGIC[i]) return false;
        }
        return true;
    }

    public static byte[] createHandshake(UUID playerUuid) {
        byte[] msg = new byte[1 + 16];
        msg[0] = MSG_HANDSHAKE;
        writeUuid(msg, 1, playerUuid);
        return msg;
    }

    public static byte[] createPing(long nanoTime) {
        byte[] msg = new byte[1 + 8];
        msg[0] = MSG_PING;
        for (int i = 0; i < 8; i++) msg[1 + i] = (byte) (nanoTime >>> (56 - i * 8));
        return msg;
    }

    public static long readTimestamp(byte[] arr, int offset) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (arr[offset + i] & 0xff);
        return v;
    }

    public static void writeUuid(byte[] arr, int offset, UUID uuid) {
        if (arr.length < offset + 16) {
            throw new IllegalArgumentException("Array too short: need " + (offset + 16) + " bytes, got " + arr.length);
        }
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) arr[offset + i] = (byte) (msb >>> (56 - i * 8));
        for (int i = 0; i < 8; i++) arr[offset + 8 + i] = (byte) (lsb >>> (56 - i * 8));
    }

    public static UUID readUuid(byte[] arr, int offset) {
        if (arr.length < offset + 16) {
            throw new IllegalArgumentException("Array too short: need " + (offset + 16) + " bytes, got " + arr.length);
        }
        long msb = 0, lsb = 0;
        for (int i = 0; i < 8; i++) msb = (msb << 8) | (arr[offset + i] & 0xff);
        for (int i = 0; i < 8; i++) lsb = (lsb << 8) | (arr[offset + 8 + i] & 0xff);
        return new UUID(msb, lsb);
    }

    public static String toHex(byte[] data, int max) {
        int n = Math.min(data.length, max);
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            sb.append(HEX[(data[i] >> 4) & 0xF]).append(HEX[data[i] & 0xF]);
        }
        return sb.toString();
    }
}
