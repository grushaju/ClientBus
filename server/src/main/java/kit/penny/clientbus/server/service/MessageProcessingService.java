package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import kit.penny.clientbus.common.dto.message.CreateInboundMessageRequest;
import kit.penny.clientbus.common.dto.message.CreateOutboundMessageRequest;
import kit.penny.clientbus.common.dto.message.InboundMessageRequest;
import kit.penny.clientbus.common.dto.message.MessageDto;
import kit.penny.clientbus.common.dto.message.OutboundMessageRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageDeliveryStatus;
import kit.penny.clientbus.common.enums.MessageProcessingStatus;
import kit.penny.clientbus.server.connector.ConnectorSendResult;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.IChannelConnectorRegistry;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.MessageEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import org.springframework.stereotype.Service;

@Service
public class MessageProcessingService
        implements IMessageProcessingService {

    private final ChannelAccountRepository channelAccountRepository;
    private final ClientAccountService clientAccountService;
    private final ConversationService conversationService;
    private final MessageService messageService;
    private final IChannelConnectorRegistry connectorRegistry;

    public MessageProcessingService(
            ChannelAccountRepository channelAccountRepository,
            ClientAccountService clientAccountService,
            ConversationService conversationService,
            MessageService messageService,
            IChannelConnectorRegistry connectorRegistry
    ) {
        this.channelAccountRepository =
                channelAccountRepository;

        this.clientAccountService =
                clientAccountService;

        this.conversationService =
                conversationService;

        this.messageService =
                messageService;

        this.connectorRegistry =
                connectorRegistry;
    }

    /**
     * Обработка входящего сообщения.
     *
     * ChannelConnector
     *      ↓
     * MessageProcessingService
     *      ↓
     * ClientAccount
     *      ↓
     * Conversation
     *      ↓
     * Message
     */
    @Override
    @Transactional
    public MessageDto processInbound(
            InboundMessageRequest request
    ) {

        ChannelAccountEntity channelAccount =
                channelAccountRepository
                        .findById(
                                request.channelAccountId()
                        )
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "ChannelAccount not found: "
                                                + request.channelAccountId()
                                )
                        );

        ChannelType channelType =
                channelAccount
                        .getChannel()
                        .getType();

        /*
         * ClientAccount создаётся автоматически.
         *
         * Client при этом НЕ создаётся.
         */
        ClientAccountEntity clientAccount =
                clientAccountService.getOrCreateForInbound(
                        channelType,
                        request.clientExternalId(),
                        request.clientUsername(),
                        request.clientPhone(),
                        request.clientDisplayName()
                );

        /*
         * Conversation определяется парой:
         *
         * ClientAccount + ChannelAccount.
         */
        ConversationEntity conversation =
                conversationService.findEntityByAccounts(
                        channelAccount.getId(),
                        clientAccount.getId()
                );

        if (conversation == null) {

            conversation =
                    conversationService.createConversationInternal(
                            channelAccount,
                            clientAccount
                    );
        }

        /*
         * MessageService отвечает за lifecycle самого Message:
         *
         * - idempotency по externalId;
         * - INBOUND;
         * - CLIENT;
         * - lastMessage;
         * - unreadCount.
         */
        return messageService.createInboundMessage(
                new CreateInboundMessageRequest(
                        conversation.getId(),
                        request.type(),
                        request.externalId(),
                        request.content(),
                        request.metadata(),
                        request.sentAt()
                )
        );
    }

    /**
     * Обработка исходящего сообщения.
     *
     * Lifecycle:
     *
     * RECEIVED + PENDING
     *          ↓
     *      PROCESSING
     *          ↓
     *      PROCESSED
     *          ↓
     *   ChannelConnector
     *       ↙       ↘
     *   success     failure
     *      ↓           ↓
     *    SENT     DELIVERY_FAILED
     */
    @Transactional
    public MessageDto processOutbound(
            OutboundMessageRequest request
    ) {

        /*
         * createOutboundMessage() выполняет:
         *
         * - поиск Conversation;
         * - Workspace ACL;
         * - проверку EMPLOYEE;
         * - создание OUTBOUND Message;
         * - привязку текущего Employee;
         * - RECEIVED + PENDING;
         * - обновление Conversation.lastMessage.
         */
        MessageDto message =
                messageService.createOutboundMessage(
                        new CreateOutboundMessageRequest(
                                request.conversationId(),
                                request.type(),
                                request.content(),
                                request.metadata()
                        )
                );

        boolean processingCompleted = false;

        try {

            /*
             * RECEIVED -> PROCESSING
             */
            message =
                    messageService.startProcessing(
                            message.id()
                    );

            /*
             * Получаем Conversation без повторной ACL-проверки.
             *
             * ACL уже был выполнен внутри
             * createOutboundMessage().
             */
            ConversationEntity conversation =
                    conversationService.findEntityForProcessing(
                            request.conversationId()
                    );

            ChannelAccountEntity channelAccount =
                    conversation.getChannelAccount();

            if (channelAccount == null) {

                throw new IllegalStateException(
                        "Conversation has no ChannelAccount: "
                                + conversation.getId()
                );
            }

            ChannelType channelType =
                    channelAccount
                            .getChannel()
                            .getType();

            if (channelType == null) {

                throw new IllegalStateException(
                        "ChannelAccount has no ChannelType: "
                                + channelAccount.getId()
                );
            }

            /*
             * Выбираем connector по ChannelType.
             */
            IChannelConnector connector =
                    connectorRegistry.getConnector(
                            channelType
                    );

            /*
             * Внутренняя обработка сообщения завершена.
             *
             * PROCESSING -> PROCESSED
             */
            message =
                    messageService.markProcessed(
                            message.id()
                    );

            processingCompleted = true;

            /*
             * Одна попытка отправки во внешнюю платформу.
             *
             * Используем существующий контракт:
             *
             * send(
             *     channelAccountId,
             *     messageId,
             *     OutboundMessageRequest
             * )
             */
            ConnectorSendResult result =
                    connector.send(
                            channelAccount.getId(),
                            message.id(),
                            request
                    );

            if (result == null) {

                throw new IllegalStateException(
                        "ChannelConnector returned null result"
                );
            }

            if (result.externalId() == null
                    || result.externalId().isBlank()) {

                throw new IllegalStateException(
                        "ChannelConnector returned blank externalId"
                );
            }

            /*
             * PROCESSED + PENDING -> SENT
             */
            return messageService.markSent(
                    message.id(),
                    result.externalId()
            );

        } catch (RuntimeException e) {

            if (!processingCompleted) {

                /*
                 * Ошибка внутренней обработки ClientBus.
                 *
                 * PROCESSING -> FAILED
                 */
                try {

                    return messageService.markProcessingFailed(
                            message.id()
                    );

                } catch (RuntimeException ignored) {

                    /*
                     * Не скрываем исходную ошибку.
                     */
                }

            } else {

                /*
                 * Вариант B:
                 *
                 * Message уже PROCESSED.
                 *
                 * Значит ошибка относится только
                 * к внешней доставке.
                 *
                 * PROCESSED + PENDING -> DELIVERY_FAILED
                 */
                try {

                    return messageService.markDeliveryFailed(
                            message.id()
                    );

                } catch (RuntimeException ignored) {

                    /*
                     * Не скрываем исходную ошибку Connector.
                     */
                }
            }

            throw e;
        }
    }
}