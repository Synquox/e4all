package link.e4all.dialtone;

import java.net.InetAddress;
import java.net.InetSocketAddress;

public class DialtoneAddress extends InetSocketAddress {
    public static final String GAME_ALPN = "e4mc-dialtone";
    public static final String VOICE_ALPN = "e4all-voice";

    public final String actualAddress;
    public final String alpn;

    public DialtoneAddress(String ticket) {
        this(ticket, GAME_ALPN);
    }

    public DialtoneAddress(String ticket, String alpn) {
        super(InetAddress.getLoopbackAddress(), 0);
        actualAddress = ticket;
        this.alpn = alpn;
    }

    @Override
    public String toString() {
        return actualAddress;
    }
}
