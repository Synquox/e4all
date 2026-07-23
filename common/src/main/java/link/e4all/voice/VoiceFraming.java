package link.e4all.voice;

import java.util.UUID;

public final class VoiceFraming {
    public static final byte VOICE_MAGIC = (byte) 0xE4;

    public static final byte MSG_VOICE_DATA = 0x01;
    public static final byte MSG_HANDSHAKE  = 0x02;
    public static final byte MSG_KEEPALIVE  = 0x03;
    public static final byte MSG_CLOSE      = 0x04;

    private VoiceFraming() {}

    public static byte[] createHandshake(UUID playerUuid) {
        byte[] msg = new byte[1 + 16];
        msg[0] = MSG_HANDSHAKE;
        writeUuid(msg, 1, playerUuid);
        return msg;
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
}
