package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.ClientEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ClientRepository
        extends JpaRepository<ClientEntity, UUID> {

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

    @Query("""
        SELECT c
        FROM ClientEntity c
        WHERE c.organization.id = :organizationId
          AND (
               LOWER(c.firstName)
                   LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(c.lastName)
                   LIKE LOWER(CONCAT('%', :query, '%'))
          )
        """)
    List<ClientEntity> searchClients(
            @Param("organizationId") UUID organizationId,
            @Param("query") String query
    );

    @Query("""
        SELECT c
        FROM ClientEntity c
        WHERE c.organization.id = :organizationId
          AND c.isEnabled = true
          AND (
               LOWER(c.firstName)
                   LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(c.lastName)
                   LIKE LOWER(CONCAT('%', :query, '%'))
          )
        """)
    List<ClientEntity> searchActiveClients(
            @Param("organizationId") UUID organizationId,
            @Param("query") String query
    );

    boolean existsByIdAndOrganizationId(
            UUID clientId,
            UUID organizationId
    );

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

    @Modifying
    @Transactional
    @Query("""
        DELETE FROM ClientEntity c
        WHERE c.organization.id = :organizationId
        """)
    void deleteAllByOrganizationId(
            @Param("organizationId") UUID organizationId
    );

    @Modifying
    @Transactional
    @Query("""
        DELETE FROM ClientEntity c
        WHERE c.id IN :clientIds
        """)
    void deleteAllByIds(
            @Param("clientIds") List<UUID> clientIds
    );
}