package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.message.InboundMessageRequest;
import kit.penny.clientbus.common.dto.message.MessageDto;
import kit.penny.clientbus.common.dto.message.OutboundMessageRequest;
import kit.penny.clientbus.common.dto.message.PlatformMessageEvent;

public interface IMessageProcessingService {

    /**
     * Обрабатывает входящее сообщение от ChannelConnector.
     *
     * Может:
     * - найти или создать ClientAccount;
     * - найти или создать Conversation;
     * - создать Message;
     * - сохранить attachments;
     * - обновить состояние Conversation.
     */
    MessageDto processInbound(
            InboundMessageRequest request
    );

    /**
     * Обрабатывает исходящее сообщение
     * от Employee / API.
     *
     * Должен:
     * - проверить доступ;
     * - создать Message;
     * - сохранить его до вызова Connector;
     * - передать сообщение Connector'у;
     * - обновить delivery lifecycle.
     */
    MessageDto processOutbound(
            OutboundMessageRequest request
    );

    /**
     * Обрабатывает событие от внешней платформы.
     *
     * Например:
     * SENT
     * DELIVERED
     * READ
     * FAILED
     */
//    MessageDto processPlatformEvent(
//            PlatformMessageEvent event
//    );
}