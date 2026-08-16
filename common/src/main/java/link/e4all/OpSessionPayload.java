package link.e4all;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.atomic.AtomicReference;

public final class OpSessionPayload {
    private static final String NAMESPACE = "e4all";
    private static final String CLIENT_HELLO_PATH = "op-client";
    private static final String SECRET_PREFIX = "op-secret/";
    private static final String VERIFY_PREFIX = "op-verify/";

    private static final AtomicReference<Object> clientHelloRef = new AtomicReference<>();

    private OpSessionPayload() {}

    private static Object clientHello() {
        Object cached = clientHelloRef.get();
        if (cached != null) return cached;
        Object created = ResourceLocReflector.create(NAMESPACE, CLIENT_HELLO_PATH);
        clientHelloRef.compareAndSet(null, created);
        return clientHelloRef.get();
    }

    public static void announceClient(Connection connection) {
        try {
            Class.forName("net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket");
            PacketHelper.sendServerbound(connection, clientHello());
        } catch (ClassNotFoundException ignored) {
            // pre-1.20.2 doesnt support config custom payloads
        }
    }

    static Object resourceLocation(String namespace, String path) {
        return ResourceLocReflector.create(namespace, path);
    }

    public static void sendSecret(ServerPlayer player, String sessionToken, String code) {
        PacketHelper.sendClientbound(player,
                ResourceLocReflector.create(NAMESPACE, SECRET_PREFIX + sessionToken + "/" + code),
                new byte[0]);
    }

    public static void requestVerification(ServerPlayer player, String sessionToken) {
        PacketHelper.sendClientbound(player,
                ResourceLocReflector.create(NAMESPACE, VERIFY_PREFIX + sessionToken),
                new byte[0]);
    }

    public static void sendVerification(Connection connection, String sessionToken, String code) {
        PacketHelper.sendServerbound(connection,
                ResourceLocReflector.create(NAMESPACE, VERIFY_PREFIX + sessionToken + "/" + code));
    }

    public static boolean isClientHello(Object id) {
        if (id == null) return false;
        return NAMESPACE.equals(ResourceLocReflector.getNamespace(id))
                && CLIENT_HELLO_PATH.equals(ResourceLocReflector.getPath(id));
    }

    public static String[] parseSecret(Object id) {
        String[] values = split(id, SECRET_PREFIX, 2);
        return values != null ? values : null;
    }

    public static String parseVerification(Object id, String expectedSessionToken) {
        if (expectedSessionToken == null) {
            return null;
        }
        String[] values = split(id, VERIFY_PREFIX, 2);
        if (values == null || !expectedSessionToken.equals(values[0])) {
            return null;
        }
        return values[1];
    }

    public static String parseVerificationRequest(Object id) {
        String[] values = split(id, VERIFY_PREFIX, 1);
        return values == null ? null : values[0];
    }

    private static String[] split(Object id, String prefix, int parts) {
        if (id == null) return null;
        String namespace = ResourceLocReflector.getNamespace(id);
        String path = ResourceLocReflector.getPath(id);
        if (namespace == null || path == null) return null;
        if (!NAMESPACE.equals(namespace) || !path.startsWith(prefix)) {
            return null;
        }
        String[] values = path.substring(prefix.length()).split("/");
        if (values.length != parts) {
            return null;
        }
        for (String v : values) {
            if (v.isEmpty()) return null;
        }
        return values;
    }
}
