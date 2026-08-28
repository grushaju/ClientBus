package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.message.*;

import java.util.List;

public interface IMessageProcessingService {

    /**
     * Обрабатывает входящее сообщение от ChannelConnector
     * вместе с его вложениями.
     */
    MessageDto processInbound(
            InboundMessageRequest request,
            List<AttachmentContent> attachments
    );

    /**
     * Обрабатывает входящее сообщение от ChannelConnector,
     * в котором attachments уже сохранены в Storage Connector-ом.
     */
    MessageDto processInbound(
            PlatformInboundMessageEvent event
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
     *
     * Attachments исходного сообщения будут обработаны
     * внутри orchestration layer.
     */
    MessageDto forwardMessage(
            ForwardMessageRequest request
    );

    /**
     * Обрабатывает lifecycle-событие
     * от внешней платформы.
     *
     * Событие идентифицирует Message
     * через channelAccountId + externalId.
     */
    MessageDto processPlatformEvent(
            PlatformMessageEvent event
    );
}