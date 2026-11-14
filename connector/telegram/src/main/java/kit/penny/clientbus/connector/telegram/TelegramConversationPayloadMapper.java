package kit.penny.clientbus.connector.telegram;

import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import kit.penny.clientbus.connector.api.model.ConnectorConversation;

/**
 * 13.3: локальный 1:1 конвертер снимка диалога в модель коннектора.
 * Зеркалит серверный ConnectorPayloadMapper.toConnector(PlatformConversationRequest):
 * границы модуля не должны зависеть от класса сервера.
 */
public final class TelegramConversationPayloadMapper {

    private TelegramConversationPayloadMapper() {
    }

    public static ConnectorConversation toConnectorConversation(
            PlatformConversationRequest request
    ) {
        if (request == null) {
            return null;
        }
        return new ConnectorConversation(
                request.channelAccountId(),
                request.clientExternalId(),
                request.clientUsername(),
                request.clientPhone(),
                request.clientDisplayName(),
                request.lastMessageAt(),
                request.lastMessagePreview(),
                request.unreadCount(),
                request.avatarUrl(),
                request.dialogExternalId());
    }
}
