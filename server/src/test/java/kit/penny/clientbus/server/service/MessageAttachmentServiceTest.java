package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.MessageAttachmentEntity;
import kit.penny.clientbus.server.persistence.entity.MessageEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.MessageAttachmentRepository;
import kit.penny.clientbus.server.persistence.repository.MessageRepository;
import kit.penny.clientbus.server.mapper.MessageAttachmentMapper;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import kit.penny.clientbus.server.storage.StoredAttachmentMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageAttachmentServiceTest {

    @Mock
    private MessageAttachmentRepository attachmentRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private MessageAttachmentMapper mapper;

    @Mock
    private IAttachmentStorage attachmentStorage;

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private MessageAttachmentService service;

    private UUID messageId;
    private UUID sourceMessageId;
    private UUID targetMessageId;
    private UUID attachmentId;
    private UUID workspaceId;

    private MessageEntity message;
    private MessageEntity sourceMessage;
    private MessageEntity targetMessage;

    private ConversationEntity conversation;
    private WorkspaceEntity workspace;

    @BeforeEach
    void setUp() {
        messageId = UUID.randomUUID();
        sourceMessageId = UUID.randomUUID();
        targetMessageId = UUID.randomUUID();
        attachmentId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();

        workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);

        conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        conversation.setWorkspace(workspace);

        message = new MessageEntity();
        message.setId(messageId);
        message.setConversation(conversation);

        sourceMessage = new MessageEntity();
        sourceMessage.setId(sourceMessageId);
        sourceMessage.setConversation(conversation);

        targetMessage = new MessageEntity();
        targetMessage.setId(targetMessageId);
        targetMessage.setConversation(conversation);
    }

    @Test
    void createAttachment_createsOriginalAttachment() {
        AttachmentContent content =
                new AttachmentContent(
                        "photo.jpg",
                        "image/jpeg",
                        1024,
                        new ByteArrayInputStream(
                                new byte[]{1, 2, 3}
                        )
                );

        StoredAttachmentMetadata metadata =
                new StoredAttachmentMetadata(
                        "storage/original-1",
                        "photo.jpg",
                        "image/jpeg",
                        1024
                );

        when(attachmentStorage.store(
                any(InputStream.class),
                eq("photo.jpg"),
                eq(1024L),
                eq("image/jpeg")
        )).thenReturn(metadata);

        when(attachmentRepository.save(
                any(MessageAttachmentEntity.class)
        )).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );

        MessageAttachmentEntity result =
                service.createAttachment(
                        message,
                        MessageAttachmentType.IMAGE,
                        content
                );

        assertNotNull(result);

        assertSame(
                message,
                result.getMessage()
        );

        assertEquals(
                MessageAttachmentType.IMAGE,
                result.getType()
        );

        assertEquals(
                "photo.jpg",
                result.getFileName()
        );

        assertEquals(
                "image/jpeg",
                result.getContentType()
        );

        assertEquals(
                1024,
                result.getSize()
        );

        assertEquals(
                "storage/original-1",
                result.getStorageKey()
        );

        /*
         * Original attachment.
         */
        assertNull(
                result.getForwardFrom()
        );

        verify(attachmentStorage).store(
                any(InputStream.class),
                eq("photo.jpg"),
                eq(1024L),
                eq("image/jpeg")
        );

        verify(attachmentRepository).save(result);
    }

    @Test
    void createForwardedAttachment_reusesStorageKeyAndSetsForwardFrom() {
        MessageAttachmentEntity sourceAttachment =
                new MessageAttachmentEntity();

        sourceAttachment.setMessage(sourceMessage);
        sourceAttachment.setType(
                MessageAttachmentType.IMAGE
        );
        sourceAttachment.setFileName(
                "photo.jpg"
        );
        sourceAttachment.setContentType(
                "image/jpeg"
        );
        sourceAttachment.setSize(1024);
        sourceAttachment.setStorageKey(
                "storage/original-1"
        );
        sourceAttachment.setForwardFrom(null);

        when(attachmentRepository.save(
                any(MessageAttachmentEntity.class)
        )).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );

        MessageAttachmentEntity result =
                service.createForwardedAttachment(
                        targetMessage,
                        sourceAttachment
                );

        assertNotNull(result);

        assertSame(
                targetMessage,
                result.getMessage()
        );

        assertEquals(
                MessageAttachmentType.IMAGE,
                result.getType()
        );

        assertEquals(
                "photo.jpg",
                result.getFileName()
        );

        assertEquals(
                "image/jpeg",
                result.getContentType()
        );

        assertEquals(
                1024,
                result.getSize()
        );

        /*
         * Самый важный момент:
         * storageKey тот же.
         */
        assertEquals(
                "storage/original-1",
                result.getStorageKey()
        );

        /*
         * Но attachment уже считается forwarded.
         */
        assertEquals(
                sourceMessageId,
                result.getForwardFrom()
        );

        /*
         * Никакого обращения к Storage при forward.
         */
        verifyNoInteractions(
                attachmentStorage
        );

        verify(
                attachmentRepository
        ).save(result);
    }

    @Test
    void deleteAttachment_originalAttachment_deletesStorageAndEntity() {
        MessageAttachmentEntity attachment =
                new MessageAttachmentEntity();

        attachment.setMessage(message);
        attachment.setType(
                MessageAttachmentType.IMAGE
        );
        attachment.setFileName(
                "photo.jpg"
        );
        attachment.setContentType(
                "image/jpeg"
        );
        attachment.setSize(1024);
        attachment.setStorageKey(
                "storage/original-1"
        );

        /*
         * NULL = original.
         */
        attachment.setForwardFrom(null);

        when(attachmentRepository.findById(
                attachmentId
        )).thenReturn(
                Optional.of(attachment)
        );

        service.deleteAttachment(
                attachmentId
        );

        verify(
                currentUserService
        ).requireWorkspaceAccess(
                workspaceId
        );

        verify(
                attachmentStorage
        ).delete(
                "storage/original-1"
        );

        verify(
                attachmentRepository
        ).delete(
                attachment
        );
    }

    @Test
    void deleteAttachment_forwardedAttachment_deletesOnlyEntity() {
        MessageAttachmentEntity attachment =
                new MessageAttachmentEntity();

        attachment.setMessage(message);
        attachment.setType(
                MessageAttachmentType.IMAGE
        );
        attachment.setFileName(
                "photo.jpg"
        );
        attachment.setContentType(
                "image/jpeg"
        );
        attachment.setSize(1024);
        attachment.setStorageKey(
                "storage/original-1"
        );

        /*
         * NOT NULL = forwarded attachment.
         */
        attachment.setForwardFrom(
                sourceMessageId
        );

        when(attachmentRepository.findById(
                attachmentId
        )).thenReturn(
                Optional.of(attachment)
        );

        service.deleteAttachment(
                attachmentId
        );

        /*
         * Ключевая проверка бизнес-правила:
         * storage object НЕ удаляется.
         */
        verify(
                attachmentStorage,
                never()
        ).delete(anyString());

        /*
         * Entity удаляется.
         */
        verify(
                attachmentRepository
        ).delete(
                attachment
        );
    }

    @Test
    void createAttachment_whenRepositorySaveFails_deletesStoredFile() {
        AttachmentContent content =
                new AttachmentContent(
                        "photo.jpg",
                        "image/jpeg",
                        1024,
                        new ByteArrayInputStream(
                                new byte[]{1, 2, 3}
                        )
                );

        StoredAttachmentMetadata metadata =
                new StoredAttachmentMetadata(
                        "storage/original-1",
                        "photo.jpg",
                        "image/jpeg",
                        1024
                );

        RuntimeException databaseException =
                new RuntimeException(
                        "Database error"
                );

        when(attachmentStorage.store(
                any(InputStream.class),
                eq("photo.jpg"),
                eq(1024L),
                eq("image/jpeg")
        )).thenReturn(metadata);

        when(attachmentRepository.save(
                any(MessageAttachmentEntity.class)
        )).thenThrow(databaseException);

        RuntimeException result =
                assertThrows(
                        RuntimeException.class,
                        () -> service.createAttachment(
                                message,
                                MessageAttachmentType.IMAGE,
                                content
                        )
                );

        assertSame(
                databaseException,
                result
        );

        /*
         * Storage object был создан,
         * но DB metadata не сохранилась.
         */
        verify(
                attachmentStorage
        ).delete(
                "storage/original-1"
        );
    }

    @Test
    void createForwardedAttachment_requiresPersistedSourceMessage() {
        MessageEntity sourceMessage =
                new MessageEntity();

        /*
         * ID намеренно не устанавливаем.
         */
        MessageAttachmentEntity sourceAttachment =
                new MessageAttachmentEntity();

        sourceAttachment.setMessage(
                sourceMessage
        );

        sourceAttachment.setType(
                MessageAttachmentType.IMAGE
        );

        sourceAttachment.setStorageKey(
                "storage/original-1"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.createForwardedAttachment(
                        targetMessage,
                        sourceAttachment
                )
        );

        verifyNoInteractions(
                attachmentRepository
        );

        verifyNoInteractions(
                attachmentStorage
        );
    }

    @Test
    void createAttachment_rejectsNullContent() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.createAttachment(
                        message,
                        MessageAttachmentType.IMAGE,
                        null
                )
        );

        verifyNoInteractions(
                attachmentStorage
        );

        verifyNoInteractions(
                attachmentRepository
        );
    }

    @Test
    void createAttachment_rejectsNullType() {
        AttachmentContent content =
                new AttachmentContent(
                        "photo.jpg",
                        "image/jpeg",
                        1024,
                        new ByteArrayInputStream(
                                new byte[]{1}
                        )
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.createAttachment(
                        message,
                        null,
                        content
                )
        );

        verifyNoInteractions(
                attachmentStorage
        );

        verifyNoInteractions(
                attachmentRepository
        );
    }

    @Test
    void deleteAttachment_throwsWhenAttachmentNotFound() {
        when(
                attachmentRepository.findById(
                        attachmentId
                )
        ).thenReturn(
                Optional.empty()
        );

        assertThrows(
                EntityNotFoundException.class,
                () -> service.deleteAttachment(
                        attachmentId
                )
        );

        verifyNoInteractions(
                attachmentStorage
        );

        verify(
                attachmentRepository,
                never()
        ).delete(any());
    }
}