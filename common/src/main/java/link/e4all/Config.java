package link.e4all;

import folk.sisby.kaleido.api.ReflectiveConfig;
import folk.sisby.kaleido.lib.quiltconfig.api.annotations.Comment;
import folk.sisby.kaleido.lib.quiltconfig.api.values.TrackedValue;

public class Config extends ReflectiveConfig {
    public static final Config INSTANCE = Config.createToml(Agnos.configDir(), "e4all", "e4all", Config.class);

    @Comment("Whether to use the broker to get the best relay based on location or use a hard-coded relay.")
    public final TrackedValue<Boolean> useBroker = this.value(true);
    public final TrackedValue<String> brokerUrl = this.value("https://broker.e4mc.link/getBestRelay");    
    public final TrackedValue<String> relayHost = this.value("test.e4mc.link");
    public final TrackedValue<Integer> relayPort = this.value(25575);

    @Comment("Allows use of certain dedicated server commands such as /ban and /whitelist")
    public final TrackedValue<Boolean> restoreDedicatedCommands = this.value(true);
    @Comment("Whether to use whitelists on LAN worlds")
    public final TrackedValue<Boolean> useWhiteList = this.value(false);

    @Comment("Whether to enable sharing LAN worlds with e4all")
    public final TrackedValue<Boolean> hostEnabled = this.value(true);
    @Comment("Keep the world ticking while e4all is hosting it. Vanilla pauses a singleplayer world on ESC and on window focus loss, and a paused world freezes every connected guest until they are dropped. Dedicated servers never pause.")
    public final TrackedValue<Boolean> preventPauseWhileHosting = this.value(true);
    @Comment("Whether to enable Dialtone peer-to-peer connections as the host")
    public final TrackedValue<Boolean> dialtoneHostEnabled = this.value(true);
    @Comment("Whether to enable Dialtone peer-to-peer connections as the player")
    public final TrackedValue<Boolean> dialtonePlayerEnabled = this.value(true);
    @Comment("The URL to get the list of Iroh relays to use")
    public final TrackedValue<String> dialtoneRelayMap = this.value("https://natives.e4mc.link/relaymap.json");
    @Comment("Whether to hide direct IP addresses from the relay")
    public final TrackedValue<Boolean> dialtoneSanitizeTicket = this.value(true);
    @Comment("How many times to try to reconnect to the relay before giving up when the connection drops")
    public final TrackedValue<Integer> reconnectMaxAttempts = this.value(5);
    @Comment("Initial reconnect delay in seconds; doubles with each subsequent attempt")
    public final TrackedValue<Integer> reconnectBaseDelaySeconds = this.value(2);
    @Comment("Keepalive probe interval in seconds to keep the relay session alive")
    public final TrackedValue<Integer> keepaliveIntervalSeconds = this.value(15);
    @Comment("How long (in ms) a guest login may wait for a starting world to finish its first tick before being rejected. Heavy modpacks can stall a full server tick for several seconds; waiting avoids kicking everyone who joins in that window. Set to 0 to reject immediately instead of waiting.")
    public final TrackedValue<Integer> loginReadyTimeoutMs = this.value(15000);

    @Comment("Whether to enable offline mode (disables Microsoft authentication for ALL LAN connections, including both tunneled and direct). Toggle via the 'Online Mode' button on the Open to LAN screen.")
    public final TrackedValue<Boolean> offlineMode = this.value(false);
    @Comment("Whether the offline mode warning has already been shown to the user")
    public final TrackedValue<Boolean> offlineWarningShown = this.value(false);

    @Comment("Whether the welcome message has already been shown to the user")
    public final TrackedValue<Boolean> welcomeShown = this.value(false);

    @Comment("Whether to hide the domain in the chat message (click to copy still works)")
    public final TrackedValue<Boolean> hideDomainInChat = this.value(false);

    @Comment("Master switch for SVC P2P voice support. When disabled, voice will not be negotiated.")
    public final TrackedValue<Boolean> voiceP2PEnabled = this.value(true);
    @Comment("Allow pure-Java UDP path when Dialtone is unavailable (e.g. Android, or iroh connection would be relayed)")
    public final TrackedValue<Boolean> voiceP2PUdpFallback = this.value(true);
    @Comment("Try direct IPv6 before UDP hole punching")
    public final TrackedValue<Boolean> voiceP2PEnableIpv6 = this.value(true);
    @Comment("Request router port mapping (UPnP/NAT-PMP/PCP) on desktop host for UDP voice")
    public final TrackedValue<Boolean> voiceP2PEnableUpnp = this.value(true);
    @Comment("Overall voice negotiation timeout in milliseconds")
    public final TrackedValue<Integer> voiceP2PConnectTimeoutMs = this.value(10000);
    @Comment("UDP hole punch attempt duration in milliseconds")
    public final TrackedValue<Integer> voiceP2PPunchDurationMs = this.value(5000);
    @Comment("Display voice negotiation progress as a HUD status line")
    public final TrackedValue<Boolean> voiceP2PHudStatus = this.value(true);

}
