package link.e4all;

import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class OpSessionManager {
    private static final char[] CODE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final int CODE_LENGTH = 12;
    private static final int SESSION_TOKEN_LENGTH = 16;
    private static final long VERIFY_TIMEOUT_MILLIS = 120_000L;
    private static final long VERIFY_RETRY_COOLDOWN_MILLIS = 1_000L;
    private static final long AUTO_VERIFY_GRACE_MILLIS = 2_500L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<UUID, SessionSecret> secrets = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingVerification> pending = new ConcurrentHashMap<>();
    private static final Set<UUID> denied = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> capableClients = ConcurrentHashMap.newKeySet();
    private static volatile MinecraftServer activeServer;
    private static volatile String sessionToken;

    private OpSessionManager() {}

    public static void beginSession(MinecraftServer server) {
        activeServer = server;
        sessionToken = newToken();
        secrets.clear();
        pending.clear();
        denied.clear();
        capableClients.clear();
    }

    public static void endSession(MinecraftServer server) {
        if (activeServer == server) {
            activeServer = null;
            sessionToken = null;
            secrets.clear();
            pending.clear();
            denied.clear();
            capableClients.clear();
        }
    }

    public static boolean suppressesOp(MinecraftServer server, GameProfile profile) {
        return isManaged(server) && (pending.containsKey(profile.getId()) || denied.contains(profile.getId()));
    }

    public static Boolean getOpOverride(MinecraftServer server, GameProfile profile) {
        if (!isManaged(server)) {
            return null;
        }
        if (suppressesOp(server, profile)) {
            return false;
        }
        return server.getPlayerList().getOps().get(profile) != null;
    }

    public static void onOpGranted(MinecraftServer server, GameProfile profile) {
        if (!isManaged(server)) {
            return;
        }

        String code = newCode();
        UUID playerId = profile.getId();
        secrets.put(playerId, new SessionSecret(code));
        pending.remove(playerId);
        denied.remove(playerId);

        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            sendSecret(player, code);
            return;
        }

        ServerPlayer host = findHost(server);
        if (host != null) {
            host.sendSystemMessage(Mirror.translatable("text.e4all_minecraft.opSessionCode", profile.getName(), code));
        }
    }

    public static void onDeop(MinecraftServer server, GameProfile profile) {
        if (!isManaged(server)) {
            return;
        }
        UUID playerId = profile.getId();
        secrets.remove(playerId);
        pending.remove(playerId);
        denied.remove(playerId);
    }

    public static void onPlayerConnecting(MinecraftServer server, ServerPlayer player) {
        if (!isManaged(server) || server.getPlayerList().getOps().get(player.getGameProfile()) == null) {
            return;
        }
        if (Mirror.isSingleplayerOwner(server, player)) {
            return;
        }

        UUID playerId = player.getUUID();
        denied.remove(playerId);
        PendingVerification verification = pending.computeIfAbsent(playerId,
                ignored -> new PendingVerification(player.getGameProfile().getName(), System.currentTimeMillis()));
        player.sendSystemMessage(Mirror.translatable("text.e4all_minecraft.verifyPreviousOpSession"));
        if (!secrets.containsKey(playerId)) {
            verification.state = VerificationState.ESCALATED;
            notifyHost(server, playerId, verification, "text.e4all_minecraft.reason.reconnectedNoOpCode");
        }
        requestAutomaticVerification(server, player, verification);
    }

    public static void onPlayerDisconnected(MinecraftServer server, ServerPlayer player) {
        if (!isManaged(server)) {
            return;
        }
        UUID playerId = player.getUUID();
        pending.remove(playerId);
        denied.remove(playerId);
        capableClients.remove(playerId);
    }

    public static void handleClientPayload(GameProfile profile, Object id) {
        if (OpSessionPayload.isClientHello(id)) {
            capableClients.add(profile.getId());
            MinecraftServer server = activeServer;
            if (server != null) {
                ServerPlayer player = server.getPlayerList().getPlayer(profile.getId());
                PendingVerification verification = pending.get(profile.getId());
                if (player != null && verification != null) {
                    requestAutomaticVerification(server, player, verification);
                }
            }
            return;
        }

        String code = OpSessionPayload.parseVerification(id, sessionToken);
        if (code == null || activeServer == null) {
            return;
        }
        ServerPlayer player = activeServer.getPlayerList().getPlayer(profile.getId());
        if (player != null) {
            verify(player, code);
        }
    }

    public static boolean verify(ServerPlayer player, String suppliedCode) {
        MinecraftServer server = player.getServer();
        if (server == null || !isManaged(server)) {
            return false;
        }

        UUID playerId = player.getUUID();
        PendingVerification verification = pending.get(playerId);
        SessionSecret secret = secrets.get(playerId);
        if (verification == null) {
            player.sendSystemMessage(Mirror.translatable("text.e4all_minecraft.noOpSessionWaiting"));
            return false;
        }

        if (verification.state == VerificationState.ESCALATED) {
            player.sendSystemMessage(Mirror.translatable("text.e4all_minecraft.hostMustDecideOp"));
            return false;
        }

        long now = System.currentTimeMillis();
        if (now - verification.lastAttemptAt < VERIFY_RETRY_COOLDOWN_MILLIS) {
            player.sendSystemMessage(Mirror.translatable("text.e4all_minecraft.pleaseWaitOpCode"));
            return false;
        }
        verification.lastAttemptAt = now;

        if (secret != null && codesMatch(secret.code, suppliedCode)) {
            pending.remove(playerId);
            denied.remove(playerId);
            server.getPlayerList().sendPlayerPermissionLevel(player);
            player.sendSystemMessage(Mirror.translatable("text.e4all_minecraft.opSessionVerified"));
            ServerPlayer host = findHost(server);
            if (host != null && host != player) {
                host.sendSystemMessage(Mirror.translatable("text.e4all_minecraft.playerVerifiedOpSession", player.getGameProfile().getName()));
            }
            return true;
        }

        player.sendSystemMessage(Mirror.translatable("text.e4all_minecraft.invalidOpCode"));
        verification.state = VerificationState.ESCALATED;
        notifyHost(server, playerId, verification, "text.e4all_minecraft.reason.couldNotVerifyOp");
        return false;
    }

    public static boolean allowWithoutOp(MinecraftServer server, UUID playerId) {
        if (!isManaged(server)) {
            return false;
        }
        PendingVerification verification = pending.get(playerId);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (verification == null || player == null) {
            return false;
        }

        pending.remove(playerId);
        denied.add(playerId);
        server.getPlayerList().sendPlayerPermissionLevel(player);
        player.sendSystemMessage(Mirror.translatable("text.e4all_minecraft.hostAllowedWithoutOp"));
        return true;
    }

    public static boolean restoreOp(MinecraftServer server, UUID playerId) {
        if (!isManaged(server)) {
            return false;
        }
        PendingVerification verification = pending.get(playerId);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (verification == null || player == null) {
            return false;
        }

        pending.remove(playerId);
        denied.remove(playerId);
        String code = newCode();
        secrets.put(playerId, new SessionSecret(code));
        server.getPlayerList().sendPlayerPermissionLevel(player);
        player.sendSystemMessage(Mirror.translatable("text.e4all_minecraft.hostRestoredOp"));
        sendSecret(player, code);
        return true;
    }

    public static boolean kick(MinecraftServer server, UUID playerId) {
        if (!isManaged(server)) {
            return false;
        }
        PendingVerification verification = pending.get(playerId);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (verification == null || player == null) {
            return false;
        }

        pending.remove(playerId);
        player.connection.disconnect(Mirror.translatable("text.e4all_minecraft.hostDidNotRestoreOp"));
        return true;
    }

    public static void tick(MinecraftServer server) {
        if (!isManaged(server)) {
            return;
        }

        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, PendingVerification> entry : pending.entrySet()) {
            PendingVerification verification = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                requestAutomaticVerification(server, player, verification);
                if (verification.automaticRequested
                        && !verification.manualFallbackSent
                        && now - verification.automaticRequestedAt >= AUTO_VERIFY_GRACE_MILLIS) {
                    SessionSecret secret = secrets.get(entry.getKey());
                    if (secret != null) {
                        sendCode(player, secret.code);
                        verification.manualFallbackSent = true;
                    }
                }
            }
            if (verification.state == VerificationState.PENDING && now - verification.connectedAt >= VERIFY_TIMEOUT_MILLIS) {
                verification.state = VerificationState.ESCALATED;
                notifyHost(server, entry.getKey(), verification, "text.e4all_minecraft.reason.didNotVerifyOpInTime");
            }
        }
    }

    private static boolean isManaged(MinecraftServer server) {
        return isLanSession(server) && Config.INSTANCE.offlineMode.value();
    }

    private static boolean isLanSession(MinecraftServer server) {
        return activeServer == server && !server.isDedicatedServer();
    }

    private static String newCode() {
        return newRandomString(CODE_LENGTH);
    }

    private static String newToken() {
        return newRandomString(SESSION_TOKEN_LENGTH);
    }

    private static String newRandomString(int length) {
        char[] code = new char[length];
        for (int i = 0; i < code.length; i++) {
            code[i] = CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)];
        }
        return new String(code);
    }

    private static boolean codesMatch(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendCode(ServerPlayer player, String code) {
        Component message = Mirror.withStyle(
                Mirror.translatable("text.e4all_minecraft.yourOpCode", code),
                style -> style.withColor(ChatFormatting.YELLOW).withClickEvent(Mirror.copyToClipboard(code))
        );
        player.sendSystemMessage(message);
    }

    private static void sendSecret(ServerPlayer player, String code) {
        if (capableClients.contains(player.getUUID()) && sessionToken != null) {
            OpSessionPayload.sendSecret(player, sessionToken, code);
            return;
        }
        sendCode(player, code);
    }

    private static void requestAutomaticVerification(MinecraftServer server, ServerPlayer player, PendingVerification verification) {
        if (!isManaged(server) || verification.state != VerificationState.PENDING || verification.automaticRequested || !capableClients.contains(player.getUUID()) || sessionToken == null) {
            return;
        }
        verification.automaticRequested = true;
        verification.automaticRequestedAt = System.currentTimeMillis();
        OpSessionPayload.requestVerification(player, sessionToken);
    }

    private static void notifyHost(MinecraftServer server, UUID playerId, PendingVerification verification, String reason) {
        ServerPlayer host = findHost(server);
        if (host == null) {
            return;
        }

        host.sendSystemMessage(Mirror.translatable("text.e4all_minecraft.hostNotifyOpIssue", verification.name, Mirror.translatable(reason)));
        host.sendSystemMessage(actionMessage("text.e4all_minecraft.action.kick", "/e4all op kick " + playerId, ChatFormatting.RED));
        host.sendSystemMessage(actionMessage("text.e4all_minecraft.action.allowWithoutOp", "/e4all op allow " + playerId, ChatFormatting.YELLOW));
        host.sendSystemMessage(actionMessage("text.e4all_minecraft.action.restoreOp", "/e4all op restore " + playerId, ChatFormatting.GREEN));
    }

    private static Component actionMessage(String translationKey, String command, ChatFormatting color) {
        return Mirror.withStyle(Mirror.translatable(translationKey),
                style -> style.withColor(color).withClickEvent(Mirror.runCommand(command)));
    }

    private static ServerPlayer findHost(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                if (Mirror.isSingleplayerOwner(server, player)) {
                    return player;
                }
            } catch (RuntimeException ignored) {}
        }
        return null;
    }

    private record SessionSecret(String code) {}

    private enum VerificationState {
        PENDING,
        ESCALATED
    }

    private static final class PendingVerification {
        private final String name;
        private final long connectedAt;
        private VerificationState state = VerificationState.PENDING;
        private long lastAttemptAt;
        private long automaticRequestedAt;
        private boolean automaticRequested;
        private boolean manualFallbackSent;

        private PendingVerification(String name, long connectedAt) {
            this.name = name;
            this.connectedAt = connectedAt;
        }
    }
}
