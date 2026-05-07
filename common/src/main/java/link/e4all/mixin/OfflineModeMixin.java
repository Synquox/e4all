package link.e4all.mixin;

import com.mojang.authlib.GameProfile;
import link.e4all.Config;
import link.e4all.E4allClient;
import link.e4all.Mirror;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Mixin that intercepts the Mojang authentication flow to support offline mode.
 * When offline mode is enabled in the config, this bypasses the session server
 * verification and allows unauthenticated (cracked) clients to join.
 *
 * This is separate from the Dialtone encryption handling in ServerLoginPacketListenerImplMixin.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class OfflineModeMixin {

    @Shadow @Final
    Connection connection;

    @Shadow @Final
    MinecraftServer server;



    /**
     * Multiple approach strategy for intercepting auth:
     *
     * In Minecraft's ServerLoginPacketListenerImpl.handleKey(), after decrypting the
     * shared secret, a new Thread is started that calls hasJoinedServer() on the
     * MinecraftSessionService. If hasJoinedServer returns null, the player is
     * disconnected. If it returns a GameProfile, the player is accepted.
     *
     * We use @Redirect to intercept this call. The method signature varies across
     * MC versions, so we have multiple targets with require = 0.
     */

    // Target for MC 1.18.x - 1.20.1 (takes String, String, InetAddress)
    @Redirect(
        method = "handleKey",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/authlib/minecraft/MinecraftSessionService;hasJoinedServer(Ljava/lang/String;Ljava/lang/String;Ljava/net/InetAddress;)Lcom/mojang/authlib/GameProfile;"
        ),
        require = 0
    )
    private GameProfile e4all$skipAuthCheck3Arg(
        Object /* MinecraftSessionService */ service,
        String username,
        String serverId,
        InetAddress address
    ) {
        if (Config.INSTANCE.offlineMode.value()) {
            E4allClient.LOGGER.info("e4all: Offline mode active, skipping auth for player: {}", username);
            return e4all$createOfflineProfile(username);
        }
        // Call original via reflection to avoid compile-time dependency on specific authlib version
        return e4all$callHasJoinedServer(service, username, serverId, address);
    }

    // Target for MC 1.20.2+ (method descriptor may differ with newer authlib)
    @Redirect(
        method = "handleKey",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/authlib/minecraft/MinecraftSessionService;hasJoinedServer(Lcom/mojang/authlib/GameProfile;Ljava/lang/String;Ljava/net/InetAddress;)Lcom/mojang/authlib/GameProfile;"
        ),
        require = 0
    )
    private GameProfile e4all$skipAuthCheckProfileArg(
        Object /* MinecraftSessionService */ service,
        GameProfile profile,
        String serverId,
        InetAddress address
    ) {
        if (Config.INSTANCE.offlineMode.value()) {
            String username = profile.getName();
            E4allClient.LOGGER.info("e4all: Offline mode active, skipping auth for player: {}", username);
            return e4all$createOfflineProfile(username);
        }
        // Call original via reflection
        return e4all$callHasJoinedServerProfile(service, profile, serverId, address);
    }

    /**
     * Creates an offline-style GameProfile with a UUID derived from the username.
     * This matches the standard Minecraft offline UUID generation:
     * UUID.nameUUIDFromBytes("OfflinePlayer:" + name)
     */
    @Unique
    private static GameProfile e4all$createOfflineProfile(String username) {
        UUID offlineUuid = UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)
        );
        return new GameProfile(offlineUuid, username);
    }

    /**
     * Calls hasJoinedServer reflectively with 3-arg signature (String, String, InetAddress).
     */
    @Unique
    private static GameProfile e4all$callHasJoinedServer(Object service, String username, String serverId, InetAddress address) {
        try {
            Method method = service.getClass().getMethod("hasJoinedServer", String.class, String.class, InetAddress.class);
            return (GameProfile) method.invoke(service, username, serverId, address);
        } catch (Exception e) {
            E4allClient.LOGGER.error("e4all: Failed to call hasJoinedServer reflectively", e);
            return null;
        }
    }

    /**
     * Calls hasJoinedServer reflectively with GameProfile-arg signature.
     */
    @Unique
    private static GameProfile e4all$callHasJoinedServerProfile(Object service, GameProfile profile, String serverId, InetAddress address) {
        try {
            Method method = service.getClass().getMethod("hasJoinedServer", GameProfile.class, String.class, InetAddress.class);
            return (GameProfile) method.invoke(service, profile, serverId, address);
        } catch (Exception e) {
            E4allClient.LOGGER.error("e4all: Failed to call hasJoinedServer reflectively", e);
            return null;
        }
    }
}


