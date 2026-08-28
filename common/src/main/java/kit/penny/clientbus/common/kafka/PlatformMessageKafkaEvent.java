package kit.penny.clientbus.common.kafka;

import kit.penny.clientbus.common.enums.PlatformMessageEventType;

import java.util.UUID;

public record PlatformMessageKafkaEvent(

        UUID channelAccountId,

        String externalId,

        PlatformMessageEventType type,

        String metadata

) {
}