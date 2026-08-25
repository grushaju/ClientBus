package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.message.ForwardMessageRequest;
import kit.penny.clientbus.common.dto.message.InboundMessageRequest;
import kit.penny.clientbus.common.dto.message.MessageDto;
import kit.penny.clientbus.common.dto.message.OutboundMessageRequest;

public interface IMessageProcessingService {

    /**
     * Обрабатывает входящее сообщение от ChannelConnector.
     */
    MessageDto processInbound(
            InboundMessageRequest request
    );

    /**
     * Обрабатывает исходящее сообщение
     * от Employee / API.
     */
    MessageDto processOutbound(
            OutboundMessageRequest request
    );

    /**
     * Форвардит существующее сообщение
     * в другой Conversation.
     *
     * Target может быть:
     *
     * 1. существующий Conversation;
     * 2. ClientAccount + ChannelAccount,
     *    для которых Conversation будет найден
     *    или создан.
     */
    MessageDto forwardMessage(
            ForwardMessageRequest request
    );
}