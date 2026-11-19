package kit.penny.clientbus.server.crm;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CrmSyncPropertiesTest {
    @Test void durationsAndDatasetPoliciesAreBoundFromConfiguration(){
        var source=new MapConfigurationPropertySource(Map.of(
            "clientbus.crm.sync.workers","7",
            "clientbus.crm.sync.scheduler-tick","25s",
            "clientbus.crm.sync.datasets.client-statistics.batch-size","3",
            "clientbus.crm.sync.datasets.appointments.future-days","14"));
        var settings=new Binder(source).bind("clientbus.crm.sync",CrmSyncProperties.class).get();settings.validate();
        assertEquals(7,settings.getWorkers());assertEquals(Duration.ofSeconds(25),settings.getSchedulerTick());
        assertEquals(3,settings.policy(CrmSyncProperties.Dataset.CLIENT_STATISTICS).getBatchSize());
        assertEquals(14,settings.policy(CrmSyncProperties.Dataset.APPOINTMENTS).getFutureDays());
        assertNotNull(settings.policy(CrmSyncProperties.Dataset.CLIENTS));
    }
    @Test void rejectsUnsafeLeaseProtocolLimitsAndInvalidDurations(){
        var settings=new CrmSyncProperties();settings.setHeartbeatInterval(Duration.ofMinutes(1));
        assertThrows(IllegalArgumentException.class,settings::validate);
        settings=new CrmSyncProperties();settings.policy(CrmSyncProperties.Dataset.CLIENT_STATISTICS).setBatchSize(6);
        assertThrows(IllegalArgumentException.class,settings::validate);
        settings=new CrmSyncProperties();settings.setSchedulerTick(Duration.ZERO);
        assertThrows(IllegalArgumentException.class,settings::validate);
    }
}
