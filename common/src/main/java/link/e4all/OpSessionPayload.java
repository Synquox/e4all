package link.e4all;

import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class OpSessionPayload {
    private static final String NAMESPACE = "e4all";
    private static final String CLIENT_HELLO_PATH = "op-client";
    private static final String SECRET_PREFIX = "op-secret/";
    private static final String VERIFY_PREFIX = "op-verify/";
    private static final ResourceLocation CLIENT_HELLO = new ResourceLocation(NAMESPACE, CLIENT_HELLO_PATH);

    private OpSessionPayload() {}

    public static void announceClient(Connection connection) {
        connection.send(new ServerboundCustomPayloadPacket(new MarkerPayload(CLIENT_HELLO)));
    }

    public static void sendSecret(ServerPlayer player, String sessionToken, String code) {
        player.connection.send(new ClientboundCustomPayloadPacket(new MarkerPayload(
                new ResourceLocation(NAMESPACE, SECRET_PREFIX + sessionToken + "/" + code)
        )));
    }

    public static void requestVerification(ServerPlayer player, String sessionToken) {
        player.connection.send(new ClientboundCustomPayloadPacket(new MarkerPayload(
                new ResourceLocation(NAMESPACE, VERIFY_PREFIX + sessionToken)
        )));
    }

    public static void sendVerification(Connection connection, String sessionToken, String code) {
        connection.send(new ServerboundCustomPayloadPacket(new MarkerPayload(
                new ResourceLocation(NAMESPACE, VERIFY_PREFIX + sessionToken + "/" + code)
        )));
    }

    public static boolean isClientHello(ResourceLocation id) {
        return NAMESPACE.equals(id.getNamespace()) && CLIENT_HELLO_PATH.equals(id.getPath());
    }

    public static String[] parseSecret(ResourceLocation id) {
        String[] values = split(id, SECRET_PREFIX, 2);
        return values != null ? values : null;
    }

    public static String parseVerification(ResourceLocation id, String expectedSessionToken) {
        if (expectedSessionToken == null) {
            return null;
        }
        String[] values = split(id, VERIFY_PREFIX, 2);
        if (values == null || !expectedSessionToken.equals(values[0])) {
            return null;
        }
        return values[1];
    }

    public static String parseVerificationRequest(ResourceLocation id) {
        String[] values = split(id, VERIFY_PREFIX, 1);
        return values == null ? null : values[0];
    }

    private static String[] split(ResourceLocation id, String prefix, int parts) {
        if (!NAMESPACE.equals(id.getNamespace()) || !id.getPath().startsWith(prefix)) {
            return null;
        }
        String[] values = id.getPath().substring(prefix.length()).split("/");
        if (values.length != parts) {
            return null;
        }
        return values;
    }

    private record MarkerPayload(ResourceLocation id) implements CustomPacketPayload {
        @Override
        public void write(FriendlyByteBuf buffer) {}
    }
}
