package kit.penny.clientbus.common.kafka;

import java.util.UUID;

public record ChannelReadKafkaCommand(

        UUID channelAccountId,

        String recipientExternalId,

        String lastReadExternalId

) {
}