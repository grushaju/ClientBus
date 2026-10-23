package kit.penny.clientbus.common.dto.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kit.penny.clientbus.common.enums.PlatformMessageEventType;

import java.time.Instant;
import java.util.UUID;

public record PlatformMessageEvent(

        /**
         * ChannelAccount, от которого пришло событие.
         */
        @NotNull
        UUID channelAccountId,

        /**
         * ID сообщения на внешней платформе.
         */
        @NotBlank
        String externalId,

        /**
         * Тип события.
         */
        @NotNull
        PlatformMessageEventType type,

        /**
         * Время возникновения события на платформе.
         */
        Instant occurredAt,

        /**
         * Дополнительные данные платформы.
         */
        String metadata,

        /** Trusted send identity; absent only for legacy messages. */
        UUID sendAttemptId

) {
    public PlatformMessageEvent(UUID channelAccountId, String externalId,
            PlatformMessageEventType type, Instant occurredAt, String metadata) {
        this(channelAccountId, externalId, type, occurredAt, metadata, null);
    }
}