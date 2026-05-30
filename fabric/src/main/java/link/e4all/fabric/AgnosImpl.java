package link.e4all.fabric;

import link.e4all.E4allClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public class AgnosImpl {
    public static boolean isClient() {
        return FabricLoader.getInstance().getEnvironmentType().equals(EnvType.CLIENT);
    }

    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    public static Path jarPath() {
        return FabricLoader.getInstance().getModContainer(E4allClient.MOD_ID).get().getOrigin().getPaths().get(0);
    }
}
