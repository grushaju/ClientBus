package kit.penny.clientbus.common.kafka;

import java.util.UUID;

public record ChannelReadKafkaCommand(

        UUID channelAccountId,

        String recipientExternalId,

        String lastReadExternalId,

        /** 13.2b: внешняя ссылка диалога платформы; nullable для старых payload. */
        String dialogExternalId

) {

    public ChannelReadKafkaCommand(
            UUID channelAccountId,
            String recipientExternalId,
            String lastReadExternalId
    ) {
        this(channelAccountId, recipientExternalId, lastReadExternalId, null);
    }
}