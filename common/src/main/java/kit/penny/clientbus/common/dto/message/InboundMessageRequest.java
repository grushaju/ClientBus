package kit.penny.clientbus.common.dto.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kit.penny.clientbus.common.enums.MessageType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InboundMessageRequest(

        /**
         * ChannelAccount, через который пришло сообщение.
         */
        @NotNull
        UUID channelAccountId,

        /**
         * Идентификатор клиента на внешней платформе.
         *
         * Например:
         * Telegram user id,
         * VK user id,
         * MAX user id и т.д.
         */
        @NotBlank
        String clientExternalId,

        /**
         * Username клиента на платформе.
         */
        String clientUsername,

        /**
         * Телефон клиента, если платформа его предоставляет.
         */
        String clientPhone,

        /**
         * Отображаемое имя клиента.
         */
        String clientDisplayName,

        /**
         * Идентификатор сообщения на внешней платформе.
         */
        @NotBlank
        String externalId,

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
         * Дополнительные данные сообщения
         * в connector-independent JSON формате.
         */
        String metadata,

        /**
         * Время сообщения на внешней платформе.
         */
        Instant sentAt,

        /**
         * Уже загруженные attachments.
         */
        List<MessageAttachmentRequest> attachments

) {
}