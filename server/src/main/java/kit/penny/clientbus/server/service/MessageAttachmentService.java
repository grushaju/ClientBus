package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import kit.penny.clientbus.common.dto.message.MessageAttachmentDto;
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.server.mapper.MessageAttachmentMapper;
import kit.penny.clientbus.server.persistence.entity.MessageAttachmentEntity;
import kit.penny.clientbus.server.persistence.entity.MessageEntity;
import kit.penny.clientbus.server.persistence.repository.MessageAttachmentRepository;
import kit.penny.clientbus.server.persistence.repository.MessageRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import kit.penny.clientbus.server.storage.StoredAttachment;
import kit.penny.clientbus.server.storage.StoredAttachmentMetadata;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@Service
public class MessageAttachmentService {

    private final MessageAttachmentRepository attachmentRepository;
    private final MessageRepository messageRepository;
    private final MessageAttachmentMapper mapper;
    private final IAttachmentStorage attachmentStorage;
    private final CurrentUserService currentUserService;

    public MessageAttachmentService(
            MessageAttachmentRepository attachmentRepository,
            MessageRepository messageRepository,
            MessageAttachmentMapper mapper,
            IAttachmentStorage attachmentStorage,
            CurrentUserService currentUserService
    ) {
        this.attachmentRepository =
                attachmentRepository;

        this.messageRepository =
                messageRepository;

        this.mapper =
                mapper;

        this.attachmentStorage =
                attachmentStorage;

        this.currentUserService =
                currentUserService;
    }

    @Transactional
    public MessageAttachmentDto uploadAttachment(
            UUID messageId,
            MessageAttachmentType type,
            AttachmentContent content
    ) {
        MessageEntity message =
                getMessageWithAccessCheck(messageId);

        MessageAttachmentEntity entity =
                createAttachment(
                        message,
                        type,
                        content
                );

        return mapper.toDto(entity);
    }

    /**
     * Создаёт оригинальное вложение.
     *
     * forwardFrom == null.
     *
     * Файл физически сохраняется в Storage.
     */
    @Transactional
    public MessageAttachmentEntity createAttachment(
            MessageEntity message,
            MessageAttachmentType type,
            AttachmentContent content
    ) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "Message must not be null"
            );
        }

        validateAttachmentType(type);
        validateContent(content);

        StoredAttachmentMetadata storedAttachment;

        try (InputStream inputStream =
                     content.inputStream()) {

            storedAttachment =
                    attachmentStorage.store(
                            inputStream,
                            content.fileName(),
                            content.size(),
                            content.contentType()
                    );

        } catch (IOException e) {

            throw new IllegalStateException(
                    "Failed to read attachment",
                    e
            );
        }

        try {

            MessageAttachmentEntity entity =
                    new MessageAttachmentEntity();

            entity.setMessage(message);
            entity.setType(type);

            entity.setFileName(
                    storedAttachment.fileName()
            );

            entity.setContentType(
                    storedAttachment.contentType()
            );

            entity.setSize(
                    storedAttachment.size()
            );

            entity.setStorageKey(
                    storedAttachment.storageKey()
            );

            entity.setForwardFrom(null);

            return attachmentRepository.save(
                    entity
            );

        } catch (RuntimeException e) {

            try {

                attachmentStorage.delete(
                        storedAttachment.storageKey()
                );

            } catch (RuntimeException ignored) {
                // Preserve original exception.
            }

            throw e;
        }
    }

    /**
     * Создаёт attachment для forwarded Message.
     *
     * Физический файл НЕ копируется.
     *
     * Новый Entity получает:
     *
     * message      = targetMessage
     * storageKey   = sourceAttachment.storageKey
     * forwardFrom  = sourceMessage.id
     */
    @Transactional
    public MessageAttachmentEntity createForwardedAttachment(
            MessageEntity targetMessage,
            MessageAttachmentEntity sourceAttachment
    ) {
        if (targetMessage == null) {
            throw new IllegalArgumentException(
                    "Target message must not be null"
            );
        }

        if (sourceAttachment == null) {
            throw new IllegalArgumentException(
                    "Source attachment must not be null"
            );
        }

        MessageEntity sourceMessage =
                sourceAttachment.getMessage();

        if (sourceMessage == null
                || sourceMessage.getId() == null) {

            throw new IllegalArgumentException(
                    "Source attachment must belong to a persisted message"
            );
        }

        MessageAttachmentEntity entity =
                new MessageAttachmentEntity();

        entity.setMessage(
                targetMessage
        );

        entity.setType(
                sourceAttachment.getType()
        );

        entity.setFileName(
                sourceAttachment.getFileName()
        );

        entity.setContentType(
                sourceAttachment.getContentType()
        );

        entity.setSize(
                sourceAttachment.getSize()
        );

        entity.setStorageKey(
                sourceAttachment.getStorageKey()
        );

        entity.setForwardFrom(
                sourceMessage.getId()
        );

        return attachmentRepository.save(
                entity
        );
    }

    /**
     * Возвращает attachments сообщения
     * для Message Processing.
     *
     * ACL здесь не выполняется:
     * MessageProcessingService уже проверяет
     * доступ к самому Message.
     */
    @Transactional
    public List<MessageAttachmentEntity>
    getAttachmentsForProcessing(
            UUID messageId
    ) {
        return attachmentRepository
                .findAllByMessageId(messageId);
    }

    /**
     * Загружает attachments сообщения
     * в connector-ready представление.
     *
     * Connector не знает о:
     *
     * - MessageAttachmentEntity
     * - storageKey
     * - IAttachmentStorage
     */
    @Transactional
    public List<ChannelAttachment>
    getChannelAttachments(
            UUID messageId
    ) {
        return getAttachmentsForProcessing(messageId)
                .stream()
                .map(this::toChannelAttachment)
                .toList();
    }

    private ChannelAttachment toChannelAttachment(
            MessageAttachmentEntity attachment
    ) {
        InputStream inputStream =
                attachmentStorage.load(
                        attachment.getStorageKey()
                );

        return new ChannelAttachment(
                attachment.getType(),
                attachment.getFileName(),
                attachment.getContentType(),
                attachment.getSize(),
                inputStream
        );
    }

    @Transactional
    public MessageAttachmentDto getAttachment(
            UUID attachmentId
    ) {
        MessageAttachmentEntity attachment =
                getAttachmentWithAccessCheck(
                        attachmentId
                );

        return mapper.toDto(attachment);
    }

    @Transactional
    public StoredAttachment downloadAttachment(
            UUID attachmentId
    ) {
        MessageAttachmentEntity attachment =
                getAttachmentWithAccessCheck(
                        attachmentId
                );

        InputStream inputStream =
                attachmentStorage.load(
                        attachment.getStorageKey()
                );

        return new StoredAttachment(
                inputStream,
                attachment.getFileName(),
                attachment.getContentType(),
                attachment.getSize()
        );
    }

    /**
     * Original attachment:
     *
     * forwardFrom == null
     * -> Storage + Entity
     *
     * Forwarded attachment:
     *
     * forwardFrom != null
     * -> Entity only
     */
    @Transactional
    public void deleteAttachment(
            UUID attachmentId
    ) {
        MessageAttachmentEntity attachment =
                getAttachmentWithAccessCheck(
                        attachmentId
                );

        if (attachment.getForwardFrom() == null) {

            attachmentStorage.delete(
                    attachment.getStorageKey()
            );
        }

        attachmentRepository.delete(
                attachment
        );
    }

    private MessageEntity getMessageWithAccessCheck(
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

        return message;
    }

    private MessageAttachmentEntity
    getAttachmentWithAccessCheck(
            UUID attachmentId
    ) {
        MessageAttachmentEntity attachment =
                attachmentRepository.findById(
                                attachmentId
                        )
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Attachment not found: "
                                                + attachmentId
                                )
                        );

        currentUserService.requireWorkspaceAccess(
                attachment.getMessage()
                        .getConversation()
                        .getWorkspace()
                        .getId()
        );

        return attachment;
    }

    private void validateContent(
            AttachmentContent content
    ) {
        if (content == null) {
            throw new IllegalArgumentException(
                    "Attachment content must not be null"
            );
        }

        if (content.fileName() == null
                || content.fileName().isBlank()) {

            throw new IllegalArgumentException(
                    "Attachment file name must not be blank"
            );
        }

        if (content.contentType() == null
                || content.contentType().isBlank()) {

            throw new IllegalArgumentException(
                    "Attachment content type must not be blank"
            );
        }

        if (content.size() <= 0) {
            throw new IllegalArgumentException(
                    "Attachment size must be greater than zero"
            );
        }
    }

    private void validateAttachmentType(
            MessageAttachmentType type
    ) {
        if (type == null) {
            throw new IllegalArgumentException(
                    "Attachment type must not be null"
            );
        }
    }
}