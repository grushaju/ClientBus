package kit.penny.clientbus.server.notification.center;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationCenterMaintenanceTest {
    @Test void emptySuccessfulCleanupIsHealthyAndFailureKeepsLastSuccessAge() {
        var cleanup = mock(NotificationCenterCleanup.class);
        var meters = new SimpleMeterRegistry();
        var maintenance = new NotificationCenterMaintenance(cleanup, meters);
        assertTrue(Double.isNaN(meters.get("clientbus.notification.cleanup.last.success.age.seconds").gauge().value()));
        assertEquals(0, meters.get("clientbus.notification.cleanup.valid").gauge().value());
        when(cleanup.cleanBatch(100)).thenReturn(0).thenThrow(new IllegalStateException("private-data")).thenReturn(7);

        maintenance.tick();
        double firstAge = meters.get("clientbus.notification.cleanup.last.success.age.seconds").gauge().value();
        assertTrue(firstAge >= 0);
        assertEquals(1, meters.get("clientbus.notification.cleanup.valid").gauge().value());
        assertEquals(1, meters.get("clientbus.notification.cleanup.runs").tag("result", "success").counter().count());

        assertDoesNotThrow(maintenance::tick);
        assertEquals(0, meters.get("clientbus.notification.cleanup.valid").gauge().value());
        assertTrue(meters.get("clientbus.notification.cleanup.last.success.age.seconds").gauge().value() >= firstAge);
        assertEquals(1, meters.get("clientbus.notification.cleanup.errors").counter().count());
        assertNull(meters.find("clientbus.notification.cleanup.removed").counter());

        maintenance.tick();
        assertEquals(1, meters.get("clientbus.notification.cleanup.valid").gauge().value());
        assertEquals(7, meters.get("clientbus.notification.cleanup.removed").counter().count());
        assertEquals(2, meters.get("clientbus.notification.cleanup.duration").tag("result", "success").timer().count());
        assertEquals(1, meters.get("clientbus.notification.cleanup.duration").tag("result", "failure").timer().count());
    }
}
