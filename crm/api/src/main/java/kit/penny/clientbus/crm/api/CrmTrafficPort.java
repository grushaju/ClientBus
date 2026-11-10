package kit.penny.clientbus.crm.api;

import java.time.Instant;
import java.util.UUID;

/** Technical pause shared by a connection's datasets. No credentials or persistence types cross this port. */
public interface CrmTrafficPort {
    record Pause(Instant notBefore,int failures) { public static final Pause NONE=new Pause(null,0); }
    Pause current(UUID connectionId);
    Pause block(UUID connectionId,Instant notBefore);
}
