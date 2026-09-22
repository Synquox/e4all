package link.e4all.voice;

// evaluate whether a late-completing dial can be adopted as a relay recovery
public final class DialRecoveryPolicy {

    public static final long RECOVERY_WINDOW_MS = 120_000L;

    public enum Decision {
        ADOPT,
        CLOSE_SUPERSEDED,
        CLOSE_SESSION_GONE,
        CLOSE_WINDOW_EXPIRED,
        CLOSE_STOPPED
    }

    private DialRecoveryPolicy() {}

    public static Decision evaluate(boolean stopped,
                                    boolean generationCurrent,
                                    boolean sessionAlive,
                                    boolean newerChannelActive,
                                    long elapsedSinceOfferMs) {
        return evaluate(stopped, generationCurrent, sessionAlive, newerChannelActive,
                elapsedSinceOfferMs, RECOVERY_WINDOW_MS);
    }

    public static Decision evaluate(boolean stopped,
                                    boolean generationCurrent,
                                    boolean sessionAlive,
                                    boolean newerChannelActive,
                                    long elapsedSinceOfferMs,
                                    long recoveryWindowMs) {
        if (stopped || !generationCurrent) return Decision.CLOSE_STOPPED;
        if (newerChannelActive) return Decision.CLOSE_SUPERSEDED;
        if (!sessionAlive) return Decision.CLOSE_SESSION_GONE;
        if (elapsedSinceOfferMs > recoveryWindowMs) return Decision.CLOSE_WINDOW_EXPIRED;
        return Decision.ADOPT;
    }
}
