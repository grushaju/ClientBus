package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ClientEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ClientRepository
        extends JpaRepository<ClientEntity, UUID> {

    interface ClientListAggregateProjection {

        UUID getClientId();

        long getAccountCount();

        Instant getLastContactAt();
    }

    List<ClientEntity> findAllByOrganizationId(
            UUID organizationId
    );

    Page<ClientEntity> findAllByOrganizationId(
            UUID organizationId,
            Pageable pageable
    );

    List<ClientEntity> findAllByOrganizationIdAndIsEnabledTrue(
            UUID organizationId
    );

    List<ClientEntity> findAllByOrganizationIdAndIsEnabledFalse(
            UUID organizationId
    );

    List<ClientEntity> findByOrganizationIdAndFirstNameContainingIgnoreCase(
            UUID organizationId,
            String firstName
    );

    List<ClientEntity> findByOrganizationIdAndLastNameContainingIgnoreCase(
            UUID organizationId,
            String lastName
    );

    List<ClientEntity>
    findByOrganizationIdAndFirstNameContainingIgnoreCaseAndLastNameContainingIgnoreCase(
            UUID organizationId,
            String firstName,
            String lastName
    );

    @Query("""
        SELECT c
        FROM ClientEntity c
        JOIN c.phoneList p
        WHERE p = :phone
        """)
    Optional<ClientEntity> findByPhone(
            @Param("phone") String phone
    );

    @Query("""
        SELECT c
        FROM ClientEntity c
        JOIN c.phoneList p
        WHERE p = :phone
          AND c.organization.id = :organizationId
        """)
    Optional<ClientEntity> findByPhoneAndOrganizationId(
            @Param("phone") String phone,
            @Param("organizationId") UUID organizationId
    );

    @Query("""
        SELECT c
        FROM ClientEntity c
        JOIN c.phoneList p
        WHERE p LIKE CONCAT(:prefix, '%')
        """)
    List<ClientEntity> findByPhoneStartingWith(
            @Param("prefix") String prefix
    );



    /*
     * ---------------------------------------------------------
     * SUPER_ADMIN / Organization scope
     * ---------------------------------------------------------
     */
    @Query("""
    SELECT
        c.id AS clientId,
        COUNT(DISTINCT ca.id) AS accountCount,
        MAX(conversation.lastMessageAt) AS lastContactAt
    FROM ClientEntity c
    LEFT JOIN ClientAccountEntity ca
        ON ca.client.id = c.id
    LEFT JOIN ConversationEntity conversation
        ON conversation.clientAccount.id = ca.id
    WHERE c.id IN :clientIds
      AND c.organization.id = :organizationId
    GROUP BY c.id
    """)
    List<ClientListAggregateProjection> findListAggregates(
            @Param("organizationId") UUID organizationId,
            @Param("clientIds") List<UUID> clientIds
    );


    @Query("""
    SELECT DISTINCT c
    FROM ClientEntity c
    LEFT JOIN c.phoneList p
    WHERE c.organization.id = :organizationId
      AND (
           LOWER(c.firstName)
               LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(c.lastName)
               LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(p)
               LIKE LOWER(CONCAT('%', :query, '%'))
        OR EXISTS (
            SELECT ca.id
            FROM ClientAccountEntity ca
            WHERE ca.client.id = c.id
              AND (
                   LOWER(ca.username)
                       LIKE LOWER(CONCAT('%', :query, '%'))
                OR LOWER(ca.phone)
                       LIKE LOWER(CONCAT('%', :query, '%'))
              )
        )
      )
    """)
    List<ClientEntity> searchClients(
            @Param("organizationId") UUID organizationId,
            @Param("query") String query
    );

    @Query("""
    SELECT DISTINCT c
    FROM ClientEntity c
    LEFT JOIN c.phoneList p
    WHERE c.organization.id = :organizationId
      AND c.isEnabled = true
      AND (
           LOWER(c.firstName)
               LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(c.lastName)
               LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(p)
               LIKE LOWER(CONCAT('%', :query, '%'))
        OR EXISTS (
            SELECT ca.id
            FROM ClientAccountEntity ca
            WHERE ca.client.id = c.id
              AND (
                   LOWER(ca.username)
                       LIKE LOWER(CONCAT('%', :query, '%'))
                OR LOWER(ca.phone)
                       LIKE LOWER(CONCAT('%', :query, '%'))
              )
        )
      )
    """)
    List<ClientEntity> searchActiveClients(
            @Param("organizationId") UUID organizationId,
            @Param("query") String query
    );



    /*
     * ---------------------------------------------------------
     * EMPLOYEE / Workspace scope
     * ---------------------------------------------------------
     *
     * Client виден Employee, если существует хотя бы один
     * ClientAccount этого Client, связанный с Conversation
     * в Workspace, доступном Employee.
     */

    @Query("""
    SELECT
        c.id AS clientId,
        COUNT(DISTINCT ca.id) AS accountCount,
        MAX(conversation.lastMessageAt) AS lastContactAt
    FROM ClientEntity c
    JOIN ClientAccountEntity ca
        ON ca.client.id = c.id
    JOIN ConversationEntity conversation
        ON conversation.clientAccount.id = ca.id
    JOIN EmployeeWorkspaceEntity ew
        ON ew.workspace.id = conversation.workspace.id
    WHERE c.id IN :clientIds
      AND c.organization.id = :organizationId
      AND ew.employee.id = :employeeId
    GROUP BY c.id
    """)
    List<ClientListAggregateProjection> findListAggregatesForEmployee(
            @Param("organizationId") UUID organizationId,
            @Param("employeeId") UUID employeeId,
            @Param("clientIds") List<UUID> clientIds
    );

    @Query("""
        SELECT DISTINCT c
        FROM ClientEntity c
        JOIN ClientAccountEntity ca
          ON ca.client.id = c.id
        JOIN ConversationEntity conversation
          ON conversation.clientAccount.id = ca.id
        JOIN EmployeeWorkspaceEntity ew
          ON ew.workspace.id = conversation.workspace.id
        WHERE c.organization.id = :organizationId
          AND ew.employee.id = :employeeId
        ORDER BY c.lastName, c.firstName
        """)
    List<ClientEntity> findAllVisibleToEmployee(
            @Param("organizationId") UUID organizationId,
            @Param("employeeId") UUID employeeId
    );

    @Query("""
    SELECT DISTINCT c
    FROM ClientEntity c
    LEFT JOIN c.phoneList p
    JOIN ClientAccountEntity ca
      ON ca.client.id = c.id
    JOIN ConversationEntity conversation
      ON conversation.clientAccount.id = ca.id
    JOIN EmployeeWorkspaceEntity ew
      ON ew.workspace.id = conversation.workspace.id
    WHERE c.organization.id = :organizationId
      AND ew.employee.id = :employeeId
      AND (
           LOWER(c.firstName)
               LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(c.lastName)
               LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(p)
               LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(ca.username)
               LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(ca.phone)
               LIKE LOWER(CONCAT('%', :query, '%'))
      )
    ORDER BY c.lastName, c.firstName
    """)
    List<ClientEntity> searchClientsForEmployee(
            @Param("organizationId") UUID organizationId,
            @Param("employeeId") UUID employeeId,
            @Param("query") String query
    );

    @Query("""
        SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END
        FROM ClientEntity c
        JOIN ClientAccountEntity ca
          ON ca.client.id = c.id
        JOIN ConversationEntity conversation
          ON conversation.clientAccount.id = ca.id
        JOIN EmployeeWorkspaceEntity ew
          ON ew.workspace.id = conversation.workspace.id
        WHERE c.id = :clientId
          AND c.organization.id = :organizationId
          AND ew.employee.id = :employeeId
        """)
    boolean existsVisibleToEmployee(
            @Param("clientId") UUID clientId,
            @Param("organizationId") UUID organizationId,
            @Param("employeeId") UUID employeeId
    );

    /*
     * ---------------------------------------------------------
     * Organization checks
     * ---------------------------------------------------------
     */

    boolean existsByIdAndOrganizationId(
            UUID clientId,
            UUID organizationId
    );

    /*
     * ---------------------------------------------------------
     * Clients without Accounts
     * ---------------------------------------------------------
     */

    @Query("""
        SELECT c
        FROM ClientEntity c
        WHERE c.organization.id = :organizationId
          AND NOT EXISTS (
              SELECT ca.id
              FROM ClientAccountEntity ca
              WHERE ca.client.id = c.id
          )
        """)
    List<ClientEntity> findClientsWithoutAccounts(
            @Param("organizationId") UUID organizationId
    );

    /*
     * ---------------------------------------------------------
     * Statistics
     * ---------------------------------------------------------
     */

    @Query("""
        SELECT COUNT(c)
        FROM ClientEntity c
        WHERE c.organization.id = :organizationId
          AND c.isEnabled = true
        """)
    long countActiveClientsByOrganization(
            @Param("organizationId") UUID organizationId
    );

    @Query("""
        SELECT COUNT(c)
        FROM ClientEntity c
        WHERE c.organization.id = :organizationId
          AND c.isEnabled = false
        """)
    long countDisabledClientsByOrganization(
            @Param("organizationId") UUID organizationId
    );

    long countByOrganizationId(
            UUID organizationId
    );

    /*
     * ---------------------------------------------------------
     * Bulk delete
     * ---------------------------------------------------------
     */

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("""
        DELETE FROM ClientEntity c
        WHERE c.organization.id = :organizationId
        """)
    void deleteAllByOrganizationId(
            @Param("organizationId") UUID organizationId
    );

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("""
        DELETE FROM ClientEntity c
        WHERE c.id IN :clientIds
        """)
    void deleteAllByIds(
            @Param("clientIds") List<UUID> clientIds
    );
}