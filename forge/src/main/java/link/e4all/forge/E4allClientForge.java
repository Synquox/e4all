package link.e4all.forge;

import link.e4all.E4allClient;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(E4allClient.MOD_ID)
public class E4allClientForge {
    public E4allClientForge() {
        E4allClient.init();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommandEvent(RegisterCommandsEvent event) {
        E4allClient.registerCommands(event.getDispatcher());
    }
}



