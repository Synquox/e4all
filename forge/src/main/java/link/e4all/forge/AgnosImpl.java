package link.e4all.forge;

import link.e4all.E4allClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;

import java.lang.reflect.Method;
import java.nio.file.Path;

public class AgnosImpl {
    public static boolean isClient() {
        try {
            return FMLLoader.getDist().equals(Dist.CLIENT);
        } catch (Throwable ignored) {}
        try {
            Class<?> envClass = Class.forName("net.minecraftforge.fml.loading.FMLEnvironment");
            Object dist = envClass.getField("dist").get(null);
            return dist.equals(Dist.CLIENT);
        } catch (Throwable ignored) {}
        throw new RuntimeException("Can't determine dist!");
    }

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    public static Path jarPath() {
        try {
            Class<?> lml = Class.forName("net.minecraftforge.fml.loading.LoadingModList");
            Object modFile = null;
            try {
                modFile = lml.getMethod("getModFileById", String.class).invoke(null, E4allClient.MOD_ID);
            } catch (Throwable ignored) {}
            if (modFile == null) {
                Object list = null;
                try {
                    list = lml.getMethod("get").invoke(null);
                } catch (Throwable ignored) {
                    Class<?> loader = Class.forName("net.minecraftforge.fml.loading.FMLLoader");
                    list = loader.getMethod("getLoadingModList").invoke(null);
                }
                if (list != null) {
                    modFile = list.getClass().getMethod("getModFileById", String.class).invoke(list, E4allClient.MOD_ID);
                }
            }
            if (modFile != null) {
                Object file = modFile.getClass().getMethod("getFile").invoke(modFile);
                return (Path) file.getClass().getMethod("getFilePath").invoke(file);
            }
        } catch (Throwable ignored) {}
        try {
            return Path.of(AgnosImpl.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Throwable ignored) {}
        throw new RuntimeException("Can't determine Forge jar path!");
    }
}


