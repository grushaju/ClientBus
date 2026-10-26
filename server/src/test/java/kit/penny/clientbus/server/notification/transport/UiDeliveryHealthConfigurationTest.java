package kit.penny.clientbus.server.notification.transport;

import kit.penny.clientbus.server.config.properties.NotificationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.health.contributor.Status;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UiDeliveryHealthConfigurationTest {
    @Test void disabledDeliveryIsExplicitAndNeedsNoGatewayBean() {
        var indicator=new UiDeliveryHealthConfiguration().uiPipelineHealthIndicator(properties(false),new DefaultListableBeanFactory().getBeanProvider(UiSseGateway.class));
        assertEquals(Status.UP,indicator.health().getStatus());assertEquals("disabled",indicator.health().getDetails().get("mode"));
    }
    @Test void enabledDeliveryDistinguishesMissingDegradedAndRecoveredGateway() {
        var beans=new DefaultListableBeanFactory();
        var indicator=new UiDeliveryHealthConfiguration().uiPipelineHealthIndicator(properties(true),beans.getBeanProvider(UiSseGateway.class));
        assertEquals(Status.DOWN,indicator.health().getStatus());
        var gateway=mock(UiSseGateway.class);beans.registerSingleton("gateway",gateway);
        assertEquals("degraded",indicator.health().getDetails().get("mode"));
        when(gateway.ready()).thenReturn(true);when(gateway.connectionCount()).thenReturn(4);
        assertEquals(Status.UP,indicator.health().getStatus());assertEquals("ready",indicator.health().getDetails().get("mode"));
        assertEquals(4,indicator.health().getDetails().get("connections"));
    }
    private NotificationProperties properties(boolean enabled) {
        return new Binder(new MapConfigurationPropertySource(Map.of("clientbus.notifications.sse-enabled",enabled)))
                .bindOrCreate("clientbus.notifications",NotificationProperties.class);
    }
}
