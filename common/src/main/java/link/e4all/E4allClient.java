package link.e4all;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.commands.BanListCommands;
import net.minecraft.server.commands.BanPlayerCommands;
import net.minecraft.server.commands.PardonCommand;
import net.minecraft.server.commands.WhitelistCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class E4allClient {
    public static final String MOD_ID = "e4all";
    public static volatile QuiclimeSession session;
    public static final Object SESSION_LOCK = new Object();
    public static final Logger LOGGER = LoggerFactory.getLogger(E4allClient.MOD_ID);

    public static boolean badurl = false;

    private static boolean canManage(CommandSourceStack source) {
        if (source == null) return false;
        try {
            if (source.getServer() == null) {
                return false;
            }
            if (source.getServer().isDedicatedServer()) {
                return Mirror.hasPermission(source, 4);
            }
            try {
                if (Mirror.isSingleplayerOwner(source.getServer(), source.getPlayerOrException())) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            return Mirror.hasPermission(source, 2);
        } catch (Throwable t) {
            LOGGER.warn("e4all: canManage check failed", t);
            return false;
        }
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
            try {
                BanListCommands.register(dispatcher);
                BanPlayerCommands.register(dispatcher);
                PardonCommand.register(dispatcher);
                WhitelistCommand.register(dispatcher);
            } catch (Throwable t) {
                LOGGER.warn("e4all: could not register restored dedicated-server commands on this MC version", t);
            }
        }

        dispatcher.register(
                Commands.literal("e4all")
                        .requires(src -> src.getServer() != null)
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
                                var server = ctx.getSource().getServer();
                                server.execute(() -> Mirror.sendSuccessToSource(ctx.getSource(), Mirror.translatable("text.e4all_minecraft.doctor.start")));
                                var diag = Doctor.doctor();
                                LOGGER.info("e4all doctor report:\n{}", diag);
                                server.execute(() -> Mirror.sendSuccessToSource(ctx.getSource(), Mirror.literal(diag)));
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
                                        E4allClient.session.stopSync();
                                    }
                                    E4allClient.session = new QuiclimeSession(rawHandler, group);
                                    E4allClient.session.startAsync();
                                    Mirror.sendSuccessToSource(ctx.getSource(), Mirror.translatable("text.e4all_minecraft.restartingRelay"));
                                } else {
                                    Mirror.sendFailureToSource(ctx.getSource(), Mirror.translatable("text.e4all_minecraft.error.noActiveSession"));
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
                                Mirror.withStyle(Mirror.translatable("text.e4all_minecraft.offlineModeNote"), it ->
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



