package link.e4all;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import link.e4all.voice.VoiceControl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class XaeroWorldIdentity {
    private static final String ID_FILE_NAME = "e4all-xaero-world-id.txt";
    private static volatile Object xaeroMinimap;
    private static volatile Object xaeroWorldmap;
    private static final ConcurrentHashMap<Path, Integer> WORLD_IDS = new ConcurrentHashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger("e4all");

    private XaeroWorldIdentity() {
    }

    private static Object xaeroMinimap() {
        Object cached = xaeroMinimap;
        if (cached != null) return cached;
        cached = ResourceLocReflector.create("xaerominimap", "main");
        xaeroMinimap = cached;
        return cached;
    }

    private static Object xaeroWorldmap() {
        Object cached = xaeroWorldmap;
        if (cached != null) return cached;
        cached = ResourceLocReflector.create("xaeroworldmap", "main");
        xaeroWorldmap = cached;
        return cached;
    }

    public static void initializeForRelay(MinecraftServer server) {
        getOrCreateWorldId(server);
    }

    private static final Map<Object, Integer> MOVE_COUNTS = Collections.synchronizedMap(new WeakHashMap<>());

    // ~1s of movement packets, guest world join and the xaero session are up by then
    private static final int MOVES_BEFORE_SEND = 20;

    public static void onPlayerMoved(Object listener, ServerPlayer player) {
        if (listener == null || player == null) return;
        int moves = MOVE_COUNTS.merge(listener, 1, Integer::sum);
        if (moves != MOVES_BEFORE_SEND) return;
        MinecraftServer server = VoiceControl.extractServer(player);
        if (server == null) return;
        sendToRelayPlayer(server, player);
    }

    public static void sendToRelayPlayer(MinecraftServer server, ServerPlayer player) {
        QuiclimeSession session = E4allClient.session;
        if (session == null || session.state != QuiclimeSession.State.STARTED) {
            return;
        }

        int worldId = getOrCreateWorldId(server);
        byte[] data = makeXaeroData(worldId);
        sendXaero(player, xaeroMinimap(), data);
        sendXaero(player, xaeroWorldmap(), data);
    }

    private static void sendXaero(ServerPlayer player, Object channel, byte[] data) {
        try {
            Packet<?> nativePkt = makeXaeroNativePacket(channel, data);
            if (nativePkt != null && PacketHelper.testEncode(nativePkt)) {
                player.connection.send(nativePkt);
                LOGGER.debug("e4all: sent native Xaero payload on channel {}", channel);
                return;
            }
        } catch (Throwable t) {
            LOGGER.debug("e4all: could not construct native Xaero packet for {}", channel, t);
        }

        PacketHelper.sendClientbound(player, channel, data);
    }

    private static Packet<?> makeXaeroNativePacket(Object channel, byte[] data) {
        try {
            Class<?> xaeroPayloadCls = Class.forName("xaero.lib.common.packet.payload.PacketPayload");
            Object type = PacketHelper.createCustomPayloadType(channel);
            for (java.lang.reflect.Constructor<?> ctor : xaeroPayloadCls.getDeclaredConstructors()) {
                try {
                    ctor.setAccessible(true);
                    Class<?>[] params = ctor.getParameterTypes();
                    if (params.length == 2 && type != null && params[0].isAssignableFrom(type.getClass())) {
                        if (params[1] == byte[].class) {
                            Object payload = ctor.newInstance(type, data);
                            return PacketHelper.createPacketFromPayload(true, payload);
                        } else if (net.minecraft.network.FriendlyByteBuf.class.isAssignableFrom(params[1])) {
                            Object payload = ctor.newInstance(type, new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data)));
                            return PacketHelper.createPacketFromPayload(true, payload);
                        } else if (io.netty.buffer.ByteBuf.class.isAssignableFrom(params[1])) {
                            Object payload = ctor.newInstance(type, io.netty.buffer.Unpooled.wrappedBuffer(data));
                            return PacketHelper.createPacketFromPayload(true, payload);
                        }
                    } else if (params.length == 1) {
                        if (params[0] == byte[].class) {
                            Object payload = ctor.newInstance((Object) data);
                            return PacketHelper.createPacketFromPayload(true, payload);
                        } else if (net.minecraft.network.FriendlyByteBuf.class.isAssignableFrom(params[0])) {
                            Object payload = ctor.newInstance(new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data)));
                            return PacketHelper.createPacketFromPayload(true, payload);
                        } else if (io.netty.buffer.ByteBuf.class.isAssignableFrom(params[0])) {
                            Object payload = ctor.newInstance(io.netty.buffer.Unpooled.wrappedBuffer(data));
                            return PacketHelper.createPacketFromPayload(true, payload);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static byte[] makeXaeroData(int worldId) {
        byte[] data = new byte[5];
        data[0] = 0; // writeByte(0)
        data[1] = (byte) (worldId >> 24);
        data[2] = (byte) (worldId >> 16);
        data[3] = (byte) (worldId >> 8);
        data[4] = (byte) worldId;
        return data;
    }

    private static int getOrCreateWorldId(MinecraftServer server) {
        Path path = server.getWorldPath(LevelResource.ROOT).resolve(ID_FILE_NAME).toAbsolutePath().normalize();
        return WORLD_IDS.computeIfAbsent(path, XaeroWorldIdentity::readOrCreateWorldId);
    }

    private static int readOrCreateWorldId(Path path) {
        try {
            if (Files.exists(path)) {
                try {
                    return parseWorldId(path);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid Xaero world identity at {}; replacing it", path);
                }
            }

            int worldId = newWorldId();
            try {
                Files.writeString(
                        path,
                        Integer.toString(worldId) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );
                LOGGER.info("Stored Xaero world identity at {}", path);
            } catch (IOException writeEx) {
                Path fallbackPath = fallbackPathFor(path);
                if (fallbackPath != null) {
                    try {
                        if (Files.exists(fallbackPath)) {
                            try {
                                return parseWorldId(fallbackPath);
                            } catch (NumberFormatException ignored) {}
                        }
                        Files.writeString(
                                fallbackPath,
                                Integer.toString(worldId) + System.lineSeparator(),
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING
                        );
                        LOGGER.warn("Could not persist Xaero world identity at {}; saved fallback at {}", path, fallbackPath, writeEx);
                    } catch (IOException fallbackWriteEx) {
                        LOGGER.warn("Could not persist Xaero world identity at {} or fallback {}", path, fallbackPath, fallbackWriteEx);
                    }
                } else {
                    LOGGER.warn("Could not persist Xaero world identity at {}; using a temporary id for this server run", path, writeEx);
                }
            }
            return worldId;
        } catch (Throwable t) {
            int fallbackId = newWorldId();
            LOGGER.warn("Could not persist Xaero world identity at {}; using a temporary id for this server run", path, t);
            return fallbackId;
        }
    }

    private static Path fallbackPathFor(Path worldPath) {
        try {
            String safeName = Integer.toHexString(worldPath.hashCode()) + "-" + Integer.toHexString(worldPath.toString().hashCode()) + ".txt";
            Path dir = Agnos.configDir().resolve("e4all-xaero-world-ids");
            Files.createDirectories(dir);
            return dir.resolve(safeName);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int parseWorldId(Path path) throws IOException {
        return Integer.parseInt(Files.readString(path, StandardCharsets.UTF_8).trim());
    }

    private static int newWorldId() {
        int id;
        do {
            id = ThreadLocalRandom.current().nextInt();
        } while (id == 0);
        return id;
    }
}
