package link.e4all;

import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class OpSessionPayload {
    private static final String NAMESPACE = "e4all";
    private static final String CLIENT_HELLO_PATH = "op-client";
    private static final String SECRET_PREFIX = "op-secret/";
    private static final String VERIFY_PREFIX = "op-verify/";
    private static final ResourceLocation CLIENT_HELLO = resourceLocation(NAMESPACE, CLIENT_HELLO_PATH);

    private OpSessionPayload() {}

    public static void announceClient(Connection connection) {
        PacketHelper.sendServerbound(connection, CLIENT_HELLO);
    }

    public static void sendSecret(ServerPlayer player, String sessionToken, String code) {
        PacketHelper.sendClientbound(player,
                resourceLocation(NAMESPACE, SECRET_PREFIX + sessionToken + "/" + code),
                new byte[0]);
    }

    public static void requestVerification(ServerPlayer player, String sessionToken) {
        PacketHelper.sendClientbound(player,
                resourceLocation(NAMESPACE, VERIFY_PREFIX + sessionToken),
                new byte[0]);
    }

    public static void sendVerification(Connection connection, String sessionToken, String code) {
        PacketHelper.sendServerbound(connection,
                resourceLocation(NAMESPACE, VERIFY_PREFIX + sessionToken + "/" + code));
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

    static ResourceLocation resourceLocation(String namespace, String path) {
        try {
            java.lang.reflect.Method m = ResourceLocation.class.getMethod("fromNamespaceAndPath", String.class, String.class);
            return (ResourceLocation) m.invoke(null, namespace, path);
        } catch (ReflectiveOperationException ignored) {}
        try {
            java.lang.reflect.Constructor<ResourceLocation> ctor = ResourceLocation.class.getDeclaredConstructor(String.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(namespace, path);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Could not create ResourceLocation", e);
        }
    }
}
