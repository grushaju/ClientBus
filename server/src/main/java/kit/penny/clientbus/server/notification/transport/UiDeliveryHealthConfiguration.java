package kit.penny.clientbus.server.notification.transport;

import kit.penny.clientbus.server.config.properties.NotificationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Delivery degradation is reported separately from the core HTTP readiness group. */
@Configuration(proxyBeanMethods=false)
public class UiDeliveryHealthConfiguration {
    @Bean HealthIndicator uiPipelineHealthIndicator(NotificationProperties properties, ObjectProvider<UiSseGateway> gateways) {
        return () -> {
            if(!properties.sseEnabled()) return Health.up().withDetail("mode","disabled").build();
            var gateway=gateways.getIfAvailable();
            boolean ready=gateway!=null && gateway.ready();
            return (ready?Health.up():Health.down()).withDetail("mode",ready?"ready":"degraded")
                    .withDetail("connections",gateway==null?0:gateway.connectionCount()).build();
        };
    }
}
