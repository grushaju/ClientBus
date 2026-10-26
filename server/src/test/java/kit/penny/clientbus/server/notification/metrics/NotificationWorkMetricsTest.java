package kit.penny.clientbus.server.notification.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.history.HistorySyncService;
import kit.penny.clientbus.server.notification.push.PushDeliveryStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationWorkMetricsTest {
    @Test void sourcesFailIndependentlyAndScrapesOnlyReadCachedStateWithDeliveryDisabled() {
        var history=mock(HistorySyncService.class);var push=mock(PushDeliveryStore.class);var meters=new SimpleMeterRegistry();
        var flags=new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("clientbus.notifications",NotificationProperties.class);
        var sample=new NotificationWorkMetrics(history,push,flags,new NotificationMetricsSettings(10000,2),meters);
        assertTrue(Double.isNaN(meters.get("clientbus.history.operations.active").tag("status","running").gauge().value()));
        when(history.statistics()).thenReturn(Map.of("pending",1,"running",2,"oldest_seconds",12));
        when(push.statistics()).thenReturn(Map.of("pending",3,"in_flight",1,"oldest_seconds",5));
        sample.sample();
        assertEquals(2,meters.get("clientbus.history.operations.active").tag("status","running").gauge().value());
        assertEquals(3,meters.get("clientbus.notification.push.pending").gauge().value());
        assertEquals(0,meters.get("clientbus.notification.features.enabled").tag("feature","sse").gauge().value());
        verify(history,times(1)).statistics();verify(push,times(1)).statistics();
        when(history.statistics()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        when(push.statistics()).thenReturn(Map.of("pending",0,"in_flight",0,"oldest_seconds",0));
        sample.sample();
        assertEquals(0,meters.get("clientbus.notification.state.sample.valid").tag("pipeline","history").gauge().value());
        assertEquals(1,meters.get("clientbus.notification.state.sample.valid").tag("pipeline","push").gauge().value());
        assertEquals(2,meters.get("clientbus.history.operations.active").tag("status","running").gauge().value());
        assertEquals(0,meters.get("clientbus.notification.push.oldest.seconds").gauge().value());
        assertTrue(meters.get("clientbus.history.operations.oldest.seconds").gauge().value()>=12);
    }
    @Test void unsafeSamplingSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new NotificationMetricsSettings(0,2));
        assertThrows(IllegalArgumentException.class,()->new NotificationMetricsSettings(1000,0));
        assertThrows(IllegalArgumentException.class,()->new NotificationMetricsSettings(61000,2));
    }
}
