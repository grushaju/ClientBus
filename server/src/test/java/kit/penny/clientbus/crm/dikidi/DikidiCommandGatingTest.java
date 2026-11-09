package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DikidiCommandGatingTest {
    @Test void createWithoutADurableProgressCallbackAndMalformedCommandsNeverOpenASession() {
        try (var provider=new DikidiProvider(new DikidiProvider.Settings(Duration.ofSeconds(1),Duration.ofSeconds(1),4096),() -> { fail("An unproved command must not access DIKIDI"); return null; });
             var access=new CrmAccess(UUID.randomUUID(),1,"login",new char[]{'x'})) {
            assertEquals(EnumSet.allOf(CrmCommand.Type.class),provider.commandCapabilities());
            assertEquals(CrmFailure.Code.CAPABILITY_UNVERIFIED,assertThrows(CrmFailure.class,() -> provider.command(access,"100",null,Instant.now().plusSeconds(1))).code());
            assertEquals(CrmFailure.Code.INVALID_RESPONSE,assertThrows(CrmFailure.class,() -> provider.reconcile(access,"100",null,Instant.now().plusSeconds(1))).code());
            var command=new CrmCommand(CrmCommand.Type.CREATE,UUID.randomUUID(),UUID.randomUUID(),null,new CrmClientReference("PROJECT","300"),"200",List.of(new CrmCommand.Offer("400",java.math.BigDecimal.ONE,"RUB",3600)),LocalDateTime.of(2030,1,1,12,0),"UTC",null,"{}");
            assertEquals(CrmFailure.Code.CAPABILITY_UNVERIFIED,assertThrows(CrmFailure.class,()->provider.command(access,"100",command,Instant.now().plusSeconds(1),null)).code());
        }
    }
}
