package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.common.dto.conversation.ConversationClientLinkFilter;
import kit.penny.clientbus.common.dto.conversation.ConversationSort;
import kit.penny.clientbus.common.dto.conversation.ConversationUnreadFilter;
import kit.penny.clientbus.common.dto.conversation.ConversationView;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ConversationInboxRepository
        extends JpaRepository<ConversationEntity, UUID> {

    @Query(
            value = """
            SELECT c
            FROM ConversationEntity c
            JOIN c.clientAccount ca
            WHERE c.workspace.id = :workspaceId
              AND (
                    (
                        :view = kit.penny.clientbus.common.dto.conversation.ConversationView.MINE
                        AND c.assignedEmployee.id = :employeeId
                    )
                    OR
                    (
                        :view = kit.penny.clientbus.common.dto.conversation.ConversationView.UNASSIGNED
                        AND c.assignedEmployee IS NULL
                    )
                    OR
                    (
                        :view = kit.penny.clientbus.common.dto.conversation.ConversationView.GROUP
                        AND EXISTS (
                            SELECT m.id
                            FROM ConversationGroupMembershipEntity m
                            JOIN m.group g
                            WHERE m.conversation.id = c.id
                              AND g.id = :groupId
                              AND g.workspace.id = :workspaceId
                              AND g.ownerEmployee.id = :employeeId
                        )
                        AND c.assignedEmployee.id = :employeeId
                    )
              )
              AND (
                    :search IS NULL
                    OR :search = ''
                    OR LOWER(COALESCE(c.lastMessagePreview, ''))
                        LIKE LOWER(CONCAT('%', :search, '%'))
                    OR LOWER(COALESCE(ca.displayName, ''))
                        LIKE LOWER(CONCAT('%', :search, '%'))
                    OR LOWER(COALESCE(ca.username, ''))
                        LIKE LOWER(CONCAT('%', :search, '%'))
                    OR LOWER(COALESCE(ca.phone, ''))
                        LIKE LOWER(CONCAT('%', :search, '%'))
                    OR LOWER(COALESCE(ca.externalId, ''))
                        LIKE LOWER(CONCAT('%', :search, '%'))
                )
              AND (
                    :channelAccountIds IS NULL
                    OR c.channelAccount.id IN :channelAccountIds
                )
              AND (
                    :clientAccountIds IS NULL
                    OR c.clientAccount.id IN :clientAccountIds
                )
              AND (
                    :clientLink = kit.penny.clientbus.common.dto.conversation.ConversationClientLinkFilter.ANY
                    OR (
                        :clientLink = kit.penny.clientbus.common.dto.conversation.ConversationClientLinkFilter.WITH_CLIENT
                        AND ca.client IS NOT NULL
                    )
                    OR (
                        :clientLink = kit.penny.clientbus.common.dto.conversation.ConversationClientLinkFilter.WITHOUT_CLIENT
                        AND ca.client IS NULL
                    )
                )
              AND (
                    :unread = kit.penny.clientbus.common.dto.conversation.ConversationUnreadFilter.ANY
                    OR (
                        :unread = kit.penny.clientbus.common.dto.conversation.ConversationUnreadFilter.HAS_UNREAD
                        AND c.unreadCount > 0
                    )
                    OR (
                        :unread = kit.penny.clientbus.common.dto.conversation.ConversationUnreadFilter.READ_ONLY
                        AND c.unreadCount = 0
                    )
                )
              AND (
                    :activityFrom IS NULL
                    OR c.lastMessageAt >= :activityFrom
                )
              AND (
                    :activityTo IS NULL
                    OR c.lastMessageAt <= :activityTo
                )
            ORDER BY
                CASE
                    WHEN :sort = kit.penny.clientbus.common.dto.conversation.ConversationSort.LAST_MESSAGE_DESC
                    THEN c.lastMessageAt
                    ELSE NULL
                END DESC,
                CASE
                    WHEN :sort = kit.penny.clientbus.common.dto.conversation.ConversationSort.LAST_MESSAGE_ASC
                    THEN c.lastMessageAt
                    ELSE NULL
                END ASC,
                c.id ASC
            """,
            countQuery = """
            SELECT COUNT(c)
            FROM ConversationEntity c
            JOIN c.clientAccount ca
            WHERE c.workspace.id = :workspaceId
              AND (
                    (
                        :view = kit.penny.clientbus.common.dto.conversation.ConversationView.MINE
                        AND c.assignedEmployee.id = :employeeId
                    )
                    OR
                    (
                        :view = kit.penny.clientbus.common.dto.conversation.ConversationView.UNASSIGNED
                        AND c.assignedEmployee IS NULL
                    )
                    OR
                    (
                        :view = kit.penny.clientbus.common.dto.conversation.ConversationView.GROUP
                        AND EXISTS (
                            SELECT m.id
                            FROM ConversationGroupMembershipEntity m
                            JOIN m.group g
                            WHERE m.conversation.id = c.id
                              AND g.id = :groupId
                              AND g.workspace.id = :workspaceId
                              AND g.ownerEmployee.id = :employeeId
                        )
                        AND c.assignedEmployee.id = :employeeId
                    )
              )
              AND (
                    :search IS NULL
                    OR :search = ''
                    OR LOWER(COALESCE(c.lastMessagePreview, ''))
                        LIKE LOWER(CONCAT('%', :search, '%'))
                    OR LOWER(COALESCE(ca.displayName, ''))
                        LIKE LOWER(CONCAT('%', :search, '%'))
                    OR LOWER(COALESCE(ca.username, ''))
                        LIKE LOWER(CONCAT('%', :search, '%'))
                    OR LOWER(COALESCE(ca.phone, ''))
                        LIKE LOWER(CONCAT('%', :search, '%'))
                    OR LOWER(COALESCE(ca.externalId, ''))
                        LIKE LOWER(CONCAT('%', :search, '%'))
                )
              AND (
                    :channelAccountIds IS NULL
                    OR c.channelAccount.id IN :channelAccountIds
                )
              AND (
                    :clientAccountIds IS NULL
                    OR c.clientAccount.id IN :clientAccountIds
                )
              AND (
                    :clientLink = kit.penny.clientbus.common.dto.conversation.ConversationClientLinkFilter.ANY
                    OR (
                        :clientLink = kit.penny.clientbus.common.dto.conversation.ConversationClientLinkFilter.WITH_CLIENT
                        AND ca.client IS NOT NULL
                    )
                    OR (
                        :clientLink = kit.penny.clientbus.common.dto.conversation.ConversationClientLinkFilter.WITHOUT_CLIENT
                        AND ca.client IS NULL
                    )
                )
              AND (
                    :unread = kit.penny.clientbus.common.dto.conversation.ConversationUnreadFilter.ANY
                    OR (
                        :unread = kit.penny.clientbus.common.dto.conversation.ConversationUnreadFilter.HAS_UNREAD
                        AND c.unreadCount > 0
                    )
                    OR (
                        :unread = kit.penny.clientbus.common.dto.conversation.ConversationUnreadFilter.READ_ONLY
                        AND c.unreadCount = 0
                    )
                )
              AND (
                    :activityFrom IS NULL
                    OR c.lastMessageAt >= :activityFrom
                )
              AND (
                    :activityTo IS NULL
                    OR c.lastMessageAt <= :activityTo
                )
            """
    )
    Page<ConversationEntity> searchConversation(
            @Param("workspaceId") UUID workspaceId,
            @Param("employeeId") UUID employeeId,
            @Param("view") ConversationView view,
            @Param("groupId") UUID groupId,
            @Param("search") String search,
            @Param("channelAccountIds") List<UUID> channelAccountIds,
            @Param("clientAccountIds") List<UUID> clientAccountIds,
            @Param("clientLink") ConversationClientLinkFilter clientLink,
            @Param("unread") ConversationUnreadFilter unread,
            @Param("activityFrom") Instant activityFrom,
            @Param("activityTo") Instant activityTo,
            @Param("sort") ConversationSort sort,
            Pageable pageable
    );
}