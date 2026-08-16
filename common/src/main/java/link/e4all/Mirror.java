package link.e4all;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public class Mirror {
    private static final String[] LITERAL_CLASS_NAMES = {
            "net.minecraft.text.LiteralText", // yarn
            "net.minecraft.network.chat.TextComponent",
            "net.minecraft.class_2585",
            "net.minecraft.src.C_5025_"
    };
    private static final String[] LITERAL_METHOD_NAMES = {
            "literal",
            "method_43470",
            "m_237113_"
    };
    private static final String[] TRANSLATABLE_CLASS_NAMES = {
            "net.minecraft.text.TranslatableText", // yarn
            "net.minecraft.network.chat.TranslatableComponent",
            "net.minecraft.class_2588",
            "net.minecraft.src.C_5026_"
    };
    private static final String[] TRANSLATABLE_METHOD_NAMES = {
            "translatable",
            "method_43469",
            "m_237110_"
    };
    private static final String[] SUCCESS_METHOD_NAMES = {
            "sendFeedback", // yarn
            "sendSuccess",
            "method_9226",
            "m_288197_"
    };
    private static final String[] FAILURE_METHOD_NAMES = {
            "sendError", // yarn
            "sendFailure",
            "method_9213",
            "m_81352_"
    };
    private static final String[] WITH_STYLE_METHOD_NAMES = {
            "styled", // yarn
            "withStyle",
            "method_27694",
            "m_130938_"
    };
    private static final String[] APPEND_METHOD_NAMES = {
            "append",
            "method_10852",
            "m_7220_"
    };
    private static final String[] RUNCOMMAND_CLASS_NAMES = {
            "net.minecraft.text.ClickEvent$RunCommand", // yarn
            "net.minecraft.network.chat.ClickEvent$RunCommand",
            "net.minecraft.class_2558$class_10609"
    };
    private static final String[] COPYTOCLIPBOARD_CLASS_NAMES = {
            "net.minecraft.text.ClickEvent$CopyToClipboard", // yarn
            "net.minecraft.network.chat.ClickEvent$CopyToClipboard",
            "net.minecraft.class_2558$class_10606"
    };

    private static final String[] SHOWTEXT_CLASS_NAMES = {
            "net.minecraft.text.HoverEvent$ShowText", // yarn
            "net.minecraft.network.chat.HoverEvent$ShowText",
            "net.minecraft.class_2568$class_10613"
    };
    private static final String[] NAME_AND_ID_METHOD_NAMES = {
            "nameAndId",
            "method_72498",
            "getPlayerConfigEntry",
            "method_73606"
    };
    private static final String[] IS_SINGLEPLAYER_OWNER_METHOD_NAMES = {
            "isSingleplayerOwner",
            "method_19466",
            "m_7779_",
            "method_73608",
            "isOwner"
    };
    private static final String[] PLAYER_IS_SINGLEPLAYER_OWNER_METHOD_NAMES = {
            "isSingleplayerOwner",
            "method_52402",
            "m_52402_",
            "isOwner"
    };
    private static final String[] GET_SINGLEPLAYER_NAME_METHOD_NAMES = {
            "getSingleplayerName",
            "getSinglePlayerName",
            "method_3823",
            "m_129792_",
            "getHostProfile",
            "getSingleplayerProfile"
    };
    private static final String[] SET_USING_WHITELIST_METHOD_NAMES = {
            "setUsingWhiteList",
            "m_6628_",
            "method_14557",
            "setWhitelistEnabled",
            "setUsingWhitelist",
            "method_73589",
    };

    public static ClickEvent runCommand(String command) {
        if (ClickEvent.class.isInterface()) {
            for (String className : RUNCOMMAND_CLASS_NAMES) {
                try {
                    Class<?> clazz = Class.forName(className);
                    Constructor<?> constructor = clazz.getConstructor(String.class);
                    return (ClickEvent) constructor.newInstance(command);
                } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException |
                         InvocationTargetException | ClassCastException ignored) {}
            }
        } else {
            return new ClickEvent(ClickEvent.Action.RUN_COMMAND, command);
        }
        throw new RuntimeException("Could not locate any way to make a ClickEvent!");
    }

    public static ClickEvent copyToClipboard(String text) {
        if (ClickEvent.class.isInterface()) {
            for (String className : COPYTOCLIPBOARD_CLASS_NAMES) {
                try {
                    Class<?> clazz = Class.forName(className);
                    Constructor<?> constructor = clazz.getConstructor(String.class);
                    return (ClickEvent) constructor.newInstance(text);
                } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException |
                         InvocationTargetException | ClassCastException ignored) {}
            }
        } else {
            return new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, text);
        }
        throw new RuntimeException("Could not locate any way to make a ClickEvent!");
    }

    public static HoverEvent showText(Component text) {
        if (HoverEvent.class.isInterface()) {
            for (String className : SHOWTEXT_CLASS_NAMES) {
                try {
                    Class<?> clazz = Class.forName(className);
                    Constructor<?> constructor = clazz.getConstructor(Component.class);
                    return (HoverEvent) constructor.newInstance(text);
                } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException |
                         InvocationTargetException | ClassCastException ignored) {}
            }
        } else {
            return new HoverEvent(HoverEvent.Action.SHOW_TEXT, text);
        }
        throw new RuntimeException("Could not locate any way to make a HoverEvent!");
    }

    public static Component withStyle(Component component, UnaryOperator<Style> operator) {
        Class<? extends Component> clazz = component.getClass();
        for (String methodName : WITH_STYLE_METHOD_NAMES) {
            try {
                Method method = clazz.getMethod(methodName, UnaryOperator.class);
                return (Component) method.invoke(component, operator);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | ClassCastException ignored) {}
        }
        throw new RuntimeException("Could not locate any way to style this Component!");
    }

    public static Component append(Component component, Component other) {
        Class<? extends Component> clazz = component.getClass();
        for (String methodName : APPEND_METHOD_NAMES) {
            try {
                Method method = clazz.getMethod(methodName, Component.class);
                return (Component) method.invoke(component, other);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | ClassCastException ignored) {}
        }
        throw new RuntimeException("Could not locate any way to append a Component to this Component!");
    }

    public static Component literal(String text) {
        // Try 1.18-and-older-style TextComponent initialization first
        for (String className : LITERAL_CLASS_NAMES) {
            try {
                Class<?> clazz = Class.forName(className);
                Constructor<?> constructor = clazz.getConstructor(String.class);
                return (Component) constructor.newInstance(text);
            } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException |
                     InvocationTargetException | ClassCastException ignored) {}
        }
        Class<Component> clazz = Component.class;
        for (String methodName : LITERAL_METHOD_NAMES) {
            try {
                Method method = clazz.getMethod(methodName, String.class);
                return (Component) method.invoke(null, text);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | ClassCastException ignored) {}
        }
        throw new RuntimeException("Could not locate any way to make a literal Component!");
    }


    public static Component translatable(String text, Object... args) {
        // Try 1.18-and-older-style TranslatableComponent initialization first
        for (String className : TRANSLATABLE_CLASS_NAMES) {
            try {
                Class<?> clazz = Class.forName(className);
                Constructor<?> constructor = clazz.getConstructor(String.class, Object[].class);
                return (Component) constructor.newInstance(text, args);
            } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException |
                     InvocationTargetException | ClassCastException ignored) {}
        }
        Class<Component> clazz = Component.class;
        for (String methodName : TRANSLATABLE_METHOD_NAMES) {
            try {
                Method method = clazz.getMethod(methodName, String.class, Object[].class);
                return (Component) method.invoke(null, text, args);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | ClassCastException ignored) {}
        }
        throw new RuntimeException("Could not locate any way to make a literal Component!");
    }

    public static void sendSuccessToSource(CommandSourceStack source, Component message) {
        sendGenericMessageToSource(source, message, SUCCESS_METHOD_NAMES);
    }

    public static void sendFailureToSource(CommandSourceStack source, Component message) {
        sendGenericMessageToSource(source, message, FAILURE_METHOD_NAMES);
    }

    private static void sendGenericMessageToSource(CommandSourceStack source, Component message, String[] methodNames) {
        Class<CommandSourceStack> clazz = CommandSourceStack.class;
        for (String methodName : methodNames) {
            try {
                Method method = clazz.getMethod(methodName, Component.class, boolean.class);
                method.invoke(source, message, true);
                return;
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {}
            try {
                Method method = clazz.getMethod(methodName, Supplier.class, boolean.class);
                method.invoke(source, (Supplier<Component>) () -> message, true);
                return;
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {}
            try {
                Method method = clazz.getMethod(methodName, Component.class);
                method.invoke(source, message);
                return;
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {}
        }
        E4allClient.LOGGER.warn("Could not send message to command source via any known method mapping: {}", message);
    }

    public static boolean isSingleplayerOwner(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null) return false;
        for (String name : PLAYER_IS_SINGLEPLAYER_OWNER_METHOD_NAMES) {
            try {
                Method m = player.getClass().getMethod(name);
                if (m.getParameterCount() == 0 && m.getReturnType() == boolean.class) {
                    return (boolean) m.invoke(player);
                }
            } catch (Throwable ignored) {}
        }
        Object profile = null;
        try {
            profile = player.getGameProfile();
        } catch (Throwable ignored) {}
        Object nameAndId = null;
        for (String methodName : NAME_AND_ID_METHOD_NAMES) {
            try {
                Method method = player.getClass().getMethod(methodName);
                Object candidate = method.invoke(player);
                if (candidate instanceof com.mojang.authlib.GameProfile) {
                    profile = candidate;
                } else if (candidate != null) {
                    nameAndId = candidate;
                }
                break;
            } catch (Throwable ignored) {}
        }
        Class<?>[] serverClasses = new Class<?>[]{server.getClass(), MinecraftServer.class};
        for (Class<?> sc : serverClasses) {
            for (Method m : sc.getMethods()) {
                if (m.getParameterCount() != 1 || m.getReturnType() != boolean.class) continue;
                boolean nameMatch = false;
                for (String name : IS_SINGLEPLAYER_OWNER_METHOD_NAMES) {
                    if (m.getName().equals(name)) {
                        nameMatch = true;
                        break;
                    }
                }
                if (!nameMatch) continue;
                Class<?> paramType = m.getParameterTypes()[0];
                if (nameAndId != null && paramType.isInstance(nameAndId)) {
                    try { return (boolean) m.invoke(server, nameAndId); } catch (Throwable ignored) {}
                }
                if (profile != null && paramType.isInstance(profile)) {
                    try { return (boolean) m.invoke(server, profile); } catch (Throwable ignored) {}
                }
                if (paramType.isInstance(player)) {
                    try { return (boolean) m.invoke(server, player); } catch (Throwable ignored) {}
                }
                if (profile instanceof com.mojang.authlib.GameProfile gp) {
                    Object adapted = adaptProfile(gp, paramType);
                    if (adapted != null) {
                        try { return (boolean) m.invoke(server, adapted); } catch (Throwable ignored) {}
                    }
                }
            }
        }
        for (Class<?> sc : serverClasses) {
            for (String name : GET_SINGLEPLAYER_NAME_METHOD_NAMES) {
                try {
                    Method m = sc.getMethod(name);
                    if (m.getParameterCount() == 0) {
                        Object res = m.invoke(server);
                        if (res instanceof String sName && !sName.isEmpty()) {
                            String pName = null;
                            try { pName = player.getScoreboardName(); } catch (Throwable ignored) {}
                            if (pName == null && profile instanceof com.mojang.authlib.GameProfile gp) pName = gp.getName();
                            if (pName != null && pName.equalsIgnoreCase(sName)) return true;
                        }
                        if (res instanceof com.mojang.authlib.GameProfile gp) {
                            if (profile instanceof com.mojang.authlib.GameProfile pgp && gp.getId() != null && gp.getId().equals(pgp.getId())) return true;
                        }
                        if (res instanceof java.util.UUID uuid) {
                            if (player.getUUID() != null && player.getUUID().equals(uuid)) return true;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        try {
            if (!server.isDedicatedServer()) {
                Method isSingle = null;
                try { isSingle = server.getClass().getMethod("isSingleplayer"); } catch (Throwable ignored) {}
                if (isSingle == null) {
                    try { isSingle = server.getClass().getMethod("method_3816"); } catch (Throwable ignored) {}
                }
                if (isSingle != null && (boolean) isSingle.invoke(server)) {
                    return true;
                }
                if (server.getPlayerList() != null && server.getPlayerList().getPlayerCount() == 1) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean isSingleplayerOwnerObj(MinecraftServer server, Object maybeProfile) {
        if (server == null || maybeProfile == null) return false;
        Class<?>[] serverClasses = new Class<?>[]{server.getClass(), MinecraftServer.class};
        for (Class<?> sc : serverClasses) {
            for (Method m : sc.getMethods()) {
                if (m.getParameterCount() != 1 || m.getReturnType() != boolean.class) continue;
                boolean nameMatch = false;
                for (String name : IS_SINGLEPLAYER_OWNER_METHOD_NAMES) {
                    if (m.getName().equals(name)) {
                        nameMatch = true;
                        break;
                    }
                }
                if (!nameMatch) continue;
                Class<?> paramType = m.getParameterTypes()[0];
                if (paramType.isInstance(maybeProfile)) {
                    try { return (boolean) m.invoke(server, maybeProfile); } catch (Throwable ignored) {}
                }
                if (maybeProfile instanceof com.mojang.authlib.GameProfile gp) {
                    Object adapted = adaptProfile(gp, paramType);
                    if (adapted != null) {
                        try { return (boolean) m.invoke(server, adapted); } catch (Throwable ignored) {}
                    }
                }
            }
        }
        return false;
    }

    private static Object adaptProfile(com.mojang.authlib.GameProfile profile, Class<?> targetType) {
        if (targetType.isInstance(profile)) return profile;
        for (Method m : com.mojang.authlib.GameProfile.class.getMethods()) {
            if (m.getParameterCount() == 0 && targetType.isAssignableFrom(m.getReturnType())) {
                try {
                    return m.invoke(profile);
                } catch (Throwable ignored) {}
            }
        }
        try {
            java.lang.reflect.Constructor<?> ctor = targetType.getConstructor(java.util.UUID.class, String.class);
            return ctor.newInstance(profile.getId(), profile.getName());
        } catch (Throwable ignored) {}
        return null;
    }

    public static void setUsingWhitelist(MinecraftServer server, PlayerList playerList, boolean enabled) {
        Class<MinecraftServer> clazz = MinecraftServer.class;
        Class<PlayerList> clazz2 = PlayerList.class;
        for (String methodName : SET_USING_WHITELIST_METHOD_NAMES) {
            try {
                Method method = clazz.getMethod(methodName, boolean.class);
                method.invoke(server, enabled);
                return;
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {}
            try {
                Method method = clazz2.getMethod(methodName, boolean.class);
                method.invoke(playerList, enabled);
                return;
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {}
        }
        throw new RuntimeException("Could not locate any way to call setUsingWhitelist!");
    }

    public static void addMessage(Component message) {
        Minecraft.getInstance().execute(() -> {
            try {
                Minecraft.getInstance().gui.getChat().addMessage(message);
            } catch (NoSuchMethodError e) {
                net.minecraft.client.gui.components.ChatComponent chat;
                try {
                    chat = Minecraft.getInstance().gui.getChat();
                } catch (NoSuchMethodError ex) {
                    try {
                        var gui = Minecraft.getInstance().gui;
                        var hud = gui.getClass().getField("hud").get(gui);
                        chat = (net.minecraft.client.gui.components.ChatComponent) hud.getClass().getMethod("getChat").invoke(hud);
                    } catch (Throwable exc) {
                        E4allClient.LOGGER.error("Failed to get client chat!");
                        return;
                    }
                }
                try {
                    chat.getClass().getMethod("addClientSystemMessage", Component.class).invoke(chat, message);
                } catch (Exception ex) {
                    E4allClient.LOGGER.error("Failed to add message to client chat!");
                }
            }
        });
    }

    public static Object createButton(int x, int y, int w, int h, Component text, Object onPress) {
        try {
            Method builderMethod = null;
            for (Method m : net.minecraft.client.gui.components.Button.class.getMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 2) {
                    if (m.getParameterTypes()[0].equals(Component.class) && m.getParameterTypes()[1].isInstance(onPress)) {
                        builderMethod = m;
                        break;
                    }
                }
            }
            if (builderMethod == null) {
                for (Method m : net.minecraft.client.gui.components.Button.class.getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 2) {
                        if (m.getParameterTypes()[0].equals(Component.class) && m.getParameterTypes()[1].isInstance(onPress)) {
                            builderMethod = m;
                            break;
                        }
                    }
                }
            }
            
            if (builderMethod != null) {
                builderMethod.setAccessible(true);
                Object builder = builderMethod.invoke(null, text, onPress);
                
                boolean boundsSet = false;
                for (Method m : builder.getClass().getMethods()) {
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 4) {
                        if (m.getParameterTypes()[0].equals(int.class) && m.getParameterTypes()[1].equals(int.class)
                            && m.getParameterTypes()[2].equals(int.class) && m.getParameterTypes()[3].equals(int.class)) {
                            m.setAccessible(true);
                            Object next = m.invoke(builder, x, y, w, h);
                            if (next != null) builder = next;
                            boundsSet = true;
                            break;
                        }
                    }
                }
                
                if (!boundsSet) {
                    Method posMethod = null;
                    Method sizeMethod = null;
                    for (Method m : builder.getClass().getMethods()) {
                        if (!java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 2 && m.getParameterTypes()[0].equals(int.class) && m.getParameterTypes()[1].equals(int.class)) {
                            String n = m.getName();
                            if (n.equals("pos") || n.equals("position") || n.equals("method_46430")) posMethod = m;
                            else if (n.equals("size") || n.equals("dimensions") || n.equals("method_46432") || n.equals("method_46434")) sizeMethod = m;
                        }
                    }
                    if (posMethod != null && sizeMethod != null) {
                        posMethod.setAccessible(true);
                        Object next = posMethod.invoke(builder, x, y);
                        if (next != null) builder = next;
                        sizeMethod.setAccessible(true);
                        next = sizeMethod.invoke(builder, w, h);
                        if (next != null) builder = next;
                    }
                }
                
                for (Method m : builder.getClass().getMethods()) {
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0 && net.minecraft.client.gui.components.Button.class.isAssignableFrom(m.getReturnType())) {
                        m.setAccessible(true);
                        return m.invoke(builder);
                    }
                }
            }
        } catch (Throwable ignored) {}
        
        try {
            Class<?> onPressClass = null;
            for (Class<?> c : net.minecraft.client.gui.components.Button.class.getDeclaredClasses()) {
                if (c.getName().endsWith("OnPress")) {
                    onPressClass = c;
                    break;
                }
            }
            if (onPressClass != null) {
                Constructor<?> ctor = net.minecraft.client.gui.components.Button.class.getConstructor(
                    int.class, int.class, int.class, int.class, Component.class, onPressClass
                );
                return ctor.newInstance(x, y, w, h, text, onPress);
            }
        } catch (Throwable ignored) {}
        
        return null;
    }
}


