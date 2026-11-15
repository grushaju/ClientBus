package kit.penny.clientbus.common.dto.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kit.penny.clientbus.common.enums.MessageType;

import java.time.Instant;
import java.util.UUID;

public record CreatePlatformMessageRequest(

        @NotNull
        UUID conversationId,

        @NotNull
        MessageType type,

        @NotBlank
        String externalId,

        String content,

        String metadata,

        Instant sentAt,

        boolean outbound,

        /**
         * 13.5 (живая приёмка DIKIDI): сотрудник ClientBus, привязанный к автору сообщения
         * на стороне платформы. Только для исходящих; null — привязки нет (в т.ч.
         * супер-админ салона) или платформа не различает авторов.
         */
        UUID employeeId

) {

    public CreatePlatformMessageRequest(UUID conversationId, MessageType type, String externalId,
            String content, String metadata, Instant sentAt, boolean outbound) {
        this(conversationId, type, externalId, content, metadata, sentAt, outbound, null);
    }
}