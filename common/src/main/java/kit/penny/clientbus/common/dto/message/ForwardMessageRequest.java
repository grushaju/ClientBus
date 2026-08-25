package kit.penny.clientbus.common.dto.message;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ForwardMessageRequest(

        @NotNull
        UUID messageId,

        /**
         * Существующий Conversation назначения.
         *
         * Взаимоисключающ с
         * targetClientAccountId + targetChannelAccountId.
         */
        UUID targetConversationId,

        /**
         * ClientAccount назначения.
         *
         * Используется совместно с targetChannelAccountId,
         * если Conversation ещё не определён.
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
                    "Specify either targetConversationId or "
                            + "targetClientAccountId + "
                            + "targetChannelAccountId"
    )
    public boolean hasValidTarget() {

        boolean existingConversation =
                targetConversationId != null;

        boolean accountPair =
                targetClientAccountId != null
                        && targetChannelAccountId != null;

        return existingConversation ^ accountPair;
    }
}