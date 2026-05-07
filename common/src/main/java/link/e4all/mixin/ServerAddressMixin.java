package link.e4all.mixin;

import link.e4all.TicketSmuggler;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerAddress.class)
public class ServerAddressMixin implements TicketSmuggler {
    @Unique
    private String e4mc$smuggledTicket = null;

    @Override
    public void e4mc$setSmuggledTicket(String ticket) {
        e4mc$smuggledTicket = ticket;
    }

    @Override
    public String e4mc$getSmuggledTicket() {
        return e4mc$smuggledTicket;
    }
}

