package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.message.CreateOutboundMessageRequest;
import kit.penny.clientbus.common.dto.message.MessageDto;
import kit.penny.clientbus.common.dto.message.OutboundMessageRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.server.exception.ChannelUnavailableException;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.MessageAttachmentEntity;
import kit.penny.clientbus.server.persistence.entity.MessageEntity;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboundMessageTransactionServiceTest {

    @Mock
    private ConversationService conversationService;

    @Mock
    private MessageService messageService;

    @Mock
    private MessageAttachmentService messageAttachmentService;

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private OutboundMessageTransactionService service;

    private UUID conversationId;
    private UUID messageId;
    private UUID channelAccountId;

    private OutboundMessageRequest request;
    private MessageDto messageDto;
    private ConversationEntity conversation;
    private ChannelAccountEntity channelAccount;
    private ChannelEntity channel;
    private ClientAccountEntity clientAccount;
    private MessageEntity messageEntity;

    @BeforeEach
    void setUp() {
        conversationId = UUID.randomUUID();
        messageId = UUID.randomUUID();
        channelAccountId = UUID.randomUUID();

        request = new OutboundMessageRequest(
                conversationId,
                MessageType.TEXT,
                "Hello",
                null,
                null
        );

        messageDto = mock(MessageDto.class);

        conversation = mock(ConversationEntity.class);
        channelAccount = mock(ChannelAccountEntity.class);
        channel = mock(ChannelEntity.class);
        clientAccount = mock(ClientAccountEntity.class);
        messageEntity = mock(MessageEntity.class);
    }

    @Test
    void prepare_nullRequest_throwsException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.prepare(null, List.of())
        );

        verifyNoInteractions(
                conversationService,
                messageService,
                messageAttachmentService
        );
    }

    @Test
    void prepare_channelUnavailable_marksMessageFailedAndDoesNotQueue() {
        when(conversationService.findEntityForProcessing(conversationId))
                .thenReturn(conversation);

        when(messageService.createOutboundMessage(
                any(CreateOutboundMessageRequest.class)))
                .thenReturn(messageDto);

        when(messageDto.id()).thenReturn(messageId);
        when(messageService.startProcessing(messageId))
                .thenReturn(messageDto);

        doThrow(new ChannelUnavailableException(
                "External operations are unavailable"
        )).when(conversationService)
                .requireExternalOperationsAvailable(conversation);

        ChannelUnavailableException exception = assertThrows(
                ChannelUnavailableException.class,
                () -> service.prepare(request, List.of())
        );

        assertEquals(
                "External operations are unavailable",
                exception.getMessage()
        );

        verify(messageService).createOutboundMessage(
                any(CreateOutboundMessageRequest.class)
        );
        verify(messageService).startProcessing(messageId);

        verify(conversationService)
                .requireExternalOperationsAvailable(conversation);

        verify(messageService, never()).markQueued(any());
        verify(messageAttachmentService, never())
                .createAttachment(any(), any());
        verify(messageAttachmentService, never())
                .getAttachmentsForProcessing(any());

        verify(messageService).markProcessingFailed(messageId);
    }

    @Test
    void prepare_connectedChannel_returnsTransactionResult() {
        when(conversationService.findEntityForProcessing(conversationId))
                .thenReturn(conversation);

        when(conversation.getChannelAccount()).thenReturn(channelAccount);
        when(channelAccount.getChannel()).thenReturn(channel);
        when(channel.getType()).thenReturn(ChannelType.TELEGRAM);
        when(conversation.getClientAccount()).thenReturn(clientAccount);
        when(clientAccount.getExternalId()).thenReturn("client-123");

        // Проверка доступности канала проходит успешно.
        // Для void-метода на моке doNothing() не требуется.

        when(messageService.createOutboundMessage(
                any(CreateOutboundMessageRequest.class)))
                .thenReturn(messageDto);

        when(messageDto.id()).thenReturn(messageId);
        when(messageDto.type()).thenReturn(MessageType.TEXT);
        when(messageDto.content()).thenReturn("Hello");

        // prepare() использует результат startProcessing().
        when(messageService.startProcessing(messageId))
                .thenReturn(messageDto);

        when(messageService.getMessageEntityForProcessing(messageId))
                .thenReturn(messageEntity);

        when(messageAttachmentService.getAttachmentsForProcessing(messageId))
                .thenReturn(List.of());

        when(messageService.markQueued(messageId))
                .thenReturn(messageDto);

        OutboundMessageTransactionService.OutboundMessageTransactionResult result =
                service.prepare(request, List.of());

        assertNotNull(result);
        assertSame(messageDto, result.message());
        assertEquals(messageId, result.messageId());
        assertEquals(ChannelType.TELEGRAM, result.channelType());

        OutboundMessageKafkaCommand command = result.command();
        assertNotNull(command);

        verify(conversationService)
                .requireExternalOperationsAvailable(conversation);

        verify(messageService).markQueued(messageId);
        verify(messageService, never()).markProcessingFailed(messageId);
    }
}
