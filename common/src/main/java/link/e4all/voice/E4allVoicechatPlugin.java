package link.e4all.voice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartingEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import link.e4all.E4allClient;
import link.e4all.Mirror;
import net.minecraft.server.level.ServerPlayer;

@ForgeVoicechatPlugin
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
        // PlayerConnectedEvent fires for each player connecting to voice
        try {
            registration.registerEvent(PlayerConnectedEvent.class, this::onPlayerConnected);
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("e4all voice: PlayerConnectedEvent registration failed (SVC version mismatch?)", t);
        }
        try {
            registration.registerEvent(VoicechatServerStoppedEvent.class, this::onServerStopped);
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("e4all voice: VoicechatServerStoppedEvent registration failed", t);
        }
        E4allClient.LOGGER.info("e4all voice events registered (server + client).");
    }

    private void onServerStart(VoicechatServerStartingEvent event) {
        if (link.e4all.AndroidDetector.isAndroid()) {
            // Android hosts have no Dialtone; SVC keeps its default UDP socket so
            // same-network SVC clients still work.
            E4allClient.LOGGER.info("e4all: Android detected - using default SVC server UDP socket.");
            return;
        }
        if (!link.e4all.Config.INSTANCE.voiceP2PEnabled.value()) {
            E4allClient.LOGGER.info("e4all voice: P2P voice disabled in config - using default SVC server socket.");
            return;
        }
        // Hybrid socket: plain UDP for SVC clients without e4all + P2P stream
        // routing (SyntheticAddress) for guests connected via Dialtone voice.
        event.setSocketImplementation(new P2PHostVoicechatSocket(VoiceConnectionManager.INSTANCE));
    }

    private void onServerStopped(VoicechatServerStoppedEvent event) {
        HostVoiceNegotiator.INSTANCE.stop();
        VoiceConnectionManager.INSTANCE.closeAll();
    }

    private void onPlayerConnected(PlayerConnectedEvent event) {
        // When a player connects to SVC, check if they lack e4all.
        // The voice control channel requires e4all on both sides.
        // If the player has e4all, they will send HELLO via the control channel.
        // If they DON'T have e4all, no HELLO arrives. We use a timeout to detect
        // this and inform the host. For now, just log the connection.
        E4allClient.LOGGER.info("e4all voice: player connected to SVC voice: {}",
                event.getConnection().getPlayer().getUuid());
    }

    private void onClientInit(ClientVoicechatInitializationEvent event) {
        if (link.e4all.AndroidDetector.isAndroid()) {
            // Android: skip Dialtone socket, let SVC use its default UDP socket.
            E4allClient.LOGGER.info("e4all: Android detected - using default SVC UDP socket.");
            return;
        }
        if (!shouldNegotiateVoice()) {
            E4allClient.LOGGER.debug("Not connecting to an e4all host. Using default SVC socket.");
            return;
        }
        // Kick off negotiation (HELLO) and route SVC voice traffic through the
        // Dialtone voice channel that the OFFER/RESULT exchange will set up.
        ClientVoiceNegotiator.INSTANCE.sendHello();
        event.setSocketImplementation(new DialtoneClientVoicechatSocket());
        E4allClient.LOGGER.info("e4all voice: e4all host detected, negotiating voice via control channel");
    }

    // true when joined to an e4all host and not hosting locally
    private static boolean shouldNegotiateVoice() {
        try {
            if (!link.e4all.Config.INSTANCE.voiceP2PEnabled.value()) {
                return false;
            }
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.hasSingleplayerServer()) {
                // We are hosting; SVC client talks to our own SVC server via UDP.
                return false;
            }
            if (VoiceBridge.hasPendingDialtoneTicket()) {
                return true;
            }
            return mc.getConnection() != null
                    && mc.getConnection().getConnection() != null
                    && mc.getConnection().getConnection().getRemoteAddress() instanceof link.e4all.dialtone.DialtoneAddress;
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("e4all voice: could not evaluate voice negotiation conditions", t);
            return false;
        }
    }
}
