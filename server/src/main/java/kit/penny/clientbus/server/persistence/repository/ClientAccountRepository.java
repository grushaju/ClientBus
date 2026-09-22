package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClientAccountRepository
        extends JpaRepository<ClientAccountEntity, UUID> {

    List<ClientAccountEntity> findAllByClientId(
            UUID clientId
    );

    @Query("""
    SELECT DISTINCT a
    FROM ClientAccountEntity a
    JOIN ConversationEntity c
      ON c.clientAccount.id = a.id
    JOIN EmployeeWorkspaceEntity ew
      ON ew.workspace.id = c.workspace.id
    WHERE a.client.id = :clientId
      AND ew.employee.id = :employeeId
    """)
    List<ClientAccountEntity> findAllByClientIdAndEmployeeId(
            @Param("clientId") UUID clientId,
            @Param("employeeId") UUID employeeId
    );

    List<ClientAccountEntity> findAllByClientIdAndChannelType(
            UUID clientId,
            ChannelType channelType
    );

    Optional<ClientAccountEntity>
    findByClientIdAndChannelTypeAndExternalId(
            UUID clientId,
            ChannelType channelType,
            String externalId
    );

    List<ClientAccountEntity> findAllByClientIsNull();

    List<ClientAccountEntity>
    findAllByClientIsNullAndChannelType(
            ChannelType channelType
    );

    @Query("""
        SELECT a
        FROM ClientAccountEntity a
        WHERE a.client.id = :clientId
          AND (
               LOWER(a.username)
                   LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(a.phone)
                   LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(a.externalId)
                   LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(a.displayName)
                   LIKE LOWER(CONCAT('%', :query, '%'))
          )
        """)
    List<ClientAccountEntity> searchByClient(
            @Param("clientId") UUID clientId,
            @Param("query") String query
    );

    /*
     * Глобальная идентичность аккаунта.
     */
    Optional<ClientAccountEntity>
    findByChannelTypeAndExternalId(
            ChannelType channelType,
            String externalId
    );

    boolean existsByChannelTypeAndExternalId(
            ChannelType channelType,
            String externalId
    );

    /*
     * Account виден SUPER_ADMIN только если у него
     * есть Conversation внутри текущей Organization.
     */
    @Query("""
        SELECT DISTINCT a
        FROM ClientAccountEntity a
        JOIN ConversationEntity c
          ON c.clientAccount.id = a.id
        WHERE a.id IN :ids
          AND c.workspace.organization.id = :organizationId
        """)
    List<ClientAccountEntity> findAllByIdsAndOrganizationId(
            @Param("ids") List<UUID> ids,
            @Param("organizationId") UUID organizationId
    );

    /*
     * Account виден EMPLOYEE только если у него есть
     * Conversation в Workspace, доступном Employee.
     */
    @Query("""
        SELECT DISTINCT a
        FROM ClientAccountEntity a
        JOIN ConversationEntity c
          ON c.clientAccount.id = a.id
        JOIN EmployeeWorkspaceEntity ew
          ON ew.workspace.id = c.workspace.id
        WHERE a.id IN :ids
          AND ew.employee.id = :employeeId
        """)
    List<ClientAccountEntity> findAllByIdsAndEmployeeId(
            @Param("ids") List<UUID> ids,
            @Param("employeeId") UUID employeeId
    );

    /*
     * Orphan Account всё ещё может существовать только
     * благодаря Conversation.
     *
     * Поэтому для SUPER_ADMIN нельзя отдавать все
     * client IS NULL аккаунты из всей БД.
     */
    @Query("""
        SELECT DISTINCT a
        FROM ClientAccountEntity a
        JOIN ConversationEntity c
          ON c.clientAccount.id = a.id
        WHERE a.client IS NULL
          AND c.workspace.organization.id = :organizationId
        """)
    List<ClientAccountEntity> findAllUnassignedByOrganizationId(
            @Param("organizationId") UUID organizationId
    );

    @Query("""
        SELECT DISTINCT a
        FROM ClientAccountEntity a
        JOIN ConversationEntity c
          ON c.clientAccount.id = a.id
        WHERE a.client IS NULL
          AND a.channelType = :channelType
          AND c.workspace.organization.id = :organizationId
        """)
    List<ClientAccountEntity>
    findAllUnassignedByOrganizationIdAndChannelType(
            @Param("organizationId") UUID organizationId,
            @Param("channelType") ChannelType channelType
    );

    /**
     * Поиск orphan ClientAccount для SUPER_ADMIN.
     *
     * Account должен:
     * - не иметь Client;
     * - иметь Conversation;
     * - иметь Conversation в текущей Organization.
     */
    @Query("""
    SELECT DISTINCT a
    FROM ClientAccountEntity a
    JOIN ConversationEntity c
      ON c.clientAccount.id = a.id
    WHERE a.client IS NULL
      AND c.workspace.organization.id = :organizationId
      AND (
           LOWER(a.username)
               LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(a.phone)
               LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(a.externalId)
               LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(a.displayName)
               LIKE LOWER(CONCAT('%', :query, '%'))
      )
    ORDER BY a.displayName
    """)
    List<ClientAccountEntity> searchUnassignedByOrganizationId(
            @Param("organizationId") UUID organizationId,
            @Param("query") String query
    );

    /**
     * Поиск orphan ClientAccount для EMPLOYEE.
     *
     * Account должен:
     * - не иметь Client;
     * - иметь Conversation;
     * - Conversation должен находиться в Workspace,
     *   доступном текущему Employee.
     */
    @Query("""
    SELECT DISTINCT a
    FROM ClientAccountEntity a
    JOIN ConversationEntity c
      ON c.clientAccount.id = a.id
    JOIN EmployeeWorkspaceEntity ew
      ON ew.workspace.id = c.workspace.id
    WHERE a.client IS NULL
      AND c.workspace.organization.id = :organizationId
      AND ew.employee.id = :employeeId
      AND (
           LOWER(a.username)
               LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(a.phone)
               LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(a.externalId)
               LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(a.displayName)
               LIKE LOWER(CONCAT('%', :query, '%'))
      )
    ORDER BY a.displayName
    """)
    List<ClientAccountEntity> searchUnassignedByOrganizationIdAndEmployeeId(
            @Param("organizationId") UUID organizationId,
            @Param("employeeId") UUID employeeId,
            @Param("query") String query
    );

    /**
     * Все orphan accounts текущей Organization,
     * доступные EMPLOYEE.
     */
    @Query("""
    SELECT DISTINCT a
    FROM ClientAccountEntity a
    JOIN ConversationEntity c
      ON c.clientAccount.id = a.id
    JOIN EmployeeWorkspaceEntity ew
      ON ew.workspace.id = c.workspace.id
    WHERE a.client IS NULL
      AND c.workspace.organization.id = :organizationId
      AND ew.employee.id = :employeeId
    ORDER BY a.displayName
    """)
    List<ClientAccountEntity> findAllUnassignedByOrganizationIdAndEmployeeId(
            @Param("organizationId") UUID organizationId,
            @Param("employeeId") UUID employeeId
    );
}