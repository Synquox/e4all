package link.e4all;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
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
@ChannelHandler.Sharable
public class VoiceChatBridgeInitializer extends ChannelInboundHandlerAdapter {
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
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        // Add the original handler FIRST, so it initializes before we do our check
        ctx.pipeline().addBefore(ctx.name(), null, originalHandler);

        // If the channel is already registered, the original handler has executed its initialization
        if (ctx.channel().isRegistered()) {
            addBridgeHandler(ctx);
        }
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx);
        // By now the original handler has processed channelRegistered and populated the pipeline
        addBridgeHandler(ctx);
    }

    private void addBridgeHandler(ChannelHandlerContext ctx) {
        if (ctx.pipeline().context(this) == null) {
            return; // We have already been removed
        }

        Channel ch = ctx.channel();
        if (Config.INSTANCE.voiceChatBridgeEnabled.value()) {
            try {
                if (ch.pipeline().get("packet_handler") != null) {
                    ch.pipeline().addBefore("packet_handler", "e4all_voicebridge", new VoiceChatBridgeHandler(isServerSide));
                    LOGGER.debug("Added voice chat bridge handler to {} pipeline", isServerSide ? "server" : "client");
                } else {
                    ch.pipeline().addLast("e4all_voicebridge", new VoiceChatBridgeHandler(isServerSide));
                    LOGGER.debug("Added voice chat bridge handler to {} pipeline (at end, packet_handler not found)", isServerSide ? "server" : "client");
                }
            } catch (Exception e) {
                LOGGER.debug("Could not add voice chat bridge handler", e);
            }
        }

        // We only need to run once, so remove ourselves from the pipeline
        ctx.pipeline().remove(this);
    }
}
