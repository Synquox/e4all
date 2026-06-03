package link.e4all.dialtone;

import java.net.InetAddress;
import java.net.InetSocketAddress;

public class DialtoneAddress extends InetSocketAddress {
    public final String actualAddress;

    public DialtoneAddress(String ticket) {
        super(InetAddress.getLoopbackAddress(), 0);
        actualAddress = ticket;
    }

    @Override
    public String toString() {
        return actualAddress;
    }
}
