package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.message.CreateInboundMessageRequest;
import kit.penny.clientbus.common.dto.message.MessageDto;
import kit.penny.clientbus.common.enums.MessageDirection;
import kit.penny.clientbus.common.enums.MessageProcessingStatus;
import kit.penny.clientbus.common.enums.MessageSenderType;
import kit.penny.clientbus.common.enums.MessageType;
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

    @Test
    void createInboundMessage_newMessage_returnsExistedFalse() {

        CreateInboundMessageRequest request =
                new CreateInboundMessageRequest(
                        conversationId,
                        MessageType.TEXT,
                        "external-123",
                        "Hello",
                        "{\"source\":\"telegram\"}",
                        Instant.parse(
                                "2026-08-26T10:00:00Z"
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
                .thenReturn(Optional.empty());

        when(messageRepository.save(any(MessageEntity.class)))
                .thenReturn(message);

        when(messageMapper.toDto(message))
                .thenReturn(expectedDto);

        MessageCreationResult result =
                messageService.createInboundMessage(request);

        assertNotNull(result);

        assertFalse(result.existed());

        assertSame(
                expectedDto,
                result.message()
        );

        assertEquals(
                conversation,
                message.getConversation()
        );

        assertEquals(
                MessageType.TEXT,
                message.getType()
        );

        assertEquals(
                MessageDirection.INBOUND,
                message.getDirection()
        );

        assertEquals(
                MessageSenderType.CLIENT,
                message.getSenderType()
        );

        assertSame(
                clientAccount,
                message.getClientAccount()
        );

        assertNull(
                message.getEmployee()
        );

        assertEquals(
                "external-123",
                message.getExternalId()
        );

        assertEquals(
                "Hello",
                message.getContent()
        );

        assertEquals(
                "{\"source\":\"telegram\"}",
                message.getMetadata()
        );

        assertEquals(
                MessageProcessingStatus.RECEIVED,
                message.getProcessingStatus()
        );

        assertNull(
                message.getDeliveryStatus()
        );

        verify(conversationRepository)
                .findById(conversationId);

        verify(messageRepository)
                .findByConversationIdAndExternalId(
                        conversationId,
                        "external-123"
                );

        verify(messageRepository)
                .save(message);

        verify(conversationService)
                .updateLastMessage(
                        eq(conversation),
                        eq(message.getSentAt()),
                        any()
                );

        verify(conversationService)
                .incrementUnreadCount(conversation);

        verify(messageMapper)
                .toDto(message);
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
}