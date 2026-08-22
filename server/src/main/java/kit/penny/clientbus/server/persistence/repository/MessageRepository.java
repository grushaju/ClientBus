package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.MessageEntity;
//import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository
        extends JpaRepository<MessageEntity, UUID> {

    Optional<MessageEntity> findByConversationIdAndExternalId(
            UUID conversationId,
            String externalId
    );

    boolean existsByConversationIdAndExternalId(
            UUID conversationId,
            String externalId
    );

    List<MessageEntity> findAllByConversationIdOrderBySentAtAscCreatedAtAsc(
            UUID conversationId
    );

    List<MessageEntity> findAllByConversationIdOrderBySentAtDescCreatedAtDesc(
            UUID conversationId
    );

//    List<MessageEntity> findAllByConversationIdOrderBySentAtDescCreatedAtDesc(
//            UUID conversationId,
//            Pageable pageable
//    );

    Optional<MessageEntity>
    findFirstByConversationIdOrderBySentAtDescCreatedAtDesc(
            UUID conversationId
    );

    long countByConversationId(
            UUID conversationId
    );
}