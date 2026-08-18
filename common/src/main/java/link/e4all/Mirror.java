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
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
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
            "m_288197_",
            "m_81354_"
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
            "m_130944_",
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
    private static final String[] HAS_PERMISSION_METHOD_NAMES = {
            "hasPermission",
            "hasPermissionLevel",
            "method_9259",
            "m_6761_"
    };

    public static ClickEvent runCommand(String command) {
        try {
            return new ClickEvent(ClickEvent.Action.RUN_COMMAND, command);
        } catch (Throwable ignored) {}
        for (String className : RUNCOMMAND_CLASS_NAMES) {
            try {
                Class<?> clazz = Class.forName(className);
                Constructor<?> constructor = clazz.getConstructor(String.class);
                return (ClickEvent) constructor.newInstance(command);
            } catch (Throwable ignored) {}
        }
        try {
            Class<?> actionClass = Class.forName("net.minecraft.network.chat.ClickEvent$Action");
            Field field = actionClass.getField("RUN_COMMAND");
            Object action = field.get(null);
            Constructor<?> constructor = ClickEvent.class.getConstructor(actionClass, String.class);
            return (ClickEvent) constructor.newInstance(action, command);
        } catch (Throwable ignored) {}
        return null;
    }

    public static ClickEvent copyToClipboard(String text) {
        try {
            return new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, text);
        } catch (Throwable ignored) {}
        for (String className : COPYTOCLIPBOARD_CLASS_NAMES) {
            try {
                Class<?> clazz = Class.forName(className);
                Constructor<?> constructor = clazz.getConstructor(String.class);
                return (ClickEvent) constructor.newInstance(text);
            } catch (Throwable ignored) {}
        }
        try {
            Class<?> actionClass = Class.forName("net.minecraft.network.chat.ClickEvent$Action");
            Field field = actionClass.getField("COPY_TO_CLIPBOARD");
            Object action = field.get(null);
            Constructor<?> constructor = ClickEvent.class.getConstructor(actionClass, String.class);
            return (ClickEvent) constructor.newInstance(action, text);
        } catch (Throwable ignored) {}
        return null;
    }

    public static HoverEvent showText(Component text) {
        try {
            return new HoverEvent(HoverEvent.Action.SHOW_TEXT, text);
        } catch (Throwable ignored) {}
        for (String className : SHOWTEXT_CLASS_NAMES) {
            try {
                Class<?> clazz = Class.forName(className);
                Constructor<?> constructor = clazz.getConstructor(Component.class);
                return (HoverEvent) constructor.newInstance(text);
            } catch (Throwable ignored) {}
        }
        try {
            Class<?> actionClass = Class.forName("net.minecraft.network.chat.HoverEvent$Action");
            Field field = actionClass.getField("SHOW_TEXT");
            Object action = field.get(null);
            Constructor<?> constructor = HoverEvent.class.getConstructor(actionClass, Component.class);
            return (HoverEvent) constructor.newInstance(action, text);
        } catch (Throwable ignored) {}
        return null;
    }

    public static Component withStyle(Component component, UnaryOperator<Style> operator) {
        if (component == null) return null;
        if (component instanceof net.minecraft.network.chat.MutableComponent mc) {
            try {
                return mc.withStyle(operator);
            } catch (Throwable ignored) {}
        }
        Class<?> clazz = component.getClass();
        for (String methodName : WITH_STYLE_METHOD_NAMES) {
            try {
                Method method = clazz.getMethod(methodName, UnaryOperator.class);
                return (Component) method.invoke(component, operator);
            } catch (Throwable ignored) {}
        }
        for (Method m : clazz.getMethods()) {
            if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(UnaryOperator.class) && Component.class.isAssignableFrom(m.getReturnType())) {
                try {
                    m.setAccessible(true);
                    return (Component) m.invoke(component, operator);
                } catch (Throwable ignored) {}
            }
        }
        try {
            Style current = component.getStyle();
            Style updated = operator.apply(current);
            for (String methodName : WITH_STYLE_METHOD_NAMES) {
                try {
                    Method method = clazz.getMethod(methodName, Style.class);
                    return (Component) method.invoke(component, updated);
                } catch (Throwable ignored) {}
            }
            for (Method m : clazz.getMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == Style.class && Component.class.isAssignableFrom(m.getReturnType())) {
                    try {
                        m.setAccessible(true);
                        return (Component) m.invoke(component, updated);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        E4allClient.LOGGER.warn("Could not locate any way to style this Component, returning fallback: {}", component);
        return component;
    }

    public static Component append(Component component, Component other) {
        if (component == null) return other;
        if (other == null) return component;
        if (component instanceof net.minecraft.network.chat.MutableComponent mc) {
            try {
                return mc.append(other);
            } catch (Throwable ignored) {}
        }
        Class<?> clazz = component.getClass();
        for (String methodName : APPEND_METHOD_NAMES) {
            try {
                Method method = clazz.getMethod(methodName, Component.class);
                return (Component) method.invoke(component, other);
            } catch (Throwable ignored) {}
        }
        for (Method m : clazz.getMethods()) {
            if (m.getParameterCount() == 1 && Component.class.isAssignableFrom(m.getParameterTypes()[0]) && Component.class.isAssignableFrom(m.getReturnType())) {
                try {
                    m.setAccessible(true);
                    return (Component) m.invoke(component, other);
                } catch (Throwable ignored) {}
            }
        }
        E4allClient.LOGGER.warn("Could not locate any way to append Component, returning fallback: {} + {}", component, other);
        return component;
    }

    public static Component literal(String text) {
        try {
            return Component.literal(text);
        } catch (Throwable ignored) {}
        // Try 1.18-and-older-style TextComponent initialization first
        for (String className : LITERAL_CLASS_NAMES) {
            try {
                Class<?> clazz = Class.forName(className);
                Constructor<?> constructor = clazz.getConstructor(String.class);
                return (Component) constructor.newInstance(text);
            } catch (Throwable ignored) {}
        }
        Class<Component> clazz = Component.class;
        for (String methodName : LITERAL_METHOD_NAMES) {
            try {
                Method method = clazz.getMethod(methodName, String.class);
                return (Component) method.invoke(null, text);
            } catch (Throwable ignored) {}
        }
        for (Method m : clazz.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class && Component.class.isAssignableFrom(m.getReturnType())) {
                try {
                    return (Component) m.invoke(null, text);
                } catch (Throwable ignored) {}
            }
        }
        throw new RuntimeException("Could not locate any way to make a literal Component!");
    }

    public static Component translatable(String text, Object... args) {
        try {
            return Component.translatable(text, args);
        } catch (Throwable ignored) {}
        // Try 1.18-and-older-style TranslatableComponent initialization first
        for (String className : TRANSLATABLE_CLASS_NAMES) {
            try {
                Class<?> clazz = Class.forName(className);
                Constructor<?> constructor = clazz.getConstructor(String.class, Object[].class);
                return (Component) constructor.newInstance(text, args);
            } catch (Throwable ignored) {}
        }
        Class<Component> clazz = Component.class;
        for (String methodName : TRANSLATABLE_METHOD_NAMES) {
            try {
                Method method = clazz.getMethod(methodName, String.class, Object[].class);
                return (Component) method.invoke(null, text, args);
            } catch (Throwable ignored) {}
        }
        for (Method m : clazz.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 2 && m.getParameterTypes()[0] == String.class && m.getParameterTypes()[1] == Object[].class && Component.class.isAssignableFrom(m.getReturnType())) {
                try {
                    return (Component) m.invoke(null, text, args);
                } catch (Throwable ignored) {}
            }
        }
        return literal(text);
    }

    public static void sendSuccessToSource(CommandSourceStack source, Component message) {
        sendGenericMessageToSource(source, message, SUCCESS_METHOD_NAMES);
    }

    public static void sendFailureToSource(CommandSourceStack source, Component message) {
        sendGenericMessageToSource(source, message, FAILURE_METHOD_NAMES);
    }

    public static boolean hasPermission(CommandSourceStack source, int level) {
        if (source == null) return false;
        Class<?> clazz = source.getClass();
        for (String name : HAS_PERMISSION_METHOD_NAMES) {
            try {
                Method m = clazz.getMethod(name, int.class);
                return (boolean) m.invoke(source, level);
            } catch (Throwable ignored) {}
        }
        for (Method m : clazz.getMethods()) {
            if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class && m.getReturnType() == boolean.class) {
                try {
                    return (boolean) m.invoke(source, level);
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static void sendGenericMessageToSource(CommandSourceStack source, Component message, String[] methodNames) {
        try {
            source.sendSuccess(() -> message, false);
            return;
        } catch (Throwable ignored) {}
        try {
            source.sendSystemMessage(message);
            return;
        } catch (Throwable ignored) {}
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
        for (Method m : clazz.getMethods()) {
            if (m.getParameterCount() == 2 && Supplier.class.isAssignableFrom(m.getParameterTypes()[0]) && m.getParameterTypes()[1] == boolean.class) {
                try {
                    m.invoke(source, (Supplier<Component>) () -> message, true);
                    return;
                } catch (Throwable ignored) {}
            }
            if (m.getParameterCount() == 2 && Component.class.isAssignableFrom(m.getParameterTypes()[0]) && m.getParameterTypes()[1] == boolean.class) {
                try {
                    m.invoke(source, message, true);
                    return;
                } catch (Throwable ignored) {}
            }
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
                            if (pName == null) pName = getProfileName(profile);
                            if (pName != null && pName.equalsIgnoreCase(sName)) return true;
                        }
                        if (res instanceof com.mojang.authlib.GameProfile gp) {
                            UUID resId = getProfileId(gp);
                            UUID profId = getProfileId(profile);
                            if (resId != null && resId.equals(profId)) return true;
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

    public static UUID getProfileId(Object profile) {
        if (profile == null) return null;
        if (profile instanceof UUID uuid) return uuid;
        for (String mName : new String[]{"getId", "id", "getUUID", "uuid"}) {
            try {
                Method m = profile.getClass().getMethod(mName);
                Object res = m.invoke(profile);
                if (res instanceof UUID u) return u;
            } catch (Throwable ignored) {}
        }
        Class<?> c = profile.getClass();
        while (c != null && c != Object.class) {
            for (String fName : new String[]{"id", "uuid", "f_237305_", "field_47545"}) {
                try {
                    Field f = c.getDeclaredField(fName);
                    f.setAccessible(true);
                    Object res = f.get(profile);
                    if (res instanceof UUID u) return u;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    public static String getProfileName(Object profile) {
        if (profile == null) return null;
        if (profile instanceof String s) return s;
        for (String mName : new String[]{"getName", "name", "getUsername"}) {
            try {
                Method m = profile.getClass().getMethod(mName);
                Object res = m.invoke(profile);
                if (res instanceof String s) return s;
            } catch (Throwable ignored) {}
        }
        Class<?> c = profile.getClass();
        while (c != null && c != Object.class) {
            for (String fName : new String[]{"name", "username", "f_237304_", "field_47544"}) {
                try {
                    Field f = c.getDeclaredField(fName);
                    f.setAccessible(true);
                    Object res = f.get(profile);
                    if (res instanceof String s) return s;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    public static MinecraftServer getServer(ServerPlayer player) {
        if (player == null) return null;
        try {
            Method m = player.getClass().getMethod("getServer");
            return (MinecraftServer) m.invoke(player);
        } catch (Throwable ignored) {}
        try {
            return player.level().getServer();
        } catch (Throwable ignored) {}
        return null;
    }


    public static boolean isSingleplayerOwnerObj(MinecraftServer server, Object maybeProfile) {
        if (server == null || maybeProfile == null) return false;
        UUID targetId = getProfileId(maybeProfile);
        String targetName = getProfileName(maybeProfile);

        Class<?>[] serverClasses = new Class<?>[]{server.getClass(), MinecraftServer.class};
        for (Class<?> sc : serverClasses) {
            for (Method m : sc.getMethods()) {
                if (m.getParameterCount() != 1 || m.getReturnType() != boolean.class) continue;
                String mn = m.getName();
                boolean nameMatch = false;
                for (String name : IS_SINGLEPLAYER_OWNER_METHOD_NAMES) {
                    if (mn.equals(name)) {
                        nameMatch = true;
                        break;
                    }
                }
                if (!nameMatch && !mn.toLowerCase().contains("owner")) continue;

                Class<?> paramType = m.getParameterTypes()[0];
                if (paramType.isInstance(maybeProfile)) {
                    try {
                        if ((boolean) m.invoke(server, maybeProfile)) return true;
                    } catch (Throwable ignored) {}
                }
                if (maybeProfile instanceof com.mojang.authlib.GameProfile gp) {
                    Object adapted = adaptProfile(gp, paramType);
                    if (adapted != null) {
                        try {
                            if ((boolean) m.invoke(server, adapted)) return true;
                        } catch (Throwable ignored) {}
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
                            if (targetName != null && targetName.equalsIgnoreCase(sName)) return true;
                        }
                        if (res instanceof com.mojang.authlib.GameProfile gp) {
                            UUID resId = getProfileId(gp);
                            if (resId != null && resId.equals(targetId)) return true;
                            String resName = getProfileName(gp);
                            if (resName != null && resName.equalsIgnoreCase(targetName)) return true;
                        }
                        if (res != null) {
                            UUID resId = getProfileId(res);
                            if (resId != null && resId.equals(targetId)) return true;
                            String resName = getProfileName(res);
                            if (resName != null && resName.equalsIgnoreCase(targetName)) return true;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }

        try {
            Minecraft client = Minecraft.getInstance();
            if (client != null) {
                if (client.getUser() != null) {
                    try {
                        UUID clientUuid = client.getUser().getProfileId();
                        if (clientUuid != null && clientUuid.equals(targetId)) return true;
                    } catch (Throwable ignored) {}
                    try {
                        String clientName = client.getUser().getName();
                        if (clientName != null && clientName.equalsIgnoreCase(targetName)) return true;
                    } catch (Throwable ignored) {}
                    try {
                        Method m = client.getUser().getClass().getMethod("getGameProfile");
                        Object clientProfile = m.invoke(client.getUser());
                        if (clientProfile != null) {
                            UUID cId = getProfileId(clientProfile);
                            if (targetId != null && targetId.equals(cId)) return true;
                            String cName = getProfileName(clientProfile);
                            if (targetName != null && targetName.equalsIgnoreCase(cName)) return true;
                        }
                    } catch (Throwable ignored) {}
                }
                if (client.player != null) {
                    if (targetId != null && targetId.equals(client.player.getUUID())) return true;
                    if (targetName != null && targetName.equalsIgnoreCase(client.player.getScoreboardName())) return true;
                }
            }
        } catch (Throwable ignored) {}

        try {
            if (!server.isDedicatedServer()) {
                if (server.getPlayerList() != null && server.getPlayerList().getPlayerCount() <= 1) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

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
            return ctor.newInstance(getProfileId(profile), getProfileName(profile));
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Constructor<?> ctor = targetType.getConstructor(com.mojang.authlib.GameProfile.class);
            return ctor.newInstance(profile);
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
                if (Minecraft.getInstance().player != null) {
                    try {
                        Minecraft.getInstance().player.displayClientMessage(message, false);
                        return;
                    } catch (Throwable ignored) {}
                    try {
                        Minecraft.getInstance().player.sendSystemMessage(message);
                        return;
                    } catch (Throwable ignored) {}
                }
                if (Minecraft.getInstance().gui != null && Minecraft.getInstance().gui.getChat() != null) {
                    try {
                        Minecraft.getInstance().gui.getChat().addMessage(message);
                        return;
                    } catch (Throwable ignored) {}
                }

                Object chat = null;
                Object gui = Minecraft.getInstance().gui;
                if (gui != null) {
                    for (String mName : new String[]{"getChat", "m_93088_", "method_1743"}) {
                        try {
                            chat = gui.getClass().getMethod(mName).invoke(gui);
                            if (chat != null) break;
                        } catch (Throwable ignored) {}
                    }
                    if (chat == null) {
                        for (String fName : new String[]{"chat", "f_93005_", "field_2014", "hud"}) {
                            try {
                                Field f = gui.getClass().getDeclaredField(fName);
                                f.setAccessible(true);
                                Object obj = f.get(gui);
                                if (obj != null) {
                                    if (obj.getClass().getName().contains("Chat")) {
                                        chat = obj;
                                        break;
                                    } else {
                                        for (String mName : new String[]{"getChat", "m_93088_", "method_1743"}) {
                                            try {
                                                chat = obj.getClass().getMethod(mName).invoke(obj);
                                                if (chat != null) break;
                                            } catch (Throwable ignored) {}
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
                if (chat != null) {
                    for (String mName : new String[]{"addMessage", "addClientSystemMessage", "m_93055_", "m_240403_", "method_1812"}) {
                        try {
                            Method m = chat.getClass().getMethod(mName, Component.class);
                            m.invoke(chat, message);
                            return;
                        } catch (Throwable ignored) {}
                    }
                    for (Method m : chat.getClass().getMethods()) {
                        if (m.getParameterCount() == 1 && Component.class.isAssignableFrom(m.getParameterTypes()[0])) {
                            try {
                                m.invoke(chat, message);
                                return;
                            } catch (Throwable ignored) {}
                        }
                    }
                }
                E4allClient.LOGGER.warn("Could not dispatch message to client chat: {}", message.getString());
            } catch (Throwable t) {
                E4allClient.LOGGER.error("Failed to add message to client chat!", t);
            }
        });
    }

    public static Object createButton(int x, int y, int w, int h, Component text, Object onPress) {
        try {
            Method builderMethod = null;
            for (Method m : net.minecraft.client.gui.components.Button.class.getMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 2) {
                    if (Component.class.isAssignableFrom(m.getParameterTypes()[0]) && m.getParameterTypes()[1].isInstance(onPress)) {
                        builderMethod = m;
                        break;
                    }
                }
            }
            if (builderMethod == null) {
                for (Method m : net.minecraft.client.gui.components.Button.class.getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 2) {
                        if (Component.class.isAssignableFrom(m.getParameterTypes()[0]) && m.getParameterTypes()[1].isInstance(onPress)) {
                            builderMethod = m;
                            break;
                        }
                    }
                }
            }
            if (builderMethod == null) {
                for (Method m : net.minecraft.client.gui.components.Button.class.getMethods()) {
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 2) {
                        if (Component.class.isAssignableFrom(m.getParameterTypes()[0]) && m.getParameterTypes()[1].isInterface()) {
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
                            if (n.equals("pos") || n.equals("position") || n.equals("method_46430") || n.equals("m_253074_")) posMethod = m;
                            else if (n.equals("size") || n.equals("dimensions") || n.equals("method_46432") || n.equals("method_46434") || n.equals("m_253018_")) sizeMethod = m;
                        }
                    }
                    if (posMethod != null) {
                        posMethod.setAccessible(true);
                        Object next = posMethod.invoke(builder, x, y);
                        if (next != null) builder = next;
                    }
                    if (sizeMethod != null) {
                        sizeMethod.setAccessible(true);
                        Object next = sizeMethod.invoke(builder, w, h);
                        if (next != null) builder = next;
                    } else {
                        for (Method m : builder.getClass().getMethods()) {
                            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1 && m.getParameterTypes()[0].equals(int.class)) {
                                String n = m.getName();
                                if (n.equals("width") || n.equals("method_46434") || n.equals("m_253086_")) {
                                    m.setAccessible(true);
                                    Object next = m.invoke(builder, w);
                                    if (next != null) builder = next;
                                    break;
                                }
                            }
                        }
                    }
                }
                
                for (Method m : builder.getClass().getMethods()) {
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0 && net.minecraft.client.gui.components.Button.class.isAssignableFrom(m.getReturnType())) {
                        m.setAccessible(true);
                        return m.invoke(builder);
                    }
                }
            }
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("[e4all] Mirror.createButton builder failed", t);
        }
        
        try {
            Class<?> onPressClass = null;
            for (Class<?> c : net.minecraft.client.gui.components.Button.class.getDeclaredClasses()) {
                if (c.isInterface()) {
                    onPressClass = c;
                    break;
                }
            }
            for (Constructor<?> ctor : net.minecraft.client.gui.components.Button.class.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 6
                        && params[0] == int.class && params[1] == int.class
                        && params[2] == int.class && params[3] == int.class
                        && Component.class.isAssignableFrom(params[4])
                        && (onPressClass == null || params[5].isAssignableFrom(onPressClass) || params[5].isInstance(onPress))) {
                    ctor.setAccessible(true);
                    return ctor.newInstance(x, y, w, h, text, onPress);
                }
            }
        } catch (Throwable ignored) {}
        
        return null;
    }
}


