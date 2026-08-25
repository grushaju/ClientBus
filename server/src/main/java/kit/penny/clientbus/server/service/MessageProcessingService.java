package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import kit.penny.clientbus.common.dto.message.CreateInboundMessageRequest;
import kit.penny.clientbus.common.dto.message.CreateOutboundMessageRequest;
import kit.penny.clientbus.common.dto.message.ForwardMessageRequest;
import kit.penny.clientbus.common.dto.message.InboundMessageRequest;
import kit.penny.clientbus.common.dto.message.MessageDto;
import kit.penny.clientbus.common.dto.message.OutboundMessageRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.connector.ConnectorSendResult;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.IChannelConnectorRegistry;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.MessageEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MessageProcessingService
        implements IMessageProcessingService {

    private final ChannelAccountRepository channelAccountRepository;
    private final ClientAccountRepository clientAccountRepository;
    private final ClientAccountService clientAccountService;
    private final ConversationService conversationService;
    private final MessageService messageService;
    private final IChannelConnectorRegistry connectorRegistry;

    public MessageProcessingService(
            ChannelAccountRepository channelAccountRepository,
            ClientAccountRepository clientAccountRepository,
            ClientAccountService clientAccountService,
            ConversationService conversationService,
            MessageService messageService,
            IChannelConnectorRegistry connectorRegistry
    ) {
        this.channelAccountRepository =
                channelAccountRepository;

        this.clientAccountRepository =
                clientAccountRepository;

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
     * Обрабатывает входящее сообщение от ChannelConnector.
     *
     * ClientAccount может быть создан автоматически.
     * ClientEntity автоматически не создаётся.
     */
    @Override
    @Transactional
    public MessageDto processInbound(
            InboundMessageRequest request,
            List<AttachmentContent> attachments
    ) {

        attachments = attachments == null
                ? List.of()
                : List.copyOf(attachments);

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

        ClientAccountEntity clientAccount =
                clientAccountService
                        .getOrCreateForInbound(
                                channelType,
                                request.clientExternalId(),
                                request.clientUsername(),
                                request.clientPhone(),
                                request.clientDisplayName()
                        );

        ConversationEntity conversation =
                conversationService
                        .findEntityByAccounts(
                                channelAccount.getId(),
                                clientAccount.getId()
                        );

        if (conversation == null) {

            conversation =
                    conversationService
                            .createConversationInternal(
                                    channelAccount,
                                    clientAccount
                            );
        }

        return messageService
                .createInboundMessage(
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
     * Обрабатывает исходящее сообщение.
     *
     * Lifecycle:
     *
     * RECEIVED
     *     -> PROCESSING
     *     -> PROCESSED
     *     -> Connector
     *     -> SENT
     *
     * При ошибке:
     *
     * PROCESSING -> FAILED
     *
     * или
     *
     * PROCESSED/PENDING -> DELIVERY_FAILED
     */
    @Override
    @Transactional
    public MessageDto processOutbound(
            OutboundMessageRequest request,
            List<AttachmentContent> attachments
    ) {

        attachments = attachments == null
                ? List.of()
                : List.copyOf(attachments);

        MessageDto message =
                messageService
                        .createOutboundMessage(
                                new CreateOutboundMessageRequest(
                                        request.conversationId(),
                                        request.type(),
                                        request.content(),
                                        request.metadata(),
                                        request.replyToMessageId()
                                )
                        );

        boolean processingCompleted = false;

        try {

            message =
                    messageService
                            .startProcessing(
                                    message.id()
                            );

            ConversationEntity conversation =
                    conversationService
                            .findEntityForProcessing(
                                    request.conversationId()
                            );

            ChannelAccountEntity channelAccount =
                    conversation
                            .getChannelAccount();

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

            IChannelConnector connector =
                    connectorRegistry
                            .getConnector(
                                    channelType
                            );

            message =
                    messageService
                            .markProcessed(
                                    message.id()
                            );

            processingCompleted = true;

            ConnectorSendResult result =
                    connector
                            .send(
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

            return messageService
                    .markSent(
                            message.id(),
                            result.externalId()
                    );

        } catch (RuntimeException e) {

            if (!processingCompleted) {

                try {

                    messageService
                            .markProcessingFailed(
                                    message.id()
                            );

                } catch (RuntimeException ignored) {

                    /*
                     * Не скрываем исходную ошибку.
                     */
                }

            } else {

                try {

                    messageService
                            .markDeliveryFailed(
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

    /**
     * Форвардит существующее сообщение в другой Conversation.
     *
     * Для EMPLOYEE:
     *
     * - source Conversation должен быть доступен;
     * - target Conversation должен быть назначен этому Employee;
     * - свободный target недопустим;
     * - target другого Employee недопустим;
     * - новый target Conversation автоматически назначается
     *   текущему Employee.
     *
     * Для SUPER_ADMIN:
     *
     * - source должен быть доступен;
     * - target может быть любым доступным Conversation;
     * - отсутствующий Conversation может быть создан.
     */
    @Override
    @Transactional
    public MessageDto forwardMessage(
            ForwardMessageRequest request
    ) {

        /*
         * Source Message.
         *
         * MessageService выполняет Workspace ACL.
         */
        MessageEntity sourceMessage =
                messageService
                        .getMessageEntity(
                                request.messageId()
                        );

        ConversationEntity targetConversation;

        /*
         * Target — существующий Conversation.
         */
        if (request.targetConversationId() != null) {

            targetConversation =
                    conversationService
                            .findEntityForProcessing(
                                    request.targetConversationId()
                            );

            /*
             * EMPLOYEE:
             * только собственный Conversation.
             *
             * SUPER_ADMIN:
             * любой доступный Conversation.
             */
            conversationService
                    .requireForwardTargetAccess(
                            targetConversation
                    );

        } else {

            /*
             * Target определяется через:
             *
             * ClientAccount + ChannelAccount.
             *
             * ClientAccount здесь не проходит через
             * ClientAccountService.getClientAccount(),
             * поскольку это CRUD ACL ClientAccount.
             */
            ClientAccountEntity clientAccount =
                    clientAccountRepository
                            .findById(
                                    request.targetClientAccountId()
                            )
                            .orElseThrow(() ->
                                    new EntityNotFoundException(
                                            "Target ClientAccount not found: "
                                                    + request.targetClientAccountId()
                                    )
                            );

            ChannelAccountEntity channelAccount =
                    channelAccountRepository
                            .findById(
                                    request.targetChannelAccountId()
                            )
                            .orElseThrow(() ->
                                    new EntityNotFoundException(
                                            "Target ChannelAccount not found: "
                                                    + request.targetChannelAccountId()
                                    )
                            );

            /*
             * ConversationService:
             *
             * 1. ищет существующий Conversation;
             * 2. проверяет Forward ACL;
             * 3. если Conversation отсутствует:
             *    - проверяет Workspace ACL;
             *    - создаёт Conversation;
             *    - для EMPLOYEE назначает его
             *      текущему Employee.
             */
            targetConversation =
                    conversationService
                            .findOrCreateForForward(
                                    channelAccount,
                                    clientAccount
                            );
        }

        /*
         * Создаём новый OUTBOUND Message.
         *
         * forwardedFromMessage = sourceMessage
         * replyToMessage       = null
         *
         * externalId источника НЕ копируется.
         */
        return messageService
                .createForwardedMessage(
                        targetConversation,
                        sourceMessage
                );
    }
}