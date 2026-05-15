package link.e4all;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridges Simple Voice Chat's UDP traffic through the Minecraft TCP connection
 * for players connected via e4all's tunnel.
 *
 * Architecture:
 * - Host side: For each tunneled player, a DatagramSocket relays UDP between
 *   the local SVC voice server and the player's Minecraft connection (encoded
 *   as custom payload packets).
 * - Client side: A local DatagramSocket accepts voice chat client traffic and
 *   relays it through the Minecraft connection to the host.
 */
public class VoiceChatBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("e4all-voicebridge");

    // -2 = not checked yet, -1 = SVC not available
    private static volatile int cachedVoiceChatPort = -2;

    /**
     * Gets the SVC voice chat server port via reflection.
     * Returns -1 if SVC is not installed or the server isn't running.
     */
    public static int getVoiceChatPort() {
        if (cachedVoiceChatPort != -2) return cachedVoiceChatPort;
        try {
            Class<?> voicechatClass = Class.forName("de.maxhenkel.voicechat.Voicechat");
            Object serverVoiceEvents = voicechatClass.getField("SERVER").get(null);
            if (serverVoiceEvents == null) {
                cachedVoiceChatPort = -1;
                return -1;
            }
            Object server = serverVoiceEvents.getClass().getMethod("getServer").invoke(serverVoiceEvents);
            if (server == null) {
                cachedVoiceChatPort = -1;
                return -1;
            }
            int port = (int) server.getClass().getMethod("getPort").invoke(server);
            if (port <= 0) {
                // SVC server hasn't finished binding yet — don't cache, retry later
                LOGGER.debug("SVC voice server port is {} (not ready yet), will retry", port);
                return -1;
            }
            cachedVoiceChatPort = port;
            LOGGER.info("Detected Simple Voice Chat server on UDP port {}", port);
            return port;
        } catch (ClassNotFoundException e) {
            LOGGER.debug("Simple Voice Chat not installed");
            cachedVoiceChatPort = -1;
            return -1;
        } catch (Exception e) {
            // Don't cache on transient errors — SVC may not be fully initialized yet.
            // Returning -1 without caching allows retry on next check.
            LOGGER.debug("Failed to detect Simple Voice Chat port (will retry)", e);
            return -1;
        }
    }

    /**
     * Reset cached port (called when server stops/restarts so we re-detect).
     */
    public static void resetCachedPort() {
        cachedVoiceChatPort = -2;
    }

    /**
     * Host-side UDP relay for a single tunneled player.
     * Creates a local DatagramSocket that talks to the SVC server on localhost.
     * Datagrams from SVC are forwarded to the player's Minecraft connection as
     * custom payload packets, and vice versa.
     */
    public static class HostUdpRelay {
        private final DatagramSocket socket;
        private final InetSocketAddress svcServerAddr;
        private final Channel minecraftChannel;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread receiveThread;

        public HostUdpRelay(int svcPort, Channel minecraftChannel) throws SocketException {
            this.socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            this.socket.setSoTimeout(1000);
            this.svcServerAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), svcPort);
            this.minecraftChannel = minecraftChannel;
            this.receiveThread = new Thread(this::receiveLoop, "e4all-vc-host-relay");
            this.receiveThread.setDaemon(true);
            this.receiveThread.start();
            LOGGER.debug("Started host UDP relay on port {} -> SVC port {}", socket.getLocalPort(), svcPort);
        }

        /**
         * Called when voice data arrives from the client (via Minecraft connection).
         * Forwards it as a UDP datagram to the local SVC server.
         */
        public void onClientData(byte[] data) {
            try {
                DatagramPacket packet = new DatagramPacket(data, data.length, svcServerAddr);
                socket.send(packet);
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.warn("Failed to forward voice data to SVC server", e);
                }
            }
        }

        /**
         * Receives UDP responses from the SVC server and forwards them
         * to the client through the Minecraft connection.
         */
        private void receiveLoop() {
            byte[] buf = new byte[4096];
            while (running.get()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    if (!running.get() || !minecraftChannel.isActive()) break;

                    byte[] data = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());

                    // Send voice data to client as custom payload
                    VoiceChatPacketHelper.sendVoiceData(minecraftChannel, data, true);
                } catch (SocketTimeoutException ignored) {
                    // Normal timeout, continue loop
                } catch (IOException e) {
                    if (running.get()) {
                        LOGGER.debug("UDP receive error in host relay", e);
                    }
                }
            }
        }

        public void close() {
            running.set(false);
            socket.close();
            LOGGER.debug("Closed host UDP relay");
        }
    }

    /**
     * Client-side UDP proxy for a single connection.
     * Listens on a local UDP port for the SVC client to connect,
     * and relays traffic through the Minecraft connection.
     */
    public static class ClientUdpProxy {
        private final DatagramSocket socket;
        private final Channel minecraftChannel;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread receiveThread;
        private volatile SocketAddress svcClientAddr; // Address of the local SVC client

        public ClientUdpProxy(Channel minecraftChannel) throws SocketException {
            this.socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            this.socket.setSoTimeout(1000);
            this.minecraftChannel = minecraftChannel;
            this.receiveThread = new Thread(this::receiveLoop, "e4all-vc-client-proxy");
            this.receiveThread.setDaemon(true);
            this.receiveThread.start();
            LOGGER.debug("Started client UDP proxy on port {}", socket.getLocalPort());
        }

        public int getLocalPort() {
            return socket.getLocalPort();
        }

        /**
         * Called when voice data arrives from the server (via Minecraft connection).
         * Forwards it as a UDP datagram to the local SVC client.
         */
        public void onServerData(byte[] data) {
            SocketAddress addr = svcClientAddr;
            if (addr == null) return; // SVC client hasn't connected yet
            try {
                DatagramPacket packet = new DatagramPacket(data, data.length, addr);
                socket.send(packet);
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.warn("Failed to forward voice data to SVC client", e);
                }
            }
        }

        /**
         * Receives UDP packets from the local SVC client and forwards them
         * to the server through the Minecraft connection.
         */
        private void receiveLoop() {
            byte[] buf = new byte[4096];
            while (running.get()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    if (!running.get() || !minecraftChannel.isActive()) break;

                    svcClientAddr = packet.getSocketAddress();

                    byte[] data = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());

                    // Send voice data to server as custom payload
                    VoiceChatPacketHelper.sendVoiceData(minecraftChannel, data, false);
                } catch (SocketTimeoutException ignored) {
                } catch (IOException e) {
                    if (running.get()) {
                        LOGGER.debug("UDP receive error in client proxy", e);
                    }
                }
            }
        }

        public void close() {
            running.set(false);
            socket.close();
            LOGGER.debug("Closed client UDP proxy");
        }
    }
}
