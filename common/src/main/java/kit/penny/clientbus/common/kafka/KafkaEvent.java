package kit.penny.clientbus.common.kafka;

import java.time.Instant;
import java.util.UUID;

public record KafkaEvent<T>(

        UUID eventId,

        KafkaEventType eventType,

        int schemaVersion,

        Instant occurredAt,

        UUID correlationId,

        T payload

) {
}