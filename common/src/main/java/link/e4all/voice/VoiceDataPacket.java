package link.e4all.voice;

public record VoiceDataPacket(byte[] data, long timestampAtReceipt) {
    public static final VoiceDataPacket POISON_PILL = new VoiceDataPacket(new byte[0], 0L);
}
