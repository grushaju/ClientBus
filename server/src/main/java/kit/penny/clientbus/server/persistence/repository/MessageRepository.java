package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.common.enums.MessageDeliveryStatus;
import kit.penny.clientbus.common.enums.MessageDirection;
import kit.penny.clientbus.common.enums.MessageProcessingStatus;
import kit.penny.clientbus.server.persistence.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository
        extends JpaRepository<MessageEntity, UUID> {

    Optional<MessageEntity>
    findByConversationIdAndExternalId(
            UUID conversationId,
            String externalId
    );

    boolean existsByConversationIdAndExternalId(
            UUID conversationId,
            String externalId
    );

    List<MessageEntity>
    findAllByConversationIdAndDirectionAndDeliveryStatus(
            UUID conversationId,
            MessageDirection direction,
            MessageDeliveryStatus deliveryStatus
    );

    List<MessageEntity>
    findAllByConversationIdOrderBySentAtAscCreatedAtAsc(
            UUID conversationId
    );

    List<MessageEntity>
    findAllByConversationIdOrderBySentAtDescCreatedAtDesc(
            UUID conversationId
    );

    Optional<MessageEntity>
    findFirstByConversationIdOrderBySentAtDescCreatedAtDesc(
            UUID conversationId
    );

    long countByConversationId(
            UUID conversationId
    );

    List<MessageEntity>
    findAllByProcessingStatus(
            MessageProcessingStatus status
    );

    List<MessageEntity>
    findAllByDeliveryStatus(
            MessageDeliveryStatus status
    );

    long countByConversationIdAndDirection(
            UUID conversationId,
            MessageDirection direction
    );

    Optional<MessageEntity>
    findByConversationChannelAccountIdAndExternalId(
            UUID channelAccountId,
            String externalId
    );

    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update MessageEntity m
           set m.processingStatus = :processingStatus,
               m.deliveryStatus = :deliveryStatus,
               m.processedAt = null,
               m.sentAt = null,
               m.deliveredAt = null,
               m.readAt = null,
               m.externalId = null
         where m.id = :messageId
           and m.direction = :direction
           and m.processingStatus = :currentProcessingStatus
           and m.deliveryStatus = :currentDeliveryStatus
        """)
    int retryDelivery(
            UUID messageId,
            MessageDirection direction,
            MessageProcessingStatus currentProcessingStatus,
            MessageDeliveryStatus currentDeliveryStatus,
            MessageProcessingStatus processingStatus,
            MessageDeliveryStatus deliveryStatus
    );
}