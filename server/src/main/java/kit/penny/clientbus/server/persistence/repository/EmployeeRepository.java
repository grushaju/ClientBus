package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeRepository
        extends JpaRepository<EmployeeEntity, UUID> {

    List<EmployeeEntity> findAllByOrganizationId(
            UUID organizationId
    );

    Optional<EmployeeEntity> findByUserId(
            UUID userId
    );

    boolean existsByIdAndOrganizationId(
            UUID employeeId,
            UUID organizationId
    );

    @Query("""
        SELECT DISTINCT e
        FROM EmployeeEntity e
        JOIN EmployeeWorkspaceEntity ew
             ON ew.employee.id = e.id
        JOIN e.user u
        WHERE ew.workspace.id = :workspaceId
        """)
    List<EmployeeEntity> findAllByWorkspaceId(
            @Param("workspaceId") UUID workspaceId
    );

    @Query("""
        SELECT DISTINCT e
        FROM EmployeeEntity e
        JOIN EmployeeWorkspaceEntity ew
             ON ew.employee.id = e.id
        JOIN e.user u
        WHERE ew.workspace.id = :workspaceId
          AND (
               LOWER(e.firstName)
                    LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(e.lastName)
                    LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(e.phone)
                    LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(u.username)
                    LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(u.email)
                    LIKE LOWER(CONCAT('%', :query, '%'))
          )
        """)
    List<EmployeeEntity> searchEmployees(
            @Param("workspaceId") UUID workspaceId,
            @Param("query") String query
    );
}