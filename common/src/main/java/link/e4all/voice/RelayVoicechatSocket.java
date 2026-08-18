package link.e4all.voice;

import de.maxhenkel.voicechat.api.VoicechatSocket;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import link.e4all.E4allClient;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

public final class RelayVoicechatSocket implements VoicechatSocket, VoiceConnectionManager.VoicePacketConsumer {
    private static final RawUdpPacket POISON_PILL = new RawUdpPacketImpl(new byte[0], 0, new SyntheticAddress(new UUID(0, 0)));
    private final VoiceConnectionManager manager;
    private final LinkedBlockingQueue<RawUdpPacket> queue = new LinkedBlockingQueue<>();
    private int port;
    private volatile boolean closed = false;

    private DatagramSocket udpSocket;
    private Thread udpReaderThread;

    public RelayVoicechatSocket(VoiceConnectionManager manager) {
        this.manager = manager;
    }

    @Override
    public void open(int port, String bindAddress) throws Exception {
        this.manager.setPacketConsumer(this);

        try {
            InetAddress bind = bindAddress.isEmpty() ? null : InetAddress.getByName(bindAddress);
            udpSocket = new DatagramSocket(port, bind);
            this.port = udpSocket.getLocalPort();
            udpSocket.setSoTimeout(0);

            udpReaderThread = new Thread(this::udpReadLoop, "e4all-vc-udp-reader");
            udpReaderThread.setDaemon(true);
            udpReaderThread.start();

            E4allClient.LOGGER.info("RelayVoicechatSocket opened on UDP port {} + relay", this.port);
        } catch (IOException e) {
            E4allClient.LOGGER.warn("Could not bind UDP socket on port {}. Relay-only mode.", port, e);
            E4allClient.LOGGER.info("RelayVoicechatSocket opened in relay-only mode (no local UDP)");
        }
    }

    private void udpReadLoop() {
        byte[] buf = new byte[4096];
        while (!closed && udpSocket != null && !udpSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                udpSocket.receive(packet);
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                queue.offer(new RawUdpPacketImpl(data, System.currentTimeMillis(), packet.getSocketAddress()));
            } catch (IOException e) {
                if (!closed) {
                    E4allClient.LOGGER.debug("UDP read error (may be normal during shutdown)", e);
                }
            }
        }
    }

    @Override
    public RawUdpPacket read() throws Exception {
        RawUdpPacket packet = queue.take();
        if (packet == POISON_PILL || closed) throw new java.io.IOException("Relay socket closed");
        return packet;
    }

    @Override
    public void send(byte[] data, SocketAddress address) throws Exception {
        if (closed) return;

        if (address instanceof SyntheticAddress) {
            UUID uuid = ((SyntheticAddress) address).getPlayerUuid();
            manager.sendToPlayer(uuid, data);
        } else if (udpSocket != null && !udpSocket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(data, data.length, address);
            udpSocket.send(packet);
        }
    }

    @Override
    public int getLocalPort() { return port; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        queue.offer(POISON_PILL);

        if (manager.clearPacketConsumer(this)) {
            manager.closeAll();
        }

        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
        if (udpReaderThread != null) {
            udpReaderThread.interrupt();
        }

        E4allClient.LOGGER.info("RelayVoicechatSocket closed.");
    }

    @Override
    public boolean isClosed() { return closed; }

    @Override
    public void accept(byte[] data, long timestamp, SyntheticAddress address) {
        enqueuePacket(data, timestamp, address);
    }

    public void enqueuePacket(byte[] data, long timestamp, SyntheticAddress address) {
        queue.offer(new RawUdpPacketImpl(data, timestamp, address));
    }
}
