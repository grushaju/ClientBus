package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import kit.penny.clientbus.common.dto.message.*;

import java.util.List;
import java.util.UUID;

public interface IMessageProcessingService {

    /**
     * Обрабатывает входящее сообщение от ChannelConnector
     * вместе с его вложениями.
     */
    MessageDto processPlatformMessage(
            PlatformMessageRequest request
    );

    /**
     * Обрабатывает snapshot Conversation,
     * полученный от ChannelConnector.
     *
     * Message при этом не создаётся.
     */
    void processPlatformConversation(
            PlatformConversationRequest request
    );

    /**
     * Обрабатывает исходящее сообщение
     * вместе с его вложениями.
     */
    MessageDto processOutbound(
            OutboundMessageRequest request,
            List<AttachmentContent> attachments
    );

    /**
     * Форвардит существующее сообщение
     * в другой Conversation.
     */
    MessageDto forwardMessage(
            ForwardMessageRequest request
    );

    /**
     * Обрабатывает lifecycle-событие
     * от внешней платформы.
     */
    MessageDto processPlatformEvent(
            PlatformMessageEvent event
    );

    /**
     * Повторно отправляет сообщения
     * со статусом FAILED.
     */
    MessageDto retryOutbound(
            UUID messageId
    );
}