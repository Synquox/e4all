package link.e4all;

import link.e4all.dialtone.DialtoneAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class XaeroWorldIdentity {
    private static final String ID_FILE_NAME = "e4all-xaero-world-id.txt";
    private static final ResourceLocation XAERO_MINIMAP = new ResourceLocation("xaerominimap", "main");
    private static final ResourceLocation XAERO_WORLDMAP = new ResourceLocation("xaeroworldmap", "main");
    private static final ConcurrentHashMap<Path, Integer> WORLD_IDS = new ConcurrentHashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger("e4all");

    private XaeroWorldIdentity() {
    }

    public static boolean isRelayConnection(Connection connection) {
        return connection.getRemoteAddress() instanceof DialtoneAddress;
    }

    public static void initializeForRelay(MinecraftServer server) {
        getOrCreateWorldId(server);
    }

    public static void sendToRelayPlayer(MinecraftServer server, Connection connection, ServerPlayer player) {
        if (!isRelayConnection(connection)) {
            return;
        }

        int worldId = getOrCreateWorldId(server);
        player.connection.send(new ClientboundCustomPayloadPacket(new LevelMapPropertiesPayload(XAERO_MINIMAP, worldId)));
        player.connection.send(new ClientboundCustomPayloadPacket(new LevelMapPropertiesPayload(XAERO_WORLDMAP, worldId)));
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
            Files.writeString(
                    path,
                    Integer.toString(worldId) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            LOGGER.info("Stored Xaero world identity at {}", path);
            return worldId;
        } catch (IOException e) {
            int fallbackId = newWorldId();
            LOGGER.warn("Could not persist Xaero world identity at {}; using a temporary id for this server run", path, e);
            return fallbackId;
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

    private record LevelMapPropertiesPayload(ResourceLocation id, int worldId) implements CustomPacketPayload {
        @Override
        public void write(FriendlyByteBuf buffer) {
            buffer.writeByte(0);
            buffer.writeInt(worldId);
        }
    }
}
