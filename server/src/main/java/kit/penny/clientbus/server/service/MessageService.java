package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import kit.penny.clientbus.common.dto.message.CreateInboundMessageRequest;
import kit.penny.clientbus.common.dto.message.CreateOutboundMessageRequest;
import kit.penny.clientbus.common.dto.message.MessageDto;
import kit.penny.clientbus.common.enums.MessageDeliveryStatus;
import kit.penny.clientbus.common.enums.MessageDirection;
import kit.penny.clientbus.common.enums.MessageProcessingStatus;
import kit.penny.clientbus.common.enums.MessageSenderType;
import kit.penny.clientbus.server.mapper.MessageMapper;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.MessageEntity;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.persistence.repository.MessageRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;
    private final MessageMapper messageMapper;
    private final CurrentUserService currentUserService;

    public MessageService(
            MessageRepository messageRepository,
            ConversationRepository conversationRepository,
            ConversationService conversationService,
            MessageMapper messageMapper,
            CurrentUserService currentUserService
    ) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.conversationService = conversationService;
        this.messageMapper = messageMapper;
        this.currentUserService = currentUserService;
    }

    /**
     * Получить Message с object-level Workspace ACL.
     */
    @Transactional
    public MessageDto getMessage(
            UUID messageId
    ) {

        MessageEntity message =
                messageRepository.findById(messageId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Message not found: "
                                                + messageId
                                )
                        );

        currentUserService.requireWorkspaceAccess(
                message.getConversation()
                        .getWorkspace()
                        .getId()
        );

        return messageMapper.toDto(message);
    }


    /**
     * Creates an inbound message received from a ChannelConnector.
     */
    @Transactional
    public MessageCreationResult createInboundMessage(
            CreateInboundMessageRequest request
    ) {

        ConversationEntity conversation =
                getConversation(
                        request.conversationId()
                );

        validateInboundRequest(request);

        MessageEntity existing =
                messageRepository
                        .findByConversationIdAndExternalId(
                                conversation.getId(),
                                request.externalId()
                        )
                        .orElse(null);

        if (existing != null) {
            return new MessageCreationResult(
                    messageMapper.toDto(existing),
                    true
            );
        }

        MessageEntity message =
                new MessageEntity(
                        conversation,
                        request.type(),
                        MessageDirection.INBOUND,
                        MessageSenderType.CLIENT
                );

        message.setClientAccount(
                conversation.getClientAccount()
        );

        message.setEmployee(null);

        message.setExternalId(
                request.externalId()
        );

        message.setContent(
                request.content()
        );

        message.setMetadata(
                request.metadata()
        );

        message.setSentAt(
                request.sentAt() != null
                        ? request.sentAt()
                        : Instant.now()
        );

        message.setProcessingStatus(
                MessageProcessingStatus.RECEIVED
        );

        message.setDeliveryStatus(null);

        message = messageRepository.save(message);

        Instant messageTime =
                message.getSentAt() != null
                        ? message.getSentAt()
                        : message.getCreatedAt();

        conversationService.updateLastMessage(
                conversation,
                messageTime,
                createPreview(message)
        );

        conversationService.incrementUnreadCount(
                conversation
        );

        return new MessageCreationResult(
                messageMapper.toDto(message),
                false
        );
    }

    /**
     * Creates an outbound message initiated by the current Employee.
     * <p>
     * If replyToMessageId is specified, the target message must
     * belong to the same Conversation.
     */
    @Transactional
    public MessageDto createOutboundMessage(
            CreateOutboundMessageRequest request
    ) {

        ConversationEntity conversation =
                getConversation(
                        request.conversationId()
                );

        currentUserService.requireWorkspaceAccess(
                conversation
                        .getWorkspace()
                        .getId()
        );

        if (!currentUserService.isEmployee()) {

            throw new AccessDeniedException(
                    "Only EMPLOYEE can create outbound messages"
            );
        }

        MessageEntity message =
                new MessageEntity(
                        conversation,
                        request.type(),
                        MessageDirection.OUTBOUND,
                        MessageSenderType.EMPLOYEE
                );

        message.setEmployee(
                currentUserService.getCurrentEmployee()
        );

        message.setClientAccount(null);

        message.setContent(
                request.content()
        );

        message.setMetadata(
                request.metadata()
        );

        message.setSentAt(
                Instant.now()
        );

        message.setProcessingStatus(
                MessageProcessingStatus.RECEIVED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        /*
         * Reply.
         *
         * Reply можно делать только на сообщение
         * из того же Conversation.
         */
        if (request.replyToMessageId() != null) {

            MessageEntity replyToMessage =
                    messageRepository
                            .findById(
                                    request.replyToMessageId()
                            )
                            .orElseThrow(() ->
                                    new EntityNotFoundException(
                                            "Reply target Message not found: "
                                                    + request.replyToMessageId()
                                    )
                            );

            if (!replyToMessage
                    .getConversation()
                    .getId()
                    .equals(conversation.getId())) {

                throw new IllegalArgumentException(
                        "Reply target Message must belong "
                                + "to the same Conversation"
                );
            }

            message.setReplyToMessage(
                    replyToMessage
            );
        }

        message = messageRepository.save(message);

        Instant messageTime =
                message.getSentAt() != null
                        ? message.getSentAt()
                        : message.getCreatedAt();

        conversationService.updateLastMessage(
                conversation,
                messageTime,
                createPreview(message)
        );

        return messageMapper.toDto(message);
    }

    /**
     * Создаёт OUTBOUND Message как Forward
     * существующего Message.
     * <p>
     * ACL Conversation должен быть проверен
     * вызывающим application layer.
     */
    @Transactional
    public MessageDto createForwardedMessage(
            ConversationEntity targetConversation,
            MessageEntity sourceMessage
    ) {

        if (targetConversation == null) {

            throw new IllegalArgumentException(
                    "Target Conversation must not be null"
            );
        }

        if (sourceMessage == null) {

            throw new IllegalArgumentException(
                    "Source Message must not be null"
            );
        }

        MessageEntity message =
                new MessageEntity(
                        targetConversation,
                        sourceMessage.getType(),
                        MessageDirection.OUTBOUND,
                        MessageSenderType.EMPLOYEE
                );

        message.setEmployee(
                currentUserService.getCurrentEmployee()
        );

        message.setClientAccount(null);

        message.setContent(
                sourceMessage.getContent()
        );

        message.setMetadata(
                sourceMessage.getMetadata()
        );

        /*
         * Это принципиально НЕ externalId источника.
         *
         * externalId появится только после отправки
         * через ChannelConnector.
         */
        message.setExternalId(null);

        /*
         * Forward и Reply — разные семантики.
         *
         * Сам Forward не является Reply.
         */
        message.setReplyToMessage(null);

        /*
         * Сохраняем происхождение сообщения.
         */
        message.setForwardedFromMessage(
                sourceMessage
        );

        message.setSentAt(
                Instant.now()
        );

        message.setProcessingStatus(
                MessageProcessingStatus.RECEIVED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        message =
                messageRepository.save(message);

        Instant messageTime =
                message.getSentAt() != null
                        ? message.getSentAt()
                        : message.getCreatedAt();

        conversationService.updateLastMessage(
                targetConversation,
                messageTime,
                createPreview(message)
        );

        return messageMapper.toDto(
                message
        );
    }

    /**
     * RECEIVED -> PROCESSING.
     */
    @Transactional
    public MessageDto startProcessing(
            UUID messageId
    ) {

        MessageEntity message =
                getMessageEntityForProcessing(messageId);

        if (message.getProcessingStatus()
                != MessageProcessingStatus.RECEIVED) {

            throw new IllegalStateException(
                    "Message must be RECEIVED to start processing: "
                            + messageId
            );
        }

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSING
        );

        return messageMapper.toDto(
                messageRepository.save(message)
        );
    }

    /**
     * PROCESSING -> PROCESSED.
     */
    @Transactional
    public MessageDto markProcessed(
            UUID messageId
    ) {

        MessageEntity message =
                getMessageEntityForProcessing(messageId);

        if (message.getProcessingStatus()
                != MessageProcessingStatus.PROCESSING) {

            throw new IllegalStateException(
                    "Message must be PROCESSING to mark it PROCESSED: "
                            + messageId
            );
        }

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setProcessedAt(
                Instant.now()
        );

        return messageMapper.toDto(
                messageRepository.save(message)
        );
    }

    /**
     * PROCESSING -> FAILED.
     */
    @Transactional
    public MessageDto markProcessingFailed(
            UUID messageId
    ) {

        MessageEntity message =
                getMessageEntityForProcessing(messageId);

        if (message.getProcessingStatus()
                != MessageProcessingStatus.PROCESSING) {

            throw new IllegalStateException(
                    "Message must be PROCESSING to mark it FAILED: "
                            + messageId
            );
        }

        message.setProcessingStatus(
                MessageProcessingStatus.FAILED
        );

        return messageMapper.toDto(
                messageRepository.save(message)
        );
    }

    /**
     * PROCESSING -> QUEUED.
     * <p>
     * Сообщение полностью подготовлено и поставлено
     * в асинхронный outbound Kafka flow.
     * <p>
     * Повторный QUEUED является идемпотентным.
     */
    @Transactional
    public MessageDto markQueued(UUID messageId) {

        MessageEntity message =
                getMessageEntityForProcessing(messageId);

        if (message.getProcessingStatus()
                == MessageProcessingStatus.QUEUED) {

            return messageMapper.toDto(message);
        }

        if (message.getProcessingStatus()
                != MessageProcessingStatus.PROCESSING) {

            throw new IllegalStateException(
                    "Message must be PROCESSING before QUEUED: "
                            + messageId
            );
        }

        message.setProcessingStatus(
                MessageProcessingStatus.QUEUED
        );

        return messageMapper.toDto(
                messageRepository.save(message)
        );
    }

    /**
     * Registers the external platform message ID after the platform
     * accepted the outbound send request.
     *
     * <p>
     * This method does NOT mark the message as SENT.
     * The message remains:
     *
     * <pre>
     * processing = QUEUED
     * delivery   = PENDING
     * externalId = platform message ID
     * </pre>
     *
     * <p>
     * For Telegram this method is called immediately after
     * SendMessage returns its TdApi.Message.
     *
     * <p>
     * Actual delivery completion is handled asynchronously by
     * UpdateMessageSendSucceeded / UpdateMessageSendFailed.
     */
    @Transactional
    public MessageDto registerExternalId(
            UUID messageId,
            String externalId
    ) {

        MessageEntity message =
                getMessageEntityForProcessing(messageId);

        requireOutbound(message);

        validateExternalId(externalId);

        if (message.getProcessingStatus()
                != MessageProcessingStatus.QUEUED) {

            throw new IllegalStateException(
                    "Message must be QUEUED before registering externalId: "
                            + messageId
            );
        }

        if (message.getDeliveryStatus()
                != MessageDeliveryStatus.PENDING) {

            throw new IllegalStateException(
                    "Message must be PENDING before registering externalId: "
                            + messageId
            );
        }

        String currentExternalId =
                message.getExternalId();

        if (currentExternalId != null) {

            if (currentExternalId.equals(externalId)) {

                return messageMapper.toDto(message);
            }

            throw new IllegalStateException(
                    "Message is already registered with another externalId: "
                            + messageId
            );
        }

        /*
         * Защита от повторного externalId
         * другого Message в Conversation.
         */
        messageRepository
                .findByConversationIdAndExternalId(
                        message.getConversation().getId(),
                        externalId
                )
                .ifPresent(existing -> {

                    if (!existing.getId()
                            .equals(message.getId())) {

                        throw new IllegalStateException(
                                "Message with externalId already exists: "
                                        + externalId
                        );
                    }
                });

        message.setExternalId(
                externalId
        );

        return messageMapper.toDto(
                messageRepository.save(message)
        );
    }

    /**
     * QUEUED -> PROCESSED + SENT.
     * <p>
     * Вызывается только после подтверждения фактической
     * отправки сообщения платформой.
     * <p>
     * Для Telegram это UpdateMessageSendSucceeded.
     * <p>
     * Повторный SENT с тем же externalId является идемпотентным.
     */
    @Transactional
    public MessageDto markSent(
            UUID messageId,
            String externalId
    ) {

        MessageEntity message =
                getMessageEntityForProcessing(messageId);

        requireOutbound(message);

        validateExternalId(externalId);

        MessageProcessingStatus processingStatus =
                message.getProcessingStatus();

        MessageDeliveryStatus deliveryStatus =
                message.getDeliveryStatus();

        /*
         * Повторная доставка одного и того же
         * UpdateMessageSendSucceeded.
         */
        if (deliveryStatus == MessageDeliveryStatus.SENT
                && externalId.equals(message.getExternalId())) {

            return messageMapper.toDto(message);
        }

        /*
         * Уже SENT с другим externalId — конфликт.
         */
        if (deliveryStatus == MessageDeliveryStatus.SENT) {

            throw new IllegalStateException(
                    "Message is already SENT with another externalId: "
                            + messageId
            );
        }

        /*
         * Фактическая отправка допустима только
         * для сообщения, находящегося в QUEUED.
         */
        if (processingStatus
                != MessageProcessingStatus.QUEUED) {

            throw new IllegalStateException(
                    "Message must be QUEUED before SENT: "
                            + messageId
            );
        }

        if (deliveryStatus != MessageDeliveryStatus.PENDING) {

            throw new IllegalStateException(
                    "Message must be PENDING before SENT: "
                            + messageId
            );
        }

        /*
         * Защита от повторного externalId
         * другого Message в Conversation.
         */
        messageRepository
                .findByConversationIdAndExternalId(
                        message.getConversation().getId(),
                        externalId
                )
                .ifPresent(existing -> {

                    if (!existing.getId()
                            .equals(message.getId())) {

                        throw new IllegalStateException(
                                "Message with externalId already exists: "
                                        + externalId
                        );
                    }
                });

        message.setExternalId(externalId);

        message.setSentAt(
                Instant.now()
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.SENT
        );

        /*
         * Processing завершён только после того,
         * как платформа подтвердила фактическую отправку.
         */
        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setProcessedAt(
                Instant.now()
        );

        return messageMapper.toDto(
                messageRepository.save(message)
        );
    }

    /**
     * SENT -> DELIVERED.
     * <p>
     * Повторное получение DELIVERED является идемпотентным.
     */
    @Transactional
    public MessageDto markDelivered(
            UUID messageId
    ) {

        MessageEntity message =
                getMessageEntityForProcessing(messageId);

        requireOutbound(message);

        if (message.getDeliveryStatus()
                == MessageDeliveryStatus.DELIVERED) {

            return messageMapper.toDto(message);
        }

        if (message.getDeliveryStatus()
                != MessageDeliveryStatus.SENT) {

            throw new IllegalStateException(
                    "Message must be SENT before DELIVERED: "
                            + messageId
            );
        }

        message.setDeliveryStatus(
                MessageDeliveryStatus.DELIVERED
        );

        message.setDeliveredAt(
                Instant.now()
        );

        return messageMapper.toDto(
                messageRepository.save(message)
        );
    }

    /**
     * SENT/DELIVERED -> READ.
     * <p>
     * Повторное получение READ является идемпотентным.
     */
    @Transactional
    public MessageDto markRead(
            UUID messageId
    ) {

        MessageEntity message =
                getMessageEntityForProcessing(messageId);

        requireOutbound(message);

        if (message.getDeliveryStatus()
                == MessageDeliveryStatus.READ) {

            return messageMapper.toDto(message);
        }

        MessageDeliveryStatus deliveryStatus =
                message.getDeliveryStatus();

        if (deliveryStatus != MessageDeliveryStatus.SENT
                && deliveryStatus != MessageDeliveryStatus.DELIVERED) {

            throw new IllegalStateException(
                    "Message must be SENT or DELIVERED before READ: "
                            + messageId
            );
        }

        message.setDeliveryStatus(
                MessageDeliveryStatus.READ
        );

        message.setReadAt(
                Instant.now()
        );

        return messageMapper.toDto(
                messageRepository.save(message)
        );
    }

    /**
     * Marks all Telegram outbound messages up to the supplied
     * Telegram message ID as READ.
     */
    @Transactional
    public void markReadUpTo(
            UUID conversationId,
            long lastReadOutboxMessageId
    ) {

        if (conversationId == null
                || lastReadOutboxMessageId <= 0) {

            return;
        }

        var messages =
                messageRepository
                        .findAllByConversationIdAndDirectionAndDeliveryStatus(
                                conversationId,
                                MessageDirection.OUTBOUND,
                                MessageDeliveryStatus.SENT
                        );

        for (MessageEntity message : messages) {

            String externalId =
                    message.getExternalId();

            if (externalId == null
                    || externalId.isBlank()) {

                continue;
            }

            long telegramMessageId;

            try {
                telegramMessageId =
                        Long.parseLong(externalId);
            } catch (NumberFormatException e) {
                continue;
            }

            if (telegramMessageId <= lastReadOutboxMessageId) {

                message.setDeliveryStatus(
                        MessageDeliveryStatus.READ
                );

                message.setReadAt(
                        Instant.now()
                );
            }
        }

        messageRepository.saveAll(messages);
    }

    /**
     * Marks an outbound message as delivery failed.
     *
     * <p>
     * PENDING -> FAILED
     * or
     * SENT -> FAILED.
     *
     * <p>
     * If processing is still QUEUED, processing is completed
     * at the same time because the asynchronous delivery attempt
     * has reached its terminal state.
     *
     * <p>
     * Repeated FAILED notification is idempotent.
     */
    @Transactional
    public MessageDto markDeliveryFailed(
            UUID messageId
    ) {

        MessageEntity message =
                getMessageEntityForProcessing(messageId);

        requireOutbound(message);

        if (message.getDeliveryStatus()
                == MessageDeliveryStatus.FAILED) {

            return messageMapper.toDto(message);
        }

        MessageDeliveryStatus deliveryStatus =
                message.getDeliveryStatus();

        if (deliveryStatus != MessageDeliveryStatus.PENDING
                && deliveryStatus != MessageDeliveryStatus.SENT) {

            throw new IllegalStateException(
                    "Message must be PENDING or SENT before FAILED: "
                            + messageId
            );
        }

        message.setDeliveryStatus(
                MessageDeliveryStatus.FAILED
        );

        if (message.getProcessingStatus()
                == MessageProcessingStatus.QUEUED) {

            message.setProcessingStatus(
                    MessageProcessingStatus.PROCESSED
            );

            message.setProcessedAt(
                    Instant.now()
            );

        } else if (message.getProcessingStatus()
                != MessageProcessingStatus.PROCESSED) {

            throw new IllegalStateException(
                    "Message must be QUEUED or PROCESSED before FAILED: "
                            + messageId
            );
        }

        return messageMapper.toDto(
                messageRepository.save(message)
        );
    }

    /**
     * Resets a failed outbound delivery for another send attempt.
     *
     * <p>
     * PROCESSED + FAILED -> PROCESSING + PENDING
     *
     * <p>
     * The state transition is performed atomically in the database
     * to prevent concurrent retries of the same message.
     */
    @Transactional
    public MessageDto retryDelivery(
            UUID messageId
    ) {

        /*
         * First load the message with object-level ACL.
         *
         * The entity itself is not modified here because the actual
         * state transition is performed by the atomic UPDATE below.
         */
        getMessageEntity(messageId);

        int updatedRows =
                messageRepository.retryDelivery(
                        messageId,
                        MessageDirection.OUTBOUND,
                        MessageProcessingStatus.PROCESSED,
                        MessageDeliveryStatus.FAILED,
                        MessageProcessingStatus.PROCESSING,
                        MessageDeliveryStatus.PENDING
                );

        if (updatedRows != 1) {

            MessageEntity message =
                    getMessageEntity(messageId);

            requireOutbound(message);

            if (message.getProcessingStatus()
                    != MessageProcessingStatus.PROCESSED) {

                throw new IllegalStateException(
                        "Message must be PROCESSED before retry: "
                                + messageId
                );
            }

            if (message.getDeliveryStatus()
                    != MessageDeliveryStatus.FAILED) {

                throw new IllegalStateException(
                        "Message must be FAILED before retry: "
                                + messageId
                );
            }

            throw new IllegalStateException(
                    "Message retry state transition failed: "
                            + messageId
            );
        }

        /*
         * The repository method performs a bulk update, so the
         * persistence context may contain stale entity state.
         *
         * Re-read the entity before mapping it to DTO.
         */
        MessageEntity retriedMessage =
                getMessageEntity(messageId);

        return messageMapper.toDto(
                retriedMessage
        );
    }

    @Transactional
    public MessageDto registerPendingExternalId(
            UUID messageId,
            String externalId
    ) {

        MessageEntity message =
                getMessageEntityForProcessing(messageId);

        requireOutbound(message);

        validateExternalId(externalId);

        if (message.getProcessingStatus()
                != MessageProcessingStatus.QUEUED) {

            throw new IllegalStateException(
                    "Message must be QUEUED before registering externalId: "
                            + messageId
            );
        }

        if (message.getDeliveryStatus()
                != MessageDeliveryStatus.PENDING) {

            throw new IllegalStateException(
                    "Message must be PENDING before registering externalId: "
                            + messageId
            );
        }

        String currentExternalId =
                message.getExternalId();

        if (currentExternalId != null) {

            if (currentExternalId.equals(externalId)) {
                return messageMapper.toDto(message);
            }

            throw new IllegalStateException(
                    "Message is already registered with another externalId: "
                            + messageId
            );
        }

        messageRepository
                .findByConversationIdAndExternalId(
                        message.getConversation().getId(),
                        externalId
                )
                .ifPresent(existing -> {

                    if (!existing.getId()
                            .equals(message.getId())) {

                        throw new IllegalStateException(
                                "Message with externalId already exists: "
                                        + externalId
                        );
                    }
                });

        message.setExternalId(externalId);

        return messageMapper.toDto(
                messageRepository.save(message)
        );
    }

    public MessageEntity getMessageEntityForProcessing(
            UUID messageId
    ) {

        return messageRepository
                .findById(messageId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Message not found: "
                                        + messageId
                        )
                );
    }

    public MessageEntity getMessageEntity(
            UUID messageId
    ) {

        MessageEntity message =
                getMessageEntityForProcessing(
                        messageId
                );

        currentUserService.requireWorkspaceAccess(
                message
                        .getConversation()
                        .getWorkspace()
                        .getId()
        );

        return message;
    }

    private ConversationEntity getConversation(
            UUID conversationId
    ) {

        return conversationRepository
                .findById(conversationId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Conversation not found: "
                                        + conversationId
                        )
                );
    }

    private void requireOutbound(
            MessageEntity message
    ) {

        if (message.getDirection()
                != MessageDirection.OUTBOUND) {

            throw new IllegalArgumentException(
                    "Message must be OUTBOUND"
            );
        }
    }

    private void validateExternalId(
            String externalId
    ) {

        if (externalId == null
                || externalId.isBlank()) {

            throw new IllegalArgumentException(
                    "externalId must not be blank"
            );
        }
    }

    private void validateInboundRequest(
            CreateInboundMessageRequest request
    ) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "Inbound message request must not be null"
            );
        }

        if (request.conversationId() == null) {
            throw new IllegalArgumentException(
                    "conversationId must not be null"
            );
        }

        if (request.externalId() == null
                || request.externalId().isBlank()) {

            throw new IllegalArgumentException(
                    "externalId must not be blank"
            );
        }

        if (request.type() == null) {
            throw new IllegalArgumentException(
                    "message type must not be null"
            );
        }
    }

    private String createPreview(
            MessageEntity message
    ) {

        if (message.getContent() == null
                || message.getContent().isBlank()) {

            return message.getType().name();
        }

        return message.getContent();
    }

}