package link.e4all;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.commands.*;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class E4allClient {
    public static final String MOD_ID = "e4all";
    public static volatile QuiclimeSession session;
    public static final Object SESSION_LOCK = new Object();
    public static final Logger LOGGER = LoggerFactory.getLogger(E4allClient.MOD_ID);

    public static boolean badurl = false;

    private static boolean canManage(CommandSourceStack source) {
        if (source.getServer() == null) {
            return false;
        }
        if (source.getServer().isDedicatedServer()) {
            return source.hasPermission(4);
        }
        try {
            return Mirror.isSingleplayerOwner(source.getServer(), source.getPlayerOrException());
        } catch (CommandSyntaxException e) {
            return false;
        }
    }

    private static boolean isPlayerSource(CommandSourceStack source) {
        try {
            source.getPlayerOrException();
            return true;
        } catch (CommandSyntaxException e) {
            return false;
        }
    }

    private static int verifyOpSession(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer player = context.getSource().getPlayerOrException();
            return OpSessionManager.verify(player, StringArgumentType.getString(context, "code")) ? 1 : 0;
        } catch (CommandSyntaxException e) {
            return 0;
        }
    }

    private static int resolveOpSession(CommandContext<CommandSourceStack> context, String action) {
        UUID playerId;
        try {
            playerId = UUID.fromString(StringArgumentType.getString(context, "player"));
        } catch (IllegalArgumentException e) {
            Mirror.sendFailureToSource(context.getSource(), Mirror.literal("e4all: Invalid player id."));
            return 0;
        }

        boolean handled = switch (action) {
            case "kick" -> OpSessionManager.kick(context.getSource().getServer(), playerId);
            case "allow" -> OpSessionManager.allowWithoutOp(context.getSource().getServer(), playerId);
            case "restore" -> OpSessionManager.restoreOp(context.getSource().getServer(), playerId);
            default -> false;
        };
        if (!handled) {
            Mirror.sendFailureToSource(context.getSource(), Mirror.literal("e4all: That player has no pending OP verification."));
            return 0;
        }
        return 1;
    }

    public static void init() {
        Config.INSTANCE.id(); // Touch to initialize for McQoy
        try {
            if (!PoisonPill.checkMotw()) {
                badurl = true;
                LOGGER.warn("MotW lists unknown source! Poison pill active!");
            }
        } catch (Exception e) {
            LOGGER.warn("MotW check failed!", e);
        }
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (Config.INSTANCE.restoreDedicatedCommands.value() && Agnos.isClient()) {
            BanListCommands.register(dispatcher);
            BanPlayerCommands.register(dispatcher);
            PardonCommand.register(dispatcher);
            WhitelistCommand.register(dispatcher);
        }
        dispatcher.register(
                Commands.literal("e4all")
                        .requires(src -> src.getServer() != null)
                        .then(Commands.literal("op")
                                .then(Commands.literal("verify")
                                        .requires(E4allClient::isPlayerSource)
                                        .then(Commands.argument("code", StringArgumentType.word())
                                                .executes(E4allClient::verifyOpSession)))
                                .then(Commands.literal("kick")
                                        .requires(E4allClient::canManage)
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(ctx -> resolveOpSession(ctx, "kick"))))
                                .then(Commands.literal("allow")
                                        .requires(E4allClient::canManage)
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(ctx -> resolveOpSession(ctx, "allow"))))
                                .then(Commands.literal("restore")
                                        .requires(E4allClient::canManage)
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(ctx -> resolveOpSession(ctx, "restore")))))
                        .then(Commands.literal("stop").requires(E4allClient::canManage).executes(ctx -> {
                            synchronized (SESSION_LOCK) {
                                if ((session != null) && (session.state != QuiclimeSession.State.STOPPED)) {
                                    session.stop();
                                    Mirror.sendSuccessToSource(ctx.getSource(), Mirror.translatable("text.e4all_minecraft.closeServer"));
                                } else {
                                    Mirror.sendFailureToSource(ctx.getSource(), Mirror.translatable("text.e4all_minecraft.serverAlreadyClosed"));
                                }
                            }
                            return 1;
                        }))
                        .then(Commands.literal("doctor").requires(E4allClient::canManage).executes(ctx -> {
                            var thread = new Thread(() -> {
                                LOGGER.info("generating e4all doctor report");
                                Mirror.sendSuccessToSource(ctx.getSource(), Mirror.translatable("text.e4all_minecraft.doctor.start"));
                                var diag = Doctor.doctor();
                                LOGGER.info("e4all doctor report:\n{}", diag);
                                Mirror.sendSuccessToSource(ctx.getSource(), Mirror.literal(diag));
                            }, "e4all_minecraft-doctor");
                            thread.setDaemon(true);
                            thread.start();
                            return 1;
                        }))
                        .then(Commands.literal("restart").requires(E4allClient::canManage).executes(ctx -> {
                            synchronized (SESSION_LOCK) {
                                if (E4allClient.session != null) {
                                    var rawHandler = E4allClient.session.handler;
                                    var group = E4allClient.session.group;
                                    if (E4allClient.session.state != QuiclimeSession.State.STOPPED) {
                                        E4allClient.session.stop();
                                    }
                                    E4allClient.session = new QuiclimeSession(rawHandler, group);
                                    E4allClient.session.startAsync();
                                    Mirror.sendSuccessToSource(ctx.getSource(), Mirror.literal("e4all: Restarting relay connection..."));
                                } else {
                                    Mirror.sendFailureToSource(ctx.getSource(), Mirror.literal("e4all: No active session to restart."));
                                }
                            }
                            return 1;
                        }))
                        .then(Commands.literal("offlinemode").requires(E4allClient::canManage).executes(ctx -> {
                            boolean current = Config.INSTANCE.offlineMode.value();
                            Config.INSTANCE.offlineMode.setValue(!current, true);
                            Mirror.sendSuccessToSource(ctx.getSource(),
                                Mirror.translatable(current
                                    ? "text.e4all_minecraft.offlineModeDisabled"
                                    : "text.e4all_minecraft.offlineModeEnabled"));
                            Mirror.sendSuccessToSource(ctx.getSource(),
                                Mirror.withStyle(Mirror.literal("Note: This change applies to new connections only."), it ->
                                    it.withColor(net.minecraft.ChatFormatting.GRAY)));
                            return 1;
                        }))
        );
        // Register /e4mc as alias for backwards compatibility
        dispatcher.register(
                Commands.literal("e4mc")
                        .redirect(dispatcher.getRoot().getChild("e4all"))
        );
    }
}



