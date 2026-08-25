package kit.penny.clientbus.common.dto.message;

import jakarta.validation.constraints.NotNull;
import kit.penny.clientbus.common.enums.MessageType;

import java.util.List;
import java.util.UUID;

public record OutboundMessageRequest(

        /**
         * Conversation, в который отправляется сообщение.
         */
        @NotNull
        UUID conversationId,

        /**
         * Тип сообщения.
         */
        @NotNull
        MessageType type,

        /**
         * Текст сообщения или caption.
         */
        String content,

        /**
         * Дополнительные данные сообщения.
         */
        String metadata,

        /**
         * Уже загруженные attachments.
         */
        List<MessageAttachmentRequest> attachments,

        /**
         * Сообщение, на которое отвечаем.
         *
         * Должно находиться в том же Conversation.
         */
        UUID replyToMessageId

) {
}