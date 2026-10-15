
package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.ConversationGroupMembershipEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationGroupMembershipRepository
        extends JpaRepository<ConversationGroupMembershipEntity, UUID> {

    List<ConversationGroupMembershipEntity>
    findAllByGroupIdOrderByCreatedAtAsc(UUID groupId);

    Optional<ConversationGroupMembershipEntity>
    findByGroupIdAndConversationId(
            UUID groupId,
            UUID conversationId
    );

    boolean existsByGroupIdAndConversationId(
            UUID groupId,
            UUID conversationId
    );

    void deleteByGroupIdAndConversationId(
            UUID groupId,
            UUID conversationId
    );

    void deleteAllByGroupId(UUID groupId);

    /**
     * Возвращает только диалоги, которые сейчас назначены
     * владельцу группы.
     *
     * Членство в группе само по себе не предоставляет доступ
     * к диалогу, переназначенному другому сотруднику.
     */
    @Query("""
            SELECT m
            FROM ConversationGroupMembershipEntity m
            JOIN m.group g
            JOIN m.conversation c
            WHERE g.id = :groupId
              AND g.workspace.id = :workspaceId
              AND g.ownerEmployee.id = :employeeId
              AND c.workspace.id = :workspaceId
              AND c.assignedEmployee.id = :employeeId
            ORDER BY c.lastMessageAt DESC, c.id ASC
            """)
    List<ConversationGroupMembershipEntity> findAccessibleMemberships(
            @Param("groupId") UUID groupId,
            @Param("workspaceId") UUID workspaceId,
            @Param("employeeId") UUID employeeId
    );

    void deleteAllByConversation_Id(UUID conversationId);

    @Query("""
        SELECT g.id
        FROM ConversationGroupMembershipEntity m
        JOIN m.group g
        JOIN m.conversation c
        WHERE c.id = :conversationId
          AND c.workspace.id = :workspaceId
          AND g.workspace.id = :workspaceId
          AND g.ownerEmployee.id = :employeeId
          AND c.assignedEmployee.id = :employeeId
        ORDER BY g.name ASC
        """)
    List<UUID> findGroupIdsForAssignedConversation(
            @Param("conversationId") UUID conversationId,
            @Param("workspaceId") UUID workspaceId,
            @Param("employeeId") UUID employeeId
    );
}
