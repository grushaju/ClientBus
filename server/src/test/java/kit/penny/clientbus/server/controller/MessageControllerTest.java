package kit.penny.clientbus.server.controller;

import kit.penny.clientbus.common.dto.message.ForwardMessageRequest;
import kit.penny.clientbus.common.dto.message.MessageAttachmentDto;
import kit.penny.clientbus.common.dto.message.MessageDto;
import kit.penny.clientbus.common.dto.message.OutboundMessageRequest;
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.server.service.AttachmentContent;
import kit.penny.clientbus.server.service.IMessageProcessingService;
import kit.penny.clientbus.server.service.MessageAttachmentService;
import kit.penny.clientbus.server.service.MessageService;
import kit.penny.clientbus.server.storage.StoredAttachment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageControllerTest {

    @Mock
    private IMessageProcessingService messageProcessingService;

    @Mock
    private MessageService messageService;

    @Mock
    private MessageAttachmentService messageAttachmentService;

    @Mock
    private MultipartFile imageFile;

    @Mock
    private MessageDto messageDto;

    private MessageController controller;

    private UUID conversationId;
    private UUID messageId;

    @BeforeEach
    void setUp() {
        controller = new MessageController(
                messageProcessingService,
                messageService,
                messageAttachmentService
        );

        conversationId = UUID.randomUUID();
        messageId = UUID.randomUUID();
    }

    @Test
    void getMessage_returnsMessage() {

        when(messageService.getMessage(messageId))
                .thenReturn(messageDto);

        ResponseEntity<MessageDto> response =
                controller.getMessage(messageId);

        assertEquals(
                200,
                response.getStatusCode().value()
        );

        assertSame(
                messageDto,
                response.getBody()
        );

        verify(messageService)
                .getMessage(messageId);
    }

    @Test
    void getAttachments_returnsAttachments() {

        UUID messageId = UUID.randomUUID();

        MessageAttachmentDto attachment =
                mock(MessageAttachmentDto.class);

        List<MessageAttachmentDto> attachments =
                List.of(attachment);

        when(messageAttachmentService.getAttachments(messageId))
                .thenReturn(attachments);

        ResponseEntity<List<MessageAttachmentDto>> response =
                controller.getAttachments(messageId);

        assertEquals(
                200,
                response.getStatusCode().value()
        );

        assertSame(
                attachments,
                response.getBody()
        );

        verify(messageAttachmentService)
                .getAttachments(messageId);
    }

    @Test
    void processOutbound_withoutAttachments_passesEmptyList() {

        OutboundMessageRequest request =
                new OutboundMessageRequest(
                        conversationId,
                        MessageType.TEXT,
                        "Hello",
                        null,
                        null
                );

        when(messageProcessingService.processOutbound(
                eq(request),
                any()
        )).thenReturn(messageDto);

        ResponseEntity<MessageDto> response =
                controller.processOutbound(
                        request,
                        null
                );

        assertEquals(
                200,
                response.getStatusCode().value()
        );

        assertSame(
                messageDto,
                response.getBody()
        );

        ArgumentCaptor<List<AttachmentContent>> captor =
                ArgumentCaptor.forClass(List.class);

        verify(messageProcessingService)
                .processOutbound(
                        eq(request),
                        captor.capture()
                );

        assertNotNull(captor.getValue());
        assertTrue(captor.getValue().isEmpty());
    }

    @Test
    void processOutbound_withImageAttachment_convertsToAttachmentContent()
            throws Exception {

        OutboundMessageRequest request =
                new OutboundMessageRequest(
                        conversationId,
                        MessageType.IMAGE,
                        null,
                        null,
                        null
                );

        byte[] content = new byte[]{1, 2, 3};

        when(imageFile.isEmpty())
                .thenReturn(false);

        when(imageFile.getContentType())
                .thenReturn("image/jpeg");

        when(imageFile.getOriginalFilename())
                .thenReturn("photo.jpg");

        when(imageFile.getSize())
                .thenReturn(3L);

        when(imageFile.getInputStream())
                .thenReturn(
                        new ByteArrayInputStream(content)
                );

        when(messageProcessingService.processOutbound(
                eq(request),
                any()
        )).thenReturn(messageDto);

        ResponseEntity<MessageDto> response =
                controller.processOutbound(
                        request,
                        List.of(imageFile)
                );

        assertEquals(
                200,
                response.getStatusCode().value()
        );

        assertSame(
                messageDto,
                response.getBody()
        );

        ArgumentCaptor<List<AttachmentContent>> captor =
                ArgumentCaptor.forClass(List.class);

        verify(messageProcessingService)
                .processOutbound(
                        eq(request),
                        captor.capture()
                );

        List<AttachmentContent> attachments =
                captor.getValue();

        assertEquals(
                1,
                attachments.size()
        );

        AttachmentContent attachment =
                attachments.get(0);

        assertEquals(
                MessageAttachmentType.IMAGE,
                attachment.type()
        );

        assertEquals(
                "photo.jpg",
                attachment.fileName()
        );

        assertEquals(
                "image/jpeg",
                attachment.contentType()
        );

        assertEquals(
                3L,
                attachment.size()
        );

        assertNotNull(
                attachment.inputStream()
        );
    }

    @Test
    void processOutbound_rejectsUnsupportedAttachmentType()
            throws Exception {

        when(imageFile.isEmpty())
                .thenReturn(false);

        when(imageFile.getContentType())
                .thenReturn("application/pdf");

        assertThrows(
                IllegalArgumentException.class,
                () -> controller.processOutbound(
                        new OutboundMessageRequest(
                                conversationId,
                                MessageType.TEXT,
                                null,
                                null,
                                null
                        ),
                        List.of(imageFile)
                )
        );

        verify(imageFile).isEmpty();
        verify(imageFile).getContentType();

        verifyNoInteractions(
                messageProcessingService
        );
    }

    @Test
    void processOutbound_rejectsEmptyAttachment() {

        when(imageFile.isEmpty())
                .thenReturn(true);

        assertThrows(
                IllegalArgumentException.class,
                () -> controller.processOutbound(
                        new OutboundMessageRequest(
                                conversationId,
                                MessageType.TEXT,
                                null,
                                null,
                                null
                        ),
                        List.of(imageFile)
                )
        );

        verifyNoInteractions(
                messageProcessingService
        );
    }

    @Test
    void forwardMessage_delegatesToProcessingService() {

        ForwardMessageRequest request =
                new ForwardMessageRequest(
                        messageId,
                        conversationId,
                        null,
                        null
                );

        when(messageProcessingService.forwardMessage(request))
                .thenReturn(messageDto);

        ResponseEntity<MessageDto> response =
                controller.forwardMessage(request);

        assertEquals(
                200,
                response.getStatusCode().value()
        );

        assertSame(
                messageDto,
                response.getBody()
        );

        verify(messageProcessingService)
                .forwardMessage(request);
    }

    @Test
    void downloadAttachment_returnsStoredContent() {

        UUID attachmentId = UUID.randomUUID();

        StoredAttachment stored =
                new StoredAttachment(
                        new ByteArrayInputStream(
                                new byte[]{1, 2, 3}
                        ),
                        "photo.jpg",
                        "image/jpeg",
                        3
                );

        when(messageAttachmentService.downloadAttachment(
                messageId,
                attachmentId
        )).thenReturn(stored);

        ResponseEntity<InputStreamResource> response =
                controller.downloadAttachment(
                        messageId,
                        attachmentId
                );

        assertEquals(
                200,
                response.getStatusCode().value()
        );

        assertNotNull(response.getBody());

        assertEquals(
                3,
                response.getHeaders().getContentLength()
        );

        assertEquals(
                "image/jpeg",
                response.getHeaders()
                        .getContentType()
                        .toString()
        );

        verify(messageAttachmentService)
                .downloadAttachment(
                        messageId,
                        attachmentId
                );
    }
}