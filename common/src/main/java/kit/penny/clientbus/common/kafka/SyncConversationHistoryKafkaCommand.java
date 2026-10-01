package kit.penny.clientbus.common.kafka;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SyncConversationHistoryKafkaCommand(

        @NotNull
        UUID channelAccountId,

        @NotBlank
        String conversationExternalId,

        String beforeExternalId,

        int limit

) {
}
