package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.message.CreateInboundMessageRequest;
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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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

        /*
         * Повторный inbound не должен менять
         * существующий Message.
         */
        assertEquals(
                "Hello",
                message.getContent()
        );

        verify(conversationRepository)
                .findById(conversationId);

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

        messageService.startProcessing(message.getId());

        assertEquals(
                MessageProcessingStatus.PROCESSING,
                message.getProcessingStatus()
        );
    }

    @Test
    void markProcessed_processingMessage_changesStatusToProcessed() {

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSING
        );

        stubMessageFound();

        messageService.markProcessed(message.getId());

        assertEquals(
                MessageProcessingStatus.PROCESSED,
                message.getProcessingStatus()
        );
    }

    @Test
    void markProcessingFailed_processingMessage_changesStatusToFailed() {

        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSING
        );

        stubMessageFound();

        messageService.markProcessingFailed(message.getId());

        assertEquals(
                MessageProcessingStatus.FAILED,
                message.getProcessingStatus()
        );
    }

    // ============================================================
    // DELIVERY STATE
    // ============================================================

    @Test
    void markSent_processedOutboundMessage_changesDeliveryToSent() {

        message.setDirection(MessageDirection.OUTBOUND);
        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );
        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        stubMessageFound();

        messageService.markSent(
                message.getId(),
                "telegram-message-123"
        );

        assertEquals(
                MessageDeliveryStatus.SENT,
                message.getDeliveryStatus()
        );

        assertEquals(
                "telegram-message-123",
                message.getExternalId()
        );

        assertNotNull(message.getSentAt());
    }

    @Test
    void markDelivered_sentMessage_changesDeliveryToDelivered() {

        message.setDirection(MessageDirection.OUTBOUND);
        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );
        message.setDeliveryStatus(
                MessageDeliveryStatus.SENT
        );

        stubMessageFound();

        messageService.markDelivered(message.getId());

        assertEquals(
                MessageDeliveryStatus.DELIVERED,
                message.getDeliveryStatus()
        );

        assertNotNull(message.getDeliveredAt());
    }

    @Test
    void markRead_sentMessage_changesDeliveryToRead() {

        message.setDirection(MessageDirection.OUTBOUND);
        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );
        message.setDeliveryStatus(
                MessageDeliveryStatus.SENT
        );

        stubMessageFound();

        messageService.markRead(message.getId());

        assertEquals(
                MessageDeliveryStatus.READ,
                message.getDeliveryStatus()
        );

        assertNotNull(message.getReadAt());
    }

    @Test
    void markRead_deliveredMessage_changesDeliveryToRead() {

        message.setDirection(MessageDirection.OUTBOUND);
        message.setProcessingStatus(
                MessageProcessingStatus.PROCESSED
        );
        message.setDeliveryStatus(
                MessageDeliveryStatus.DELIVERED
        );

        stubMessageFound();

        messageService.markRead(message.getId());

        assertEquals(
                MessageDeliveryStatus.READ,
                message.getDeliveryStatus()
        );

        assertNotNull(message.getReadAt());
    }

    @Test
    void markDeliveryFailed_pendingMessage_changesDeliveryToFailed() {

        message.setDirection(MessageDirection.OUTBOUND);
        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        stubMessageFound();

        messageService.markDeliveryFailed(message.getId());

        assertEquals(
                MessageDeliveryStatus.FAILED,
                message.getDeliveryStatus()
        );
    }

    @Test
    void markDeliveryFailed_sentMessage_changesDeliveryToFailed() {

        message.setDirection(MessageDirection.OUTBOUND);
        message.setDeliveryStatus(
                MessageDeliveryStatus.SENT
        );

        stubMessageFound();

        messageService.markDeliveryFailed(message.getId());

        assertEquals(
                MessageDeliveryStatus.FAILED,
                message.getDeliveryStatus()
        );
    }

    // ============================================================
    // INVALID TRANSITIONS
    // ============================================================

    @Test
    void markDelivered_pendingMessage_throwsException() {

        message.setDirection(MessageDirection.OUTBOUND);
        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markDelivered(message.getId())
        );
    }

    @Test
    void markRead_pendingMessage_throwsException() {

        message.setDirection(MessageDirection.OUTBOUND);
        message.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markRead(message.getId())
        );
    }

    @Test
    void markDeliveryFailed_deliveredMessage_throwsException() {

        message.setDirection(MessageDirection.OUTBOUND);
        message.setDeliveryStatus(
                MessageDeliveryStatus.DELIVERED
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markDeliveryFailed(message.getId())
        );
    }

    @Test
    void markDeliveryFailed_readMessage_throwsException() {

        message.setDirection(MessageDirection.OUTBOUND);
        message.setDeliveryStatus(
                MessageDeliveryStatus.READ
        );

        stubMessageFound();

        assertThrows(
                IllegalStateException.class,
                () -> messageService.markDeliveryFailed(message.getId())
        );
    }

    // ============================================================
    // IDEMPOTENCY
    // ============================================================

    @Test
    void markDelivered_deliveredMessage_isIdempotent() {

        message.setDirection(MessageDirection.OUTBOUND);
        message.setDeliveryStatus(
                MessageDeliveryStatus.DELIVERED
        );

        Instant deliveredAt =
                Instant.parse("2026-08-26T10:05:00Z");

        message.setDeliveredAt(deliveredAt);

        stubMessageFound();

        messageService.markDelivered(message.getId());

        assertEquals(
                MessageDeliveryStatus.DELIVERED,
                message.getDeliveryStatus()
        );

        assertEquals(
                deliveredAt,
                message.getDeliveredAt()
        );
    }

    @Test
    void markRead_readMessage_isIdempotent() {

        message.setDirection(MessageDirection.OUTBOUND);
        message.setDeliveryStatus(
                MessageDeliveryStatus.READ
        );

        Instant readAt =
                Instant.parse("2026-08-26T10:06:00Z");

        message.setReadAt(readAt);

        stubMessageFound();

        messageService.markRead(message.getId());

        assertEquals(
                MessageDeliveryStatus.READ,
                message.getDeliveryStatus()
        );

        assertEquals(
                readAt,
                message.getReadAt()
        );
    }

    @Test
    void markDeliveryFailed_failedMessage_isIdempotent() {

        message.setDirection(MessageDirection.OUTBOUND);
        message.setDeliveryStatus(
                MessageDeliveryStatus.FAILED
        );

        stubMessageFound();

        messageService.markDeliveryFailed(message.getId());

        assertEquals(
                MessageDeliveryStatus.FAILED,
                message.getDeliveryStatus()
        );
    }
}