package kit.penny.clientbus.common.dto.message;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ForwardMessageRequest(

        /**
         * Исходное сообщение.
         */
        @NotNull
        UUID messageId,

        /**
         * Существующий Conversation назначения.
         */
        UUID targetConversationId,

        /**
         * ClientAccount назначения.
         *
         * Используется совместно с targetChannelAccountId.
         */
        UUID targetClientAccountId,

        /**
         * ChannelAccount назначения.
         *
         * Используется совместно с targetClientAccountId.
         */
        UUID targetChannelAccountId

) {

    @AssertTrue(
            message =
                    "Specify either targetConversationId or " +
                            "targetClientAccountId + targetChannelAccountId"
    )
    public boolean hasValidTarget() {

        boolean conversationTarget =
                targetConversationId != null;

        boolean accountTarget =
                targetClientAccountId != null
                        && targetChannelAccountId != null;

        return conversationTarget ^ accountTarget;
    }
}
