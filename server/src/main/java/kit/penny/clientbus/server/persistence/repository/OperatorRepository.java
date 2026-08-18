package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.OperatorEntity;
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
public interface OperatorRepository extends JpaRepository<OperatorEntity, UUID> {

    // Базовые поиски
    Optional<OperatorEntity> findByLogin(String login);

    Optional<OperatorEntity> findByEmail(String email);

    Optional<OperatorEntity> findByLoginAndWorkspaceId(String login, UUID workspaceId);

    List<OperatorEntity> findAllByWorkspaceId(UUID workspaceId);

    Page<OperatorEntity> findAllByWorkspaceId(UUID workspaceId, Pageable pageable);

    List<OperatorEntity> findAllByIsEnabledTrue();

    List<OperatorEntity> findAllByIsEnabledTrueAndWorkspaceId(UUID workspaceId);

    // Проверка существования
    boolean existsByLogin(String login);

    boolean existsByEmail(String email);

    boolean existsByLoginAndWorkspaceId(String login, UUID workspaceId);

    // Поиск по имени или фамилии (LIKE)
    List<OperatorEntity> findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(
            String firstName, String lastName);

    List<OperatorEntity> findByFirstNameContainingIgnoreCaseAndWorkspaceId(
            String firstName, UUID workspaceId);

    // Сложные запросы с @Query
    @Query("SELECT o FROM OperatorEntity o WHERE o.workspace.id = :workspaceId AND o.isEnabled = true")
    List<OperatorEntity> findActiveOperatorsByWorkspace(@Param("workspaceId") UUID workspaceId);

    @Query("SELECT o FROM OperatorEntity o WHERE o.workspace.id = :workspaceId AND o.login LIKE %:search%")
    List<OperatorEntity> searchByLogin(@Param("workspaceId") UUID workspaceId,
                                       @Param("search") String search);

    @Query("SELECT o FROM OperatorEntity o WHERE o.workspace.id = :workspaceId AND " +
            "(LOWER(o.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(o.lastName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(o.login) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<OperatorEntity> searchOperators(@Param("workspaceId") UUID workspaceId,
                                         @Param("query") String query);

    // Подсчет операторов в рабочем пространстве
    @Query("SELECT COUNT(o) FROM OperatorEntity o WHERE o.workspace.id = :workspaceId AND o.isEnabled = true")
    long countActiveOperatorsByWorkspace(@Param("workspaceId") UUID workspaceId);

    // Безопасное обновление (включение/отключение)
    @Modifying
    @Transactional
    @Query("UPDATE OperatorEntity o SET o.isEnabled = :enabled WHERE o.id = :operatorId")
    void updateEnabledStatus(@Param("operatorId") UUID operatorId,
                             @Param("enabled") boolean enabled);

    @Modifying
    @Transactional
    @Query("UPDATE OperatorEntity o SET o.workspace.id = :newWorkspaceId WHERE o.id = :operatorId")
    void transferOperatorToWorkspace(@Param("operatorId") UUID operatorId,
                                     @Param("newWorkspaceId") UUID newWorkspaceId);

    // Удаление всех операторов рабочего пространства
    @Modifying
    @Transactional
    @Query("DELETE FROM OperatorEntity o WHERE o.workspace.id = :workspaceId")
    void deleteAllByWorkspaceId(@Param("workspaceId") UUID workspaceId);
}