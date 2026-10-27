package link.e4all.forge;

import io.netty.buffer.Unpooled;
import link.e4all.E4allClient;
import link.e4all.voice.VoiceControl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.EventNetworkChannel;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.event.network.CustomPayloadEvent;

public final class ForgeVoiceControlBridge {
    private static volatile EventNetworkChannel channel;

    private ForgeVoiceControlBridge() {}

    public static synchronized void register() {
        if (channel != null) return;

        EventNetworkChannel created = ChannelBuilder.named("e4all:voice")
                .acceptedVersions((status, version) -> true)
                .optional()
                .networkProtocolVersion(1)
                .eventNetworkChannel();

        created.addListener((CustomPayloadEvent event) -> handle(event));
        channel = created;
        E4allClient.LOGGER.info("e4all voice: Forge voice control channel registered");
    }

    private static void handle(CustomPayloadEvent event) {
        CustomPayloadEvent.Context context = event.getSource();
        context.setPacketHandled(true);

        FriendlyByteBuf payload = event.getPayload();
        if (payload == null) {
            E4allClient.LOGGER.warn("e4all voice: Forge control event had no payload");
            return;
        }
        byte[] data = new byte[payload.readableBytes()];
        payload.readBytes(data);
        if (data.length == 0) {
            E4allClient.LOGGER.warn("e4all voice: received an empty Forge control payload");
            return;
        }

        E4allClient.LOGGER.info("e4all voice: Forge {} control payload received (type=0x{}, bytes={})",
                context.isServerSide() ? "serverbound" : "clientbound",
                Integer.toHexString(data[0] & 0xFF), data.length);
        if (context.isServerSide()) {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                E4allClient.LOGGER.warn("e4all voice: Forge serverbound packet missing sender");
                return;
            }
            VoiceControl.handleServerPayloadDirect(sender, data);
        } else {
            VoiceControl.handleClientPayload(data);
        }
    }

    public static boolean sendServerbound(byte[] data) {
        EventNetworkChannel active = channel;
        if (active == null || data == null) return false;
        try {
            FriendlyByteBuf payload = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
            active.send(payload, PacketDistributor.SERVER.noArg());
            E4allClient.LOGGER.info("e4all voice: Forge sent serverbound control payload (type=0x{}, bytes={})",
                    data.length > 0 ? Integer.toHexString(data[0] & 0xFF) : "empty", data.length);
            return true;
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("e4all voice: Forge serverbound control send failed", t);
            return false;
        }
    }

    public static boolean sendClientbound(ServerPlayer player, byte[] data) {
        EventNetworkChannel active = channel;
        if (active == null || player == null || data == null) return false;
        try {
            FriendlyByteBuf payload = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
            active.send(payload, PacketDistributor.PLAYER.with(player));
            E4allClient.LOGGER.info("e4all voice: Forge sent clientbound control payload to {} (type=0x{}, bytes={})",
                    player.getScoreboardName(),
                    data.length > 0 ? Integer.toHexString(data[0] & 0xFF) : "empty", data.length);
            return true;
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("e4all voice: Forge clientbound control send failed", t);
            return false;
        }
    }
}
