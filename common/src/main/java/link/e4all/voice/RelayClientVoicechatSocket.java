package link.e4all.voice;

import de.maxhenkel.voicechat.api.ClientVoicechatSocket;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import link.e4all.E4allClient;
import link.e4all.dialtone.DialtoneAddress;
import link.e4all.dialtone.DialtoneAmbientSession;
import link.e4all.dialtone.DialtoneChannel;
import net.minecraft.client.Minecraft;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

public final class RelayClientVoicechatSocket implements ClientVoicechatSocket {
    private static final RawUdpPacket POISON_PILL = new RawUdpPacketImpl(new byte[0], 0, new InetSocketAddress(0));
    private static final long KEEPALIVE_INTERVAL_MS = 15_000;

    private final LinkedBlockingQueue<RawUdpPacket> queue = new LinkedBlockingQueue<>();

    private DatagramSocket udpSocket;
    private Thread udpReaderThread;

    private DialtoneChannel dialtoneChannel;
    private Thread keepaliveThread;

    private volatile boolean closed = false;
    private volatile boolean useDialtone = false;

    private static final AtomicReference<String> pendingDialtoneTicket = new AtomicReference<>(null);

    public static void setPendingDialtoneTicket(String ticket) {
        pendingDialtoneTicket.set(ticket);
    }

    public static boolean shouldUseCustomSocket() {
        if (pendingDialtoneTicket.get() != null) return true;
        return false;
    }

    @Override
    public void open() throws Exception {
        String ticket = pendingDialtoneTicket.getAndSet(null);
        if (ticket != null) {
            useDialtone = true;
            openDialtone(ticket);
        } else {
            useDialtone = false;
            openDefaultUdp();
        }
    }

    private void openDialtone(String ticket) throws Exception {
        E4allClient.LOGGER.info("e4all voice: Opening Dialtone voice connection");

        if (!DialtoneAmbientSession.INSTANCE.isStarted()) {
            DialtoneAmbientSession.INSTANCE.start();
        }

        Bootstrap bs = new Bootstrap()
                .group(DialtoneAmbientSession.INSTANCE.group)
                .channel(DialtoneChannel.class);

        dialtoneChannel = (DialtoneChannel) bs.connect(new DialtoneAddress(ticket)).syncUninterruptibly().channel();

        dialtoneChannel.pipeline().addLast("voiceFrameDecoder",
                new LengthFieldBasedFrameDecoder(65535, 0, 2, 0, 2));
        dialtoneChannel.pipeline().addLast("voiceClientHandler",
                new ClientVoiceReadHandler(queue));

        ByteBuf magic = Unpooled.buffer(1);
        magic.writeByte(VoiceFraming.VOICE_MAGIC);
        dialtoneChannel.writeAndFlush(magic).syncUninterruptibly();

        dialtoneChannel.pipeline().addLast("voiceFrameEncoder",
                new LengthFieldPrepender(2));

        UUID playerUuid = Minecraft.getInstance().getUser().getProfileId();
        if (playerUuid == null) {
            playerUuid = UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:" + Minecraft.getInstance().getUser().getName()).getBytes());
        }

        ByteBuf handshake = Unpooled.buffer(1 + 16);
        handshake.writeByte(VoiceFraming.MSG_HANDSHAKE);
        handshake.writeLong(playerUuid.getMostSignificantBits());
        handshake.writeLong(playerUuid.getLeastSignificantBits());
        dialtoneChannel.writeAndFlush(handshake).syncUninterruptibly();

        keepaliveThread = new Thread(this::keepaliveLoop, "e4all-vc-client-keepalive");
        keepaliveThread.setDaemon(true);
        keepaliveThread.start();

        E4allClient.LOGGER.info("e4all voice: Dialtone voice connected for player {}", playerUuid);
    }

    private void openDefaultUdp() throws Exception {
        E4allClient.LOGGER.info("e4all voice: No Dialtone ticket — using default UDP voice socket.");
        udpSocket = new DatagramSocket();
        udpSocket.setSoTimeout(0);

        udpReaderThread = new Thread(this::udpReadLoop, "e4all-vc-client-udp-reader");
        udpReaderThread.setDaemon(true);
        udpReaderThread.start();
    }

    private void keepaliveLoop() {
        while (!closed) {
            try {
                Thread.sleep(KEEPALIVE_INTERVAL_MS);
                if (!closed && dialtoneChannel != null && dialtoneChannel.isActive()) {
                    ByteBuf buf = Unpooled.buffer(1);
                    buf.writeByte(VoiceFraming.MSG_KEEPALIVE);
                    dialtoneChannel.writeAndFlush(buf);
                }
            } catch (InterruptedException e) {
                break;
            } catch (Throwable e) {
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
            } catch (java.io.IOException e) {
                if (!closed) {
                    E4allClient.LOGGER.debug("UDP read error (may be normal during shutdown)", e);
                }
            }
        }
    }

    @Override
    public RawUdpPacket read() throws Exception {
        RawUdpPacket packet = queue.take();
        if (packet == POISON_PILL || closed) throw new java.io.IOException("Voice socket closed");
        return packet;
    }

    @Override
    public void send(byte[] data, SocketAddress address) throws Exception {
        if (closed) return;
        if (useDialtone) {
            sendDialtone(data);
        } else {
            sendUdp(data, address);
        }
    }

    private void sendDialtone(byte[] data) {
        if (dialtoneChannel == null || !dialtoneChannel.isActive()) return;
        ByteBuf buf = dialtoneChannel.alloc().buffer(1 + data.length);
        buf.writeByte(VoiceFraming.MSG_VOICE_DATA);
        buf.writeBytes(data);
        dialtoneChannel.writeAndFlush(buf);
    }

    private void sendUdp(byte[] data, SocketAddress address) throws java.io.IOException {
        if (udpSocket == null || udpSocket.isClosed()) return;
        DatagramPacket packet = new DatagramPacket(data, data.length, address);
        udpSocket.send(packet);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        queue.offer(POISON_PILL);

        if (dialtoneChannel != null && dialtoneChannel.isActive()) {
            try {
                ByteBuf buf = dialtoneChannel.alloc().buffer(1);
                buf.writeByte(VoiceFraming.MSG_CLOSE);
                dialtoneChannel.writeAndFlush(buf);
            } catch (Throwable ignored) {}
        }
        if (dialtoneChannel != null) {
            try { dialtoneChannel.close().syncUninterruptibly(); } catch (Throwable ignored) {}
        }
        if (keepaliveThread != null) keepaliveThread.interrupt();

        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
        if (udpReaderThread != null) udpReaderThread.interrupt();

        pendingDialtoneTicket.set(null);
    }

    @Override
    public boolean isClosed() { return closed; }
}
