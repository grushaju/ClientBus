package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository
        extends JpaRepository<ConversationEntity, UUID> {

    Optional<ConversationEntity>
    findByChannelAccountIdAndClientAccountId(
            UUID channelAccountId,
            UUID clientAccountId
    );

    boolean existsByChannelAccountIdAndClientAccountId(
            UUID channelAccountId,
            UUID clientAccountId
    );

    List<ConversationEntity>
    findAllByWorkspaceIdOrderByLastMessageAtDesc(
            UUID workspaceId
    );

    List<ConversationEntity>
    findAllByWorkspaceIdAndAssignedEmployeeIdOrderByLastMessageAtDesc(
            UUID workspaceId,
            UUID employeeId
    );

    List<ConversationEntity>
    findAllByWorkspaceIdAndAssignedEmployeeIsNullOrderByLastMessageAtDesc(
            UUID workspaceId
    );

    List<ConversationEntity>
    findAllByAssignedEmployeeIdOrderByLastMessageAtDesc(
            UUID employeeId
    );

    List<ConversationEntity>
    findAllByClientAccountIdOrderByLastMessageAtDesc(
            UUID clientAccountId
    );

    List<ConversationEntity>
    findAllByChannelAccountIdOrderByLastMessageAtDesc(
            UUID channelAccountId
    );

    long countByWorkspaceIdAndUnreadCountGreaterThan(
            UUID workspaceId,
            int unreadCount
    );

    long countByAssignedEmployeeIdAndUnreadCountGreaterThan(
            UUID employeeId,
            int unreadCount
    );
}