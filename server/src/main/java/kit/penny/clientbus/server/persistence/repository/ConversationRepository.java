package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}