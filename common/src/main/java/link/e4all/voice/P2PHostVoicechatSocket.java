package link.e4all.voice;

import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.api.VoicechatSocket;
import link.e4all.E4allClient;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

// Host SVC socket: UDP for local clients + P2P streams for Dialtone guests
public final class P2PHostVoicechatSocket implements VoicechatSocket, VoiceConnectionManager.VoicePacketConsumer {
    private static final RawUdpPacket POISON_PILL =
            new RawUdpPacketImpl(new byte[0], 0, new SyntheticAddress(new UUID(0, 0)));

    private final VoiceConnectionManager manager;
    private final LinkedBlockingQueue<RawUdpPacket> queue = new LinkedBlockingQueue<>();
    private final int instanceId = System.identityHashCode(this);
    private int port;
    private volatile boolean closed = false;

    private DatagramSocket udpSocket;
    private Thread udpReaderThread;

    public P2PHostVoicechatSocket(VoiceConnectionManager manager) {
        this.manager = manager;
    }

    @Override
    public void open(int port, String bindAddress) throws Exception {
        this.manager.setPacketConsumer(this);
        HostVoiceNegotiator.INSTANCE.ensureVoiceEndpointAsync();
        E4allClient.LOGGER.info("[e4all-vc#{}] open(requestedPort={}, bindAddress='{}')", instanceId, port, bindAddress);

        InetAddress bind;
        try {
            bind = bindAddress.isEmpty() ? null : InetAddress.getByName(bindAddress);
        } catch (IOException e) {
            E4allClient.LOGGER.warn("[e4all-vc#{}] Failed to parse bind IP '{}'; using wildcard", instanceId, bindAddress, e);
            bind = null;
        }

        boolean fallback = false;
        try {
            udpSocket = new DatagramSocket(port, bind);
        } catch (IOException e) {
            fallback = true;
            E4allClient.LOGGER.warn(
                    "[e4all-vc#{}] Could not bind UDP socket on port {} (in use by another mod/process). "
                            + "Falling back to an OS-assigned port; P2P stream bridging is unaffected.",
                    instanceId, port, e);
            // Same address semantics as vanilla's VoicechatSocketImpl, just a free port.
            udpSocket = new DatagramSocket(0, bind);
        }

        // report the real bound port, otherwise SVC clients get 127.0.0.1:0 on fallback
        this.port = udpSocket.getLocalPort();
        udpSocket.setSoTimeout(0);

        udpReaderThread = new Thread(this::udpReadLoop, "e4all-vc-udp-reader-" + instanceId);
        udpReaderThread.setDaemon(true);
        udpReaderThread.start();

        E4allClient.LOGGER.info("[e4all-vc#{}] bound UDP {}:{} ({}) + P2P streams", instanceId,
                bind == null ? "0.0.0.0" : bind.getHostAddress(), this.port,
                fallback ? "fallback after BindException" : "primary bind");
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
                    E4allClient.LOGGER.debug("[e4all-vc#{}] UDP read error (may be normal during shutdown)", instanceId, e);
                }
            }
        }
    }

    @Override
    public RawUdpPacket read() throws Exception {
        RawUdpPacket packet = queue.take();
        if (packet == POISON_PILL || closed) throw new IOException("P2P voice socket closed");
        return packet;
    }

    @Override
    public void send(byte[] data, SocketAddress address) throws Exception {
        if (closed) return;

        if (address instanceof SyntheticAddress synthetic) {
            // SVC wants to talk to a P2P guest; route over their voice stream
            manager.sendToPlayer(synthetic.getPlayerUuid(), data);
        } else if (udpSocket != null && !udpSocket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(data, data.length, address);
            udpSocket.send(packet);
        } else {
            E4allClient.LOGGER.warn("[e4all-vc#{}] dropping outgoing packet: address {} is neither SyntheticAddress nor is UDP socket available",
                    instanceId, address);
        }
    }

    @Override
    public int getLocalPort() {
        return port;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (!queue.offer(POISON_PILL)) {
            queue.clear();
            queue.offer(POISON_PILL);
        }

        if (manager.clearPacketConsumer(this)) {
            E4allClient.LOGGER.info("[e4all-vc#{}] was the active consumer; closing all voice streams", instanceId);
            manager.closeAll();
        }

        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
        if (udpReaderThread != null) {
            udpReaderThread.interrupt();
        }

        E4allClient.LOGGER.info("P2P host voice socket closed.");
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void accept(byte[] data, long timestamp, SyntheticAddress address) {
        queue.offer(new RawUdpPacketImpl(data, timestamp, address));
    }
}
