package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kit.penny.clientbus.common.dto.conversation.ConversationClientLinkFilter;
import kit.penny.clientbus.common.dto.conversation.ConversationSort;
import kit.penny.clientbus.common.dto.conversation.ConversationUnreadFilter;
import kit.penny.clientbus.common.dto.conversation.ConversationView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository
        extends JpaRepository<ConversationEntity, UUID> {

    /*
     * ---------------------------------------------------------
     * Basic lookup
     * ---------------------------------------------------------
     */

    Optional<ConversationEntity>
    findByChannelAccountIdAndClientAccountId(
            UUID channelAccountId,
            UUID clientAccountId
    );

    Optional<ConversationEntity>
    findByChannelAccountIdAndClientAccountExternalId(
            UUID channelAccountId,
            String clientAccountExternalId
    );

    boolean existsByChannelAccountIdAndClientAccountId(
            UUID channelAccountId,
            UUID clientAccountId
    );

    /*
     * ---------------------------------------------------------
     * Workspace
     * ---------------------------------------------------------
     */

    List<ConversationEntity>
    findAllByWorkspaceIdOrderByLastMessageAtDesc(
            UUID workspaceId
    );

    List<ConversationEntity>
    findAllByWorkspaceIdAndAssignedEmployeeIsNullOrderByLastMessageAtDesc(
            UUID workspaceId
    );

    /*
     * ---------------------------------------------------------
     * ClientAccount
     * ---------------------------------------------------------
     *
     * SUPER_ADMIN:
     * доступ ко всем Workspace своей Organization.
     */

    @Query("""
            SELECT c
            FROM ConversationEntity c
            WHERE c.clientAccount.id = :clientAccountId
              AND c.workspace.organization.id = :organizationId
            ORDER BY c.lastMessageAt DESC
            """)
    List<ConversationEntity>
    findAllByClientAccountIdAndOrganizationIdOrderByLastMessageAtDesc(
            @Param("clientAccountId")
            UUID clientAccountId,

            @Param("organizationId")
            UUID organizationId
    );

    /*
     * ---------------------------------------------------------
     * Client
     * ---------------------------------------------------------
     *
     * ВАЖНО:
     * Conversation не имеет прямой связи с Client.
     *
     * Связь:
     *
     * Client
     *   -> ClientAccount
     *       -> Conversation
     *
     * Поэтому здесь используется:
     * c.clientAccount.client.id
     */

    /**
     * EMPLOYEE:
     * только Conversation ClientAccount данного Client,
     * находящиеся в Workspace, доступных Employee.
     */

    @Query("""
    SELECT c
    FROM ConversationEntity c
    JOIN EmployeeWorkspaceEntity ew
      ON ew.workspace.id = c.workspace.id
    WHERE c.clientAccount.client.id = :clientId
      AND c.workspace.organization.id = :organizationId
      AND ew.employee.id = :employeeId
    ORDER BY c.lastMessageAt DESC
    """)
    List<ConversationEntity>
    findAllByClientIdAndOrganizationIdAndEmployeeIdOrderByLastMessageAtDesc(
            @Param("clientId")
            UUID clientId,

            @Param("organizationId")
            UUID organizationId,

            @Param("employeeId")
            UUID employeeId
    );

    /**
     * EMPLOYEE:
     * только Conversation Client,
     * которую текущий Employee действительно может открыть.
     *
     * Учитываются оба уровня ACL:
     *
     * 1. Employee имеет доступ к Workspace.
     * 2. Conversation:
     *      - не назначена сотруднику
     *        или
     *      - назначена текущему Employee.
     *
     * Это соответствует CurrentUserService.requireConversationAccess().
     */
    @Query("""
    SELECT DISTINCT c
    FROM ConversationEntity c
    JOIN EmployeeWorkspaceEntity ew
      ON ew.workspace.id = c.workspace.id
    WHERE c.clientAccount.client.id = :clientId
      AND ew.employee.id = :employeeId
      AND (
          c.assignedEmployee IS NULL
          OR c.assignedEmployee.id = :employeeId
      )
    ORDER BY c.lastMessageAt DESC
    """)
    List<ConversationEntity>
    findAllAccessibleByClientIdAndEmployeeIdOrderByLastMessageAtDesc(
            @Param("clientId")
            UUID clientId,

            @Param("employeeId")
            UUID employeeId
    );

    /*
     * EMPLOYEE:
     * только Workspace, к которым Employee имеет доступ.
     */

    @Query("""
            SELECT c
            FROM ConversationEntity c
            JOIN EmployeeWorkspaceEntity ew
              ON ew.workspace.id = c.workspace.id
            WHERE c.clientAccount.id = :clientAccountId
              AND ew.employee.id = :employeeId
            ORDER BY c.lastMessageAt DESC
            """)
    List<ConversationEntity>
    findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
            @Param("clientAccountId")
            UUID clientAccountId,

            @Param("employeeId")
            UUID employeeId
    );

    /*
     * ---------------------------------------------------------
     * ChannelAccount
     * ---------------------------------------------------------
     */

    @Query("""
            SELECT c
            FROM ConversationEntity c
            WHERE c.channelAccount.id = :channelAccountId
              AND c.workspace.organization.id = :organizationId
            ORDER BY c.lastMessageAt DESC
            """)
    List<ConversationEntity>
    findAllByChannelAccountIdAndOrganizationIdOrderByLastMessageAtDesc(
            @Param("channelAccountId")
            UUID channelAccountId,

            @Param("organizationId")
            UUID organizationId
    );

    @Query("""
            SELECT c
            FROM ConversationEntity c
            JOIN EmployeeWorkspaceEntity ew
              ON ew.workspace.id = c.workspace.id
            WHERE c.channelAccount.id = :channelAccountId
              AND ew.employee.id = :employeeId
            ORDER BY c.lastMessageAt DESC
            """)
    List<ConversationEntity>
    findAllByChannelAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
            @Param("channelAccountId")
            UUID channelAccountId,

            @Param("employeeId")
            UUID employeeId
    );

    /*
     * ---------------------------------------------------------
     * Assigned Employee
     * ---------------------------------------------------------
     */

    /*
     * SUPER_ADMIN:
     * Conversation Employee в своей Organization.
     */
    @Query("""
            SELECT c
            FROM ConversationEntity c
            WHERE c.assignedEmployee.id = :employeeId
              AND c.workspace.organization.id = :organizationId
            ORDER BY c.lastMessageAt DESC
            """)
    List<ConversationEntity>
    findAllByAssignedEmployeeIdAndOrganizationIdOrderByLastMessageAtDesc(
            @Param("employeeId")
            UUID employeeId,

            @Param("organizationId")
            UUID organizationId
    );

    /*
     * EMPLOYEE:
     * только Conversation в Workspace,
     * доступных текущему Employee.
     */

    @Query("""
        SELECT DISTINCT c
        FROM ConversationEntity c
        JOIN c.clientAccount ca
        JOIN EmployeeWorkspaceEntity ew
          ON ew.workspace.id = c.workspace.id
        WHERE ca.client.id = :clientId
          AND ew.employee.id = :employeeId
        ORDER BY c.lastMessageAt DESC
        """)
    List<ConversationEntity>
    findAllByClientIdAndEmployeeIdOrderByLastMessageAtDesc(
            @Param("clientId")
            UUID clientId,

            @Param("employeeId")
            UUID employeeId
    );

    @Query("""
            SELECT c
            FROM ConversationEntity c
            JOIN EmployeeWorkspaceEntity ew
              ON ew.workspace.id = c.workspace.id
            WHERE c.assignedEmployee.id = :employeeId
              AND ew.employee.id = :employeeId
            ORDER BY c.lastMessageAt DESC
            """)
    List<ConversationEntity>
    findAllByAssignedEmployeeIdAndEmployeeAccessOrderByLastMessageAtDesc(
            @Param("employeeId")
            UUID employeeId
    );

    @Query("""
        SELECT c
        FROM ConversationEntity c
        JOIN EmployeeWorkspaceEntity ew
          ON ew.workspace.id = c.workspace.id
        WHERE c.workspace.id = :workspaceId
          AND c.assignedEmployee.id = :employeeId
          AND ew.employee.id = :employeeId
        ORDER BY c.lastMessageAt DESC
        """)
    List<ConversationEntity>
    findAllByWorkspaceIdAndAssignedEmployeeIdAndEmployeeAccessOrderByLastMessageAtDesc(
            @Param("workspaceId")
            UUID workspaceId,

            @Param("employeeId")
            UUID employeeId
    );

    /*
     * ---------------------------------------------------------
     * Unread count
     * ---------------------------------------------------------
     */

    long countByWorkspaceIdAndUnreadCountGreaterThan(
            UUID workspaceId,
            int unreadCount
    );

    /*
     * SUPER_ADMIN.
     */
    @Query("""
        SELECT DISTINCT c
        FROM ConversationEntity c
        JOIN c.clientAccount ca
        WHERE ca.client.id = :clientId
          AND c.workspace.organization.id = :organizationId
        ORDER BY c.lastMessageAt DESC
        """)
    List<ConversationEntity>
    findAllByClientIdAndOrganizationIdOrderByLastMessageAtDesc(
            @Param("clientId")
            UUID clientId,

            @Param("organizationId")
            UUID organizationId
    );

    @Query("""
            SELECT COUNT(c)
            FROM ConversationEntity c
            WHERE c.assignedEmployee.id = :employeeId
              AND c.workspace.organization.id = :organizationId
              AND c.unreadCount > :unreadCount
            """)
    long countAssignedUnreadByEmployeeAndOrganization(
            @Param("employeeId")
            UUID employeeId,

            @Param("organizationId")
            UUID organizationId,

            @Param("unreadCount")
            int unreadCount
    );

    /*
     * EMPLOYEE.
     */
    @Query("""
            SELECT COUNT(c)
            FROM ConversationEntity c
            JOIN EmployeeWorkspaceEntity ew
              ON ew.workspace.id = c.workspace.id
            WHERE c.assignedEmployee.id = :employeeId
              AND ew.employee.id = :employeeId
              AND c.unreadCount > :unreadCount
            """)
    long countAssignedUnreadByEmployeeWithWorkspaceAccess(
            @Param("employeeId")
            UUID employeeId,

            @Param("unreadCount")
            int unreadCount
    );


    /**
     * Пагинированный поиск диалогов Inbox.
     * Доступ к Workspace и принадлежность группы
     * дополнительно проверяются в сервисе.
     */
    @Query(
            value = """
        SELECT DISTINCT c
        FROM ConversationEntity c
        LEFT JOIN ConversationGroupMembershipEntity gm
          ON gm.conversation.id = c.id
         AND gm.group.id = :groupId
         AND gm.group.workspace.id = :workspaceId
         AND gm.group.ownerEmployee.id = :employeeId
        WHERE c.workspace.id = :workspaceId
          AND (
              (:view = kit.penny.clientbus.common.dto.conversation.ConversationView.MINE
                  AND c.assignedEmployee.id = :employeeId)
              OR
              (:view = kit.penny.clientbus.common.dto.conversation.ConversationView.UNASSIGNED
                  AND c.assignedEmployee IS NULL)
              OR
              (:view = kit.penny.clientbus.common.dto.conversation.ConversationView.GROUP
                  AND gm.id IS NOT NULL
                  AND c.assignedEmployee.id = :employeeId)
              OR
              (:view = kit.penny.clientbus.common.dto.conversation.ConversationView.ALL
                  AND :canViewAll = true)
          )
          AND (
              :search IS NULL
              OR LOWER(COALESCE(c.clientAccount.displayName, ''))
                    LIKE CONCAT('%', :search, '%')
              OR LOWER(COALESCE(c.clientAccount.username, ''))
                    LIKE CONCAT('%', :search, '%')
              OR LOWER(COALESCE(c.clientAccount.externalId, ''))
                    LIKE CONCAT('%', :search, '%')
              OR LOWER(COALESCE(c.lastMessagePreview, ''))
                    LIKE CONCAT('%', :search, '%')
          )
          AND (
              :filterChannels = false
              OR c.channelAccount.id IN :channelAccountIds
          )
          AND (
              :clientLink = kit.penny.clientbus.common.dto.conversation.ConversationClientLinkFilter.ANY
              OR (
                  :clientLink = kit.penny.clientbus.common.dto.conversation.ConversationClientLinkFilter.WITH_CLIENT
                  AND c.clientAccount.client IS NOT NULL
              )
              OR (
                  :clientLink = kit.penny.clientbus.common.dto.conversation.ConversationClientLinkFilter.WITHOUT_CLIENT
                  AND c.clientAccount.client IS NULL
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
          AND (:activityFrom IS NULL OR c.lastMessageAt >= :activityFrom)
          AND (:activityTo IS NULL OR c.lastMessageAt <= :activityTo)
        ORDER BY
          CASE WHEN :sort = kit.penny.clientbus.common.dto.conversation.ConversationSort.LAST_MESSAGE_ASC
               THEN c.lastMessageAt END ASC,
          CASE WHEN :sort = kit.penny.clientbus.common.dto.conversation.ConversationSort.OLDEST_ACTIVITY_FIRST
               THEN COALESCE(c.lastMessageAt, c.createdAt) END ASC,
          c.lastMessageAt DESC,
          c.id ASC
        """,
            countQuery = """
        SELECT COUNT(DISTINCT c.id)
        FROM ConversationEntity c
        LEFT JOIN ConversationGroupMembershipEntity gm
          ON gm.conversation.id = c.id
         AND gm.group.id = :groupId
         AND gm.group.workspace.id = :workspaceId
         AND gm.group.ownerEmployee.id = :employeeId
        WHERE c.workspace.id = :workspaceId
          AND (
              (:view = kit.penny.clientbus.common.dto.conversation.ConversationView.MINE
                  AND c.assignedEmployee.id = :employeeId)
              OR
              (:view = kit.penny.clientbus.common.dto.conversation.ConversationView.UNASSIGNED
                  AND c.assignedEmployee IS NULL)
              OR
              (:view = kit.penny.clientbus.common.dto.conversation.ConversationView.GROUP
                  AND gm.id IS NOT NULL
                  AND c.assignedEmployee.id = :employeeId)
              OR
              (:view = kit.penny.clientbus.common.dto.conversation.ConversationView.ALL
                  AND :canViewAll = true)
          )
          AND (
              :search IS NULL
              OR LOWER(COALESCE(c.clientAccount.displayName, ''))
                    LIKE CONCAT('%', :search, '%')
              OR LOWER(COALESCE(c.clientAccount.username, ''))
                    LIKE CONCAT('%', :search, '%')
              OR LOWER(COALESCE(c.clientAccount.externalId, ''))
                    LIKE CONCAT('%', :search, '%')
              OR LOWER(COALESCE(c.lastMessagePreview, ''))
                    LIKE CONCAT('%', :search, '%')
          )
          AND (
              :filterChannels = false
              OR c.channelAccount.id IN :channelAccountIds
          )
          AND (
              :clientLink = kit.penny.clientbus.common.dto.conversation.ConversationClientLinkFilter.ANY
              OR (
                  :clientLink = kit.penny.clientbus.common.dto.conversation.ConversationClientLinkFilter.WITH_CLIENT
                  AND c.clientAccount.client IS NOT NULL
              )
              OR (
                  :clientLink = kit.penny.clientbus.common.dto.conversation.ConversationClientLinkFilter.WITHOUT_CLIENT
                  AND c.clientAccount.client IS NULL
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
          AND (:activityFrom IS NULL OR c.lastMessageAt >= :activityFrom)
          AND (:activityTo IS NULL OR c.lastMessageAt <= :activityTo)
        """
    )
    Page<ConversationEntity> searchInbox(
            @Param("workspaceId") UUID workspaceId,
            @Param("view") ConversationView view,
            @Param("groupId") UUID groupId,
            @Param("employeeId") UUID employeeId,
            @Param("canViewAll") boolean canViewAll,
            @Param("search") String search,
            @Param("filterChannels") boolean filterChannels,
            @Param("channelAccountIds") List<UUID> channelAccountIds,
            @Param("clientLink") ConversationClientLinkFilter clientLink,
            @Param("unread") ConversationUnreadFilter unread,
            @Param("activityFrom") Instant activityFrom,
            @Param("activityTo") Instant activityTo,
            @Param("sort") ConversationSort sort,
            Pageable pageable
    );

}