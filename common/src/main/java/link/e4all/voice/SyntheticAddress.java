package link.e4all.voice;

import java.net.SocketAddress;
import java.util.UUID;

public final class SyntheticAddress extends SocketAddress {
    private static final long serialVersionUID = 1L;
    private final UUID playerUuid;

    public SyntheticAddress(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public UUID getPlayerUuid() { return playerUuid; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return playerUuid.equals(((SyntheticAddress) o).playerUuid);
    }

    @Override
    public int hashCode() { return playerUuid.hashCode(); }

    @Override
    public String toString() { return "SyntheticAddress{" + playerUuid + "}"; }
}
