package link.e4all;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps the Minecraft server's channel initializer to add the
 * VoiceChatBridgeHandler to each tunneled connection's pipeline.
 *
 * The original handler sets up the full Minecraft protocol pipeline.
 * After that, we append our handler which intercepts SVC custom payload
 * packets and bridges UDP voice traffic.
 */
public class VoiceChatBridgeInitializer extends ChannelInitializer<Channel> {
    private static final Logger LOGGER = LoggerFactory.getLogger("e4all-voicebridge");

    private final ChannelHandler originalHandler;
    private final boolean isServerSide;

    public VoiceChatBridgeInitializer(ChannelHandler originalHandler, boolean isServerSide) {
        this.originalHandler = originalHandler;
        this.isServerSide = isServerSide;
    }

    public ChannelHandler getOriginalHandler() {
        return originalHandler;
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        // First, apply the original Minecraft pipeline initializer
        ch.pipeline().addLast(originalHandler);

        // Then, if the voice chat bridge is enabled, add our handler
        if (Config.INSTANCE.voiceChatBridgeEnabled.value()) {
            try {
                if (ch.isActive() || ch.isOpen()) {
                    if (ch.pipeline().get("packet_handler") != null) {
                        ch.pipeline().addBefore("packet_handler", "e4all_voicebridge", new VoiceChatBridgeHandler(isServerSide));
                    } else {
                        ch.pipeline().addLast("e4all_voicebridge", new VoiceChatBridgeHandler(isServerSide));
                    }
                    LOGGER.debug("Added voice chat bridge handler to {} pipeline", isServerSide ? "server" : "client");
                }
            } catch (Exception e) {
                LOGGER.debug("Could not add voice chat bridge handler", e);
            }
        }
    }
}
