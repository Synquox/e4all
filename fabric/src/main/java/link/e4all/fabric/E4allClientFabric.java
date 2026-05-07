package link.e4all.fabric;

import link.e4all.E4allClient;
import net.fabricmc.api.ModInitializer;

public class E4allClientFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        E4allClient.init();
        try {
            net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, ignored, ignored2) -> E4allClient.registerCommands(dispatcher));
        } catch (NoClassDefFoundError e) {
            net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback.EVENT.register((dispatcher, ignored) -> E4allClient.registerCommands(dispatcher));
        }
    }
}



