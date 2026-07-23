package link.e4all.voice;

import de.maxhenkel.voicechat.api.ClientVoicechatSocket;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import link.e4all.E4allClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

public final class RelayClientVoicechatSocket implements ClientVoicechatSocket {
    private static final RawUdpPacket POISON_PILL = new RawUdpPacketImpl(new byte[0], 0, new InetSocketAddress(0));
    private static final long KEEPALIVE_INTERVAL_MS = 15_000;
    private final LinkedBlockingQueue<RawUdpPacket> queue = new LinkedBlockingQueue<>();

    private Socket tcpSocket;
    private DataInputStream in;
    private DataOutputStream out;
    private Thread readerThread;
    private Thread keepaliveThread;

    private DatagramSocket udpSocket;
    private Thread udpReaderThread;

    private volatile boolean closed = false;
    private volatile boolean useRelay = false;

    private static volatile boolean connectedViaDialtone = false;

    public static void setConnectedViaDialtone(boolean value) {
        connectedViaDialtone = value;
        if (value) {
            E4allClient.LOGGER.info("e4all voice: Dialtone connection detected — will use relay voice socket.");
        }
    }

    @Override
    public void open() throws Exception {
        if (isConnectedViaRelay()) {
            useRelay = true;
            openRelay();
        } else {
            useRelay = false;
            openDefaultUdp();
        }
    }

    private void openRelay() throws Exception {
        String[] hostPort = getRelayAddress();
        String host = hostPort[0];
        int port = Integer.parseInt(hostPort[1]);

        E4allClient.LOGGER.info("e4all voice: Opening relay voice connection to {}:{}", host, port);

        tcpSocket = new Socket();
        tcpSocket.setTcpNoDelay(true);
        tcpSocket.setKeepAlive(true);
        tcpSocket.connect(new InetSocketAddress(host, port), 5000);

        in = new DataInputStream(tcpSocket.getInputStream());
        out = new DataOutputStream(tcpSocket.getOutputStream());

        out.writeByte(VoiceFraming.VOICE_MAGIC);

        UUID playerUuid = Minecraft.getInstance().getUser().getProfileId();
        if (playerUuid == null) {
            playerUuid = UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:" + Minecraft.getInstance().getUser().getName()).getBytes());
        }
        byte[] handshake = VoiceFraming.createHandshake(playerUuid);
        out.writeShort(handshake.length);
        out.write(handshake);
        out.flush();

        readerThread = new Thread(this::relayReadLoop, "e4all-vc-client-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        keepaliveThread = new Thread(this::keepaliveLoop, "e4all-vc-client-keepalive");
        keepaliveThread.setDaemon(true);
        keepaliveThread.start();

        E4allClient.LOGGER.info("e4all voice: Relay voice connected to {}:{} for player {}", host, port, playerUuid);
    }

    private void openDefaultUdp() throws Exception {
        E4allClient.LOGGER.info("e4all voice: Not connected via relay — using default UDP voice socket.");
        udpSocket = new DatagramSocket();
        udpSocket.setSoTimeout(0);

        udpReaderThread = new Thread(this::udpReadLoop, "e4all-vc-client-udp-reader");
        udpReaderThread.setDaemon(true);
        udpReaderThread.start();
    }

    private void relayReadLoop() {
        try {
            while (!closed) {
                int length = in.readUnsignedShort();
                if (length < 1) continue;
                byte type = in.readByte();
                byte[] payload = new byte[length - 1];
                if (payload.length > 0) in.readFully(payload);

                if (type == VoiceFraming.MSG_VOICE_DATA) {
                    queue.offer(new RawUdpPacketImpl(payload, System.currentTimeMillis(),
                            new InetSocketAddress("127.0.0.1", 0)));
                } else if (type == VoiceFraming.MSG_CLOSE) {
                    break;
                }
            }
        } catch (IOException e) {
            if (!closed) {
                E4allClient.LOGGER.debug("Voice relay connection lost", e);
            }
        } finally {
            closed = true;
            queue.offer(POISON_PILL);
        }
    }

    private void keepaliveLoop() {
        while (!closed) {
            try {
                Thread.sleep(KEEPALIVE_INTERVAL_MS);
                if (!closed && out != null) {
                    synchronized (out) {
                        out.writeShort(1);
                        out.writeByte(VoiceFraming.MSG_KEEPALIVE);
                        out.flush();
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                if (!closed) {
                    E4allClient.LOGGER.debug("Keepalive write failed", e);
                }
                break;
            }
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
        if (packet == POISON_PILL || closed) throw new IOException("Voice relay socket closed");
        return packet;
    }

    @Override
    public void send(byte[] data, SocketAddress address) throws Exception {
        if (closed) return;
        if (useRelay) {
            sendRelay(data);
        } else {
            sendUdp(data, address);
        }
    }

    private void sendRelay(byte[] data) throws IOException {
        if (out == null || closed) return;
        synchronized (out) {
            out.writeShort(1 + data.length);
            out.writeByte(VoiceFraming.MSG_VOICE_DATA);
            out.write(data);
            out.flush();
        }
    }

    private void sendUdp(byte[] data, SocketAddress address) throws IOException {
        if (udpSocket == null || udpSocket.isClosed()) return;
        DatagramPacket packet = new DatagramPacket(data, data.length, address);
        udpSocket.send(packet);
    }

    @Override
    public void close() {
        if (closed) return;

        try {
            if (out != null) {
                synchronized (out) {
                    out.writeShort(1);
                    out.writeByte(VoiceFraming.MSG_CLOSE);
                    out.flush();
                }
            }
        } catch (IOException ignored) {}

        closed = true;
        connectedViaDialtone = false;
        queue.offer(POISON_PILL);

        try { if (tcpSocket != null) tcpSocket.close(); } catch (IOException ignored) {}
        if (readerThread != null) readerThread.interrupt();
        if (keepaliveThread != null) keepaliveThread.interrupt();

        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
        if (udpReaderThread != null) udpReaderThread.interrupt();
    }

    @Override
    public boolean isClosed() { return closed; }

    private String[] getRelayAddress() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isEmpty()) {
            String addr = server.ip;
            if (addr.contains(":")) {
                String[] parts = addr.split(":", 2);
                return new String[]{parts[0], parts[1]};
            }
            return new String[]{addr, "25565"};
        }
        throw new IllegalStateException("Cannot determine server address for voice relay");
    }

    public static boolean isConnectedViaRelay() {
        if (connectedViaDialtone) {
            E4allClient.LOGGER.debug("e4all voice: Relay detected via Dialtone flag.");
            return true;
        }

        ServerData server = Minecraft.getInstance().getCurrentServer();
        if (server == null || server.ip == null) {
            E4allClient.LOGGER.debug("e4all voice: getCurrentServer() returned null — not on a relay.");
            return false;
        }

        String addr = server.ip.toLowerCase();
        boolean isRelay = addr.contains(".e4mc.link")
                       || addr.contains(".e4mc.")
                       || addr.contains(".e4all.");

        if (isRelay) {
            E4allClient.LOGGER.debug("e4all voice: Relay detected via domain match: {}", server.ip);
        } else {
            E4allClient.LOGGER.debug("e4all voice: Domain '{}' does not match relay patterns.", server.ip);
        }
        return isRelay;
    }
}
