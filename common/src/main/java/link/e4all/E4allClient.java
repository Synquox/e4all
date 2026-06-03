package link.e4all;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.commands.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class E4allClient {
    public static final String MOD_ID = "e4all";
    public static volatile QuiclimeSession session;
    public static final Object SESSION_LOCK = new Object();
    public static final Logger LOGGER = LoggerFactory.getLogger(E4allClient.MOD_ID);

    public static boolean badurl = false;

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
                        .requires(src -> {
                            if (src.getServer() == null) {
                                return false;
                            }
                            if (src.getServer().isDedicatedServer()) {
                                return src.hasPermission(4);
                            } else {
                                try {
                                    return Mirror.isSingleplayerOwner(src.getServer(), src.getPlayerOrException());
                                } catch (CommandSyntaxException e) {
                                    return false;
                                }
                            }
                        })
                        .then(Commands.literal("stop").executes(ctx -> {
                            synchronized (SESSION_LOCK) {
                                if ((session != null) && (session.state != QuiclimeSession.State.STOPPED)) {
                                    session.stop();
                                    Mirror.sendSuccessToSource(ctx.getSource(), Mirror.translatable("text.e4mc_minecraft.closeServer"));
                                } else {
                                    Mirror.sendFailureToSource(ctx.getSource(), Mirror.translatable("text.e4mc_minecraft.serverAlreadyClosed"));
                                }
                            }
                            return 1;
                        }))
                        .then(Commands.literal("doctor").executes(ctx -> {
                            var thread = new Thread(() -> {
                                LOGGER.info("generating e4all doctor report");
                                Mirror.sendSuccessToSource(ctx.getSource(), Mirror.translatable("text.e4mc_minecraft.doctor.start"));
                                var diag = Doctor.doctor();
                                LOGGER.info("e4all doctor report:\n{}", diag);
                                Mirror.sendSuccessToSource(ctx.getSource(), Mirror.literal(diag));
                            }, "e4all_minecraft-doctor");
                            thread.setDaemon(true);
                            thread.start();
                            return 1;
                        }))
                        .then(Commands.literal("restart").executes(ctx -> {
                            synchronized (SESSION_LOCK) {
                                if (E4allClient.session != null) {
                                    var rawHandler = E4allClient.session.handler;
                                    var group = E4allClient.session.group;
                                    if (rawHandler instanceof VoiceChatBridgeInitializer wrapper) {
                                        rawHandler = wrapper.getOriginalHandler();
                                    }
                                    if (E4allClient.session.state != QuiclimeSession.State.STOPPED) {
                                        E4allClient.session.stop();
                                    }
                                    VoiceChatBridge.resetCachedPort();
                                    E4allClient.session = new QuiclimeSession(
                                        new VoiceChatBridgeInitializer(rawHandler, true), group);
                                    E4allClient.session.startAsync();
                                    Mirror.sendSuccessToSource(ctx.getSource(), Mirror.literal("e4all: Restarting relay connection..."));
                                } else {
                                    Mirror.sendFailureToSource(ctx.getSource(), Mirror.literal("e4all: No active session to restart."));
                                }
                            }
                            return 1;
                        }))
                        .then(Commands.literal("offlinemode").executes(ctx -> {
                            boolean current = Config.INSTANCE.offlineMode.value();
                            Config.INSTANCE.offlineMode.setValue(!current, true);
                            Mirror.sendSuccessToSource(ctx.getSource(),
                                Mirror.translatable(current
                                    ? "text.e4mc_minecraft.offlineModeDisabled"
                                    : "text.e4mc_minecraft.offlineModeEnabled"));
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



