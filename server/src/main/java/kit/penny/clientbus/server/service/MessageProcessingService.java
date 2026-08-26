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
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.server.connector.ConnectorSendResult;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.IChannelConnectorRegistry;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.MessageAttachmentEntity;
import kit.penny.clientbus.server.persistence.entity.MessageEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MessageProcessingService
        implements IMessageProcessingService {

    private final ChannelAccountRepository
            channelAccountRepository;

    private final ClientAccountRepository
            clientAccountRepository;

    private final ClientAccountService
            clientAccountService;

    private final ConversationService
            conversationService;

    private final MessageService
            messageService;

    private final MessageAttachmentService
            messageAttachmentService;

    private final IChannelConnectorRegistry
            connectorRegistry;

    public MessageProcessingService(
            ChannelAccountRepository channelAccountRepository,
            ClientAccountRepository clientAccountRepository,
            ClientAccountService clientAccountService,
            ConversationService conversationService,
            MessageService messageService,
            MessageAttachmentService messageAttachmentService,
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

        this.messageAttachmentService =
                messageAttachmentService;

        this.connectorRegistry =
                connectorRegistry;
    }

    /**
     * Обрабатывает входящее сообщение
     * вместе с attachments.
     */
    @Override
    @Transactional
    public MessageDto processInbound(
            InboundMessageRequest request,
            List<AttachmentContent> attachments
    ) {

        attachments = normalizeAttachments(
                attachments
        );

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

        MessageCreationResult result =
                messageService.createInboundMessage(
                        new CreateInboundMessageRequest(
                                conversation.getId(),
                                request.type(),
                                request.externalId(),
                                request.content(),
                                request.metadata(),
                                request.sentAt()
                        )
                );

        MessageDto message = result.message();

        if (!result.existed()) {

            MessageEntity messageEntity =
                    messageService.getMessageEntityForProcessing(
                            message.id()
                    );

            for (AttachmentContent attachment : attachments) {

                messageAttachmentService.createAttachment(
                        messageEntity,
                        attachment
                );
            }
        }

        return message;
    }

    /**
     * Обрабатывает исходящее сообщение
     * вместе с attachments.
     *
     * Пока намеренно используется одна транзакция:
     *
     * create Message
     * -> create Attachments
     * -> PROCESSING
     * -> PROCESSED
     * -> Connector
     * -> SENT
     */
    @Override
    @Transactional
    public MessageDto processOutbound(
            OutboundMessageRequest request,
            List<AttachmentContent> attachments
    ) {

        attachments = normalizeAttachments(
                attachments
        );

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
                    messageService.startProcessing(
                            message.id()
                    );

            ConversationEntity conversation =
                    conversationService
                            .findEntityForProcessing(
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
             * Message Entity нужен для создания
             * оригинальных attachments.
             */
            MessageEntity messageEntity =
                    messageService
                            .getMessageEntityForProcessing(
                                    message.id()
                            );

            for (AttachmentContent attachment :
                    attachments) {

                messageAttachmentService.createAttachment(
                        messageEntity,
                        attachment
                );
            }

            /*
             * Только после создания Message +
             * attachments переводим Message
             * в PROCESSED.
             */
            message =
                    messageService.markProcessed(
                            message.id()
                    );

            processingCompleted = true;

            /*
             * Формируем connector-level request.
             */
            List<ChannelAttachment>
                    channelAttachments =
                    messageAttachmentService
                            .getChannelAttachments(
                                    message.id()
                            );

            ChannelSendRequest sendRequest =
                    new ChannelSendRequest(
                            message.id(),
                            channelAccount.getId(),
                            conversation
                                    .getClientAccount()
                                    .getExternalId(),
                            message.type(),
                            message.content(),
                            channelAttachments
                    );

            IChannelConnector connector =
                    connectorRegistry
                            .getConnector(
                                    channelType
                            );

            ConnectorSendResult result =
                    connector.send(
                            sendRequest
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

            return messageService.markSent(
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
     * Форвардит Message вместе с attachments.
     *
     * Forwarded attachments:
     *
     * - получают новый Entity;
     * - принадлежат новому Message;
     * - используют тот же storageKey;
     * - forwardFrom = sourceMessage.id;
     * - физический файл не копируется.
     */
    @Override
    @Transactional
    public MessageDto forwardMessage(
            ForwardMessageRequest request
    ) {

        MessageEntity sourceMessage =
                messageService
                        .getMessageEntity(
                                request.messageId()
                        );

        ConversationEntity targetConversation;

        if (request.targetConversationId() != null) {

            targetConversation =
                    conversationService
                            .findEntityForProcessing(
                                    request.targetConversationId()
                            );

            conversationService
                    .requireForwardTargetAccess(
                            targetConversation
                    );

        } else {

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

            targetConversation =
                    conversationService
                            .findOrCreateForForward(
                                    channelAccount,
                                    clientAccount
                            );
        }

        MessageDto forwarded =
                messageService
                        .createForwardedMessage(
                                targetConversation,
                                sourceMessage
                        );

        MessageEntity targetMessage =
                messageService
                        .getMessageEntityForProcessing(
                                forwarded.id()
                        );

        List<MessageAttachmentEntity>
                sourceAttachments =
                messageAttachmentService
                        .getAttachmentsForProcessing(
                                sourceMessage.getId()
                        );

        for (MessageAttachmentEntity sourceAttachment :
                sourceAttachments) {

            messageAttachmentService
                    .createForwardedAttachment(
                            targetMessage,
                            sourceAttachment
                    );
        }

        return forwarded;
    }

    private List<AttachmentContent> normalizeAttachments(
            List<AttachmentContent> attachments
    ) {
        if (attachments == null) {
            return List.of();
        }

        return List.copyOf(attachments);
    }

}