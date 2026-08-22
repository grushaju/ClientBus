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
    private final MessageMapper messageMapper;
    private final CurrentUserService currentUserService;

    public MessageService(
            MessageRepository messageRepository,
            ConversationRepository conversationRepository,
            MessageMapper messageMapper,
            CurrentUserService currentUserService
    ) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.messageMapper = messageMapper;
        this.currentUserService = currentUserService;
    }

    /**
     * Creates an inbound message received from a ChannelConnector.
     *
     * Lifecycle:
     *
     * RECEIVED
     *     ↓
     * PROCESSING
     *     ↓
     * PROCESSED
     *
     * Rules:
     * - direction = INBOUND
     * - senderType = CLIENT
     * - clientAccount = Conversation.clientAccount
     * - employee = null
     * - deliveryStatus = null
     *
     * externalId is mandatory because inbound messages must be
     * idempotent.
     *
     * This method intentionally does not perform CurrentUserService ACL
     * validation. It is an internal Message Processing operation invoked
     * by a trusted ChannelConnector/application flow.
     */
    @Transactional
    public MessageDto createInboundMessage(
            CreateInboundMessageRequest request
    ) {
        ConversationEntity conversation =
                getConversation(request.conversationId());

        validateInboundRequest(request);

        /*
         * Idempotency:
         *
         * The same external message received twice must not create
         * two MessageEntity records.
         */
        MessageEntity existing =
                messageRepository
                        .findByConversationIdAndExternalId(
                                conversation.getId(),
                                request.externalId()
                        )
                        .orElse(null);

        if (existing != null) {
            return messageMapper.toDto(existing);
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

        /*
         * Delivery status is not applicable to inbound messages.
         */
        message.setDeliveryStatus(null);

        message = messageRepository.save(message);

        updateConversationForInboundMessage(
                conversation,
                message
        );

        return messageMapper.toDto(message);
    }

    /**
     * Creates an outbound message initiated by the current employee.
     *
     * Lifecycle:
     *
     * RECEIVED / PENDING
     *        ↓
     * PROCESSING / PENDING
     *        ↓
     * PROCESSED / PENDING
     *        ↓
     * PROCESSED / SENT
     *        ↓
     * PROCESSED / DELIVERED
     *        ↓
     * PROCESSED / READ
     */
    @Transactional
    public MessageDto createOutboundMessage(
            CreateOutboundMessageRequest request
    ) {
        ConversationEntity conversation =
                getConversation(request.conversationId());

        /*
         * Object-level ACL:
         *
         * Employee can send messages only to a Conversation
         * belonging to a Workspace accessible to him.
         */
        currentUserService.requireWorkspaceAccess(
                conversation.getWorkspace().getId()
        );

        /*
         * Creating an outbound message is an employee operation.
         */
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

        message = messageRepository.save(message);

        updateConversationForOutboundMessage(
                conversation,
                message
        );

        return messageMapper.toDto(message);
    }

    /**
     * Starts internal message processing.
     *
     * RECEIVED -> PROCESSING
     */
    @Transactional
    public MessageDto startProcessing(
            UUID messageId
    ) {
        MessageEntity message =
                getMessageForProcessing(messageId);

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
     * Marks internal message processing as successfully completed.
     *
     * PROCESSING -> PROCESSED
     */
    @Transactional
    public MessageDto markProcessed(
            UUID messageId
    ) {
        MessageEntity message =
                getMessageForProcessing(messageId);

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
     * Marks internal message processing as failed.
     *
     * PROCESSING -> FAILED
     */
    @Transactional
    public MessageDto markProcessingFailed(
            UUID messageId
    ) {
        MessageEntity message =
                getMessageForProcessing(messageId);

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
     * Marks an outbound message as accepted by the external platform.
     *
     * PROCESSED / PENDING -> PROCESSED / SENT
     *
     * externalId is assigned here because it is generated/known
     * by the external platform only after sending.
     */
    @Transactional
    public MessageDto markSent(
            UUID messageId,
            String externalId
    ) {
        MessageEntity message =
                getMessageForProcessing(messageId);

        requireOutbound(message);

        if (message.getProcessingStatus()
                != MessageProcessingStatus.PROCESSED) {

            throw new IllegalStateException(
                    "Message must be PROCESSED before sending: "
                            + messageId
            );
        }

        if (message.getDeliveryStatus()
                != MessageDeliveryStatus.PENDING) {

            throw new IllegalStateException(
                    "Message must be PENDING before SENT: "
                            + messageId
            );
        }

        validateExternalId(externalId);

        /*
         * Prevent assigning an external ID that already belongs
         * to another message in the same Conversation.
         */
        messageRepository
                .findByConversationIdAndExternalId(
                        message.getConversation().getId(),
                        externalId
                )
                .ifPresent(existing -> {

                    if (!existing.getId().equals(message.getId())) {
                        throw new IllegalStateException(
                                "Message with externalId already exists: "
                                        + externalId
                        );
                    }
                });

        message.setExternalId(externalId);

        message.setDeliveryStatus(
                MessageDeliveryStatus.SENT
        );

        return messageMapper.toDto(
                messageRepository.save(message)
        );
    }

    /**
     * Marks an outbound message as delivered by the platform.
     *
     * SENT -> DELIVERED
     */
    @Transactional
    public MessageDto markDelivered(
            UUID messageId
    ) {
        MessageEntity message =
                getMessageForProcessing(messageId);

        requireOutbound(message);

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
     * Marks an outbound message as read by the recipient.
     *
     * SENT / DELIVERED -> READ
     */
    @Transactional
    public MessageDto markRead(
            UUID messageId
    ) {
        MessageEntity message =
                getMessageForProcessing(messageId);

        requireOutbound(message);

        MessageDeliveryStatus status =
                message.getDeliveryStatus();

        if (status != MessageDeliveryStatus.SENT
                && status != MessageDeliveryStatus.DELIVERED) {

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
     * Marks outbound delivery as failed.
     *
     * PENDING / SENT -> FAILED
     */
    @Transactional
    public MessageDto markDeliveryFailed(
            UUID messageId
    ) {
        MessageEntity message =
                getMessageForProcessing(messageId);

        requireOutbound(message);

        MessageDeliveryStatus status =
                message.getDeliveryStatus();

        if (status != MessageDeliveryStatus.PENDING
                && status != MessageDeliveryStatus.SENT) {

            throw new IllegalStateException(
                    "Message cannot be marked delivery FAILED "
                            + "from status: " + status
            );
        }

        message.setDeliveryStatus(
                MessageDeliveryStatus.FAILED
        );

        return messageMapper.toDto(
                messageRepository.save(message)
        );
    }

    /**
     * Returns a message to an authenticated application user.
     *
     * Object-level Workspace ACL is enforced.
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

    /**
     * Loads a Message for an internal Message Processing operation.
     *
     * IMPORTANT:
     * No CurrentUserService ACL check is performed here.
     *
     * Message Processing is an application-level operation invoked
     * by trusted infrastructure/Connector flow, not by an employee
     * directly.
     */
    private MessageEntity getMessageForProcessing(
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

    private void requireOutbound(
            MessageEntity message
    ) {
        if (message.getDirection()
                != MessageDirection.OUTBOUND) {

            throw new IllegalStateException(
                    "Operation is allowed only for OUTBOUND messages: "
                            + message.getId()
            );
        }
    }

    private void validateInboundRequest(
            CreateInboundMessageRequest request
    ) {
        if (request.type() == null) {
            throw new IllegalArgumentException(
                    "Message type is required"
            );
        }

        validateExternalId(request.externalId());
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

    private void updateConversationForInboundMessage(
            ConversationEntity conversation,
            MessageEntity message
    ) {
        Instant messageTime =
                message.getSentAt() != null
                        ? message.getSentAt()
                        : message.getCreatedAt();

        conversation.setLastMessageAt(messageTime);

        conversation.setLastMessagePreview(
                createPreview(message)
        );

        conversation.setUnreadCount(
                conversation.getUnreadCount() + 1
        );

        conversationRepository.save(conversation);
    }

    private void updateConversationForOutboundMessage(
            ConversationEntity conversation,
            MessageEntity message
    ) {
        Instant messageTime =
                message.getSentAt() != null
                        ? message.getSentAt()
                        : message.getCreatedAt();

        conversation.setLastMessageAt(messageTime);

        conversation.setLastMessagePreview(
                createPreview(message)
        );

        /*
         * Outbound message does not increment unreadCount.
         */
        conversationRepository.save(conversation);
    }

    private String createPreview(
            MessageEntity message
    ) {
        if (message.getContent() != null
                && !message.getContent().isBlank()) {

            String content =
                    message.getContent().trim();

            if (content.length() <= 500) {
                return content;
            }

            return content.substring(0, 497) + "...";
        }

        return switch (message.getType()) {
            case TEXT -> "";
            case IMAGE -> "[Фото]";
            case VIDEO -> "[Видео]";
            case AUDIO -> "[Аудио]";
            case DOCUMENT -> "[Документ]";
            case STICKER -> "[Стикер]";
            case LOCATION -> "[Локация]";
            case CONTACT -> "[Контакт]";
            case SYSTEM -> "[Системное сообщение]";
        };
    }
}