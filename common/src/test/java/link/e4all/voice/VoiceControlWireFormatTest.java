package link.e4all.voice;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceControlWireFormatTest {

    private static DataInputStream parse(byte[] data) {
        return new DataInputStream(new ByteArrayInputStream(data));
    }

    @Test
    void offerWithZeroCandidatesEncodesCleanly() throws Exception {
        byte[] data = VoiceControl.encodeOffer(
                VoiceControl.TRANSPORT_DIALTONE, "endpointabidglxigo32", List.of(), null);
        DataInputStream in = parse(data);
        assertEquals(VoiceControl.MSG_OFFER, in.readByte());
        assertEquals(VoiceControl.TRANSPORT_DIALTONE, in.readByte());
        assertEquals("endpointabidglxigo32", in.readUTF());
        assertEquals(0, in.readUnsignedShort()); // candidates=0 must be a legal, empty list
        assertEquals(-1, in.readByte());         // failure=none
        assertEquals(0, in.available());
    }

    @Test
    void offerEncodesFailureReasonWhenTransportUnavailable() throws Exception {
        byte[] data = VoiceControl.encodeOffer(
                VoiceControl.TRANSPORT_NONE, "", List.of(), VoiceFailure.TIMEOUT);
        DataInputStream in = parse(data);
        assertEquals(VoiceControl.MSG_OFFER, in.readByte());
        assertEquals(VoiceControl.TRANSPORT_NONE, in.readByte());
        assertEquals("", in.readUTF());
        assertEquals(0, in.readUnsignedShort());
        assertEquals(VoiceFailure.TIMEOUT.ordinal, in.readByte());
    }

    @Test
    void helloEncodesVoiceClientFlag() throws Exception {
        DataInputStream yes = parse(VoiceControl.encodeHello(true));
        assertEquals(VoiceControl.MSG_HELLO, yes.readByte());
        assertTrue(yes.readBoolean());

        DataInputStream no = parse(VoiceControl.encodeHello(false));
        assertEquals(VoiceControl.MSG_HELLO, no.readByte());
        assertEquals(false, no.readBoolean());
    }

    @Test
    void resultRoundTripsThroughServerDecodeFields() throws Exception {
        byte[] data = VoiceControl.encodeResult(
                true, VoiceControl.TRANSPORT_DIALTONE, 42, null, List.of());
        DataInputStream in = parse(data);
        assertEquals(VoiceControl.MSG_RESULT, in.readByte());
        assertTrue(in.readBoolean());
        assertEquals(VoiceControl.TRANSPORT_DIALTONE, in.readByte());
        assertEquals(42, in.readInt());
        assertEquals(-1, in.readByte());
        assertEquals(0, in.readUnsignedShort());
    }

    @Test
    void timeoutResultDecodesToTheTimeoutFailure() throws Exception {
        byte[] data = VoiceControl.encodeResult(
                false, VoiceControl.TRANSPORT_DIALTONE, 0, VoiceFailure.TIMEOUT, List.of());
        DataInputStream in = parse(data);
        assertEquals(VoiceControl.MSG_RESULT, in.readByte());
        assertEquals(false, in.readBoolean());
        assertEquals(VoiceControl.TRANSPORT_DIALTONE, in.readByte());
        assertEquals(0, in.readInt());
        int failOrd = in.readByte();
        assertEquals(VoiceFailure.TIMEOUT, VoiceFailure.fromOrdinal(failOrd));
        in.readUnsignedShort();
    }

    @Test
    void readyRecoveryMessageCarriesSuccess() throws Exception {
        byte[] data = VoiceControl.encodeReady(true, VoiceControl.TRANSPORT_DIALTONE, 42, null);
        DataInputStream in = parse(data);
        assertEquals(VoiceControl.MSG_READY, in.readByte());
        assertTrue(in.readBoolean());
        assertEquals(VoiceControl.TRANSPORT_DIALTONE, in.readByte());
        assertEquals(42, in.readInt());
        assertEquals(-1, in.readByte());
    }

    @Test
    void offerAndResultCandidateListsAreSymmetricAndEmpty() throws Exception {
        byte[] offer = VoiceControl.encodeOffer(
                VoiceControl.TRANSPORT_DIALTONE, "ticket", List.of(), null);
        byte[] result = VoiceControl.encodeResult(
                true, VoiceControl.TRANSPORT_DIALTONE, 0, null, new ArrayList<>());
        try (DataInputStream o = parse(offer); DataInputStream r = parse(result)) {
            o.readByte(); o.readByte(); o.readUTF();
            assertEquals(0, o.readUnsignedShort());
            r.readByte(); r.readBoolean(); r.readByte(); r.readInt(); r.readByte();
            assertEquals(0, r.readUnsignedShort());
        }
    }
}
