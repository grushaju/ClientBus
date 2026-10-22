package kit.penny.clientbus.server.notification.transport;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;
import java.util.UUID;

@ConfigurationProperties("clientbus.notifications.gateway")
public record UiGatewayProperties(
        @DefaultValue("") String instanceId,
        @DefaultValue("256") int maxConnections,
        @DefaultValue("4") int maxConnectionsPerEmployee,
        @DefaultValue("8") int workers,
        @DefaultValue("256") int executorQueueCapacity,
        @DefaultValue("PT5S") Duration writeTimeout,
        @DefaultValue("PT2S") Duration brokerTimeout,
        @DefaultValue("PT2S") Duration probeInterval,
        @DefaultValue("PT10S") Duration pipelineStaleAfter,
        @DefaultValue("100") int maxPollRecords,
        @DefaultValue("1048576") int maxFetchBytes,
        @DefaultValue("true") boolean cleanupGroupOnStop) {
    public UiGatewayProperties(String instanceId, int maxConnections, int maxConnectionsPerEmployee, int workers,
            int executorQueueCapacity, Duration writeTimeout, Duration brokerTimeout, Duration probeInterval, Duration pipelineStaleAfter) {
        this(instanceId,maxConnections,maxConnectionsPerEmployee,workers,executorQueueCapacity,writeTimeout,brokerTimeout,
                probeInterval,pipelineStaleAfter,100,1048576,true);
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public UiGatewayProperties {
        if (instanceId == null || instanceId.isBlank()) instanceId = UUID.randomUUID().toString();
        if (!instanceId.matches("[A-Za-z0-9_-]{1,80}") || maxConnections < 1 || maxConnections > 10000
                || maxConnectionsPerEmployee < 1 || maxConnectionsPerEmployee > maxConnections
                || workers < 1 || workers > 64 || executorQueueCapacity < 1 || executorQueueCapacity > 10000
                || maxPollRecords < 1 || maxPollRecords > 1000 || maxFetchBytes < 65536 || maxFetchBytes > 16777216)
            throw new IllegalArgumentException("Invalid gateway limits");
        for (var value : java.util.List.of(writeTimeout, brokerTimeout, probeInterval, pipelineStaleAfter))
            if (value.isZero() || value.isNegative()) throw new IllegalArgumentException("Positive duration required");
        if (probeInterval.plus(brokerTimeout).compareTo(pipelineStaleAfter) >= 0)
            throw new IllegalArgumentException("Pipeline deadline must exceed probe interval + broker timeout");
    }
}
