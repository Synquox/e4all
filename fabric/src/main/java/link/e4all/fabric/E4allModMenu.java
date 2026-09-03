package link.e4all.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import link.e4all.E4allClient;
import net.minecraft.client.gui.screens.Screen;

public class E4allModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<E4allConfigScreen> getModConfigScreenFactory() {
        return parent -> {
            try {
                return new E4allConfigScreen(parent);
            } catch (Throwable t) {
                E4allClient.LOGGER.error("e4all: failed to open config screen", t);
                return null;
            }
        };
    }

    // legacy hook for mod menu < 3.0
    public Screen getModConfigScreen() {
        try {
            return new E4allConfigScreen(null);
        } catch (Throwable t) {
            E4allClient.LOGGER.error("e4all: failed to open config screen", t);
            return null;
        }
    }
}
