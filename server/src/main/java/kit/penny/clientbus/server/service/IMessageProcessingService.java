package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.message.ForwardMessageRequest;
import kit.penny.clientbus.common.dto.message.InboundMessageRequest;
import kit.penny.clientbus.common.dto.message.MessageDto;
import kit.penny.clientbus.common.dto.message.OutboundMessageRequest;

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
}