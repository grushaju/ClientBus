package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.MessageAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageAttachmentRepository
        extends JpaRepository<MessageAttachmentEntity, UUID> {

    List<MessageAttachmentEntity>
    findAllByMessageIdOrderBySortOrderAsc(
            UUID messageId
    );

    Optional<MessageAttachmentEntity>
    findByStorageKey(
            String storageKey
    );

    boolean existsByStorageKey(
            String storageKey
    );

    long countByMessageId(
            UUID messageId
    );

    void deleteAllByMessageId(
            UUID messageId
    );

    List<MessageAttachmentEntity> findAllByMessageId(
            UUID messageId
    );
}