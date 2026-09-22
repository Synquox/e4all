package link.e4all.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DialRecoveryPolicyTest {

    @Test
    void lateRelayDialCompleting110sAfterOfferIsAdopted_notDiscarded() {
        DialRecoveryPolicy.Decision d = DialRecoveryPolicy.evaluate(
                false, true, true, false, 110_000L);
        assertEquals(DialRecoveryPolicy.Decision.ADOPT, d);
    }

    @Test
    void lateDialExactlyAtWindowBoundaryIsAdopted() {
        DialRecoveryPolicy.Decision d = DialRecoveryPolicy.evaluate(
                false, true, true, false, DialRecoveryPolicy.RECOVERY_WINDOW_MS);
        assertEquals(DialRecoveryPolicy.Decision.ADOPT, d);
    }

    @Test
    void lateDialBeyondWindowIsExpired() {
        DialRecoveryPolicy.Decision d = DialRecoveryPolicy.evaluate(
                false, true, true, false, DialRecoveryPolicy.RECOVERY_WINDOW_MS + 1);
        assertEquals(DialRecoveryPolicy.Decision.CLOSE_WINDOW_EXPIRED, d);
    }

    @Test
    void lateDialIsSupersededWhenNewerChannelIsActive() {
        DialRecoveryPolicy.Decision d = DialRecoveryPolicy.evaluate(
                false, true, true, true, 30_000L);
        assertEquals(DialRecoveryPolicy.Decision.CLOSE_SUPERSEDED, d);
    }

    @Test
    void lateDialIsClosedWhenGameSessionIsGone() {
        DialRecoveryPolicy.Decision d = DialRecoveryPolicy.evaluate(
                false, true, false, false, 30_000L);
        assertEquals(DialRecoveryPolicy.Decision.CLOSE_SESSION_GONE, d);
    }

    @Test
    void lateDialIsClosedAfterStop() {
        DialRecoveryPolicy.Decision stoppedDial = DialRecoveryPolicy.evaluate(
                true, true, true, false, 30_000L);
        assertEquals(DialRecoveryPolicy.Decision.CLOSE_STOPPED, stoppedDial);
    }

    @Test
    void lateDialFromStaleGenerationIsClosed() {
        DialRecoveryPolicy.Decision d = DialRecoveryPolicy.evaluate(
                false, false, true, false, 30_000L);
        assertEquals(DialRecoveryPolicy.Decision.CLOSE_STOPPED, d);
    }

    @Test
    void emptyCandidateOfferTimeoutStillRecoversViaLateDial() {
        DialRecoveryPolicy.Decision zeroCandidates =
                DialRecoveryPolicy.evaluate(false, true, true, false, 90_000L);
        assertEquals(DialRecoveryPolicy.Decision.ADOPT, zeroCandidates);
    }

    @Test
    void recoveryWindowComfortablyExceedsObservedDialTime() {
        org.junit.jupiter.api.Assertions.assertTrue(DialRecoveryPolicy.RECOVERY_WINDOW_MS > 110_000L);
    }
}
