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

        int limit,

        /** 13.2b: внешняя ссылка диалога платформы; nullable для старых payload. */
        String dialogExternalId

) {
        public SyncConversationHistoryKafkaCommand(
                UUID channelAccountId,
                String conversationExternalId,
                String beforeExternalId,
                int limit
        ) {
                this(channelAccountId, conversationExternalId, beforeExternalId, limit, null);
        }

        public record Result(
                List<MessageDto> messages,
                boolean historyStartReached,

                /** 13.2b: причина завершения страницы истории; nullable для старых payload. */
                String endReason
        ) {

            public Result(
                    List<MessageDto> messages,
                    boolean historyStartReached
            ) {
                this(messages, historyStartReached, null);
            }
        }
}
