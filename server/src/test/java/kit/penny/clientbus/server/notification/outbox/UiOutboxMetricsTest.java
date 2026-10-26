package kit.penny.clientbus.server.notification.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UiOutboxMetricsTest {
    @Test void snapshotsExpireWithoutDatabaseCallsFromScrapesAndSeparatePipelines() {
        var store=mock(UiOutboxStore.class);var meters=new SimpleMeterRegistry();
        var clock=new java.util.concurrent.atomic.AtomicLong();
        var metrics=new UiOutboxMetrics(store,meters,
                new kit.penny.clientbus.server.notification.metrics.NotificationMetricsSettings(1000,2),clock::get);
        when(store.statistics()).thenReturn(Map.of("pending",3,"quarantined",1,"oldest_seconds",20,
                "uipending",2,"uiquarantined",1,"uioldest",20,"historypending",1,"historyquarantined",0,"historyoldest",5));
        metrics.sample();
        assertEquals(2,meters.get("clientbus.notification.outbox.pipeline.pending").tag("pipeline","ui").gauge().value());
        assertEquals(1,meters.get("clientbus.notification.outbox.pipeline.pending").tag("pipeline","history").gauge().value());
        clock.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(4));
        assertEquals(0,meters.get("clientbus.notification.outbox.sample.valid").gauge().value());
        assertEquals(9,meters.get("clientbus.notification.outbox.pipeline.oldest.seconds").tag("pipeline","history").gauge().value());
        verify(store,times(1)).statistics();
        metrics.sample();
        assertEquals(1,meters.get("clientbus.notification.outbox.sample.valid").gauge().value());
    }
    @Test void missingAndFailedSamplesAreVisibleAndCachedAgeKeepsAdvancing() throws Exception {
        var store=mock(UiOutboxStore.class);var meters=new SimpleMeterRegistry();
        var metrics=new UiOutboxMetrics(store,meters);
        assertTrue(Double.isNaN(meters.get("clientbus.notification.outbox.pending").gauge().value()));
        assertEquals(0,meters.get("clientbus.notification.outbox.sample.valid").gauge().value());
        when(store.statistics()).thenReturn(Map.of("pending",2,"quarantined",1,"oldest_seconds",12));
        metrics.sample();
        assertEquals(2,meters.get("clientbus.notification.outbox.pending").gauge().value());
        assertEquals(1,meters.get("clientbus.notification.outbox.sample.valid").gauge().value());
        double age=meters.get("clientbus.notification.outbox.sample.age.seconds").gauge().value();
        when(store.statistics()).thenThrow(new DataAccessResourceFailureException("offline"));
        Thread.sleep(20);metrics.sample();
        assertEquals(0,meters.get("clientbus.notification.outbox.sample.valid").gauge().value());
        assertEquals(2,meters.get("clientbus.notification.outbox.pending").gauge().value());
        assertTrue(meters.get("clientbus.notification.outbox.sample.age.seconds").gauge().value()>age);
        assertTrue(meters.get("clientbus.notification.outbox.oldest.seconds").gauge().value()>12);
        assertEquals(1,meters.get("clientbus.notification.outbox.errors").tag("stage","statistics").counter().count());
    }
}
