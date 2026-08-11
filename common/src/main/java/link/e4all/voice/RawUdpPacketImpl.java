package link.e4all.voice;

import de.maxhenkel.voicechat.api.RawUdpPacket;
import java.net.SocketAddress;

public final class RawUdpPacketImpl implements RawUdpPacket {
    private final byte[] data;
    private final long timestamp;
    private final SocketAddress address;

    public RawUdpPacketImpl(byte[] data, long timestamp, SocketAddress address) {
        this.data = data;
        this.timestamp = timestamp;
        this.address = address;
    }

    @Override public byte[] getData() { return data.clone(); }
    @Override public long getTimestamp() { return timestamp; }
    @Override public SocketAddress getSocketAddress() { return address; }
}
