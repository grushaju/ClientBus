package kit.penny.clientbus.common.kafka;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kit.penny.clientbus.common.dto.message.MessageDto;

import java.util.List;
import java.util.UUID;

public record SyncConversationHistoryKafkaCommand(

        @NotNull
        UUID channelAccountId,

        @NotBlank
        String conversationExternalId,

        String beforeExternalId,

        int limit

) {
        public record Result(
                List<MessageDto> messages,
                boolean historyStartReached
        ) {
        }
}
