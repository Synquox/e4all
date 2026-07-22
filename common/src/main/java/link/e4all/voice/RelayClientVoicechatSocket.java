package link.e4all.voice;

import de.maxhenkel.voicechat.api.ClientVoicechatSocket;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import link.e4all.E4allClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

public final class RelayClientVoicechatSocket implements ClientVoicechatSocket {
    private static final RawUdpPacket POISON_PILL = new RawUdpPacketImpl(new byte[0], 0, new InetSocketAddress(0));
    private final LinkedBlockingQueue<RawUdpPacket> queue = new LinkedBlockingQueue<>();
    private Socket tcpSocket;
    private DataInputStream in;
    private DataOutputStream out;
    private Thread readerThread;
    private volatile boolean closed = false;

    @Override
    public void open() throws Exception {
        String[] hostPort = getRelayAddress();
        String host = hostPort[0];
        int port = Integer.parseInt(hostPort[1]);

        tcpSocket = new Socket();
        tcpSocket.setTcpNoDelay(true);
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

        readerThread = new Thread(this::readLoop, "e4all-vc-client-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        E4allClient.LOGGER.info("Voice relay connected to {}:{} for player {}", host, port, playerUuid);
    }

    private void readLoop() {
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

    @Override
    public RawUdpPacket read() throws Exception {
        RawUdpPacket packet = queue.take();
        if (packet == POISON_PILL || closed) throw new IOException("Voice relay socket closed");
        return packet;
    }

    @Override
    public void send(byte[] data, SocketAddress address) throws Exception {
        if (closed || out == null) return;
        synchronized (out) {
            out.writeShort(1 + data.length);
            out.writeByte(VoiceFraming.MSG_VOICE_DATA);
            out.write(data);
            out.flush();
        }
    }

    @Override
    public void close() {
        closed = true;
        queue.offer(POISON_PILL);
        try {
            if (out != null) {
                synchronized (out) {
                    out.writeShort(1);
                    out.writeByte(VoiceFraming.MSG_CLOSE);
                    out.flush();
                }
            }
        } catch (IOException ignored) {}
        try { if (tcpSocket != null) tcpSocket.close(); } catch (IOException ignored) {}
        if (readerThread != null) readerThread.interrupt();
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
        ServerData server = Minecraft.getInstance().getCurrentServer();
        if (server == null || server.ip == null) return false;
        String addr = server.ip.toLowerCase();
        return addr.contains(".e4mc.link") || addr.contains(".e4all.");
    }
}
