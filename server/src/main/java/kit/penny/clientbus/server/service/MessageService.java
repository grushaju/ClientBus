package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import kit.penny.clientbus.common.dto.message.CreateOutboundMessageRequest;
import kit.penny.clientbus.common.dto.message.CreatePlatformMessageRequest;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

        currentUserService.requireConversationAccess(
                message.getConversation()
        );

        currentUserService.requireWorkspaceAccess(
                message.getConversation()
                        .getWorkspace()
                        .getId()
        );

        return messageMapper.toDto(message);
    }

    @Transactional
    public Page<MessageDto> getConversationMessages(
            UUID conversationId,
            Pageable pageable
    ) {

        ConversationEntity conversation =
                conversationRepository.findById(conversationId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Conversation not found: "
                                                + conversationId
                                )
                        );

        currentUserService.requireConversationAccess(
                conversation
        );

        currentUserService.requireWorkspaceAccess(
                conversation.getWorkspace().getId()
        );

        return messageRepository
                .findAllByConversationIdOrderBySentAtDescCreatedAtDesc(
                        conversationId,
                        pageable
                )
                .map(messageMapper::toDto);
    }

    @Transactional
    public MessageCreationResult createPlatformMessage(
            CreatePlatformMessageRequest request
    ) {

        ConversationEntity conversation =
                getConversation(
                        request.conversationId()
                );

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

        MessageDirection direction =
                request.outbound()
                        ? MessageDirection.OUTBOUND
                        : MessageDirection.INBOUND;

        MessageSenderType senderType =
                request.outbound()
                        ? MessageSenderType.EMPLOYEE
                        : MessageSenderType.CLIENT;

        MessageEntity message =
                new MessageEntity(
                        conversation,
                        request.type(),
                        direction,
                        senderType
                );

        if (request.outbound()) {

            message.setEmployee(null);
            message.setClientAccount(null);

            message.setDeliveryStatus(
                    MessageDeliveryStatus.SENT
            );

        } else {

            message.setClientAccount(
                    conversation.getClientAccount()
            );

            message.setEmployee(null);

            message.setDeliveryStatus(null);
        }

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

        message =
                messageRepository.save(message);

        Instant messageTime =
                message.getSentAt() != null
                        ? message.getSentAt()
                        : message.getCreatedAt();

        conversationService.updateLastMessage(
                conversation,
                messageTime,
                createPreview(message)
        );

        if (!request.outbound()) {

            conversationService.incrementUnreadCount(
                    conversation
            );
        }

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

        message =
                messageRepository.save(message);

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
    public MessageDto markQueued(
            UUID messageId
    ) {

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
     * Atomically claims an outbound message for delivery.
     *
     * <p>
     * QUEUED + PENDING -> PROCESSING + PENDING
     *
     * <p>
     * Only one concurrent Kafka delivery attempt can successfully
     * perform this transition.
     *
     * <p>
     * This transition MUST happen before calling the external
     * platform connector.
     */
    @Transactional
    public boolean claimOutboundDelivery(
            UUID messageId
    ) {

        int updatedRows =
                messageRepository.claimOutboundDelivery(
                        messageId,
                        MessageDirection.OUTBOUND,
                        MessageProcessingStatus.QUEUED,
                        MessageDeliveryStatus.PENDING,
                        MessageProcessingStatus.PROCESSING,
                        Instant.now()
                );

        return updatedRows == 1;
    }

    /**
     * Releases an outbound delivery claim after the external
     * connector failed before accepting the message.
     *
     * <p>
     * PROCESSING + PENDING -> QUEUED + PENDING
     *
     * <p>
     * Kafka can then retry the message.
     */
    @Transactional
    public void releaseOutboundDeliveryClaim(
            UUID messageId
    ) {

        messageRepository.releaseOutboundDeliveryClaim(
                messageId,
                MessageDirection.OUTBOUND,
                MessageProcessingStatus.PROCESSING,
                MessageDeliveryStatus.PENDING,
                MessageProcessingStatus.QUEUED
        );
    }

    /**
     * Registers the external platform message ID after the platform
     * accepted the outbound send request.
     *
     * <p>
     * This method does NOT mark the message as SENT.
     *
     * <pre>
     * processing = PROCESSING
     * delivery   = PENDING
     * externalId = platform message ID
     * </pre>
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
                != MessageProcessingStatus.PROCESSING) {

            throw new IllegalStateException(
                    "Message must be PROCESSING before registering externalId: "
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
     * PROCESSING -> PROCESSED + SENT.
     * <p>
     * Вызывается после подтверждения фактической
     * отправки сообщения платформой.
     *
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
         * для сообщения, находящегося в PROCESSING.
         */
        if (processingStatus
                != MessageProcessingStatus.PROCESSING) {

            throw new IllegalStateException(
                    "Message must be PROCESSING before SENT: "
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

        message.setExternalId(
                externalId
        );

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

        message.setDeliveryAttemptAt(
                null
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

        if (!messages.isEmpty()) {

            messageRepository.saveAll(messages);
        }
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
     * PROCESSING is also accepted because an external connector
     * can fail after the delivery claim but before the platform
     * accepts the message.
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
                == MessageProcessingStatus.QUEUED
                || message.getProcessingStatus()
                == MessageProcessingStatus.PROCESSING) {

            message.setProcessingStatus(
                    MessageProcessingStatus.PROCESSED
            );

            message.setProcessedAt(
                    Instant.now()
            );

            message.setDeliveryAttemptAt(
                    null
            );

        } else if (message.getProcessingStatus()
                != MessageProcessingStatus.PROCESSED) {

            throw new IllegalStateException(
                    "Message must be QUEUED, PROCESSING or PROCESSED "
                            + "before FAILED: "
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
         * Only load the entity here.
         *
         * Retry-specific conversation ACL is checked
         * by OutboundMessageTransactionService.
         */
        getMessageEntityForProcessing(messageId);

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

        MessageEntity retriedMessage =
                getMessageEntity(messageId);

        return messageMapper.toDto(
                retriedMessage
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

        currentUserService.requireConversationAccess(
                message.getConversation()
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