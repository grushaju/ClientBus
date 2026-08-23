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
import kit.penny.clientbus.server.storage.AttachmentStorage;
import kit.penny.clientbus.server.storage.StoredAttachment;
import kit.penny.clientbus.server.storage.StoredAttachmentMetadata;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

@Service
public class MessageAttachmentService {

    private final MessageAttachmentRepository attachmentRepository;
    private final MessageRepository messageRepository;
    private final MessageAttachmentMapper mapper;
    private final AttachmentStorage attachmentStorage;
    private final CurrentUserService currentUserService;

    public MessageAttachmentService(
            MessageAttachmentRepository attachmentRepository,
            MessageRepository messageRepository,
            MessageAttachmentMapper mapper,
            AttachmentStorage attachmentStorage,
            CurrentUserService currentUserService
    ) {
        this.attachmentRepository = attachmentRepository;
        this.messageRepository = messageRepository;
        this.mapper = mapper;
        this.attachmentStorage = attachmentStorage;
        this.currentUserService = currentUserService;
    }

    /**
     * Stores the binary attachment and creates its database metadata.
     *
     * The binary content is stored through AttachmentStorage.
     * PostgreSQL stores only attachment metadata and storageKey.
     */
    @Transactional
    public MessageAttachmentDto uploadAttachment(
            UUID messageId,
            MessageAttachmentType type,
            MultipartFile file
    ) {

        validateFile(file);
        validateAttachmentType(type);

        MessageEntity message =
                getMessageWithAccessCheck(messageId);

        StoredAttachmentMetadata storedAttachment;

        try (InputStream inputStream = file.getInputStream()) {

            storedAttachment =
                    attachmentStorage.store(
                            inputStream,
                            file.getOriginalFilename(),
                            file.getSize(),
                            file.getContentType()
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

            entity =
                    attachmentRepository.save(entity);

            return mapper.toDto(entity);

        } catch (RuntimeException e) {

            /*
             * The file has already been stored physically,
             * but the database operation failed.
             *
             * Remove the physical file to avoid an orphaned
             * object in storage.
             */
            try {

                attachmentStorage.delete(
                        storedAttachment.storageKey()
                );

            } catch (RuntimeException ignored) {

                /*
                 * Do not hide the original database exception.
                 */
            }

            throw e;
        }
    }

    /**
     * Returns attachment metadata.
     *
     * Does not load binary data from storage.
     */
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

    /**
     * Loads the actual binary content from storage.
     *
     * The database is used only to resolve storageKey.
     */
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
     * Deletes both the physical file and its database metadata.
     */
    @Transactional
    public void deleteAttachment(
            UUID attachmentId
    ) {

        MessageAttachmentEntity attachment =
                getAttachmentWithAccessCheck(
                        attachmentId
                );

        String storageKey =
                attachment.getStorageKey();

        /*
         * Remove the physical object first.
         *
         * If storage deletion fails, the DB record remains,
         * which is preferable to having metadata that points
         * to an object we know was not deleted.
         */
        attachmentStorage.delete(storageKey);

        attachmentRepository.delete(attachment);
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

    private MessageAttachmentEntity getAttachmentWithAccessCheck(
            UUID attachmentId
    ) {

        MessageAttachmentEntity attachment =
                attachmentRepository.findById(attachmentId)
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

    private void validateFile(
            MultipartFile file
    ) {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(
                    "Attachment file must not be empty"
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