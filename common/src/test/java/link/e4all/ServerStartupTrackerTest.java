package link.e4all;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerStartupTrackerTest {

    @Test
    void alreadyReadyWorldReturnsImmediatelyEvenWithZeroTimeout() {
        assertTrue(ServerStartupTracker.waitForReady(() -> true, () -> false, () -> true, 0L, 50L));
    }

    @Test
    void holdsTheLoginUntilTheWorldStartsTicking() {
        AtomicInteger polls = new AtomicInteger();
        boolean ready = ServerStartupTracker.waitForReady(
                () -> polls.incrementAndGet() >= 4,
                () -> false,
                () -> true,
                2000L, 5L);
        assertTrue(ready);
        assertTrue(polls.get() >= 4, "the wait must poll until the world ticks");
    }

    @Test
    void givesUpWhenTheWorldNeverStartsTicking() {
        assertFalse(ServerStartupTracker.waitForReady(() -> false, () -> false, () -> true, 60L, 10L));
    }

    @Test
    void stoppingWorldIsNeverWaitedFor() {
        assertTimeoutPreemptively(Duration.ofMillis(750), () ->
                assertFalse(ServerStartupTracker.waitForReady(() -> true, () -> true, () -> true, 60_000L, 10L)));
    }

    @Test
    void aWorldThatLostTheTunnelIsNeverAdmitted() {
        assertFalse(ServerStartupTracker.waitForReady(() -> true, () -> false, () -> false, 60L, 10L));
    }

    @Test
    void throwingReadinessPredicateIsTreatedAsNotReadyInsteadOfEscaping() {
        assertFalse(ServerStartupTracker.waitForReady(
                () -> { throw new IllegalStateException("boom"); },
                () -> false,
                () -> true,
                60L, 10L));
    }

    @Test
    void throwingStoppingPredicateDoesNotBlockAnAlreadyReadyWorld() {
        assertTrue(ServerStartupTracker.waitForReady(
                () -> true,
                () -> { throw new IllegalStateException("boom"); },
                () -> true,
                60L, 10L));
    }

    @Test
    void throwingOwnershipPredicateIsTreatedAsNotOwned() {
        assertFalse(ServerStartupTracker.waitForReady(
                () -> true,
                () -> false,
                () -> { throw new IllegalStateException("boom"); },
                60L, 10L));
    }

    @Test
    void zeroTimeoutDoesNotWaitAtAll() {
        assertTimeoutPreemptively(Duration.ofMillis(500), () ->
                assertFalse(ServerStartupTracker.waitForReady(() -> false, () -> false, () -> true, 0L, 50L)));
    }

    @Test
    void interruptedWaitReturnsFalseAndKeepsTheInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertFalse(ServerStartupTracker.waitForReady(() -> false, () -> false, () -> true, 5000L, 10L));
            assertTrue(Thread.interrupted(), "the interrupt flag must survive the wait");
        } finally {
            Thread.interrupted();
        }
    }
}
