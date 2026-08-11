package link.e4all;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class PoisonPill {
    public static boolean checkMotw() {
        String osName = System.getProperty("os.name");
        if (osName != null && osName.startsWith("Windows")) {
            return checkWindowsMotw();
        }
        return true;
    }

    private static boolean checkWindowsMotw() {
        var path = Agnos.jarPath();
        var motwPath = path + ":Zone.Identifier";
        boolean motwFileExists = new java.io.File(motwPath).exists();
        boolean sawHostUrlLine = false;
        try (FileInputStream inputStream = new FileInputStream(motwPath)) {
            String hidden = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            for (var line : hidden.split("\n")) {
                if (!line.startsWith("HostUrl=")) continue;
                sawHostUrlLine = true;
                if (!isAllowedHostUrl(line)) {
                    return false;
                }
            }
        } catch (IOException ignored) {
            return true;
        }
        if (motwFileExists && !sawHostUrlLine) {
            return false;
        }
        return true;
    }

    private static boolean isAllowedHostUrl(String line) {
        return line.startsWith("HostUrl=https://mediafilez.forgecdn.net/")
                || line.startsWith("HostUrl=https://cdn.modrinth.com/")
                || line.startsWith("HostUrl=https://modrinth.com/")
                || line.startsWith("HostUrl=https://www.curseforge.com/")
                || line.startsWith("HostUrl=https://legacy.curseforge.com/")
                || line.startsWith("HostUrl=https://edge.forgecdn.net/")
                || line.startsWith("HostUrl=https://maven.is-quite.gay/")
                || line.startsWith("HostUrl=https://github.com/");
    }
}

