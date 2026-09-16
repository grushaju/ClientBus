package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.message.CreateInboundMessageRequest;
import kit.penny.clientbus.common.dto.message.CreateOutboundMessageRequest;
import kit.penny.clientbus.common.dto.message.MessageDto;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.server.mapper.MessageMapper;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.MessageEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.persistence.repository.MessageRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ConversationService conversationService;

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private MessageService messageService;

    private UUID conversationId;
    private UUID messageId;

    private WorkspaceEntity workspace;
    private ClientAccountEntity clientAccount;
    private ConversationEntity conversation;
    private MessageEntity message;

    @Mock
    private MessageDto expectedDto;

    @BeforeEach
    void setUp() {

        conversationId = UUID.randomUUID();
        messageId = UUID.randomUUID();

        workspace = new WorkspaceEntity();
        workspace.setId(UUID.randomUUID());
        workspace.setName("Test Workspace");

        clientAccount = new ClientAccountEntity();
        clientAccount.setId(UUID.randomUUID());
        clientAccount.setExternalId("client-123");

        conversation = new ConversationEntity();
        conversation.setId(conversationId);
        conversation.setWorkspace(workspace);
        conversation.setClientAccount(clientAccount);

        message = new MessageEntity(
                conversation,
                MessageType.TEXT,
                MessageDirection.INBOUND,
                MessageSenderType.CLIENT
        );

        message.setId(messageId);
        message.setExternalId("external-123");
        message.setContent("Hello");
        message.setSentAt(
                Instant.parse("2026-08-26T10:00:00Z")
        );
        message.setProcessingStatus(
                MessageProcessingStatus.RECEIVED
        );
    }

    private void stubMessageFound() {

        when(messageRepository.findById(messageId))
                .thenReturn(Optional.of(message));
    }

    // ============================================================
    // CREATE INBOUND
    // ============================================================

    @Test
    void createInboundMessage_newMessage_returnsExistedFalse() {

        CreateInboundMessageRequest request =
                new CreateInboundMessageRequest(
                        conversation.getId(),
                        MessageType.TEXT,
                        "external-123",
                        "Hello",
                        "{\"source\":\"telegram\"}",
                        Instant.now()
                );

        when(conversationRepository.findById(conversation.getId()))
                .thenReturn(Optional.of(conversation));

        when(messageRepository.findByConversationIdAndExternalId(
                conversation.getId(),
                request.externalId()
        )).thenReturn(Optional.empty());

        when(messageRepository.save(any(MessageEntity.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0)
                );

        when(messageMapper.toDto(any(MessageEntity.class)))
                .thenReturn(expectedDto);

        MessageCreationResult result =
                messageService.createInboundMessage(request);

        assertNotNull(result);
        assertFalse(result.existed());
        assertSame(expectedDto, result.message());

        ArgumentCaptor<MessageEntity> captor =
                ArgumentCaptor.forClass(MessageEntity.class);

        verify(messageRepository)
                .save(captor.capture());

        MessageEntity savedMessage =
                captor.getValue();

        assertSame(
                conversation,
                savedMessage.getConversation()
        );

        assertSame(
                conversation.getClientAccount(),
                savedMessage.getClientAccount()
        );

        assertEquals(
                request.externalId(),
                savedMessage.getExternalId()
        );

        assertEquals(
                request.metadata(),
                savedMessage.getMetadata()
        );

        assertEquals(
                MessageProcessingStatus.RECEIVED,
                savedMessage.getProcessingStatus()
        );

        assertNull(
                savedMessage.getDeliveryStatus()
        );

        verify(conversationService)
                .updateLastMessage(
                        eq(conversation),
                        any(),
                        any()
                );

        verify(conversationService)
                .incrementUnreadCount(conversation);
    }

    @Test
    void createInboundMessage_existingMessage_returnsExistedTrue() {

        CreateInboundMessageRequest request =
                new CreateInboundMessageRequest(
                        conversationId,
                        MessageType.TEXT,
                        "external-123",
                        "Hello again",
                        "{\"source\":\"telegram\"}",
                        Instant.parse(
                                "2026-08-26T10:01:00Z"
                        )
                );

        MessageDto expectedDto =
                mock(MessageDto.class);

        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));

        when(messageRepository
                .findByConversationIdAndExternalId(
                        conversationId,
                        "external-123"
                ))
                .thenReturn(Optional.of(message));

        when(messageMapper.toDto(message))
                .thenReturn(expectedDto);

        MessageCreationResult result =
                messageService.createInboundMessage(request);

        assertNotNull(result);
        assertTrue(result.existed());

        assertSame(
                expectedDto,
                result.message()
        );

        assertEquals(
                "Hello",
                message.getContent()
        );

        verify(messageRepository)
                .findByConversationIdAndExternalId(
                        conversationId,
                        "external-123"
                );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));

        verify(conversationService, never())
                .updateLastMessage(
                        any(),
                        any(),
                        any()
                );

        verify(conversationService, never())
                .incrementUnreadCount(any());

        verify(messageMapper)
                .toDto(message);
    }

    @Test
    void createInboundMessage_differentExternalId_createsNewMessage() {

        CreateInboundMessageRequest request =
                new CreateInboundMessageRequest(
                        conversationId,
                        MessageType.TEXT,
                        "external-456",
                        "New message",
                        null,
                        Instant.parse(
                                "2026-08-26T11:00:00Z"
                        )
                );

        MessageEntity newMessage =
                new MessageEntity(
                        conversation,
                        MessageType.TEXT,
                        MessageDirection.INBOUND,
                        MessageSenderType.CLIENT
                );

        newMessage.setId(UUID.randomUUID());
        newMessage.setExternalId("external-456");
        newMessage.setContent("New message");
        newMessage.setSentAt(
                Instant.parse(
                        "2026-08-26T11:00:00Z"
                )
        );

        MessageDto expectedDto =
                mock(MessageDto.class);

        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));

        when(messageRepository
                .findByConversationIdAndExternalId(
                        conversationId,
                        "external-456"
                ))
                .thenReturn(Optional.empty());

        when(messageRepository.save(any(MessageEntity.class)))
                .thenReturn(newMessage);

        when(messageMapper.toDto(newMessage))
                .thenReturn(expectedDto);

        MessageCreationResult result =
                messageService.createInboundMessage(request);

        assertFalse(result.existed());

        assertSame(
                expectedDto,
                result.message()
        );

        verify(messageRepository)
                .findByConversationIdAndExternalId(
                        conversationId,
                        "external-456"
                );

        verify(messageRepository)
                .save(any(MessageEntity.class));

        verify(conversationService)
                .updateLastMessage(
                        eq(conversation),
                        eq(newMessage.getSentAt()),
                        any()
                );

        verify(conversationService)
                .incrementUnreadCount(conversation);
    }

    @Test
    void createInboundMessage_conversationNotFound_throwsException() {

        CreateInboundMessageRequest request =
                new CreateInboundMessageRequest(
                        conversationId,
                        MessageType.TEXT,
                        "external-123",
                        "Hello",
                        null,
                        null
                );

        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.empty());

        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () -> messageService.createInboundMessage(
                                request
                        )
                );

        assertEquals(
                "Conversation not found: " + conversationId,
                exception.getMessage()
        );

        verify(conversationRepository)
                .findById(conversationId);

        verifyNoInteractions(
                messageRepository,
                conversationService,
                messageMapper
        );
    }

    // ============================================================
    // PROCESSING STATE
    // ============================================================

    @Test
    void startProcessing_receivedMessage_changesStatusToProcessing() {

        message.setProcessingStatus(
                MessageProcessingStatus.RECEIVED
        );

        stubMessageFound();

        when(messageRepository.save(message))
                .thenReturn(message);

        when(messageMapper.toDto(message))
                .thenReturn(expectedDto);

        MessageDto result =
                messageService.startProcessing(
                        message.getId()
                );

        assertSame(
                expectedDto,
                result
        );

        assertEquals(
                MessageProcessingStatus.PROCESSING,
                message.getProcessingStatus()
        );

        verify(messageRepository)
                .save(message);

        verify(messageMapper)
                .toDto(message);
    }

    @Test
    void startProcessing_processingMessage_throwsException() {

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSING
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.startProcessing(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void startProcessing_processedMessage_throwsException() {

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.startProcessing(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void startProcessing_queuedMessage_throwsException() {

        message.setProcessingStatus(
                MessageProcessingStatus.QUEUED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.startProcessing(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void startProcessing_failedMessage_throwsException() {

        message.setProcessingStatus(
                MessageProcessingStatus.FAILED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.startProcessing(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markProcessed_processingMessage_changesStatusToProcessed() {

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSING
        );

        stubMessageFound();

        when(messageRepository.save(message))
                .thenReturn(message);

        when(messageMapper.toDto(message))
                .thenReturn(expectedDto);

        MessageDto result =
                messageService.markProcessed(
                        message.getId()
                );

        assertSame(
                expectedDto,
                result
        );

        assertEquals(
                MessageProcessingStatus.PROCESSED,
                message.getProcessingStatus()
        );

        assertNotNull(
                message.getProcessedAt()
        );

        verify(messageRepository)
                .save(message);

        verify(messageMapper)
                .toDto(message);
    }

    @Test
    void markProcessed_receivedMessage_throwsException() {

        message.setProcessingStatus(
                MessageProcessingStatus.RECEIVED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markProcessed(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markProcessed_queuedMessage_throwsException() {

        message.setProcessingStatus(
                MessageProcessingStatus.QUEUED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markProcessed(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markProcessed_processedMessage_throwsException() {

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markProcessed(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markProcessed_failedMessage_throwsException() {

        message.setProcessingStatus(
                MessageProcessingStatus.FAILED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markProcessed(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markProcessingFailed_processingMessage_changesStatusToFailed() {

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSING
        );

        stubMessageFound();

        when(messageRepository.save(message))
                .thenReturn(message);

        when(messageMapper.toDto(message))
                .thenReturn(expectedDto);

        MessageDto result =
                messageService.markProcessingFailed(
                        message.getId()
                );

        assertSame(
                expectedDto,
                result
        );

        assertEquals(
                MessageProcessingStatus.FAILED,
                message.getProcessingStatus()
        );

        assertNull(
                message.getProcessedAt()
        );

        verify(messageRepository)
                .save(message);

        verify(messageMapper)
                .toDto(message);
    }

    @Test
    void markProcessingFailed_receivedMessage_throwsException() {

        message.setProcessingStatus(
                MessageProcessingStatus.RECEIVED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markProcessingFailed(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markProcessingFailed_queuedMessage_throwsException() {

        message.setProcessingStatus(
                MessageProcessingStatus.QUEUED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markProcessingFailed(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markProcessingFailed_processedMessage_throwsException() {

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markProcessingFailed(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    // ============================================================
    // QUEUED STATE
    // ============================================================

    @Test
    void markQueued_processingMessage_changesStatusToQueued() {

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSING
        );

        stubMessageFound();

        when(messageRepository.save(message))
                .thenReturn(message);

        when(messageMapper.toDto(message))
                .thenReturn(expectedDto);

        MessageDto result =
                messageService.markQueued(
                        message.getId()
                );

        assertSame(
                expectedDto,
                result
        );

        assertEquals(
                MessageProcessingStatus.QUEUED,
                message.getProcessingStatus()
        );

        verify(messageRepository)
                .save(message);

        verify(messageMapper)
                .toDto(message);
    }

    @Test
    void markQueued_queuedMessage_isIdempotent() {

        message.setProcessingStatus(
                MessageProcessingStatus.QUEUED
        );

        stubMessageFound();

        when(messageMapper.toDto(message))
                .thenReturn(expectedDto);

        MessageDto result =
                messageService.markQueued(
                        message.getId()
                );

        assertSame(
                expectedDto,
                result
        );

        assertEquals(
                MessageProcessingStatus.QUEUED,
                message.getProcessingStatus()
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));

        verify(messageMapper)
                .toDto(message);
    }

    @Test
    void markQueued_receivedMessage_throwsException() {

        message.setProcessingStatus(
                MessageProcessingStatus.RECEIVED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markQueued(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markQueued_processedMessage_throwsException() {

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markQueued(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markQueued_failedMessage_throwsException() {

        message.setProcessingStatus(
                MessageProcessingStatus.FAILED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markQueued(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    // ============================================================
    // DELIVERY STATE
    // ============================================================

    @Test
    void markSent_queuedOutboundMessage_changesDeliveryToSent() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.QUEUED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        stubMessageFound();

        when(messageRepository
                .findByConversationIdAndExternalId(
                        conversation.getId(),
                        "telegram-message-123"
                ))
                .thenReturn(Optional.empty());

        when(messageRepository.save(message))
                .thenReturn(message);

        when(messageMapper.toDto(message))
                .thenReturn(expectedDto);

        MessageDto result =
                messageService.markSent(
                        message.getId(),
                        "telegram-message-123"
                );

        assertSame(
                expectedDto,
                result
        );

        assertEquals(
                MessageProcessingStatus.PROCESSED,
                message.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.SENT,
                message.getDeliveryStatus()
        );

        assertEquals(
                "telegram-message-123",
                message.getExternalId()
        );

        assertNotNull(
                message.getSentAt()
        );

        assertNotNull(
                message.getProcessedAt()
        );

        verify(messageRepository)
                .findByConversationIdAndExternalId(
                        conversation.getId(),
                        "telegram-message-123"
                );

        verify(messageRepository)
                .save(message);

        verify(messageMapper)
                .toDto(message);
    }

    @Test
    void markSent_inboundMessage_throwsException() {

        message.setDirection(
                MessageDirection.INBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.QUEUED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markSent(
                        message.getId(),
                        "telegram-message-123"
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markSent_nullExternalId_throwsException() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.QUEUED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        stubMessageFound();

        assertThrows(
                IllegalArgumentException.class,
                () -> messageService.markSent(
                        message.getId(),
                        null
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markSent_blankExternalId_throwsException() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.QUEUED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        stubMessageFound();

        assertThrows(
                IllegalArgumentException.class,
                () -> messageService.markSent(
                        message.getId(),
                        "   "
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markSent_processedOutboundMessage_throwsException() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markSent(
                        message.getId(),
                        "telegram-message-123"
                )
        );

        assertEquals(
                MessageDeliveryStatus.PENDING,
                message.getDeliveryStatus()
        );

        assertEquals(
                "external-123",
                message.getExternalId()
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markSent_sentMessageWithSameExternalId_isIdempotent() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.SENT
        );

        message.setExternalId(
                "telegram-message-123"
        );

        Instant sentAt =
                Instant.parse("2026-08-26T10:05:00Z");

        Instant processedAt =
                Instant.parse("2026-08-26T10:05:01Z");

        message.setSentAt(sentAt);
        message.setProcessedAt(processedAt);

        stubMessageFound();

        when(messageMapper.toDto(message))
                .thenReturn(expectedDto);

        MessageDto result =
                messageService.markSent(
                        message.getId(),
                        "telegram-message-123"
                );

        assertSame(
                expectedDto,
                result
        );

        assertEquals(
                MessageProcessingStatus.PROCESSED,
                message.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.SENT,
                message.getDeliveryStatus()
        );

        assertEquals(
                "telegram-message-123",
                message.getExternalId()
        );

        assertEquals(
                sentAt,
                message.getSentAt()
        );

        assertEquals(
                processedAt,
                message.getProcessedAt()
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));

        verify(messageMapper)
                .toDto(message);
    }

    @Test
    void markSent_sentMessageWithDifferentExternalId_throwsException() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.SENT
        );

        message.setExternalId(
                "telegram-message-123"
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markSent(
                        message.getId(),
                        "telegram-message-456"
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markDelivered_sentMessage_changesDeliveryToDelivered() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.SENT
        );

        stubMessageFound();

        when(messageRepository.save(message))
                .thenReturn(message);

        when(messageMapper.toDto(message))
                .thenReturn(expectedDto);

        MessageDto result =
                messageService.markDelivered(
                        message.getId()
                );

        assertSame(
                expectedDto,
                result
        );

        assertEquals(
                MessageProcessingStatus.PROCESSED,
                message.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.DELIVERED,
                message.getDeliveryStatus()
        );

        assertNotNull(
                message.getDeliveredAt()
        );

        verify(messageRepository)
                .save(message);

        verify(messageMapper)
                .toDto(message);
    }

    @Test
    void markDelivered_inboundMessage_throwsException() {

        message.setDirection(
                MessageDirection.INBOUND
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.SENT
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markDelivered(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markRead_sentMessage_changesDeliveryToRead() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.SENT
        );

        stubMessageFound();

        when(messageRepository.save(message))
                .thenReturn(message);

        when(messageMapper.toDto(message))
                .thenReturn(expectedDto);

        MessageDto result =
                messageService.markRead(
                        message.getId()
                );

        assertSame(
                expectedDto,
                result
        );

        assertEquals(
                MessageProcessingStatus.PROCESSED,
                message.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.READ,
                message.getDeliveryStatus()
        );

        assertNotNull(
                message.getReadAt()
        );

        verify(messageRepository)
                .save(message);

        verify(messageMapper)
                .toDto(message);
    }

    @Test
    void markRead_deliveredMessage_changesDeliveryToRead() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.DELIVERED
        );

        stubMessageFound();

        when(messageRepository.save(message))
                .thenReturn(message);

        when(messageMapper.toDto(message))
                .thenReturn(expectedDto);

        messageService.markRead(
                message.getId()
        );

        assertEquals(
                MessageProcessingStatus.PROCESSED,
                message.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.READ,
                message.getDeliveryStatus()
        );

        assertNotNull(
                message.getReadAt()
        );

        verify(messageRepository)
                .save(message);
    }

    @Test
    void markRead_inboundMessage_throwsException() {

        message.setDirection(
                MessageDirection.INBOUND
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.SENT
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markRead(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markDeliveryFailed_pendingMessage_changesDeliveryToFailed() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.QUEUED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        stubMessageFound();

        when(messageRepository.save(message))
                .thenReturn(message);

        when(messageMapper.toDto(message))
                .thenReturn(expectedDto);

        MessageDto result =
                messageService.markDeliveryFailed(
                        message.getId()
                );

        assertSame(
                expectedDto,
                result
        );

        assertEquals(
                MessageProcessingStatus.PROCESSED,
                message.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.FAILED,
                message.getDeliveryStatus()
        );

        assertNotNull(
                message.getProcessedAt()
        );

        verify(messageRepository)
                .save(message);

        verify(messageMapper)
                .toDto(message);
    }

    @Test
    void markDeliveryFailed_sentMessage_changesDeliveryToFailed() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.SENT
        );

        stubMessageFound();

        when(messageRepository.save(message))
                .thenReturn(message);

        when(messageMapper.toDto(message))
                .thenReturn(expectedDto);

        MessageDto result =
                messageService.markDeliveryFailed(
                        message.getId()
                );

        assertSame(
                expectedDto,
                result
        );

        assertEquals(
                MessageProcessingStatus.PROCESSED,
                message.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.FAILED,
                message.getDeliveryStatus()
        );

        verify(messageRepository)
                .save(message);

        verify(messageMapper)
                .toDto(message);
    }

    // ============================================================
    // INVALID TRANSITIONS
    // ============================================================

    @Test
    void markDelivered_pendingMessage_throwsException() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markDelivered(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markRead_pendingMessage_throwsException() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markRead(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markDeliveryFailed_deliveredMessage_throwsException() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.DELIVERED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markDeliveryFailed(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markDeliveryFailed_readMessage_throwsException() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.READ
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markDeliveryFailed(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    // ============================================================
    // IDEMPOTENCY
    // ============================================================

    @Test
    void markDelivered_deliveredMessage_isIdempotent() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.DELIVERED
        );

        Instant deliveredAt =
                Instant.parse("2026-08-26T10:05:00Z");

        message.setDeliveredAt(deliveredAt);

        stubMessageFound();

        when(messageMapper.toDto(message))
                .thenReturn(expectedDto);

        messageService.markDelivered(
                message.getId()
        );

        assertEquals(
                MessageDeliveryStatus.DELIVERED,
                message.getDeliveryStatus()
        );

        assertEquals(
                deliveredAt,
                message.getDeliveredAt()
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markRead_readMessage_isIdempotent() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.READ
        );

        Instant readAt =
                Instant.parse("2026-08-26T10:06:00Z");

        message.setReadAt(readAt);

        stubMessageFound();

        when(messageMapper.toDto(message))
                .thenReturn(expectedDto);

        messageService.markRead(
                message.getId()
        );

        assertEquals(
                MessageDeliveryStatus.READ,
                message.getDeliveryStatus()
        );

        assertEquals(
                readAt,
                message.getReadAt()
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    @Test
    void markDeliveryFailed_failedMessage_isIdempotent() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.FAILED
        );

        Instant processedAt =
                Instant.parse("2026-08-26T10:05:00Z");

        message.setProcessedAt(processedAt);

        stubMessageFound();

        when(messageMapper.toDto(message))
                .thenReturn(expectedDto);

        messageService.markDeliveryFailed(
                message.getId()
        );

        assertEquals(
                MessageProcessingStatus.PROCESSED,
                message.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.FAILED,
                message.getDeliveryStatus()
        );

        assertEquals(
                processedAt,
                message.getProcessedAt()
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));
    }

    // ============================================================
    // MARK READ UP TO
    // ============================================================

    @Test
    void markReadUpTo_marksSentOutboundMessagesUpToWatermarkAsRead() {

        UUID conversationId =
                UUID.fromString(
                        "11111111-1111-1111-1111-111111111111"
                );

        ConversationEntity conversation =
                new ConversationEntity();

        conversation.setId(conversationId);

        MessageEntity message101 =
                message(
                        conversation,
                        "101",
                        MessageDirection.OUTBOUND,
                        MessageDeliveryStatus.SENT
                );

        MessageEntity message102 =
                message(
                        conversation,
                        "102",
                        MessageDirection.OUTBOUND,
                        MessageDeliveryStatus.SENT
                );

        MessageEntity message105 =
                message(
                        conversation,
                        "105",
                        MessageDirection.OUTBOUND,
                        MessageDeliveryStatus.SENT
                );

        MessageEntity message106 =
                message(
                        conversation,
                        "106",
                        MessageDirection.OUTBOUND,
                        MessageDeliveryStatus.SENT
                );

        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));

        when(messageRepository
                .findAllByConversationIdAndDirectionAndDeliveryStatus(
                        conversationId,
                        MessageDirection.OUTBOUND,
                        MessageDeliveryStatus.SENT
                ))
                .thenReturn(List.of(
                        message101,
                        message102,
                        message105,
                        message106
                ));

        messageService.markReadUpTo(
                conversationId,
                105
        );

        assertThat(message101.getDeliveryStatus())
                .isEqualTo(MessageDeliveryStatus.READ);

        assertThat(message102.getDeliveryStatus())
                .isEqualTo(MessageDeliveryStatus.READ);

        assertThat(message105.getDeliveryStatus())
                .isEqualTo(MessageDeliveryStatus.READ);

        assertThat(message106.getDeliveryStatus())
                .isEqualTo(MessageDeliveryStatus.SENT);

        assertThat(message101.getReadAt()).isNotNull();
        assertThat(message102.getReadAt()).isNotNull();
        assertThat(message105.getReadAt()).isNotNull();
        assertThat(message106.getReadAt()).isNull();

        assertThat(message101.getReadAt())
                .isEqualTo(message102.getReadAt())
                .isEqualTo(message105.getReadAt());

        verify(messageRepository)
                .saveAll(List.of(
                        message101,
                        message102,
                        message105,
                        message106
                ));
    }

    @Test
    void markReadUpTo_ignoresInvalidExternalIds() {

        UUID conversationId =
                UUID.fromString(
                        "11111111-1111-1111-1111-111111111111"
                );

        ConversationEntity conversation =
                new ConversationEntity();

        conversation.setId(conversationId);

        MessageEntity nullExternalId =
                message(
                        conversation,
                        null,
                        MessageDirection.OUTBOUND,
                        MessageDeliveryStatus.SENT
                );

        MessageEntity blankExternalId =
                message(
                        conversation,
                        "   ",
                        MessageDirection.OUTBOUND,
                        MessageDeliveryStatus.SENT
                );

        MessageEntity invalidExternalId =
                message(
                        conversation,
                        "telegram-message-id",
                        MessageDirection.OUTBOUND,
                        MessageDeliveryStatus.SENT
                );

        MessageEntity validMessage =
                message(
                        conversation,
                        "100",
                        MessageDirection.OUTBOUND,
                        MessageDeliveryStatus.SENT
                );

        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));

        when(messageRepository
                .findAllByConversationIdAndDirectionAndDeliveryStatus(
                        conversationId,
                        MessageDirection.OUTBOUND,
                        MessageDeliveryStatus.SENT
                ))
                .thenReturn(List.of(
                        nullExternalId,
                        blankExternalId,
                        invalidExternalId,
                        validMessage
                ));

        messageService.markReadUpTo(
                conversationId,
                100
        );

        assertThat(nullExternalId.getDeliveryStatus())
                .isEqualTo(MessageDeliveryStatus.SENT);

        assertThat(blankExternalId.getDeliveryStatus())
                .isEqualTo(MessageDeliveryStatus.SENT);

        assertThat(invalidExternalId.getDeliveryStatus())
                .isEqualTo(MessageDeliveryStatus.SENT);

        assertThat(validMessage.getDeliveryStatus())
                .isEqualTo(MessageDeliveryStatus.READ);

        assertThat(nullExternalId.getReadAt()).isNull();
        assertThat(blankExternalId.getReadAt()).isNull();
        assertThat(invalidExternalId.getReadAt()).isNull();
        assertThat(validMessage.getReadAt()).isNotNull();

        verify(messageRepository)
                .saveAll(List.of(
                        nullExternalId,
                        blankExternalId,
                        invalidExternalId,
                        validMessage
                ));
    }

    @Test
    void markReadUpTo_doesNothingWhenWatermarkIsInvalid() {

        UUID conversationId =
                UUID.fromString(
                        "11111111-1111-1111-1111-111111111111"
                );

        messageService.markReadUpTo(
                conversationId,
                0
        );

        messageService.markReadUpTo(
                conversationId,
                -1
        );

        verifyNoInteractions(
                conversationRepository,
                messageRepository
        );
    }

    @Test
    void markReadUpTo_doesNothingWhenNoSentMessagesExist() {

        UUID conversationId =
                UUID.fromString(
                        "11111111-1111-1111-1111-111111111111"
                );

        ConversationEntity conversation =
                new ConversationEntity();

        conversation.setId(conversationId);

        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));

        when(messageRepository
                .findAllByConversationIdAndDirectionAndDeliveryStatus(
                        conversationId,
                        MessageDirection.OUTBOUND,
                        MessageDeliveryStatus.SENT
                ))
                .thenReturn(List.of());

        messageService.markReadUpTo(
                conversationId,
                105
        );

        verify(messageRepository, never())
                .saveAll(anyList());
    }

    @Test
    void markReadUpTo_doesNotMarkMessagesAboveWatermark() {

        UUID conversationId =
                UUID.fromString(
                        "11111111-1111-1111-1111-111111111111"
                );

        ConversationEntity conversation =
                new ConversationEntity();

        conversation.setId(conversationId);

        MessageEntity message106 =
                message(
                        conversation,
                        "106",
                        MessageDirection.OUTBOUND,
                        MessageDeliveryStatus.SENT
                );

        MessageEntity message200 =
                message(
                        conversation,
                        "200",
                        MessageDirection.OUTBOUND,
                        MessageDeliveryStatus.SENT
                );

        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));

        when(messageRepository
                .findAllByConversationIdAndDirectionAndDeliveryStatus(
                        conversationId,
                        MessageDirection.OUTBOUND,
                        MessageDeliveryStatus.SENT
                ))
                .thenReturn(List.of(
                        message106,
                        message200
                ));

        messageService.markReadUpTo(
                conversationId,
                105
        );

        assertThat(message106.getDeliveryStatus())
                .isEqualTo(MessageDeliveryStatus.SENT);

        assertThat(message200.getDeliveryStatus())
                .isEqualTo(MessageDeliveryStatus.SENT);

        assertThat(message106.getReadAt()).isNull();
        assertThat(message200.getReadAt()).isNull();

        verify(messageRepository)
                .saveAll(List.of(
                        message106,
                        message200
                ));
    }

    @Test
    void markReadUpTo_unknownConversation_throwsException() {

        UUID conversationId =
                UUID.fromString(
                        "11111111-1111-1111-1111-111111111111"
                );

        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.empty());

        assertThrows(
                EntityNotFoundException.class,
                () -> messageService.markReadUpTo(
                        conversationId,
                        105
                )
        );

        verify(conversationRepository)
                .findById(conversationId);

        verify(messageRepository, never())
                .findAllByConversationIdAndDirectionAndDeliveryStatus(
                        any(),
                        any(),
                        any()
                );

        verify(messageRepository, never())
                .saveAll(anyList());
    }

    @Test
    void markReadUpTo_usesSameReadTimestampForAllMessages() {

        UUID conversationId =
                UUID.fromString(
                        "11111111-1111-1111-1111-111111111111"
                );

        ConversationEntity conversation =
                new ConversationEntity();

        conversation.setId(conversationId);

        MessageEntity message101 =
                message(
                        conversation,
                        "101",
                        MessageDirection.OUTBOUND,
                        MessageDeliveryStatus.SENT
                );

        MessageEntity message102 =
                message(
                        conversation,
                        "102",
                        MessageDirection.OUTBOUND,
                        MessageDeliveryStatus.SENT
                );

        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));

        when(messageRepository
                .findAllByConversationIdAndDirectionAndDeliveryStatus(
                        conversationId,
                        MessageDirection.OUTBOUND,
                        MessageDeliveryStatus.SENT
                ))
                .thenReturn(List.of(
                        message101,
                        message102
                ));

        messageService.markReadUpTo(
                conversationId,
                102
        );

        assertThat(message101.getReadAt())
                .isNotNull();

        assertThat(message102.getReadAt())
                .isNotNull();

        assertThat(message101.getReadAt())
                .isEqualTo(message102.getReadAt());

        verify(messageRepository)
                .saveAll(List.of(
                        message101,
                        message102
                ));
    }

// ============================================================
// RETRY DELIVERY
// ============================================================

    @Test
    void retryDelivery_failedOutboundMessage_changesStateToProcessingPending() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.FAILED
        );

        message.setExternalId(
                "telegram-message-123"
        );

        Instant sentAt =
                Instant.parse("2026-08-26T10:05:00Z");

        Instant deliveredAt =
                Instant.parse("2026-08-26T10:06:00Z");

        Instant readAt =
                Instant.parse("2026-08-26T10:07:00Z");

        message.setSentAt(sentAt);
        message.setDeliveredAt(deliveredAt);
        message.setReadAt(readAt);
        message.setProcessedAt(
                Instant.parse("2026-08-26T10:05:01Z")
        );

        stubMessageFound();

        when(messageRepository.save(message))
                .thenReturn(message);

        when(messageMapper.toDto(message))
                .thenReturn(expectedDto);

        MessageDto result =
                messageService.retryDelivery(
                        message.getId()
                );

        assertSame(
                expectedDto,
                result
        );

        assertEquals(
                MessageProcessingStatus.PROCESSING,
                message.getProcessingStatus()
        );

        assertEquals(
                MessageDeliveryStatus.PENDING,
                message.getDeliveryStatus()
        );

        assertEquals(
                "telegram-message-123",
                message.getExternalId()
        );

        assertEquals(
                sentAt,
                message.getSentAt()
        );

        assertEquals(
                deliveredAt,
                message.getDeliveredAt()
        );

        assertEquals(
                readAt,
                message.getReadAt()
        );

        assertNull(
                message.getProcessedAt()
        );

        verify(messageRepository)
                .save(message);

        verify(messageMapper)
                .toDto(message);
    }

    @Test
    void retryDelivery_inboundMessage_throwsException() {

        message.setDirection(
                MessageDirection.INBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.FAILED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.retryDelivery(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));

        verifyNoInteractions(messageMapper);
    }

    @Test
    void retryDelivery_processingStatusIsNotProcessed_throwsException() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSING
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.FAILED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.retryDelivery(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));

        verifyNoInteractions(messageMapper);
    }

    @Test
    void retryDelivery_deliveryStatusIsNotFailed_throwsException() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.SENT
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.retryDelivery(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));

        verifyNoInteractions(messageMapper);
    }

    @Test
    void retryDelivery_deliveredMessage_throwsException() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.DELIVERED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.retryDelivery(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));

        verifyNoInteractions(messageMapper);
    }

    @Test
    void retryDelivery_readMessage_throwsException() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.READ
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.retryDelivery(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));

        verifyNoInteractions(messageMapper);
    }

    @Test
    void retryDelivery_receivedMessage_throwsException() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.RECEIVED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.FAILED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.retryDelivery(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));

        verifyNoInteractions(messageMapper);
    }

    @Test
    void retryDelivery_queuedMessage_throwsException() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.QUEUED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.FAILED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.retryDelivery(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));

        verifyNoInteractions(messageMapper);
    }

    @Test
    void retryDelivery_failedProcessingStatus_throwsException() {

        message.setDirection(
                MessageDirection.OUTBOUND
        );

        message.setProcessingStatus(
                MessageProcessingStatus.FAILED
        );

        message.setDeliveryStatus(
                MessageDeliveryStatus.FAILED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.retryDelivery(
                        message.getId()
                )
        );

        verify(messageRepository, never())
                .save(any(MessageEntity.class));

        verifyNoInteractions(messageMapper);
    }

    private MessageEntity message(
            ConversationEntity conversation,
            String externalId,
            MessageDirection direction,
            MessageDeliveryStatus deliveryStatus
    ) {
        MessageEntity message =
                new MessageEntity();

        message.setConversation(conversation);
        message.setExternalId(externalId);
        message.setDirection(direction);
        message.setDeliveryStatus(deliveryStatus);
        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );

        return message;
    }
}