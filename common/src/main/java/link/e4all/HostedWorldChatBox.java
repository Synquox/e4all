package link.e4all;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public final class HostedWorldChatBox {
    private HostedWorldChatBox() {}

    public static Component create(String domain, int port, boolean offline, boolean hide) {
        String cleanDomain = domain != null ? domain : "localhost";
        String copyTarget = cleanDomain;
        String displayDomain = hide ? "hidden" : cleanDomain;
        int displayPort = port > 0 ? port : 25565;

        Component domainComp = Mirror.withStyle(
                Mirror.literal(displayDomain),
                s -> s.withColor(ChatFormatting.GREEN)
                      .withUnderlined(true)
                      .withClickEvent(Mirror.copyToClipboard(copyTarget))
                      .withHoverEvent(Mirror.showText(Mirror.literal("Click to copy domain")))
        );

        Component copyBtn = Mirror.withStyle(
                Mirror.literal("[Copy]"),
                s -> s.withColor(ChatFormatting.YELLOW)
                      .withClickEvent(Mirror.copyToClipboard(copyTarget))
                      .withHoverEvent(Mirror.showText(Mirror.literal("Copy domain to clipboard")))
        );

        Component stopBtn = Mirror.withStyle(
                Mirror.literal("[Stop]"),
                s -> s.withColor(ChatFormatting.RED)
                      .withClickEvent(Mirror.runCommand("/e4all stop"))
                      .withHoverEvent(Mirror.showText(Mirror.literal("Stop hosting (/e4all stop)")))
        );

        Component msg = Mirror.literal("§6[e4all]§r World hosted!");
        msg = Mirror.append(msg, Mirror.literal("\n§7Domain: "));
        msg = Mirror.append(msg, domainComp);
        msg = Mirror.append(msg, Mirror.literal("\n§7Port: §f" + displayPort));
        msg = Mirror.append(msg, Mirror.literal("\n"));
        msg = Mirror.append(msg, copyBtn);
        msg = Mirror.append(msg, Mirror.literal(" "));
        msg = Mirror.append(msg, stopBtn);

        if (offline) {
            msg = Mirror.append(msg, Mirror.literal(
                    "\n§e⚠ Offline mode: cracked clients can join."
            ));
        }

        return msg;
    }

    public static boolean isPublishStarted(Component component) {
        if (component == null) return false;
        String key = DisconnectScreens.translationKeyOf(component);
        if (key != null && (key.equals("commands.publish.started") || key.equals("commands.publish.success") || key.startsWith("commands.publish."))) {
            return true;
        }
        for (Component sibling : component.getSiblings()) {
            String sibKey = DisconnectScreens.translationKeyOf(sibling);
            if (sibKey != null && (sibKey.equals("commands.publish.started") || sibKey.equals("commands.publish.success") || sibKey.startsWith("commands.publish."))) {
                return true;
            }
        }
        String text = component.getString();
        if (text == null) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("local game hosted on port") || lower.contains("hosted on port") || lower.startsWith("this world is now open to lan")) {
            return true;
        }
        if (lower.contains("world successfully hosted") || lower.contains("world hosted successfully")) {
            return true;
        }
        if (lower.startsWith("lokales spiel auf port") || lower.contains("gehostet auf port") || lower.contains("erfolgreich im lan freigegeben")) {
            return true;
        }
        if (lower.startsWith("partie locale") || lower.startsWith("partida local")) {
            return true;
        }
        return false;
    }
}

