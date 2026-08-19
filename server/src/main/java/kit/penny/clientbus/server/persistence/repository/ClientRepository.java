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
public interface ClientRepository extends JpaRepository<ClientEntity, UUID> {

    // Базовые поиски по workspace
    List<ClientEntity> findAllByWorkspaceId(UUID workspaceId);

    Page<ClientEntity> findPagedAllByWorkspaceId(UUID workspaceId, Pageable pageable);

    List<ClientEntity> findAllByWorkspaceIdAndIsEnabledTrue(UUID workspaceId);

    List<ClientEntity> findAllByWorkspaceIdAndIsEnabledFalse(UUID workspaceId);

    // Поиск по имени и фамилии
    List<ClientEntity> findByFirstNameContainingIgnoreCase(String firstName);

    List<ClientEntity> findByLastNameContainingIgnoreCase(String lastName);

    List<ClientEntity> findByFirstNameContainingIgnoreCaseAndLastNameContainingIgnoreCase(
            String firstName, String lastName);

    List<ClientEntity> findByWorkspaceIdAndFirstNameContainingIgnoreCase(
            UUID workspaceId, String firstName);

    List<ClientEntity> findByWorkspaceIdAndLastNameContainingIgnoreCase(
            UUID workspaceId, String lastName);

    // Поиск по имени и фамилии в рамках workspace
    List<ClientEntity> findByWorkspaceIdAndFirstNameContainingIgnoreCaseAndLastNameContainingIgnoreCase(
            UUID workspaceId, String firstName, String lastName);

    // Поиск по телефону (через вложенную коллекцию)
    @Query("SELECT c FROM ClientEntity c JOIN c.phoneList p WHERE p = :phone")
    Optional<ClientEntity> findByPhone(@Param("phone") String phone);

    @Query("SELECT c FROM ClientEntity c JOIN c.phoneList p WHERE p = :phone AND c.workspace.id = :workspaceId")
    Optional<ClientEntity> findByPhoneAndWorkspaceId(@Param("phone") String phone,
                                                     @Param("workspaceId") UUID workspaceId);

    @Query("SELECT c FROM ClientEntity c JOIN c.phoneList p WHERE p LIKE CONCAT(:prefix, '%')")
    List<ClientEntity> findByPhoneStartingWith(@Param("prefix") String prefix);

    // Сложные поиски
    @Query("SELECT c FROM ClientEntity c WHERE c.workspace.id = :workspaceId AND " +
            "(LOWER(c.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(c.lastName) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<ClientEntity> searchClients(@Param("workspaceId") UUID workspaceId,
                                     @Param("query") String query);

    @Query("SELECT c FROM ClientEntity c WHERE c.workspace.id = :workspaceId AND " +
            "c.isEnabled = true AND " +
            "(LOWER(c.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(c.lastName) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<ClientEntity> searchActiveClients(@Param("workspaceId") UUID workspaceId,
                                           @Param("query") String query);

    // Подсчеты
    @Query("SELECT COUNT(c) FROM ClientEntity c WHERE c.workspace.id = :workspaceId AND c.isEnabled = true")
    long countActiveClientsByWorkspace(@Param("workspaceId") UUID workspaceId);

    @Query("SELECT COUNT(c) FROM ClientEntity c WHERE c.workspace.id = :workspaceId AND c.isEnabled = false")
    long countDisabledClientsByWorkspace(@Param("workspaceId") UUID workspaceId);

    long countByWorkspaceId(UUID workspaceId);

    // Обновления

    @Modifying
    @Transactional
    @Query("DELETE FROM ClientEntity c WHERE c.workspace.id = :workspaceId")
    void deleteAllByWorkspaceId(@Param("workspaceId") UUID workspaceId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ClientEntity c WHERE c.id IN :clientIds")
    void deleteAllByIds(@Param("clientIds") List<UUID> clientIds);
}
