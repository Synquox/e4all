package link.e4all.neoforge;

import link.e4all.E4allClient;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod(E4allClient.MOD_ID)
public class E4allClientNeoForge {
    public E4allClientNeoForge() {
        E4allClient.init();
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommandEvent(RegisterCommandsEvent event) {
        E4allClient.registerCommands(event.getDispatcher());
    }
}



