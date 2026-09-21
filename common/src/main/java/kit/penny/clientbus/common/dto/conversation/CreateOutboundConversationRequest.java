package kit.penny.clientbus.common.dto.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kit.penny.clientbus.common.enums.ChannelType;

import java.util.UUID;

public record CreateOutboundConversationRequest(

        /**
         * Workspace, в котором будет создан Conversation.
         *
         * Backend проверяет, что текущий Employee
         * имеет доступ к этому Workspace.
         */
        @NotNull
        UUID workspaceId,

        /**
         * ChannelAccount, с которого будет выполняться
         * исходящая коммуникация.
         *
         * Backend проверяет принадлежность ChannelAccount
         * указанному Workspace.
         */
        @NotNull
        UUID channelAccountId,

        /**
         * Тип внешней платформы.
         *
         * Должен совпадать с ChannelAccount.channel.type.
         */
        @NotNull
        ChannelType channelType,

        /**
         * ID пользователя на внешней платформе.
         *
         * Для Telegram это, например, Telegram user ID.
         */
        @NotBlank
        String externalId,

        /**
         * Имя пользователя на внешней платформе.
         *
         * Используется для первоначального заполнения
         * ClientAccount и может быть null/пустым.
         */
        String username,

        /**
         * Телефон пользователя на внешней платформе.
         */
        String phone,

        /**
         * Отображаемое имя пользователя.
         */
        String displayName

) {
}