package link.e4all.voice;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceFramingTest {

    @Test
    void magicIsRecognizedAndMinecraftTrafficIsNot() {
        byte[] voiceHeader = new byte[VoiceFraming.HEADER_LEN];
        System.arraycopy(VoiceFraming.MAGIC, 0, voiceHeader, 0, VoiceFraming.MAGIC.length);
        voiceHeader[VoiceFraming.MAGIC.length] = VoiceFraming.VERSION;
        assertTrue(VoiceFraming.matchesMagic(voiceHeader, voiceHeader.length));

        byte[] gameTraffic = {0x0E, 0x00, 0x03, 0x09};
        assertFalse(VoiceFraming.matchesMagic(gameTraffic, gameTraffic.length));
    }

    @Test
    void handshakeCarriesThePlayerUuid() {
        UUID uuid = UUID.fromString("e1a4029b-730a-320b-9465-1f8ff5145f9a");
        byte[] handshake = VoiceFraming.createHandshake(uuid);
        assertEquals(1 + 16, handshake.length);
        assertEquals(VoiceFraming.MSG_HANDSHAKE, handshake[0]);
        assertEquals(uuid, VoiceFraming.readUuid(handshake, 1));
    }

    @Test
    void pingTimestampRoundTrips() {
        long now = System.nanoTime();
        byte[] ping = VoiceFraming.createPing(now);
        assertEquals(VoiceFraming.MSG_PING, ping[0]);
        assertEquals(now, VoiceFraming.readTimestamp(ping, 1));
    }
}
