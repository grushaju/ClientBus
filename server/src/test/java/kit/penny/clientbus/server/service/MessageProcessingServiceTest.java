package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.message.*;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.common.kafka.PlatformOutboundAttachment;
import kit.penny.clientbus.server.kafka.producer.IOutboundMessagePublisher;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.MessageAttachmentEntity;
import kit.penny.clientbus.server.persistence.entity.MessageEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.persistence.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageProcessingServiceTest {

    @Mock
    private ChannelAccountRepository channelAccountRepository;

    @Mock
    private ClientAccountRepository clientAccountRepository;

    @Mock
    private ClientAccountService clientAccountService;

    @Mock
    private ConversationService conversationService;

    @Mock
    private MessageService messageService;

    @Mock
    private MessageAttachmentService messageAttachmentService;

    @Mock
    private IOutboundMessagePublisher outboundMessagePublisher;

    @Mock
    private MessageRepository messageRepository;

    @InjectMocks
    private MessageProcessingService messageProcessingService;

    private UUID channelAccountId;
    private UUID clientAccountId;
    private UUID conversationId;
    private UUID messageId;
    private UUID sourceMessageId;
    private UUID targetConversationId;

    private WorkspaceEntity workspace;

    private ChannelEntity channel;

    private ChannelAccountEntity channelAccount;

    private ClientAccountEntity clientAccount;

    private ConversationEntity conversation;

    private ConversationEntity targetConversation;

    private MessageEntity messageEntity;

    private MessageEntity sourceMessageEntity;

    private MessageEntity targetMessageEntity;

    private MessageDto messageDto;

    private MessageDto forwardedMessageDto;

    @BeforeEach
    void setUp() {

        channelAccountId = UUID.randomUUID();
        clientAccountId = UUID.randomUUID();
        conversationId = UUID.randomUUID();
        messageId = UUID.randomUUID();
        sourceMessageId = UUID.randomUUID();
        targetConversationId = UUID.randomUUID();

        workspace = new WorkspaceEntity();
        workspace.setId(UUID.randomUUID());
        workspace.setName("Test Workspace");

        channel = new ChannelEntity(
                workspace,
                ChannelType.TELEGRAM,
                "Telegram"
        );

        channel.setId(UUID.randomUUID());

        channelAccount =
                new ChannelAccountEntity(
                        channel,
                        "company-telegram",
                        "company",
                        "+79990000000",
                        "Company"
                );

        channelAccount.setId(channelAccountId);

        channel.setAccount(channelAccount);

        clientAccount =
                new ClientAccountEntity();

        clientAccount.setId(clientAccountId);
        clientAccount.setChannelType(
                ChannelType.TELEGRAM
        );
        clientAccount.setExternalId(
                "client-123"
        );
        clientAccount.setUsername(
                "client"
        );
        clientAccount.setDisplayName(
                "Client"
        );

        conversation =
                new ConversationEntity(
                        workspace,
                        channelAccount,
                        clientAccount
                );

        conversation.setId(conversationId);

        messageEntity =
                new MessageEntity(
                        conversation,
                        MessageType.TEXT,
                        MessageDirection.OUTBOUND,
                        MessageSenderType.EMPLOYEE
                );

        messageEntity.setId(messageId);
        messageEntity.setContent("Hello");
        messageEntity.setProcessingStatus(
                MessageProcessingStatus.RECEIVED
        );
        messageEntity.setDeliveryStatus(
                MessageDeliveryStatus.PENDING
        );

        messageDto =
                mock(MessageDto.class);

        targetConversation =
                new ConversationEntity(
                        workspace,
                        channelAccount,
                        clientAccount
                );

        targetConversation.setId(
                targetConversationId
        );

        sourceMessageEntity =
                new MessageEntity(
                        conversation,
                        MessageType.TEXT,
                        MessageDirection.INBOUND,
                        MessageSenderType.CLIENT
                );

        sourceMessageEntity.setId(
                sourceMessageId
        );

        sourceMessageEntity.setContent(
                "Original message"
        );

        sourceMessageEntity.setMetadata(
                "{\"source\":\"telegram\"}"
        );

        forwardedMessageDto =
                mock(MessageDto.class);

    }

    // =========================================================
    // INBOUND
    // =========================================================

    @Test
    void processInbound_newMessage_createsAttachments() {

        InboundMessageRequest request =
                new InboundMessageRequest(
                        channelAccountId,
                        "client-123",
                        "client",
                        "+79991112233",
                        "Client",
                        "external-123",
                        MessageType.TEXT,
                        "Hello",
                        "{\"source\":\"telegram\"}",
                        Instant.parse(
                                "2026-08-26T10:00:00Z"
                        )
                );

        AttachmentContent attachment =
                new AttachmentContent(
                        MessageAttachmentType.IMAGE,
                        "photo.jpg",
                        "image/jpeg",
                        1024,
                        new ByteArrayInputStream(
                                new byte[]{1, 2, 3}
                        )
                );

        MessageCreationResult creationResult =
                new MessageCreationResult(
                        messageDto,
                        false
                );

        when(messageDto.id())
                .thenReturn(messageId);

        when(channelAccountRepository.findById(
                channelAccountId
        )).thenReturn(
                Optional.of(channelAccount)
        );

        when(clientAccountService.getOrCreateForInbound(
                ChannelType.TELEGRAM,
                "client-123",
                "client",
                "+79991112233",
                "Client"
        )).thenReturn(
                clientAccount
        );

        when(conversationService.findEntityByAccounts(
                channelAccountId,
                clientAccountId
        )).thenReturn(
                conversation
        );

        when(messageService.createInboundMessage(
                any(CreateInboundMessageRequest.class)
        )).thenReturn(
                creationResult
        );

        when(messageService.getMessageEntityForProcessing(
                messageId
        )).thenReturn(
                messageEntity
        );

        MessageDto result =
                messageProcessingService.processInbound(
                        request,
                        List.of(attachment)
                );

        assertSame(
                messageDto,
                result
        );

        verify(channelAccountRepository)
                .findById(channelAccountId);

        verify(clientAccountService)
                .getOrCreateForInbound(
                        ChannelType.TELEGRAM,
                        "client-123",
                        "client",
                        "+79991112233",
                        "Client"
                );

        verify(conversationService)
                .findEntityByAccounts(
                        channelAccountId,
                        clientAccountId
                );

        ArgumentCaptor<CreateInboundMessageRequest>
                requestCaptor =
                ArgumentCaptor.forClass(
                        CreateInboundMessageRequest.class
                );

        verify(messageService)
                .createInboundMessage(
                        requestCaptor.capture()
                );

        CreateInboundMessageRequest
                createRequest =
                requestCaptor.getValue();

        assertEquals(
                conversationId,
                createRequest.conversationId()
        );

        assertEquals(
                MessageType.TEXT,
                createRequest.type()
        );

        assertEquals(
                "external-123",
                createRequest.externalId()
        );

        assertEquals(
                "Hello",
                createRequest.content()
        );

        verify(messageService)
                .getMessageEntityForProcessing(
                        messageId
                );

        verify(messageAttachmentService)
                .createAttachment(
                        messageEntity,
                        attachment
                );
    }

    @Test
    void processInbound_existingMessage_doesNotCreateAttachments() {

        InboundMessageRequest request =
                new InboundMessageRequest(
                        channelAccountId,
                        "client-123",
                        "client",
                        null,
                        "Client",
                        "external-123",
                        MessageType.TEXT,
                        "Hello",
                        null,
                        null
                );

        AttachmentContent attachment =
                new AttachmentContent(
                        MessageAttachmentType.IMAGE,
                        "photo.jpg",
                        "image/jpeg",
                        1024,
                        new ByteArrayInputStream(
                                new byte[]{1, 2, 3}
                        )
                );

        MessageCreationResult creationResult =
                new MessageCreationResult(
                        messageDto,
                        true
                );

        when(channelAccountRepository.findById(
                channelAccountId
        )).thenReturn(
                Optional.of(channelAccount)
        );

        when(clientAccountService.getOrCreateForInbound(
                ChannelType.TELEGRAM,
                "client-123",
                "client",
                null,
                "Client"
        )).thenReturn(
                clientAccount
        );

        when(conversationService.findEntityByAccounts(
                channelAccountId,
                clientAccountId
        )).thenReturn(
                conversation
        );

        when(messageService.createInboundMessage(
                any(CreateInboundMessageRequest.class)
        )).thenReturn(
                creationResult
        );

        MessageDto result =
                messageProcessingService.processInbound(
                        request,
                        List.of(attachment)
                );

        assertSame(
                messageDto,
                result
        );

        verify(messageService)
                .createInboundMessage(
                        any(CreateInboundMessageRequest.class)
                );

        verify(
                messageService,
                never()
        ).getMessageEntityForProcessing(any());

        verify(
                messageAttachmentService,
                never()
        ).createAttachment(
                any(MessageEntity.class),
                any(AttachmentContent.class)
        );
    }

    @Test
    void processInbound_conversationDoesNotExist_createsConversation() {

        InboundMessageRequest request =
                new InboundMessageRequest(
                        channelAccountId,
                        "client-123",
                        "client",
                        null,
                        "Client",
                        "external-123",
                        MessageType.TEXT,
                        "Hello",
                        null,
                        null
                );

        MessageCreationResult creationResult =
                new MessageCreationResult(
                        messageDto,
                        true
                );

        when(channelAccountRepository.findById(
                channelAccountId
        )).thenReturn(
                Optional.of(channelAccount)
        );

        when(clientAccountService.getOrCreateForInbound(
                any(),
                anyString(),
                any(),
                any(),
                any()
        )).thenReturn(
                clientAccount
        );

        when(conversationService.findEntityByAccounts(
                channelAccountId,
                clientAccountId
        )).thenReturn(null);

        when(conversationService.createConversationInternal(
                channelAccount,
                clientAccount
        )).thenReturn(
                conversation
        );

        when(messageService.createInboundMessage(
                any(CreateInboundMessageRequest.class)
        )).thenReturn(
                creationResult
        );

        messageProcessingService.processInbound(
                request,
                List.of()
        );

        verify(conversationService)
                .createConversationInternal(
                        channelAccount,
                        clientAccount
                );
    }


    @Test
    void processInbound_event_newMessage_createsStoredAttachments() {

        UUID channelAccountId = UUID.randomUUID();
        UUID clientAccountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        ChannelAccountEntity channelAccount =
                mock(ChannelAccountEntity.class);

        ClientAccountEntity clientAccount =
                mock(ClientAccountEntity.class);

        ConversationEntity conversation =
                mock(ConversationEntity.class);

        ChannelEntity channel =
                mock(ChannelEntity.class);

        MessageDto message =
                mock(MessageDto.class);

        MessageEntity messageEntity =
                new MessageEntity();

        InboundMessageRequest request =
                new InboundMessageRequest(
                        channelAccountId,
                        "client-123",
                        "username",
                        "+79990000000",
                        "Test Client",
                        "external-123",
                        MessageType.TEXT,
                        "hello",
                        null,
                        Instant.now()
                );

        PlatformInboundAttachment attachment =
                new PlatformInboundAttachment(
                        MessageAttachmentType.IMAGE,
                        "storage/image.jpg",
                        "image.jpg",
                        "image/jpeg",
                        1024
                );

        PlatformInboundMessageEvent event =
                new PlatformInboundMessageEvent(
                        request,
                        List.of(attachment)
                );

        when(channelAccountRepository.findById(channelAccountId))
                .thenReturn(Optional.of(channelAccount));

        when(channelAccount.getChannel())
                .thenReturn(channel);

        when(channel.getType())
                .thenReturn(ChannelType.TELEGRAM);

        when(clientAccountService.getOrCreateForInbound(
                ChannelType.TELEGRAM,
                "client-123",
                "username",
                "+79990000000",
                "Test Client"
        )).thenReturn(clientAccount);

        when(channelAccount.getId())
                .thenReturn(channelAccountId);

        when(clientAccount.getId())
                .thenReturn(clientAccountId);

        when(conversationService.findEntityByAccounts(
                channelAccountId,
                clientAccountId
        )).thenReturn(conversation);

        when(conversation.getId())
                .thenReturn(conversationId);

        MessageCreationResult creationResult =
                new MessageCreationResult(
                        message,
                        false
                );

        when(messageService.createInboundMessage(
                any(CreateInboundMessageRequest.class)
        )).thenReturn(creationResult);

        when(message.id())
                .thenReturn(messageId);

        when(messageService.getMessageEntityForProcessing(
                messageId
        )).thenReturn(messageEntity);

        MessageDto result =
                messageProcessingService.processInbound(event);

        assertSame(message, result);

        verify(messageAttachmentService)
                .createAttachmentFromStorage(
                        messageEntity,
                        MessageAttachmentType.IMAGE,
                        "storage/image.jpg",
                        "image.jpg",
                        "image/jpeg",
                        1024
                );
    }

    @Test
    void processInbound_event_existingMessage_doesNotCreateAttachments() {

        UUID channelAccountId = UUID.randomUUID();
        UUID clientAccountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();

        ChannelAccountEntity channelAccount =
                mock(ChannelAccountEntity.class);

        ClientAccountEntity clientAccount =
                mock(ClientAccountEntity.class);

        ConversationEntity conversation =
                mock(ConversationEntity.class);

        ChannelEntity channel =
                mock(ChannelEntity.class);

        MessageDto message =
                mock(MessageDto.class);

        InboundMessageRequest request =
                new InboundMessageRequest(
                        channelAccountId,
                        "client-123",
                        "username",
                        null,
                        "Test Client",
                        "external-123",
                        MessageType.TEXT,
                        "hello",
                        null,
                        Instant.now()
                );

        PlatformInboundAttachment attachment =
                new PlatformInboundAttachment(
                        MessageAttachmentType.IMAGE,
                        "storage/image.jpg",
                        "image.jpg",
                        "image/jpeg",
                        1024
                );

        PlatformInboundMessageEvent event =
                new PlatformInboundMessageEvent(
                        request,
                        List.of(attachment)
                );

        when(channelAccountRepository.findById(channelAccountId))
                .thenReturn(Optional.of(channelAccount));

        when(channelAccount.getChannel())
                .thenReturn(channel);

        when(channel.getType())
                .thenReturn(ChannelType.TELEGRAM);

        when(clientAccountService.getOrCreateForInbound(
                ChannelType.TELEGRAM,
                "client-123",
                "username",
                null,
                "Test Client"
        )).thenReturn(clientAccount);

        when(channelAccount.getId())
                .thenReturn(channelAccountId);

        when(clientAccount.getId())
                .thenReturn(clientAccountId);

        when(conversationService.findEntityByAccounts(
                channelAccountId,
                clientAccountId
        )).thenReturn(conversation);

        when(conversation.getId())
                .thenReturn(conversationId);

        when(messageService.createInboundMessage(
                any(CreateInboundMessageRequest.class)
        )).thenReturn(
                new MessageCreationResult(
                        message,
                        true
                )
        );

        MessageDto result =
                messageProcessingService.processInbound(event);

        assertSame(message, result);

        verify(messageAttachmentService, never())
                .createAttachmentFromStorage(
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong()
                );

        verify(messageService, never())
                .getMessageEntityForProcessing(
                        any()
                );
    }

    @Test
    void processInbound_event_newMessage_createsAllStoredAttachments() {

        UUID channelAccountId = UUID.randomUUID();
        UUID clientAccountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        ChannelAccountEntity channelAccount =
                mock(ChannelAccountEntity.class);

        ClientAccountEntity clientAccount =
                mock(ClientAccountEntity.class);

        ConversationEntity conversation =
                mock(ConversationEntity.class);

        ChannelEntity channel =
                mock(ChannelEntity.class);

        MessageDto message =
                mock(MessageDto.class);

        MessageEntity messageEntity =
                new MessageEntity();

        InboundMessageRequest request =
                new InboundMessageRequest(
                        channelAccountId,
                        "client-123",
                        "username",
                        null,
                        "Test Client",
                        "external-123",
                        MessageType.TEXT,
                        "hello",
                        null,
                        Instant.now()
                );

        PlatformInboundAttachment first =
                new PlatformInboundAttachment(
                        MessageAttachmentType.IMAGE,
                        "storage/image.jpg",
                        "image.jpg",
                        "image/jpeg",
                        100
                );

        PlatformInboundAttachment second =
                new PlatformInboundAttachment(
                        MessageAttachmentType.AUDIO,
                        "storage/audio.mp3",
                        "audio.mp3",
                        "audio/mpeg",
                        200
                );

        PlatformInboundMessageEvent event =
                new PlatformInboundMessageEvent(
                        request,
                        List.of(first, second)
                );

        when(channelAccountRepository.findById(channelAccountId))
                .thenReturn(Optional.of(channelAccount));

        when(channelAccount.getChannel())
                .thenReturn(channel);

        when(channel.getType())
                .thenReturn(ChannelType.TELEGRAM);

        when(clientAccountService.getOrCreateForInbound(
                ChannelType.TELEGRAM,
                "client-123",
                "username",
                null,
                "Test Client"
        )).thenReturn(clientAccount);

        when(channelAccount.getId())
                .thenReturn(channelAccountId);

        when(clientAccount.getId())
                .thenReturn(clientAccountId);

        when(conversationService.findEntityByAccounts(
                channelAccountId,
                clientAccountId
        )).thenReturn(conversation);

        when(conversation.getId())
                .thenReturn(conversationId);

        when(messageService.createInboundMessage(
                any(CreateInboundMessageRequest.class)
        )).thenReturn(
                new MessageCreationResult(
                        message,
                        false
                )
        );

        when(message.id())
                .thenReturn(messageId);

        when(messageService.getMessageEntityForProcessing(
                messageId
        )).thenReturn(messageEntity);

        MessageDto result =
                messageProcessingService.processInbound(event);

        assertSame(message, result);

        verify(messageAttachmentService)
                .createAttachmentFromStorage(
                        messageEntity,
                        MessageAttachmentType.IMAGE,
                        "storage/image.jpg",
                        "image.jpg",
                        "image/jpeg",
                        100
                );

        verify(messageAttachmentService)
                .createAttachmentFromStorage(
                        messageEntity,
                        MessageAttachmentType.AUDIO,
                        "storage/audio.mp3",
                        "audio.mp3",
                        "audio/mpeg",
                        200
                );
    }


    // =========================================================
    // OUTBOUND
    // =========================================================

    @Test
    void processOutbound_success_sendsMessageWithAttachments() {

        OutboundMessageRequest request =
                new OutboundMessageRequest(
                        conversationId,
                        MessageType.TEXT,
                        "Hello",
                        "{\"source\":\"ui\"}",
                        null
                );

        AttachmentContent attachment =
                new AttachmentContent(
                        MessageAttachmentType.IMAGE,
                        "photo.jpg",
                        "image/jpeg",
                        1024,
                        new ByteArrayInputStream(
                                new byte[]{1, 2, 3}
                        )
                );

        MessageAttachmentEntity storedAttachment =
                new MessageAttachmentEntity();

        storedAttachment.setType(
                MessageAttachmentType.IMAGE
        );

        storedAttachment.setStorageKey(
                "storage/photo.jpg"
        );

        storedAttachment.setFileName(
                "photo.jpg"
        );

        storedAttachment.setContentType(
                "image/jpeg"
        );

        storedAttachment.setSize(
                1024
        );

        MessageDto processingMessage =
                mock(MessageDto.class);

        when(processingMessage.id())
                .thenReturn(messageId);

        MessageDto processedMessage =
                mock(MessageDto.class);

        when(processedMessage.id())
                .thenReturn(messageId);

        when(processedMessage.type())
                .thenReturn(MessageType.TEXT);

        when(processedMessage.content())
                .thenReturn("Hello");

        MessageDto queuedMessage =
                mock(MessageDto.class);

        when(messageService.createOutboundMessage(
                any(CreateOutboundMessageRequest.class)
        )).thenReturn(
                processingMessage
        );

        when(messageService.startProcessing(
                messageId
        )).thenReturn(
                processingMessage
        );

        when(conversationService.findEntityForProcessing(
                conversationId
        )).thenReturn(
                conversation
        );

        when(messageService.getMessageEntityForProcessing(
                messageId
        )).thenReturn(
                messageEntity
        );

        when(messageService.markProcessed(
                messageId
        )).thenReturn(
                processedMessage
        );

        when(messageAttachmentService.getAttachmentsForProcessing(
                messageId
        )).thenReturn(
                List.of(storedAttachment)
        );

        when(messageService.markQueued(
                messageId
        )).thenReturn(
                queuedMessage
        );

        MessageDto result =
                messageProcessingService.processOutbound(
                        request,
                        List.of(attachment)
                );

        assertSame(
                queuedMessage,
                result
        );

        verify(messageService)
                .createOutboundMessage(
                        any(CreateOutboundMessageRequest.class)
                );

        verify(messageService)
                .startProcessing(
                        messageId
                );

        verify(conversationService)
                .findEntityForProcessing(
                        conversationId
                );

        verify(messageService)
                .getMessageEntityForProcessing(
                        messageId
                );

        verify(messageAttachmentService)
                .createAttachment(
                        messageEntity,
                        attachment
                );

        verify(messageService)
                .markProcessed(
                        messageId
                );

        verify(messageAttachmentService)
                .getAttachmentsForProcessing(
                        messageId
                );

        verify(messageService)
                .markQueued(
                        messageId
                );

        ArgumentCaptor<ChannelType> channelTypeCaptor =
                ArgumentCaptor.forClass(
                        ChannelType.class
                );

        ArgumentCaptor<OutboundMessageKafkaCommand> commandCaptor =
                ArgumentCaptor.forClass(
                        OutboundMessageKafkaCommand.class
                );

        verify(outboundMessagePublisher)
                .publish(
                        channelTypeCaptor.capture(),
                        commandCaptor.capture()
                );

        assertEquals(
                ChannelType.TELEGRAM,
                channelTypeCaptor.getValue()
        );

        OutboundMessageKafkaCommand command =
                commandCaptor.getValue();

        assertEquals(
                messageId,
                command.messageId()
        );

        assertEquals(
                channelAccountId,
                command.channelAccountId()
        );

        assertEquals(
                "client-123",
                command.recipientExternalId()
        );

        assertEquals(
                MessageType.TEXT,
                command.type()
        );

        assertEquals(
                "Hello",
                command.content()
        );

        assertEquals(
                1,
                command.attachments().size()
        );

        PlatformOutboundAttachment outboundAttachment =
                command.attachments().get(0);

        assertEquals(
                MessageAttachmentType.IMAGE,
                outboundAttachment.type()
        );

        assertEquals(
                "storage/photo.jpg",
                outboundAttachment.storageKey()
        );

        assertEquals(
                "photo.jpg",
                outboundAttachment.fileName()
        );

        assertEquals(
                "image/jpeg",
                outboundAttachment.contentType()
        );

        assertEquals(
                1024,
                outboundAttachment.size()
        );

        verify(
                messageService,
                never()
        ).markSent(
                any(),
                anyString()
        );

        verify(
                messageService,
                never()
        ).markDeliveryFailed(
                any()
        );
    }

    // =========================================================
    // FORWARD
    // =========================================================

    @Test
    void forwardMessage_existingTargetConversation_forwardsAttachments() {

        ForwardMessageRequest request =
                new ForwardMessageRequest(
                        sourceMessageId,
                        targetConversationId,
                        null,
                        null
                );

        MessageAttachmentEntity sourceAttachment =
                new MessageAttachmentEntity();

        sourceAttachment.setMessage(
                sourceMessageEntity
        );

        sourceAttachment.setType(
                MessageAttachmentType.IMAGE
        );

        sourceAttachment.setFileName(
                "photo.jpg"
        );

        sourceAttachment.setContentType(
                "image/jpeg"
        );

        sourceAttachment.setSize(
                1024
        );

        sourceAttachment.setStorageKey(
                "storage/original-123"
        );

        sourceAttachment.setForwardFrom(
                null
        );

        UUID forwardedId =
                UUID.randomUUID();

        when(forwardedMessageDto.id())
                .thenReturn(forwardedId);

        when(messageService.getMessageEntity(
                sourceMessageId
        )).thenReturn(
                sourceMessageEntity
        );

        when(conversationService.findEntityForProcessing(
                targetConversationId
        )).thenReturn(
                targetConversation
        );

        when(messageService.createForwardedMessage(
                targetConversation,
                sourceMessageEntity
        )).thenReturn(
                forwardedMessageDto
        );

        targetMessageEntity =
                new MessageEntity(
                        targetConversation,
                        MessageType.TEXT,
                        MessageDirection.OUTBOUND,
                        MessageSenderType.EMPLOYEE
                );

        targetMessageEntity.setId(
                forwardedId
        );

        when(messageService.getMessageEntityForProcessing(
                forwardedId
        )).thenReturn(
                targetMessageEntity
        );

        when(messageAttachmentService
                .getAttachmentsForProcessing(
                        sourceMessageId
                ))
                .thenReturn(
                        List.of(sourceAttachment)
                );

        MessageDto result =
                messageProcessingService.forwardMessage(
                        request
                );

        assertSame(
                forwardedMessageDto,
                result
        );

        verify(messageService)
                .getMessageEntity(
                        sourceMessageId
                );

        verify(conversationService)
                .findEntityForProcessing(
                        targetConversationId
                );

        verify(conversationService)
                .requireForwardTargetAccess(
                        targetConversation
                );

        verify(messageService)
                .createForwardedMessage(
                        targetConversation,
                        sourceMessageEntity
                );

        verify(messageService)
                .getMessageEntityForProcessing(
                        forwardedId
                );

        verify(messageAttachmentService)
                .getAttachmentsForProcessing(
                        sourceMessageId
                );

        verify(messageAttachmentService)
                .createForwardedAttachment(
                        targetMessageEntity,
                        sourceAttachment
                );
    }

    @Test
    void forwardMessage_accountPair_createsOrFindsTargetConversation() {

        UUID targetClientAccountId =
                UUID.randomUUID();

        UUID targetChannelAccountId =
                UUID.randomUUID();

        ForwardMessageRequest request =
                new ForwardMessageRequest(
                        sourceMessageId,
                        null,
                        targetClientAccountId,
                        targetChannelAccountId
                );

        ClientAccountEntity targetClientAccount =
                new ClientAccountEntity();

        targetClientAccount.setId(
                targetClientAccountId
        );

        ChannelAccountEntity targetChannelAccount =
                new ChannelAccountEntity();

        targetChannelAccount.setId(
                targetChannelAccountId
        );

        when(messageService.getMessageEntity(
                sourceMessageId
        )).thenReturn(
                sourceMessageEntity
        );

        when(clientAccountRepository.findById(
                targetClientAccountId
        )).thenReturn(
                Optional.of(targetClientAccount)
        );

        when(channelAccountRepository.findById(
                targetChannelAccountId
        )).thenReturn(
                Optional.of(targetChannelAccount)
        );

        when(conversationService.findOrCreateForForward(
                targetChannelAccount,
                targetClientAccount
        )).thenReturn(
                targetConversation
        );

        when(messageService.createForwardedMessage(
                targetConversation,
                sourceMessageEntity
        )).thenReturn(
                forwardedMessageDto
        );

        UUID forwardedId = UUID.randomUUID();

        when(forwardedMessageDto.id())
                .thenReturn(forwardedId);

        targetMessageEntity =
                new MessageEntity(
                        targetConversation,
                        MessageType.TEXT,
                        MessageDirection.OUTBOUND,
                        MessageSenderType.EMPLOYEE
                );

        targetMessageEntity.setId(
                forwardedId
        );

        when(messageService.getMessageEntityForProcessing(
                forwardedId
        )).thenReturn(
                targetMessageEntity
        );

        when(messageAttachmentService
                .getAttachmentsForProcessing(
                        sourceMessageId
                ))
                .thenReturn(
                        List.of()
                );

        MessageDto result =
                messageProcessingService.forwardMessage(
                        request
                );

        assertSame(
                forwardedMessageDto,
                result
        );

        verify(clientAccountRepository)
                .findById(targetClientAccountId);

        verify(channelAccountRepository)
                .findById(targetChannelAccountId);

        verify(conversationService)
                .findOrCreateForForward(
                        targetChannelAccount,
                        targetClientAccount
                );

        verify(messageService)
                .createForwardedMessage(
                        targetConversation,
                        sourceMessageEntity
                );

        verify(messageAttachmentService)
                .getAttachmentsForProcessing(
                        sourceMessageId
                );
    }

    @Test
    void forwardMessage_sourceMessageNotFound_throwsException() {

        ForwardMessageRequest request =
                new ForwardMessageRequest(
                        sourceMessageId,
                        targetConversationId,
                        null,
                        null
                );

        when(messageService.getMessageEntity(
                sourceMessageId
        )).thenThrow(
                new EntityNotFoundException(
                        "Message not found: "
                                + sourceMessageId
                )
        );

        EntityNotFoundException result =
                assertThrows(
                        EntityNotFoundException.class,
                        () ->
                                messageProcessingService
                                        .forwardMessage(
                                                request
                                        )
                );

        assertEquals(
                "Message not found: " + sourceMessageId,
                result.getMessage()
        );

        verifyNoInteractions(
                messageAttachmentService
        );
    }

    // =========================================================
    // PLATFORM EVENTS
    // =========================================================

    @Test
    void processPlatformEvent_delivered_marksMessageDelivered() {

        PlatformMessageEvent event =
                new PlatformMessageEvent(
                        channelAccountId,
                        "telegram-message-123",
                        PlatformMessageEventType.DELIVERED,
                        Instant.parse(
                                "2026-08-26T10:05:00Z"
                        ),
                        null
                );

        when(messageRepository
                .findByConversationChannelAccountIdAndExternalId(
                        channelAccountId,
                        "telegram-message-123"
                ))
                .thenReturn(
                        Optional.of(messageEntity)
                );

        when(messageService.markDelivered(
                messageId
        )).thenReturn(
                messageDto
        );

        MessageDto result =
                messageProcessingService.processPlatformEvent(
                        event
                );

        assertSame(
                messageDto,
                result
        );

        verify(messageRepository)
                .findByConversationChannelAccountIdAndExternalId(
                        channelAccountId,
                        "telegram-message-123"
                );

        verify(messageService)
                .markDelivered(messageId);

        verify(messageService, never())
                .markRead(any());

        verify(messageService, never())
                .markDeliveryFailed(any());
    }

    @Test
    void processPlatformEvent_read_marksMessageRead() {

        PlatformMessageEvent event =
                new PlatformMessageEvent(
                        channelAccountId,
                        "telegram-message-123",
                        PlatformMessageEventType.READ,
                        Instant.parse(
                                "2026-08-26T10:06:00Z"
                        ),
                        null
                );

        when(messageRepository
                .findByConversationChannelAccountIdAndExternalId(
                        channelAccountId,
                        "telegram-message-123"
                ))
                .thenReturn(
                        Optional.of(messageEntity)
                );

        when(messageService.markRead(
                messageId
        )).thenReturn(
                messageDto
        );

        MessageDto result =
                messageProcessingService.processPlatformEvent(
                        event
                );

        assertSame(
                messageDto,
                result
        );

        verify(messageRepository)
                .findByConversationChannelAccountIdAndExternalId(
                        channelAccountId,
                        "telegram-message-123"
                );

        verify(messageService)
                .markRead(messageId);

        verify(messageService, never())
                .markDelivered(any());

        verify(messageService, never())
                .markDeliveryFailed(any());
    }

    @Test
    void processPlatformEvent_failed_marksDeliveryFailed() {

        PlatformMessageEvent event =
                new PlatformMessageEvent(
                        channelAccountId,
                        "telegram-message-123",
                        PlatformMessageEventType.FAILED,
                        Instant.parse(
                                "2026-08-26T10:07:00Z"
                        ),
                        "{\"error\":\"delivery failed\"}"
                );

        when(messageRepository
                .findByConversationChannelAccountIdAndExternalId(
                        channelAccountId,
                        "telegram-message-123"
                ))
                .thenReturn(
                        Optional.of(messageEntity)
                );

        when(messageService.markDeliveryFailed(
                messageId
        )).thenReturn(
                messageDto
        );

        MessageDto result =
                messageProcessingService.processPlatformEvent(
                        event
                );

        assertSame(
                messageDto,
                result
        );

        verify(messageRepository)
                .findByConversationChannelAccountIdAndExternalId(
                        channelAccountId,
                        "telegram-message-123"
                );

        verify(messageService)
                .markDeliveryFailed(messageId);

        verify(messageService, never())
                .markDelivered(any());

        verify(messageService, never())
                .markRead(any());
    }

    @Test
    void processPlatformEvent_sent_throwsException() {

        PlatformMessageEvent event =
                new PlatformMessageEvent(
                        channelAccountId,
                        "telegram-message-123",
                        PlatformMessageEventType.SENT,
                        Instant.parse(
                                "2026-08-26T10:08:00Z"
                        ),
                        null
                );

        when(messageRepository
                .findByConversationChannelAccountIdAndExternalId(
                        channelAccountId,
                        "telegram-message-123"
                ))
                .thenReturn(
                        Optional.of(messageEntity)
                );

        IllegalStateException result =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                messageProcessingService
                                        .processPlatformEvent(event)
                );

        assertEquals(
                "SENT platform events are not processed "
                        + "by the current synchronous outbound flow",
                result.getMessage()
        );

        verify(messageRepository)
                .findByConversationChannelAccountIdAndExternalId(
                        channelAccountId,
                        "telegram-message-123"
                );

        verify(messageService, never())
                .markDelivered(any());

        verify(messageService, never())
                .markRead(any());

        verify(messageService, never())
                .markDeliveryFailed(any());
    }

    @Test
    void processPlatformEvent_messageNotFound_throwsException() {

        String externalId =
                "telegram-message-missing";

        PlatformMessageEvent event =
                new PlatformMessageEvent(
                        channelAccountId,
                        externalId,
                        PlatformMessageEventType.DELIVERED,
                        Instant.parse(
                                "2026-08-26T10:09:00Z"
                        ),
                        null
                );

        when(messageRepository
                .findByConversationChannelAccountIdAndExternalId(
                        channelAccountId,
                        externalId
                ))
                .thenReturn(
                        Optional.empty()
                );

        EntityNotFoundException result =
                assertThrows(
                        EntityNotFoundException.class,
                        () ->
                                messageProcessingService
                                        .processPlatformEvent(event)
                );

        assertEquals(
                "Message not found for "
                        + "channelAccountId="
                        + channelAccountId
                        + ", externalId="
                        + externalId,
                result.getMessage()
        );

        verify(messageRepository)
                .findByConversationChannelAccountIdAndExternalId(
                        channelAccountId,
                        externalId
                );

        verifyNoInteractions(
                messageService
        );
    }

    @Test
    void processPlatformEvent_nullEvent_throwsException() {

        IllegalArgumentException result =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                messageProcessingService
                                        .processPlatformEvent(null)
                );

        assertEquals(
                "PlatformMessageEvent must not be null",
                result.getMessage()
        );

        verifyNoInteractions(
                messageRepository
        );

        verifyNoInteractions(
                messageService
        );
    }
}