package link.e4all.voice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartingEvent;
import link.e4all.E4allClient;

public final class E4allVoicechatPlugin implements VoicechatPlugin {
    @Override
    public String getPluginId() {
        return E4allClient.MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        E4allClient.LOGGER.info("SVC Plugin initialized for e4all Voice Chat.");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartingEvent.class, this::onServerStart);
        registration.registerEvent(ClientVoicechatInitializationEvent.class, this::onClientInit);
        E4allClient.LOGGER.info("e4all voice events registered (server + client).");
    }

    private void onServerStart(VoicechatServerStartingEvent event) {
        E4allClient.LOGGER.info("Installing RelayVoicechatSocket (hybrid UDP + relay).");
        RelayVoicechatSocket socket = new RelayVoicechatSocket(VoiceConnectionManager.INSTANCE);
        event.setSocketImplementation(socket);
    }

    private void onClientInit(ClientVoicechatInitializationEvent event) {
        if (!RelayClientVoicechatSocket.shouldUseCustomSocket()) {
            E4allClient.LOGGER.debug("Not connecting to an e4all host. Using default SVC socket.");
            return;
        }
        try {
            E4allClient.LOGGER.info("ClientVoicechatInitializationEvent fired — installing RelayClientVoicechatSocket.");
            RelayClientVoicechatSocket socket = new RelayClientVoicechatSocket();
            event.setSocketImplementation(socket);
        } catch (Exception e) {
            E4allClient.LOGGER.error("Failed to inject client voice socket. Falling back to UDP.", e);
        }
    }
}
