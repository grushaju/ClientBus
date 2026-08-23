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
     * Creates an inbound message received from a ChannelConnector.
     */
    @Transactional
    public MessageDto createInboundMessage(
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

        return messageMapper.toDto(message);
    }

    /**
     * Creates an outbound message initiated by the current Employee.
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
     * RECEIVED -> PROCESSING.
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
     * PROCESSING -> PROCESSED.
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
     * PROCESSING -> FAILED.
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
     * PROCESSED/PENDING -> SENT.
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

        message.setDeliveryStatus(
                MessageDeliveryStatus.SENT
        );

        return messageMapper.toDto(
                messageRepository.save(message)
        );
    }

    /**
     * SENT -> DELIVERED.
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
     * SENT / DELIVERED -> READ.
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
     * PENDING / SENT -> FAILED.
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
                            + "from status: "
                            + status
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

        validateExternalId(
                request.externalId()
        );
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