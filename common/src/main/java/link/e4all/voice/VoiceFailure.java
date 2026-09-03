package link.e4all.voice;

public enum VoiceFailure {
    PEER_LACKS_E4ALL(0, "peer_lacks_e4all", "text.e4all_minecraft.voice.fail.peerLacksE4all"),
    HOST_LACKS_SVC(1, "host_lacks_svc", "text.e4all_minecraft.voice.fail.hostLacksSvc"),
    DIALTONE_RELAY_ONLY(2, "dialtone_relay_only", "text.e4all_minecraft.voice.fail.dialtoneRelayOnly"),
    TIMEOUT(3, "timeout", "text.e4all_minecraft.voice.fail.timeout"),
    NAT_BLOCKED(4, "nat_blocked", "text.e4all_minecraft.voice.fail.natBlocked"),
    NO_SHARED_ADDRESS_FAMILY(5, "no_shared_af", "text.e4all_minecraft.voice.fail.noSharedAf"),
    UPNP_DENIED(6, "upnp_denied", "text.e4all_minecraft.voice.fail.upnpDenied"),
    FIREWALL(7, "firewall", "text.e4all_minecraft.voice.fail.firewall"),
    TRANSPORT_UNAVAILABLE(8, "transport_unavailable", "text.e4all_minecraft.voice.fail.transportUnavailable");

    public final int ordinal;
    public final String logTag;
    public final String langKey;

    VoiceFailure(int ordinal, String logTag, String langKey) {
        this.ordinal = ordinal;
        this.logTag = logTag;
        this.langKey = langKey;
    }

    private static final VoiceFailure[] BY_ORDINAL;
    static {
        int max = 0;
        for (VoiceFailure f : values()) max = Math.max(max, f.ordinal);
        BY_ORDINAL = new VoiceFailure[max + 1];
        for (VoiceFailure f : values()) BY_ORDINAL[f.ordinal] = f;
    }

    public static VoiceFailure fromOrdinal(int ord) {
        if (ord >= 0 && ord < BY_ORDINAL.length && BY_ORDINAL[ord] != null) {
            return BY_ORDINAL[ord];
        }
        return TRANSPORT_UNAVAILABLE;
    }
}
