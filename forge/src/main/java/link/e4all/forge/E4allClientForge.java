package link.e4all.forge;

import link.e4all.E4allClient;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

@Mod(E4allClient.MOD_ID)
public class E4allClientForge {
    public E4allClientForge() {
        E4allClient.init();
        try {
            ForgeVoiceControlBridge.register();
        } catch (Throwable t) {
            E4allClient.LOGGER.error("[e4all] Failed to register Forge voice control channel", t);
        }
        try {
            registerRegisterCommandsListener();
        } catch (Throwable t) {
            E4allClient.LOGGER.error("[e4all] Failed to register Forge event listener", t);
        }
    }

    private static void registerRegisterCommandsListener() throws Exception {
        Consumer<RegisterCommandsEvent> handler = event -> E4allClient.registerCommands(event.getDispatcher());
        try {
            Field busField = RegisterCommandsEvent.class.getField("BUS");
            Object eventBus = busField.get(null);
            if (eventBus != null) {
                Method addListener = eventBus.getClass().getMethod("addListener", Consumer.class);
                addListener.invoke(eventBus, handler);
                return;
            }
        } catch (NoSuchFieldException ignored) {}

        Field eventBusField = MinecraftForge.class.getField("EVENT_BUS");
        Object bus = eventBusField.get(null);
        Class<?> priorityClass = Class.forName("net.minecraftforge.eventbus.api.EventPriority");
        Object normal = priorityClass.getField("NORMAL").get(null);
        Method addListener = bus.getClass().getMethod(
                "addListener", priorityClass, boolean.class, Class.class, Consumer.class);
        addListener.invoke(bus, normal, false, RegisterCommandsEvent.class, handler);
    }
}



