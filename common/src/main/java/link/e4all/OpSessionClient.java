package link.e4all;

import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class OpSessionClient {
    private static final Map<String, String> secrets = new ConcurrentHashMap<>();
    private static volatile Connection connection;

    private OpSessionClient() {}

    public static void announce(Connection activeConnection) {
        connection = activeConnection;
        OpSessionPayload.announceClient(activeConnection);
    }

    public static void handlePayload(ResourceLocation id) {
        String[] secret = OpSessionPayload.parseSecret(id);
        if (secret != null) {
            secrets.put(secret[0], secret[1]);
            return;
        }

        String sessionToken = OpSessionPayload.parseVerificationRequest(id);
        if (sessionToken == null || connection == null) {
            return;
        }
        String code = secrets.get(sessionToken);
        if (code != null) {
            OpSessionPayload.sendVerification(connection, sessionToken, code);
        }
    }
}
