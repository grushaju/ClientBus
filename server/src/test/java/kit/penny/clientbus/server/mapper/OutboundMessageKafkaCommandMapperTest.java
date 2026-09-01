package kit.penny.clientbus.server.mapper;

import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.common.kafka.PlatformOutboundAttachment;
import kit.penny.clientbus.server.service.ChannelAttachment;
import kit.penny.clientbus.server.service.ChannelSendRequest;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboundMessageKafkaCommandMapperTest {

    private IAttachmentStorage attachmentStorage;

    private OutboundMessageKafkaCommandMapper mapper;

    @BeforeEach
    void setUp() {
        attachmentStorage = mock(IAttachmentStorage.class);

        mapper = new OutboundMessageKafkaCommandMapper(
                attachmentStorage
        );
    }

    @Test
    void shouldMapMessageFields() {
        UUID messageId = UUID.randomUUID();
        UUID channelAccountId = UUID.randomUUID();

        OutboundMessageKafkaCommand command =
                new OutboundMessageKafkaCommand(
                        messageId,
                        channelAccountId,
                        "recipient-123",
                        MessageType.TEXT,
                        "Hello",
                        List.of()
                );

        ChannelSendRequest result =
                mapper.toRequest(command);

        assertEquals(messageId, result.messageId());
        assertEquals(
                channelAccountId,
                result.channelAccountId()
        );
        assertEquals(
                "recipient-123",
                result.recipientExternalId()
        );
        assertEquals(
                MessageType.TEXT,
                result.type()
        );
        assertEquals(
                "Hello",
                result.content()
        );
        assertNotNull(result.attachments());
        assertTrue(result.attachments().isEmpty());

        verifyNoInteractions(attachmentStorage);
    }

    @Test
    void shouldMapNullAttachmentsToEmptyList() {
        OutboundMessageKafkaCommand command =
                new OutboundMessageKafkaCommand(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "recipient-123",
                        MessageType.TEXT,
                        "Hello",
                        null
                );

        ChannelSendRequest result =
                mapper.toRequest(command);

        assertNotNull(result.attachments());
        assertTrue(result.attachments().isEmpty());

        verifyNoInteractions(attachmentStorage);
    }

    @Test
    void shouldLoadAndMapAttachment() {
        UUID messageId = UUID.randomUUID();
        UUID channelAccountId = UUID.randomUUID();

        InputStream content =
                new ByteArrayInputStream(
                        new byte[]{1, 2, 3, 4}
                );

        PlatformOutboundAttachment attachment =
                new PlatformOutboundAttachment(
                        MessageAttachmentType.IMAGE,
                        "storage/photo.jpg",
                        "photo.jpg",
                        "image/jpeg",
                        4L
                );

        when(attachmentStorage.load("storage/photo.jpg"))
                .thenReturn(content);

        OutboundMessageKafkaCommand command =
                new OutboundMessageKafkaCommand(
                        messageId,
                        channelAccountId,
                        "recipient-123",
                        MessageType.TEXT,
                        "Photo",
                        List.of(attachment)
                );

        ChannelSendRequest result =
                mapper.toRequest(command);

        assertEquals(1, result.attachments().size());

        ChannelAttachment mapped =
                result.attachments().get(0);

        assertEquals(
                MessageAttachmentType.IMAGE,
                mapped.type()
        );
        assertEquals(
                "photo.jpg",
                mapped.fileName()
        );
        assertEquals(
                "image/jpeg",
                mapped.contentType()
        );
        assertEquals(
                4L,
                mapped.size()
        );
        assertSame(
                content,
                mapped.content()
        );

        verify(attachmentStorage)
                .load("storage/photo.jpg");
    }

    @Test
    void shouldPreserveAttachmentOrder() {
        InputStream firstContent =
                new ByteArrayInputStream(
                        new byte[]{1}
                );

        InputStream secondContent =
                new ByteArrayInputStream(
                        new byte[]{2}
                );

        PlatformOutboundAttachment first =
                new PlatformOutboundAttachment(
                        MessageAttachmentType.IMAGE,
                        "storage/first.jpg",
                        "first.jpg",
                        "image/jpeg",
                        1L
                );

        PlatformOutboundAttachment second =
                new PlatformOutboundAttachment(
                        MessageAttachmentType.AUDIO,
                        "storage/second.mp3",
                        "second.mp3",
                        "application/media",
                        1L
                );

        when(attachmentStorage.load("storage/first.jpg"))
                .thenReturn(firstContent);

        when(attachmentStorage.load("storage/second.mp3"))
                .thenReturn(secondContent);

        OutboundMessageKafkaCommand command =
                new OutboundMessageKafkaCommand(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "recipient-123",
                        MessageType.TEXT,
                        "Files",
                        List.of(first, second)
                );

        ChannelSendRequest result =
                mapper.toRequest(command);

        assertEquals(2, result.attachments().size());

        assertEquals(
                "first.jpg",
                result.attachments()
                        .get(0)
                        .fileName()
        );

        assertEquals(
                "second.mp3",
                result.attachments()
                        .get(1)
                        .fileName()
        );

        assertSame(
                firstContent,
                result.attachments()
                        .get(0)
                        .content()
        );

        assertSame(
                secondContent,
                result.attachments()
                        .get(1)
                        .content()
        );

        verify(attachmentStorage)
                .load("storage/first.jpg");

        verify(attachmentStorage)
                .load("storage/second.pdf");
    }
}