package kit.penny.clientbus.server.notification.transport;

import java.time.Duration;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UiGatewayPropertiesTest {
    private UiGatewayProperties config(String id,int records,int bytes,boolean cleanup) {
        return new UiGatewayProperties(id,256,4,8,256,Duration.ofSeconds(5),Duration.ofSeconds(2),
                Duration.ofSeconds(2),Duration.ofSeconds(10),records,bytes,cleanup);
    }
    @Test void generatedInstanceIdsAreIndependentAndConsumerBudgetsAreValidated() {
        var ids=new HashSet<String>();
        for(int i=0;i<100;i++) assertTrue(ids.add(config("",100,1048576,true).instanceId()));
        for(int records:new int[]{0,1001}) assertThrows(IllegalArgumentException.class,()->config("test",records,1048576,true));
        for(int bytes:new int[]{65535,16777217}) assertThrows(IllegalArgumentException.class,()->config("test",100,bytes,true));
        assertThrows(IllegalArgumentException.class,()->config("invalid/name",100,1048576,true));
    }
    @Test void cleanupCanBeDisabledWhenAdministrativePermissionsAreUnavailable() {
        assertFalse(config("managed",100,1048576,false).cleanupGroupOnStop());
        assertTrue(config("automatic",100,1048576,true).cleanupGroupOnStop());
    }
}
